(ns plugins.review-pipeline
  "Evidence-first pull-request review state machine as tools.

   The reviewer drives one bounded pass by calling these tools in order:
   review_begin -> review_read_diff_chunk / review_assess_diff_chunk (all pages)
   -> review_record_evidence (per stage) -> review_propose_finding /
   review_classify_finding -> review_submit. Every call is validated by
   eta-mu.domain.review; the final submission is machine-written to
   .opencode/review-evidence/submission.json. Nothing is parsed from model
   output text."
  (:require [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.review :as review]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.shape.review-input :as input-shape]
            [eta-mu.dsl :refer [deftool defplugin]]))

(def evidence-dir-name ".opencode/review-evidence")
(def context-file-name "pr-context.md")
(def submission-file-name "submission.json")
(def events-file-name "review-events.jsonl")

;; Session state is in-process. The host-controlled evidence directory binds
;; one admitted review to this invocation; changing a host session id cannot
;; discard the calls already made against that input and output artifact.
(defonce !sessions (atom {}))

(defn- evidence-dir [ctx]
  (or (bfs/env "REVIEW_EVIDENCE_DIR")
      (bfs/join (or (:worktree ctx) (:directory ctx)) evidence-dir-name)))

(defn- session-key [ctx]
  (evidence-dir ctx))

(defn- record-event! [ctx kind data]
  (bfs/append-jsonl! (bfs/join (evidence-dir ctx) events-file-name)
                     (merge {:ts (bfs/now-iso) :kind kind} data)))

(defn- require-session [ctx]
  (get @!sessions (session-key ctx)))

(defn- apply-step!
  "Run a pure domain transition, persist the new session, log a receipt."
  [ctx kind f & args]
  (if-let [session (require-session ctx)]
    (let [result (apply f session args)]
      (if (:ok? result)
        (do (swap! !sessions assoc (session-key ctx) (:session result))
            (when (:restart-required? result)
              (bfs/remove-file! (bfs/join (evidence-dir ctx) submission-file-name)))
            (record-event! ctx kind (merge {:ok true :chunk-id (or (:chunk-id result) (get-in result [:chunk :id]))}
                                           (select-keys result [:coverage :restart-required?])
                                           (when (= "assess-diff-chunk" kind)
                                             (cond-> {:note (second args)}
                                               (:assessment result) (assoc :assessment (:assessment result))))))
            (dissoc result :session))
        (do (record-event! ctx kind {:ok false :error (:error result)})
            result)))
    {:ok? false :error "No review session; call review_begin first."}))

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(deftool begin
  {:id          :review/begin
   :description "Begin one evidence-first review. Legacy input verifies the full diff/native input. Ordinary v2 is selected only through independently supplied host context, verifies every raw-diff/summary/log original, indexes only raw diff, and returns all role descriptors and required coverage. Model args or candidate metadata cannot supply missing host trust. Call first; any refusal ends this invocation. A second begin requires a fresh model process."
   :args        [:map]
   :tags        #{:review}}
  [_params ctx]
  (if-let [session (require-session ctx)]
    (let [result (review/reject-restart session)]
      (swap! !sessions assoc (session-key ctx) (:session result))
      (bfs/remove-file! (bfs/join (evidence-dir ctx) submission-file-name))
      (record-event! ctx "begin" (merge {:ok false} (select-keys result [:restart-required? :error])))
      (dissoc result :session))
    (try
        (bfs/remove-file! (bfs/join (evidence-dir ctx) submission-file-name))
        (let [{:keys [text manifest] :as prepared}
              (if-let [trusted (:review-input-context ctx)]
                (bfs/read-review-input (evidence-dir ctx) trusted)
                (bfs/read-review-input (evidence-dir ctx)))
              session (if (= "open-hax.review-input/v2" (:schema manifest))
                        (review/begin text prepared)
                        (assoc (review/begin text) :input-source manifest))
              _ (when (#{"open-hax.actionability-input/v1"
                         "open-hax.rejection-assessment-input/v1"
                         "open-hax.positive-cohort-assessment-input/v1"} (:schema manifest))
                  (when-not (invocation/context-valid?
                             {:input-source manifest
                              :pages (mapv #(select-keys % [:id :start :end]) (:diff-chunks session))
                              :page-count (count (:diff-chunks session))
                              :full-input-sha256 (:sha256 (input-shape/input-descriptor manifest))
                              :review-tools (vec (sort invocation/review-tools))
                              :submission-file (bfs/join (evidence-dir ctx) submission-file-name)})
                    (throw (js/Error. "Invalid native full-input page geometry."))))
              context-file (bfs/join (evidence-dir ctx) context-file-name)
              context (when (bfs/exists? context-file) (bfs/read-text context-file))]
          (swap! !sessions assoc (session-key ctx) session)
          (record-event! ctx "begin" {:files (get-in session [:diff-stats :files]) :input-source manifest})
          (cond-> {:ok? true :stage (name (:stage session)) :stages (mapv name review/stages)
           :diff-stats (:diff-stats session) :input-source manifest
           :input-coverage (review/input-coverage session) :pr-context context
           :contract "Read every full-input page with review_read_diff_chunk, then assess each with review_assess_diff_chunk BEFORE the first deterministic stage note. Delivery alone is not assessment. Finish every page read and assessment before recording any stage evidence; retain the returned content for later stages. A later reread permanently invalidates this invocation. Record all five stage notes in order, propose/classify candidates and submit only after complete input assessment. Any failed review call ends this invocation; the host alone owns verified bounded recovery. pr.diff is a preview, never complete review input."}
            (:components session) (assoc :components (:components session) :view (:view session))))
      (catch :default e {:ok? false :error (str "Full input unavailable: " (.-message e))}))))

(deftool read-diff-chunk
  {:id :review/read_diff_chunk
   :description "Read one lossless bounded page of verified input. Ordinary v2 pages name raw-diff, summary or log, component digest, component-local byte/text spans and view spans. Each page belongs to one role. Read every global page id and pair it with a matching structured assessment. Use coverage.missing and coverage.roles; zero-byte roles require empty observations without page ids. Retain actual payload for later stages. Finish all reads before assessing; a later reread irreversibly invalidates this invocation."
   :args [:map [:id :int]] :tags #{:review}}
  [{:keys [id]} ctx]
  (apply-step! ctx "read-diff-chunk" review/read-diff-chunk id))

(deftool assess-diff-chunk
  {:id :review/assess_diff_chunk
   :description "Assess actual delivered input. Legacy uses id+note. Ordinary v2 also requires assessment:{page-ref:{id,role,component-sha256,component-byte-span,component-text-span,view-byte-span,view-text-span,start,end},kind,observations:[{kind,text-span,text,...}]}. Observation text-span is local to the returned page; text must match exactly. Raw diff carries page-local hunk-spans and changed-spans across page boundaries. Use changed-hunk with an actual header or changed-span reference; a context-only hunk slice still references its hunk-spans. Use no-changed-hunk with reason only when hunk-spans is empty. Summary-field names gate, status or limitation with exact lexical field/value. Log-observation names result, failure or environment with the exact delimited context/value. Summary/log may use no-relevant-evidence with reason and exact slice reference. Empty roles omit id and use assessment:{kind:empty-component,component-ref:<exact source descriptor>}. Complete every role/page/empty observation before FIRST deterministic evidence. Any refusal ends the invocation."
   :args [:map [:id {:optional true} [:maybe :int]] [:note [:string {:min 1}]]
          [:assessment {:optional true} :map]] :tags #{:review}}
  [{:keys [id note assessment]} ctx]
  (apply-step! ctx "assess-diff-chunk" review/assess-diff-chunk id note assessment))

(deftool record-evidence
  {:id          :review/record_evidence
   :description "Record the evidence note for the current review stage and advance to the next stage. Complete every full-input page read and assessment BEFORE the first deterministic note; incomplete input refuses advancement. Retain page content without rereading or reassessing after stage evidence. Stages: deterministic, map-change, generate-candidates, adversarial-validate, publish. Any refusal ends this invocation; only the host owns verified bounded recovery."
   :args        [:map
                 [:stage [:enum "deterministic" "map-change" "generate-candidates" "adversarial-validate" "publish"]]
                 [:note [:string {:min 1}]]]
   :tags        #{:review}}
  [{:keys [stage note]} ctx]
  (apply-step! ctx "record-evidence" review/record-evidence (keyword stage) note))

(deftool propose-finding
  {:id          :review/propose_finding
   :description "Register a candidate finding. The path and line are validated against the staged diff at call time: only added lines on the PR head side are reviewable. A candidate is not a defect until classified."
   :args        [:map
                 [:id [:string {:min 1}]]
                 [:severity [:enum "critical" "high" "medium" "low"]]
                 [:category [:enum "semantic-regression" "security" "contract" "state-transition" "test-gap"]]
                 [:claim [:string {:min 1}]]
                 [:path [:string {:min 1}]]
                 [:line :int]
                 [:body [:string {:min 1}]]
                 [:confidence [:double {:min 0.0 :max 1.0}]]
                 [:blocking {:optional true} :boolean]]
   :tags        #{:review}}
  [params ctx]
  (apply-step! ctx "propose-finding" review/propose-finding params))

(deftool classify-finding
  {:id          :review/classify_finding
   :description "Classify a pending candidate after adversarial validation: confirmed (independently plausible failure trace), rejected (disproved), or needs-human (cannot be settled from available evidence). Legal only at adversarial-validate or publish. From generate-candidates, first record that stage's evidence note with review_record_evidence to advance into adversarial-validate; in that stage, prefer classifying candidates before recording the adversarial-validate evidence note. Candidates may still be classified at publish after that evidence note. Any failed review_* call ends this invocation; do not submit, retry, restart, or make further review calls."
   :args        [:map
                 [:id [:string {:min 1}]]
                 [:status [:enum "confirmed" "rejected" "needs-human"]]
                 [:rationale [:string {:min 1}]]]
   :tags        #{:review}}
  [{:keys [id status rationale]} ctx]
  (apply-step! ctx "classify-finding" review/classify-finding id status rationale))

(deftool status
  {:id          :review/status
   :description "Read the current review session: stage, evidence count, diff stats, and every candidate with its classification."
   :args        [:map]
   :tags        #{:review}}
  [_params ctx]
  (if-let [session (require-session ctx)]
    (assoc (review/status session)
           :ok?  true
           :stage (name (:stage session))
           :stages (mapv name review/stages))
    {:ok? false :error "No review session; call review_begin first."}))

(deftool submit
  {:id          :review/submit
   :description "Finish the review. Every full-input page must be delivered and assessed, every candidate classified and every stage evidenced. The review event (APPROVE, COMMENT, REQUEST_CHANGES) is derived from confirmed findings by law. On success the machine-readable submission is written to .opencode/review-evidence/submission.json for the deterministic publisher."
   :args        [:map
                 [:summary [:string {:min 1}]]]
   :tags        #{:review}}
  [{:keys [summary]} ctx]
  (if-let [session (require-session ctx)]
    (let [result (review/submission session summary)]
      (if (:ok? result)
        (let [file (bfs/join (evidence-dir ctx) submission-file-name)
              json (js/JSON.stringify (clj->js (:envelope result)) nil 2)]
          (bfs/write-text! file (str json "\n"))
          (record-event! ctx "submit" {:ok true :event (get-in result [:envelope :event])})
          {:ok?    true
           :event  (get-in result [:envelope :event])
           :file   file
           :inline-comments (count (get-in result [:envelope :comments]))})
        (do (record-event! ctx "submit" {:ok false :error (:error result)})
            result)))
    {:ok? false :error "No review session; call review_begin first."}))

(defplugin plugin {:id :eta-mu/review-pipeline}
  begin
  read-diff-chunk
  assess-diff-chunk
  record-evidence
  propose-finding
  classify-finding
  status
  submit)
