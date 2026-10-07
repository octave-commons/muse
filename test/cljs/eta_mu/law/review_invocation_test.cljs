(ns eta-mu.law.review-invocation-test
  "Source-only synthetic controls in actual OpenCode host-event shape."
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.review :as review]
            [eta-mu.law.review-invocation :as invocation]))

(def manifest
  {:schema "open-hax.review-input/v1"
   :base_sha (apply str (repeat 40 "a")) :head_sha (apply str (repeat 40 "b"))
   :diff_base_sha (apply str (repeat 40 "a"))
   :full_diff {:path "basehead.diff" :bytes 4 :sha256 "df087996d45b03e7eb8c133c0298fd98d35113fca26aaba58612fef3cc212cad"}
   :provenance {:repository "fixture/repo" :pull_request "1" :run_id "2" :run_attempt "1"
                :workflow_sha (apply str (repeat 40 "d")) :workflow_ref "fixture/repo/workflow@refs/pull/1/merge"}})

(def context
  {:input-source manifest :pages [{:id 1 :start 0 :end 4}] :page-count 1
   :full-input-sha256 (get-in manifest [:full_diff :sha256])
   :review-tools (vec (sort invocation/review-tools)) :submission-file "/fixture/submission.json"})

(def coverage {:chunks 1 :delivered 1 :assessed 1 :missing []})
(def submission
  {:schema "open-hax.github-review/v1" :event "APPROVE" :summary "fixture summary" :comments []
   :input-source manifest :input-coverage coverage
   :input-assessments [{:id 1 :start 0 :end 4 :note "whole-page assessment"}]})

(defn tool-event [n tool args result]
  {:type "tool_use" :timestamp n :session-id "session-fixture"
   :part {:type "tool" :id (str "part-" n) :call-id (str "call-" n) :session-id "session-fixture"
          :tool tool :state {:status "completed" :input args :output result}}})

(def begin-output
  {:ok? true :stage "deterministic" :stages invocation/stages :input-source manifest
   :diff-stats {:files 1 :bytes 4 :truncated? false}
   :input-coverage {:chunks 1 :delivered 0 :assessed 0 :missing [1]}})
(def read-output {:ok? true :chunk {:id 1 :start 0 :end 4 :text "diff"}})
(def assess-output {:ok? true :chunk-id 1 :coverage coverage})
(def submit-output {:ok? true :event "APPROVE" :file "/fixture/submission.json" :inline-comments 0})

(defn terminal [n]
  {:type "step_finish" :timestamp n :session-id "session-fixture"
   :part {:type "step-finish" :id (str "stop-" n) :session-id "session-fixture" :reason "stop"}})

(defn trace []
  (vec (concat [(tool-event 1 "review_begin" {} begin-output)
                (tool-event 2 "review_read_diff_chunk" {:id 1} read-output)
                (tool-event 3 "review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output)]
               (map-indexed #(tool-event (+ 4 %1) "review_record_evidence" {:stage %2 :note "stage evidence"} {:ok? true}) invocation/stages)
               [(tool-event 9 "review_submit" {:summary "fixture summary"} submit-output) (terminal 10)])))

(defn append-calls [events calls]
  (conj (into (pop events) calls) (terminal (inc (apply max (map :timestamp calls))))))

(defn verdict
  ([events] (invocation/verify events submission context))
  ([events s ctx] (invocation/verify events s ctx)))

(deftest accepts-actual-host-shape-with-complete-bindings
  (let [result (verdict (trace))]
    (is (:ok? result))
    (is (= :verified-review-invocation (:code result)))
    (is (= "call-9" (get-in result [:accepted-invocation :submission-call-id])))
    (is (= 1 (get-in result [:accepted-invocation :page-count])))
    (is (nil? (:reason-kind result)))))

(deftest all-assessment-calls-must-follow-last-read
  (doseq [result [read-output {:ok? false :error "Read refused by the actual tool."}]]
    (let [events (append-calls (trace) [(tool-event 10 "review_read_diff_chunk" {:id 1} result)])
          failed (verdict events)]
      (is (false? (:ok? failed)))
      (is (= (if (:ok? result) :stale-review-coverage :unestablished-review-trace) (:reason-kind failed)))
      (is (= [{:id 1 :assessment-position 3 :last-read-position 10}] (:violations failed)))
      (is (nil? (:accepted-invocation failed)))))
  (let [events (append-calls (trace) [(tool-event 10 "review_read_diff_chunk" {:id 1} read-output)
                                    (tool-event 11 "review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output)])]
    (is (= :stale-review-coverage (:reason-kind (verdict events))))
    (is (= 1 (count (:violations (verdict events)))))
    (is (= 3 (:assessment-position (first (:violations (verdict events))))))))

(deftest observed-stale-with-no-submission-does-not-require-fictional-returns
  (let [events [(tool-event 1 "review_begin" {} begin-output)
                (tool-event 2 "review_read_diff_chunk" {:id 1} read-output)
                (tool-event 3 "review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output)
                (tool-event 4 "review_read_diff_chunk" {:id 1} (assoc read-output :restart-required? true))
                (terminal 5)]
        result (verdict events nil context)]
    (is (= :stale-review-coverage (:reason-kind result)))
    (is (false? (:ok? result)))
    (is (= 1 (count (:violations result))))))

(deftest reassessment-before-final-submit-does-not-erase-earlier-assessment
  (doseq [reassess? [false true]]
    (let [inserted (cond-> [(tool-event 4 "review_read_diff_chunk" {:id 1} read-output)]
                     reassess? (conj (tool-event 5 "review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output)))
          stages (map-indexed #(tool-event (+ 6 %1) "review_record_evidence" {:stage %2 :note "stage evidence"} {:ok? true}) invocation/stages)
          events (vec (concat (take 3 (trace)) inserted stages
                               [(tool-event 11 "review_submit" {:summary "fixture summary"} submit-output) (terminal 12)]))
          result (verdict events)]
      (is (false? (:ok? result)))
      (is (= :stale-review-coverage (:reason-kind result)))
      (is (= 1 (count (:violations result))))
      (is (= 3 (:assessment-position (first (:violations result))))))))

(deftest metadata-flag-without-whole-host-witness-is-unknown
  (let [result (verdict (assoc-in (trace) [1 :part :state :output :restart-required?] true))]
    (is (false? (:ok? result)))
    (is (= :unestablished-review-trace (:reason-kind result)))
    (is (= :restart-without-host-witness (:code result)))))

(deftest surviving-submission-on-host-tool-error-is-never-accepted-or-recoverable
  (let [events (append-calls (trace) [(-> (tool-event 10 "review_read_diff_chunk" {:id 1} nil)
                                         (assoc-in [:part :state :status] "error")
                                         (assoc-in [:part :state :error] "EACCES: actual host cleanup failed"))])
        result (verdict events)]
    (is (false? (:ok? result)))
    (is (= :unestablished-review-trace (:reason-kind result)))
    (is (nil? (:accepted-invocation result)))
    (is (= 1 (count (:violations result))))))

(deftest reads-before-assessment-and-reassessment-without-reread-are-healthy
  (let [base (trace)
        reread (tool-event 2 "review_read_diff_chunk" {:id 1} read-output)
        events (vec (concat [(first base) reread
                             (assoc-in (assoc reread :timestamp 2) [:part :call-id] "another-call")]
                            (drop 2 base)))
        events (assoc-in events [2 :part :id] "another-part")]
    (is (:ok? (verdict events))))
  (let [events (vec (concat (take 3 (trace))
                            [(tool-event 3 "review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output)]
                            (drop 3 (trace))))
        events (-> events (assoc-in [3 :part :id] "another-part") (assoc-in [3 :part :call-id] "another-call"))]
    (is (:ok? (verdict events)))))

(deftest healthy-reset-cannot-manufacture-an-accepted-invocation
  (let [events (vec (concat [(first (trace))
                            (tool-event 1 "review_begin" {} begin-output)]
                           (rest (trace))))
        events (-> events (assoc-in [1 :part :id] "reset-part") (assoc-in [1 :part :call-id] "reset-call"))]
    (is (= :begin-cardinality (:code (verdict events))))
    (is (= :unestablished-review-trace (:reason-kind (verdict events)))))
  (let [events (append-calls (trace) [(tool-event 10 "review_begin" {} begin-output)
                                    (tool-event 11 "review_read_diff_chunk" {:id 1} read-output)])
        failed (verdict events)]
    (is (= :begin-cardinality (:code failed)))
    (is (= 1 (count (:violations failed))))
    (is (= :unestablished-review-trace (:reason-kind failed)))))

(deftest host-session-reset-does-not-partition-away-last-read
  (let [events (append-calls (trace) [(-> (tool-event 10 "review_read_diff_chunk" {:id 1} read-output)
                                                        (assoc :session-id "another-host")
                                                        (assoc-in [:part :session-id] "another-host"))])
        failed (verdict events)]
    (is (= :host-session-binding (:code failed)))
    (is (= 1 (count (:violations failed))))
    (is (= :unestablished-review-trace (:reason-kind failed)))))

(deftest fixed-latched-rebegin-can-only-retain-known-stale-refusal
  (let [events (append-calls (trace) [(tool-event 10 "review_read_diff_chunk" {:id 1} (assoc read-output :restart-required? true))
                                    (tool-event 11 "review_begin" {} {:ok? false :restart-required? true :error "Source rejected restart."})])]
    (is (= :stale-review-coverage (:reason-kind (verdict events))))
    (is (false? (:ok? (verdict events))))
    (is (= 1 (count (:violations (verdict events)))))
    (is (= :unestablished-review-trace
           (:reason-kind (verdict (assoc-in events [10 :part :state :output :restart-required?] false))))))
  (let [events (append-calls (trace) [(tool-event 10 "review_begin" {} {:ok? false :restart-required? true :error "No prior stale witness."})])]
    (is (= :unestablished-review-trace (:reason-kind (verdict events))))))

(deftest envelope-cannot-invent-fifth-host-stage
  (let [events (vec (concat (take 7 (trace)) (drop 8 (trace))))]
    (is (= :incomplete-five-stages (:code (verdict events))))
    (is (false? (:ok? (verdict events))))))

(deftest original-source-oid-grammar-keeps-sha256-git-compatibility
  (let [source (-> manifest
                   (assoc :base_sha (apply str (repeat 64 "a")) :head_sha (apply str (repeat 64 "b"))
                          :diff_base_sha (apply str (repeat 64 "a")))
                   (assoc-in [:provenance :workflow_sha] (apply str (repeat 64 "d"))))
        events (assoc-in (trace) [0 :part :state :output :input-source] source)]
    (is (:ok? (verdict events (assoc submission :input-source source) (assoc context :input-source source))))))

(defn empty-fixture []
  (let [digest "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        source (assoc manifest :full_diff {:path "basehead.diff" :bytes 0 :sha256 digest})
        empty-coverage {:chunks 0 :delivered 0 :assessed 0 :missing []}
        ctx (assoc context :input-source source :pages [] :page-count 0 :full-input-sha256 digest)
        envelope (assoc submission :input-source source :input-coverage empty-coverage :input-assessments [])
        begin (assoc begin-output :input-source source :input-coverage empty-coverage
                     :diff-stats {:files 0 :bytes 0 :truncated? false})
        events (vec (concat [(tool-event 1 "review_begin" {} begin)]
                             (map-indexed #(tool-event (+ 2 %1) "review_record_evidence" {:stage %2 :note "empty-input stage evidence"} {:ok? true}) invocation/stages)
                             [(tool-event 7 "review_submit" {:summary "fixture summary"} submit-output) (terminal 8)]))]
    {:context ctx :submission envelope :events events}))

(deftest empty-exact-diff-retains-generic-five-host-stage-contract
  (let [{:keys [context submission events]} (empty-fixture)
        result (verdict events submission context)]
    (is (:ok? result))
    (is (= 0 (:page-count result)))
    (is (= [] (:violations result)))
    (is (= 0 (get-in result [:accepted-invocation :page-count])))))

(deftest malformed-or-unbound-evidence-never-grants-stale-recovery
  (doseq [[label change] [["missing actual output" #(assoc-in % [2 :part :state :output] nil)]
                         ["malformed result" #(assoc-in % [2 :part :state :output :ok?] "true")]
                         ["host error" #(assoc-in % [2 :part :state :status] "error")]
                         ["unknown page" #(assoc-in % [2 :part :state :input :id] 2)]
                         ["noninteger page" #(assoc-in % [2 :part :state :input :id] 1.5)]
                         ["blank note" #(assoc-in % [2 :part :state :input :note] " ")]
                         ["unknown event" #(assoc-in % [0 :type] "unavailable")]
                         ["bad timestamp" #(assoc-in % [1 :timestamp] -1)]
                         ["duplicate call" #(assoc-in % [1 :part :call-id] "call-1")]
                         ["duplicate part" #(assoc-in % [1 :part :id] "part-1")]
                         ["part session mismatch" #(assoc-in % [1 :part :session-id] "foreign")]
                         ["wrong source" #(assoc-in % [0 :part :state :output :input-source :head_sha] (apply str (repeat 40 "e")))]
                         ["unavailable tool" #(assoc-in % [1 :part :tool] "invalid")]
                         ["unknown review tool" #(assoc-in % [1 :part :tool] "review_new_control")]
                         ["stage reorder" #(assoc-in % [3 :part :state :input :stage] "publish")]
                         ["failed stage" #(assoc-in % [3 :part :state :output] {:ok? false :error "refused"})]
                         ["wrong submit event" #(assoc-in % [8 :part :state :output :event] "COMMENT")]
                         ["wrong submit file" #(assoc-in % [8 :part :state :output :file] "/other/submission.json")]]]
    (testing label
      (let [events (change (append-calls (trace) [(tool-event 10 "review_read_diff_chunk" {:id 1} read-output)]))
            result (verdict events)]
        (is (false? (:ok? result)))
        (is (= :unestablished-review-trace (:reason-kind result)))))))

(deftest missing-context-or-submission-is-unestablished-not-an-approval
  (doseq [ctx [nil (dissoc context :pages) (assoc context :page-count 2)
               (assoc context :full-input-sha256 (apply str (repeat 64 "d")))
               (assoc context :review-tools ["review_begin"])]]
    (is (= :unestablished-review-trace (:reason-kind (verdict (trace) submission ctx)))))
  (doseq [s [nil {} (assoc submission :summary "other")
             (assoc-in submission [:input-assessments 0 :note] "different note")
             (assoc-in submission [:input-assessments 0 :end] 3)]]
    (is (= :unestablished-review-trace (:reason-kind (verdict (trace) s context)))))
  (is (= :unestablished-review-trace (:reason-kind (verdict (pop (trace)))))))

(deftest healthy-terminal-omission-is-unfinished-never-passing
  (doseq [events [[(terminal 1)]
                  [(tool-event 1 "review_begin" {} begin-output) (terminal 2)]
                  [(tool-event 1 "review_begin" {} begin-output)
                   (tool-event 2 "review_read_diff_chunk" {:id 1} read-output) (terminal 3)]
                  (conj (vec (take 8 (trace))) (terminal 9))]]
    (let [result (verdict events nil context)]
      (is (false? (:ok? result)))
      (is (= :missing-review-submit (:reason-kind result)))
      (is (nil? (:accepted-invocation result)))
      (is (= [] (:violations result)))))
  ;; A successful submit with subsequently missing bytes is not simple omission.
  (is (= :unestablished-review-trace (:reason-kind (verdict (trace) nil context)))))

(deftest omission-never-waives-unknown-source-schema-host-or-tool-failure
  (let [partial [(tool-event 1 "review_begin" {} begin-output) (terminal 2)]]
    (doseq [events [(assoc-in partial [0 :part :state :output :input-source :head_sha] "wrong")
                    (assoc-in partial [0 :part :state :output] nil)
                    (assoc-in partial [0 :part :state :status] "error")
                    (assoc-in partial [0 :part :session-id] "foreign")
                    [(tool-event 1 "unknown_external_tool" {} "uninterpreted") (terminal 2)]
                    [(tool-event 1 "invalid" {} "unavailable") (terminal 2)]
                    [{:type "error" :timestamp 1 :session-id "session-fixture"
                      :part {:type "error" :id "error-1" :session-id "session-fixture"}} (terminal 2)]
                    (pop partial)]]
      (let [result (verdict events nil context)]
        (is (false? (:ok? result)))
        (is (= :unestablished-review-trace (:reason-kind result))))))
  (is (= :unestablished-review-trace (:reason-kind (verdict [(terminal 1)] nil nil)))))

(defn completion-boundary-fixture
  "Synthetic host events from actual pure producer calls. The untouched tail
   is first delivered and assessed before adversarial-to-publish completion."
  []
  (let [diff (str "diff --git a/tail b/tail\n--- a/tail\n+++ b/tail\n@@ -0,0 +1,130 @@\n"
                  (apply str (repeat 129 "+prefix\n")) "+tail-risk\n")
        ;; SHA256 of the exact ASCII fixture above; geometry comes from Muse.
        digest "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e"
        source (assoc manifest :full_diff {:path "basehead.diff" :bytes (count diff) :sha256 digest})
        begun (assoc (review/begin diff) :input-source source)
        ctx (assoc context :input-source source
                   :pages (mapv #(select-keys % [:id :start :end]) (:diff-chunks begun))
                   :page-count (count (:diff-chunks begun)) :full-input-sha256 digest)
        began (assoc begin-output :input-source source :diff-stats (:diff-stats begun)
                     :input-coverage (review/input-coverage begun))
        operations [["review_read_diff_chunk" {:id 1}]
                    ["review_assess_diff_chunk" {:id 1 :note "Prefix assessed before early stages."}]
                    ["review_record_evidence" {:stage "deterministic" :note "Actual fixture gate evidence."}]
                    ["review_record_evidence" {:stage "map-change" :note "Prefix mapped; tail still untouched."}]
                    ["review_record_evidence" {:stage "generate-candidates" :note "Tail recovery remains possible."}]
                    ["review_read_diff_chunk" {:id 2}]
                    ["review_assess_diff_chunk" {:id 2 :note "Untouched tail first-read and assessed."}]
                    ["review_record_evidence" {:stage "adversarial-validate" :note "All input now assessed."}]
                    ["review_record_evidence" {:stage "publish" :note "Five actual host stages recorded."}]]
        produced (reduce
                  (fn [{:keys [session events results]} [tool args]]
                    (let [result (case tool
                                   "review_read_diff_chunk" (review/read-diff-chunk session (:id args))
                                   "review_assess_diff_chunk" (review/assess-diff-chunk session (:id args) (:note args))
                                   "review_record_evidence" (review/record-evidence session (keyword (:stage args)) (:note args)))
                          n (inc (count events))]
                      {:session (or (:session result) session)
                       :events (conj events (tool-event n tool args (dissoc result :session)))
                       :results (conj results result)}))
                  {:session begun :events [(tool-event 1 "review_begin" {} began)] :results []}
                  operations)
        submitted (review/submission (:session produced) "fixture summary")
        envelope (:envelope submitted)
        submitted-output (assoc submit-output :event (:event envelope)
                                :inline-comments (count (:comments envelope)))
        events (conj (:events produced)
                     (tool-event 11 "review_submit" {:summary "fixture summary"} submitted-output)
                     (terminal 12))]
    {:events events :submission envelope :context ctx
     :producer-results (conj (:results produced) submitted)}))

(defn ordered-fixture-events [events]
  (mapv (fn [index event] (assoc event :timestamp (inc index))) (range) events))

(deftest untouched-tail-first-read-and-assessment-before-completion-are-supported
  (let [{:keys [events submission context producer-results]} (completion-boundary-fixture)
        result (verdict events submission context)]
    (is (every? :ok? producer-results) "Actual pure producer accepts this recovery before completion.")
    (is (= 2 (:page-count context)))
    (is (= invocation/stages
           (mapv #(get-in % [:part :state :input :stage])
                 (filter #(= "review_record_evidence" (get-in % [:part :tool])) events))))
    (is (= [] (:violations result)) "Every assessment follows its page's LAST read.")
    (is (:ok? result))
    (is (= :verified-review-invocation (:code result)))
    (is (= 2 (get-in result [:accepted-invocation :page-count])))))

(deftest assessment-after-adversarial-completion-is-too-late
  (let [{:keys [events submission context]} (completion-boundary-fixture)]
    (doseq [late-events [(concat (take 7 events) [(nth events 8) (nth events 7)] (drop 9 events))
                        (concat (take 7 events) [(nth events 8) (nth events 9) (nth events 7)] (drop 10 events))]]
      (let [result (verdict (ordered-fixture-events late-events) submission context)]
        (is (false? (:ok? result)))
        (is (= :unestablished-review-trace (:reason-kind result)))
        (is (= :stage-chronology (:code result)))
        (is (= [] (:violations result)) "Too-late assessment is refused even when LAST is healthy.")
        (is (nil? (:accepted-invocation result)))))))

(deftest late-tail-recovery-never-waives-stale-last-read
  (let [{:keys [events submission context]} (completion-boundary-fixture)
        reread (-> (nth events 6)
                   (assoc-in [:part :id] "part-tail-reread")
                   (assoc-in [:part :call-id] "call-tail-reread")
                   (assoc-in [:part :state :output :restart-required?] true))
        stale (ordered-fixture-events (concat (take 8 events) [reread] (drop 8 events)))
        result (verdict stale submission context)]
    (is (false? (:ok? result)))
    (is (= :stale-review-coverage (:reason-kind result)))
    (is (= :last-read-order (:code result)))
    (is (= [{:id 2 :assessment-position 8 :last-read-position 9}] (:violations result)))
    (is (nil? (:accepted-invocation result)))))

(deftest late-tail-recovery-still-requires-five-successful-ordered-host-stages
  (let [{:keys [events submission context]} (completion-boundary-fixture)]
    (doseq [stage-index [3 4 5 8 9]]
      (let [omitted (ordered-fixture-events (concat (take stage-index events) (drop (inc stage-index) events)))
            result (verdict omitted submission context)]
        (is (false? (:ok? result)))
        (is (= :unestablished-review-trace (:reason-kind result)))
        (is (nil? (:accepted-invocation result)))))
    (doseq [invalid [(ordered-fixture-events (concat (take 4 events) [(nth events 5) (nth events 4)] (drop 6 events)))
                    (assoc-in events [8 :part :state :output] {:ok? false :error "Actual stage refused."})]]
      (let [result (verdict invalid submission context)]
        (is (false? (:ok? result)))
        (is (= :unestablished-review-trace (:reason-kind result)))
        (is (= :stage-order (:code result)))
        (is (nil? (:accepted-invocation result)))))))

(deftest failed-stage-and-submit-cannot-be-repaired-within-one-invocation
  (let [unassessed (reduce (fn [session stage]
                            (:session (review/record-evidence session stage "stage evidence")))
                          (review/begin "diff")
                          [:deterministic :map-change :generate-candidates])
        failed-stage (review/record-evidence unassessed :adversarial-validate "stage evidence")
        failed-submit (review/submission unassessed "fixture summary")]
    (is (false? (:ok? failed-stage)) "Actual producer refuses completion with unassessed input.")
    (is (false? (:ok? failed-submit)) "Actual producer refuses submission before publish.")
    (doseq [[index tool args output code]
            [[6 "review_record_evidence" {:stage "adversarial-validate" :note "stage evidence"}
              failed-stage :stage-order]
             [8 "review_submit" {:summary "fixture summary"} failed-submit :submit-cardinality]]]
      (testing (str tool " failure followed by its successful call")
        (let [base (trace)
              events (ordered-fixture-events
                      (concat (take index base) [(tool-event 99 tool args output)] (drop index base)))
              result (verdict events)]
          (is (false? (:ok? result)))
          (is (= :unestablished-review-trace (:reason-kind result)))
          (is (= code (:code result)))
          (is (= [] (:violations result)) "Healthy LAST does not waive failed-call cardinality.")
          (is (nil? (:accepted-invocation result))))))
    (let [base (trace)
          result (verdict base)]
      (is (= invocation/stages
             (mapv #(get-in % [:part :state :input :stage])
                   (filter #(= "review_record_evidence" (get-in % [:part :tool])) base))))
      (is (:ok? result))
      (is (= :verified-review-invocation (:code result))))))
