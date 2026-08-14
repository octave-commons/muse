(ns plugins.review-pipeline
  "Evidence-first pull-request review state machine as tools.

   The reviewer drives one bounded pass by calling these tools in order:
   review_begin -> review_record_evidence (per stage) -> review_propose_finding /
   review_classify_finding -> review_submit. Every call is validated by
   eta-mu.domain.review; the final submission is machine-written to
   .opencode/review-evidence/submission.json. Nothing is parsed from model
   output text."
  (:require [clojure.string :as str]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.review :as review]
            [eta-mu.dsl :refer [deftool defplugin]]))

(def evidence-dir-name ".opencode/review-evidence")
(def diff-file-name "pr.diff")
(def context-file-name "pr-context.md")
(def submission-file-name "submission.json")
(def events-file-name "review-events.jsonl")

;; Session state is in-process: the review workflow runs one bounded review per
;; opencode invocation. Keyed by session id when the host provides one.
(defonce !sessions (atom {}))

(defn- session-key [ctx]
  (or (:session/id ctx) :default))

(defn- evidence-dir [ctx]
  (bfs/join (or (:worktree ctx) (:directory ctx)) evidence-dir-name))

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
            (record-event! ctx kind {:ok true})
            (dissoc result :session))
        (do (record-event! ctx kind {:ok false :error (:error result)})
            result)))
    {:ok? false :error "No review session; call review_begin first."}))

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(deftool begin
  {:id          :review/begin
   :description "Begin an evidence-first pull-request review. Reads the staged diff and pull-request context from .opencode/review-evidence, indexes the changed lines findings may attach to, and returns the review contract. Call this first, exactly once."
   :args        [:map]
   :tags        #{:review}}
  [_params ctx]
  (let [diff-file    (bfs/join (evidence-dir ctx) diff-file-name)
        context-file (bfs/join (evidence-dir ctx) context-file-name)]
    (if-not (bfs/exists? diff-file)
      {:ok? false :error (str "Staged diff not found at " diff-file "; the review workflow must stage it before the reviewer runs.")}
      (let [session (review/begin (bfs/read-text diff-file))
            context (when (bfs/exists? context-file) (bfs/read-text context-file))]
        (swap! !sessions assoc (session-key ctx) session)
        (record-event! ctx "begin" {:files (get-in session [:diff-stats :files])})
        {:ok?        true
         :stage      (name (:stage session))
         :stages     (mapv name review/stages)
         :diff-stats (:diff-stats session)
         :pr-context context
         :contract   "Record one evidence note per stage with review_record_evidence; propose candidates with review_propose_finding at :generate-candidates; classify each as confirmed/rejected/needs-human at :adversarial-validate; finish with review_submit at :publish. Inline findings must attach to added diff lines; the tools reject anything else."}))))

(deftool record-evidence
  {:id          :review/record_evidence
   :description "Record the evidence note for the current review stage and advance to the next stage. Stages: deterministic, map-change, generate-candidates, adversarial-validate, publish."
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
   :description "Classify a pending candidate after adversarial validation: confirmed (independently plausible failure trace), rejected (disproved), or needs-human (cannot be settled from available evidence)."
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
   :description "Finish the review. Every candidate must be classified and every stage must have evidence. The review event (APPROVE, COMMENT, REQUEST_CHANGES) is derived from the confirmed findings by law, not asserted. On success the machine-readable submission is written to .opencode/review-evidence/submission.json for the deterministic publisher."
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
  record-evidence
  propose-finding
  classify-finding
  status
  submit)
