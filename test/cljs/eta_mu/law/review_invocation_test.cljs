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

(defn first-stage-control-trace
  "Synthetic raw host controls, built independently of producer admission.
   Premature complete and post-stage reassessment have healthy LAST."
  [operations submit?]
  (let [events (into [(tool-event 1 "review_begin" {} begin-output)]
                     (map-indexed (fn [index [tool args result]]
                                    (tool-event (+ 2 index) tool args result)) operations))
        events (cond-> events submit?
                 (conj (tool-event (inc (count events)) "review_submit"
                                   {:summary "fixture summary"} submit-output)))]
    (conj events (terminal (inc (count events))))))

(declare empty-fixture)

(defn first-stage-guard-controls []
  (let [read ["review_read_diff_chunk" {:id 1} read-output]
        assess ["review_assess_diff_chunk" {:id 1 :note "whole-page assessment"} assess-output]
        stage (fn [name] ["review_record_evidence" {:stage name :note "stage evidence"} {:ok? true}])
        stages (mapv stage invocation/stages)
        early (first-stage-control-trace (concat [(first stages) read assess] (rest stages)) true)
        omission (first-stage-control-trace [(first stages) read assess] false)
        empty-coverage (first-stage-control-trace [(first stages)] false)
        reassessed (first-stage-control-trace (concat [read assess (first stages) assess] (rest stages)) true)
        healthy (trace)
        empty-input (empty-fixture)]
    [{:label "premature complete trace" :events early :submission submission :context context
      :ok? false :code :stage-chronology}
     {:label "early omission with all pages eventually assessed" :events omission :submission nil :context context
      :ok? false :code :stage-chronology}
     {:label "first stage with no expected page coverage" :events empty-coverage :submission nil :context context
      :ok? false :code :stage-chronology}
     {:label "post-stage reassessment with healthy LAST" :events reassessed :submission submission :context context
      :ok? false :code :stage-chronology}
     {:label "all input before first" :events healthy :submission submission :context context
      :ok? true :code :verified-review-invocation}
     (assoc empty-input :label "empty verified diff" :ok? true :code :verified-review-invocation)
     {:label "source mismatch retains precedence" :events (assoc-in early [0 :part :state :output :input-source :head_sha] (apply str (repeat 40 "e")))
      :submission submission :context context :ok? false :code :begin-source-binding}
     {:label "session mismatch retains precedence" :events (-> early (assoc-in [2 :session-id] "foreign") (assoc-in [2 :part :session-id] "foreign"))
      :submission submission :context context :ok? false :code :host-session-binding}
     {:label "failed stage retains precedence" :events (assoc-in early [1 :part :state :output] {:ok? false :error "Refused stage."})
      :submission submission :context context :ok? false :code :stage-order}
     {:label "length remains unterminated" :events (assoc-in omission [(dec (count omission)) :part :reason] "length")
      :submission nil :context context :ok? false :code :unterminated-host-trace}
     {:label "LAST violation retains precedence" :events (append-calls healthy [(tool-event 10 "review_read_diff_chunk" {:id 1} read-output)])
      :submission submission :context context :ok? false :code :last-read-order}]))

(deftest first-stage-guard-closes-publication-and-generic-omission
  (doseq [{:keys [label events submission context ok? code]} (first-stage-guard-controls)]
    (testing label
      (let [result (invocation/verify events submission context)]
        (is (= ok? (:ok? result)))
        (is (= code (:code result)))
        (when-not ok?
          (is (nil? (:accepted-invocation result))))))))

(deftest first-stage-faults-can-have-healthy-LAST
  (doseq [{:keys [events]} (take 4 (first-stage-guard-controls))]
    (let [calls (vec (keep-indexed #(when (= "tool_use" (:type %2))
                                    (assoc %2 :position (inc %1))) events))]
      (is (= [] (invocation/last-read-violations calls))))))

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
  "Frozen pre-guard synthetic producer trace: tail input follows early stages.
   Literal host events, envelope and context are retained unchanged so strict
   chronology can refuse the old accepted shape without rewriting its events.
   Producer results are historical observations, never current admission."
  []
  {:events
 [{:type "tool_use",
   :timestamp 1,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-1",
    :call-id "call-1",
    :session-id "session-fixture",
    :tool "review_begin",
    :state
    {:status "completed",
     :input {},
     :output
     {:ok? true,
      :stage "deterministic",
      :stages
      ["deterministic"
       "map-change"
       "generate-candidates"
       "adversarial-validate"
       "publish"],
      :input-source
      {:schema "open-hax.review-input/v1",
       :base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       :head_sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
       :diff_base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       :full_diff
       {:path "basehead.diff",
        :bytes 1108,
        :sha256
        "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e"},
       :provenance
       {:repository "fixture/repo",
        :pull_request "1",
        :run_id "2",
        :run_attempt "1",
        :workflow_sha "dddddddddddddddddddddddddddddddddddddddd",
        :workflow_ref "fixture/repo/workflow@refs/pull/1/merge"}},
      :diff-stats {:files 1, :bytes 1108, :truncated? false},
      :input-coverage
      {:chunks 2, :delivered 0, :assessed 0, :missing [1 2]}}}}}
  {:type "tool_use",
   :timestamp 2,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-2",
    :call-id "call-2",
    :session-id "session-fixture",
    :tool "review_read_diff_chunk",
    :state
    {:status "completed",
     :input {:id 1},
     :output
     {:ok? true,
      :chunk
      {:id 1,
       :start 0,
       :end 1057,
       :text
       "diff --git a/tail b/tail\n--- a/tail\n+++ b/tail\n@@ -0,0 +1,130 @@\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n"},
      :restart-required? false}}}}
  {:type "tool_use",
   :timestamp 3,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-3",
    :call-id "call-3",
    :session-id "session-fixture",
    :tool "review_assess_diff_chunk",
    :state
    {:status "completed",
     :input {:id 1, :note "Prefix assessed before early stages."},
     :output
     {:ok? true,
      :chunk-id 1,
      :coverage
      {:chunks 2, :delivered 1, :assessed 1, :missing [2]}}}}}
  {:type "tool_use",
   :timestamp 4,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-4",
    :call-id "call-4",
    :session-id "session-fixture",
    :tool "review_record_evidence",
    :state
    {:status "completed",
     :input
     {:stage "deterministic", :note "Actual fixture gate evidence."},
     :output {:ok? true}}}}
  {:type "tool_use",
   :timestamp 5,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-5",
    :call-id "call-5",
    :session-id "session-fixture",
    :tool "review_record_evidence",
    :state
    {:status "completed",
     :input
     {:stage "map-change",
      :note "Prefix mapped; tail still untouched."},
     :output {:ok? true}}}}
  {:type "tool_use",
   :timestamp 6,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-6",
    :call-id "call-6",
    :session-id "session-fixture",
    :tool "review_record_evidence",
    :state
    {:status "completed",
     :input
     {:stage "generate-candidates",
      :note "Tail recovery remains possible."},
     :output {:ok? true}}}}
  {:type "tool_use",
   :timestamp 7,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-7",
    :call-id "call-7",
    :session-id "session-fixture",
    :tool "review_read_diff_chunk",
    :state
    {:status "completed",
     :input {:id 2},
     :output
     {:ok? true,
      :chunk
      {:id 2,
       :start 1057,
       :end 1108,
       :text
       "+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+tail-risk\n"},
      :restart-required? false}}}}
  {:type "tool_use",
   :timestamp 8,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-8",
    :call-id "call-8",
    :session-id "session-fixture",
    :tool "review_assess_diff_chunk",
    :state
    {:status "completed",
     :input {:id 2, :note "Untouched tail first-read and assessed."},
     :output
     {:ok? true,
      :chunk-id 2,
      :coverage {:chunks 2, :delivered 2, :assessed 2, :missing []}}}}}
  {:type "tool_use",
   :timestamp 9,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-9",
    :call-id "call-9",
    :session-id "session-fixture",
    :tool "review_record_evidence",
    :state
    {:status "completed",
     :input
     {:stage "adversarial-validate", :note "All input now assessed."},
     :output {:ok? true}}}}
  {:type "tool_use",
   :timestamp 10,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-10",
    :call-id "call-10",
    :session-id "session-fixture",
    :tool "review_record_evidence",
    :state
    {:status "completed",
     :input
     {:stage "publish", :note "Five actual host stages recorded."},
     :output {:ok? true}}}}
  {:type "tool_use",
   :timestamp 11,
   :session-id "session-fixture",
   :part
   {:type "tool",
    :id "part-11",
    :call-id "call-11",
    :session-id "session-fixture",
    :tool "review_submit",
    :state
    {:status "completed",
     :input {:summary "fixture summary"},
     :output
     {:ok? true,
      :event "APPROVE",
      :file "/fixture/submission.json",
      :inline-comments 0}}}}
  {:type "step_finish",
   :timestamp 12,
   :session-id "session-fixture",
   :part
   {:type "step-finish",
    :id "stop-12",
    :session-id "session-fixture",
    :reason "stop"}}],
 :submission
 {:schema "open-hax.github-review/v1",
  :event "APPROVE",
  :summary "fixture summary",
  :input-source
  {:schema "open-hax.review-input/v1",
   :base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
   :head_sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
   :diff_base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
   :full_diff
   {:path "basehead.diff",
    :bytes 1108,
    :sha256
    "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e"},
   :provenance
   {:repository "fixture/repo",
    :pull_request "1",
    :run_id "2",
    :run_attempt "1",
    :workflow_sha "dddddddddddddddddddddddddddddddddddddddd",
    :workflow_ref "fixture/repo/workflow@refs/pull/1/merge"}},
  :input-coverage {:chunks 2, :delivered 2, :assessed 2, :missing []},
  :input-assessments
  [{:id 1,
    :start 0,
    :end 1057,
    :note "Prefix assessed before early stages."}
   {:id 2,
    :start 1057,
    :end 1108,
    :note "Untouched tail first-read and assessed."}],
  :comments []},
 :context
 {:input-source
  {:schema "open-hax.review-input/v1",
   :base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
   :head_sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
   :diff_base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
   :full_diff
   {:path "basehead.diff",
    :bytes 1108,
    :sha256
    "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e"},
   :provenance
   {:repository "fixture/repo",
    :pull_request "1",
    :run_id "2",
    :run_attempt "1",
    :workflow_sha "dddddddddddddddddddddddddddddddddddddddd",
    :workflow_ref "fixture/repo/workflow@refs/pull/1/merge"}},
  :pages
  [{:id 1, :start 0, :end 1057} {:id 2, :start 1057, :end 1108}],
  :page-count 2,
  :full-input-sha256
  "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e",
  :review-tools
  ["review_assess_diff_chunk"
   "review_begin"
   "review_classify_finding"
   "review_propose_finding"
   "review_read_diff_chunk"
   "review_record_evidence"
   "review_status"
   "review_submit"],
  :submission-file "/fixture/submission.json"},
 :producer-results
 [{:ok? true,
   :chunk
   {:id 1,
    :start 0,
    :end 1057,
    :text
    "diff --git a/tail b/tail\n--- a/tail\n+++ b/tail\n@@ -0,0 +1,130 @@\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n"},
   :restart-required? false}
  {:ok? true,
   :chunk-id 1,
   :coverage {:chunks 2, :delivered 1, :assessed 1, :missing [2]}}
  {:ok? true}
  {:ok? true}
  {:ok? true}
  {:ok? true,
   :chunk
   {:id 2,
    :start 1057,
    :end 1108,
    :text "+prefix\n+prefix\n+prefix\n+prefix\n+prefix\n+tail-risk\n"},
   :restart-required? false}
  {:ok? true,
   :chunk-id 2,
   :coverage {:chunks 2, :delivered 2, :assessed 2, :missing []}}
  {:ok? true}
  {:ok? true}
  {:ok? true,
   :envelope
   {:schema "open-hax.github-review/v1",
    :event "APPROVE",
    :summary "fixture summary",
    :input-source
    {:schema "open-hax.review-input/v1",
     :base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
     :head_sha "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
     :diff_base_sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
     :full_diff
     {:path "basehead.diff",
      :bytes 1108,
      :sha256
      "deb92dbef040938d1016154de3228d8a684d5473cd3bb609945f7735f2e64b6e"},
     :provenance
     {:repository "fixture/repo",
      :pull_request "1",
      :run_id "2",
      :run_attempt "1",
      :workflow_sha "dddddddddddddddddddddddddddddddddddddddd",
      :workflow_ref "fixture/repo/workflow@refs/pull/1/merge"}},
    :input-coverage
    {:chunks 2, :delivered 2, :assessed 2, :missing []},
    :input-assessments
    [{:id 1,
      :start 0,
      :end 1057,
      :note "Prefix assessed before early stages."}
     {:id 2,
      :start 1057,
      :end 1108,
      :note "Untouched tail first-read and assessed."}],
    :comments []}}]})

(defn ordered-fixture-events [events]
  (mapv (fn [index event] (assoc event :timestamp (inc index))) (range) events))

(deftest untouched-tail-after-first-stage-is-refused
  (let [{:keys [events submission context producer-results]} (completion-boundary-fixture)
        result (verdict events submission context)]
    (is (every? :ok? producer-results) "Frozen pre-guard producer accepted this synthetic shape.")
    (is (= 2 (:page-count context)))
    (is (= invocation/stages
           (mapv #(get-in % [:part :state :input :stage])
                 (filter #(= "review_record_evidence" (get-in % [:part :tool])) events))))
    (is (= [] (:violations result)) "Every assessment follows its page's LAST read.")
    (is (false? (:ok? result)))
    (is (= :stage-chronology (:code result)))
    (is (nil? (:accepted-invocation result)))))

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
  (let [;; Isolate the later defensive gate without admitting an early stage.
        unassessed (assoc (review/begin "diff") :stage :adversarial-validate)
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

;; Length controls preserved; the frozen tail acceptance expectation above
;; is strengthened explicitly by the paired before-first-stage guard.
(defn length-events []
  (conj (vec (take 4 (trace))) (assoc-in (terminal 5) [:part :reason] "length")))

(def length-context (assoc context :session-id "session-fixture" :full-input-text "diff"))

(defn length-custody [ctx]
  {:invocation-state "completed" :exit-code 0 :submission-state "missing"
   :response-sha256 (apply str (repeat 64 "e"))
   :response-sha256-before (apply str (repeat 64 "e"))
   :response-sha256-after (apply str (repeat 64 "e"))
   :context-before ctx :context-after ctx})

(defn length-classification
  ([events] (length-classification events nil length-context (length-custody length-context)))
  ([events serialized ctx custody]
   (invocation/classify-length-ended-review events serialized ctx custody)))

(deftest length-eligibility-never-completes-or-accepts-a-review
  (doseq [n (range 1 6)]
    (let [events (conj (vec (take (+ 3 n) (trace)))
                       (assoc-in (terminal (+ 4 n)) [:part :reason] "length"))
          result (length-classification events)
          strict (verdict events nil context)]
      (is (:eligible? result))
      (is (= :length-ended-unfinished-review (:classification result)))
      (is (= :new-complete-review-invocation (:required-action result)))
      (is (= "length" (:terminal-reason result)))
      (is (= (vec (take n invocation/stages)) (:recorded-stages result)))
      (is (every? #(not (contains? result %)) [:ok? :accepted-invocation :approval]))
      (is (false? (:ok? strict)))
      (is (= :unterminated-host-trace (:code strict)))
      (is (nil? (:accepted-invocation strict))))))

(deftest length-classifier-reuses-canonical-host-source-call-and-stage-guards
  (doseq [[label change]
          [["unknown event" #(assoc-in % [0 :type] "error")]
           ["negative timestamp" #(assoc-in % [1 :timestamp] -1)]
           ["time reversal" #(assoc-in % [1 :timestamp] 0)]
           ["wrong session" #(-> % (assoc-in [1 :session-id] "other")
                                   (assoc-in [1 :part :session-id] "other"))]
           ["part session" #(assoc-in % [1 :part :session-id] "other")]
           ["duplicate call" #(assoc-in % [1 :part :call-id] "call-1")]
           ["duplicate part" #(assoc-in % [4 :part :id] "part-1")]
           ["failed HOST" #(assoc-in % [1 :part :state :status] "error")]
           ["HOST error" #(assoc-in % [1 :part :state :error] "EACCES")]
           ["unavailable" #(assoc-in % [1 :part :tool] "invalid")]
           ["unknown HOST" #(assoc-in % [1 :part :tool] "unknown_host")]
           ["unknown review" #(assoc-in % [1 :part :tool] "review_other")]
           ["missing output" #(assoc-in % [1 :part :state :output] nil)]
           ["malformed result" #(assoc-in % [1 :part :state :output :ok?] "true")]
           ["failed review" #(assoc-in % [2 :part :state :output] {:ok? false :error "refused"})]
           ["failed stage" #(assoc-in % [3 :part :state :output] {:ok? false :error "refused"})]
           ["wrong stage" #(assoc-in % [3 :part :state :input :stage] "map-change")]
           ["blank stage" #(assoc-in % [3 :part :state :input :note] " ")]
           ["source changed" #(assoc-in % [0 :part :state :output :input-source :head_sha]
                                         (apply str (repeat 40 "f")))]
           ["begin stages" #(assoc-in % [0 :part :state :output :stages] [])]
           ["begin geometry" #(assoc-in % [0 :part :state :output :diff-stats :bytes] 3)]
           ["truncation" #(assoc-in % [0 :part :state :output :diff-stats :truncated?] true)]
           ["unknown page" #(assoc-in % [1 :part :state :input :id] 2)]
           ["wrong returned page" #(assoc-in % [1 :part :state :output :chunk :end] 3)]
           ["wrong equal-length text" #(assoc-in % [1 :part :state :output :chunk :text] "dirt")]
           ["blank assessment" #(assoc-in % [2 :part :state :input :note] " ")]
           ["assessment count" #(assoc-in % [2 :part :state :output :coverage :assessed] 0)]
           ["incomplete returned coverage" #(assoc-in % [2 :part :state :output :coverage :missing] [1])]
           ["restart flag" #(assoc-in % [1 :part :state :output :restart-required?] true)]
           ["stop" #(assoc-in % [4 :part :reason] "stop")]
           ["unknown finish" #(assoc-in % [4 :part :reason] "unknown")]
           ["no final event" pop]]]
    (testing label
      (let [result (length-classification (change (length-events)))]
        (is (false? (:eligible? result)))
        (is (= :unestablished-length-ended-review (:classification result)))
        (is (nil? (:required-action result)))
        (is (not (contains? result :accepted-invocation)))))))

(deftest length-eligibility-requires-complete-input-before-first-recorded-stage
  (let [base (length-events)
        early (ordered-fixture-events [(base 0) (base 3) (base 1) (base 2) (base 4)])]
    (is (= :input-after-first-stage (:code (length-classification early))))
    (is (false? (:eligible? (length-classification early))))
    (doseq [events [(ordered-fixture-events [(base 0) (base 1) (base 3) (base 4)])
                    (ordered-fixture-events [(base 0) (base 2) (base 3) (base 4)])
                    [(assoc-in (terminal 1) [:part :reason] "length")]
                    (ordered-fixture-events (concat [(base 0) (base 0)] (rest base)))
                    (ordered-fixture-events [(base 0) (base 1) (base 2) (base 4)])]]
      (is (false? (:eligible? (length-classification events)))))
    (let [{:keys [events context]} (completion-boundary-fixture)
          partial (conj (vec (take 10 events)) (assoc-in (terminal 11) [:part :reason] "length"))
          ctx (assoc context :full-input-text (apply str (map #(get-in % [:part :state :output :chunk :text])
                                                            (filter #(= "review_read_diff_chunk" (get-in % [:part :tool])) events))))]
      (is (= :input-after-first-stage (:code (length-classification partial nil ctx (length-custody ctx))))
          "The unchanged complete-review chronology remains broader than this initial repair."))))

(deftest length-eligibility-preserves-every-LAST-violation
  (doseq [reassess? [false true]]
    (let [events (ordered-fixture-events
                  (concat (pop (length-events))
                          [(tool-event 6 "review_read_diff_chunk" {:id 1} read-output)]
                          (when reassess? [(tool-event 7 "review_assess_diff_chunk" {:id 1 :note "later"} assess-output)])
                          [(assoc-in (terminal 8) [:part :reason] "length")]))
          result (length-classification events)]
      (is (false? (:eligible? result)))
      (is (= :last-read-order (:code result)))
      (is (= 1 (count (:violations result))))
      (is (= 3 (:assessment-position (first (:violations result))))))))

(deftest length-eligibility-refuses-any-present-or-failed-submission
  (doseq [serialized [{} false submission]]
    (is (false? (:eligible? (length-classification (length-events) serialized length-context (length-custody length-context))))))
  (doseq [out [submit-output {:ok? false :error "actual submit failed"}]]
    (let [events (ordered-fixture-events
                  (concat (pop (length-events)) [(tool-event 6 "review_submit" {:summary "fixture summary"} out)
                                                 (assoc-in (terminal 7) [:part :reason] "length")]))]
      (is (= :submission-present (:code (length-classification events))))
      (is (false? (:eligible? (length-classification events)))))))

(deftest length-eligibility-requires-stable-completed-child-custody
  (doseq [custody [nil
                  (assoc (length-custody length-context) :exit-code 1)
                  (assoc (length-custody length-context) :invocation-state "unknown")
                  (assoc (length-custody length-context) :submission-state "present")
                  (assoc (length-custody length-context) :response-sha256-after (apply str (repeat 64 "f")))
                  (assoc (length-custody length-context) :response-sha256-before "malformed")
                  (assoc-in (length-custody length-context) [:context-after :submission-file] "/other")
                  (assoc-in (length-custody length-context) [:context-before :session-id] "other")]]
    (let [result (length-classification (length-events) nil length-context custody)]
      (is (false? (:eligible? result)))
      (is (nil? (:required-action result)))))
  (doseq [ctx [nil (dissoc length-context :full-input-text)
               (assoc length-context :session-id "other")
               (assoc length-context :page-count 2)
               (assoc length-context :pages [{:id 1 :start 1 :end 5}])
               (assoc length-context :review-tools ["review_begin"])]]
    (is (false? (:eligible? (length-classification (length-events) nil ctx (length-custody ctx)))))))

(deftest length-law-refuses-malformed-top-level-shapes-with-typed-output
  (doseq [events [nil 42 "events" {} (seq (length-events))]]
    (let [result (length-classification events)]
      (is (false? (:eligible? result)))
      (is (= :missing-host-events (:code result)))))
  (doseq [ctx [nil 42 "context" {} (assoc length-context :pages 42)
               (assoc length-context :pages "pages")]]
    (let [result (length-classification (length-events) nil ctx (length-custody ctx))]
      (is (false? (:eligible? result)))
      (is (= :trusted-context-unestablished (:code result))))))
