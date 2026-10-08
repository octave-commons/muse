(ns eta-mu.law.review-invocation
  "Pure closure of one complete HOST tool trace. This is not all INPUT49,
   authentication, or authority to invoke a model. GPL-3.0-or-later."
  (:require [clojure.string :as str]))

(def stages ["deterministic" "map-change" "generate-candidates"
             "adversarial-validate" "publish"])

(def review-tools #{"review_begin" "review_read_diff_chunk"
                    "review_assess_diff_chunk" "review_record_evidence"
                    "review_propose_finding" "review_classify_finding"
                    "review_status" "review_submit"})

(def host-tools
  "Known generic host tools. Additional names require actual staged registry
   membership; nested tool output remains data, never a control stream."
  #{"bash" "read" "glob" "grep" "list" "edit" "write" "patch" "apply_patch"
    "todowrite" "todoread" "webfetch" "websearch" "task" "skill"})

(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))
(defn- natural? [x] (and (integer? x) (<= 0 x 9007199254740991)))
(defn- positive? [x] (and (natural? x) (pos? x)))
(defn- sha? [n x] (and (string? x) (boolean (re-matches (re-pattern (str "[0-9a-f]{" n "}")) x))))
(defn- oid? [x] (or (sha? 40 x) (sha? 64 x)))
(defn- success? [call] (true? (get-in call [:part :state :output :ok?])))
(defn- tool [call] (get-in call [:part :tool]))
(defn- input [call] (get-in call [:part :state :input]))
(defn- output [call] (get-in call [:part :state :output]))

(def restricted-host-read-profile
  "Explicit restricted-review contract, bound to the actual Eta HOST EDN source.
   Selection is trusted caller data, never a model event or recovery authority."
  {:id "eta-mu.restricted-host-read/v1"
   :source-sha256 "2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7"})

(defn host-read-profile-valid?
  "Only this exact source-bound profile is supported; absence stays generic."
  [profile]
  (= restricted-host-read-profile profile))

(defn context-valid?
  "Validate caller-bound, canonically prepared geometry; this is not native
   source authority. Preserve the existing manifest's SHA40-or-SHA64 grammar."
  [{:keys [input-source pages page-count full-input-sha256 review-tools submission-file] :as context}]
  (let [p (:provenance input-source) d (:full_diff input-source)]
    (and (map? input-source) (= "open-hax.review-input/v1" (:schema input-source))
         (every? #(oid? (get input-source %)) [:base_sha :head_sha :diff_base_sha])
         (map? d) (= "basehead.diff" (:path d)) (natural? (:bytes d)) (sha? 64 (:sha256 d))
         (map? p) (every? #(nonblank? (get p %))
                         [:repository :pull_request :run_id :run_attempt :workflow_ref])
         (oid? (:workflow_sha p))
         (vector? pages)
         (= (count pages) page-count) (= (:sha256 d) full-input-sha256)
         (loop [remaining pages id 1 start 0]
           (if-let [page (first remaining)]
             (and (map? page) (= id (:id page)) (= start (:start page))
                  (natural? (:start page)) (positive? (:end page)) (< start (:end page))
                  (recur (next remaining) (inc id) (:end page)))
             true))
         (vector? review-tools) (every? nonblank? review-tools)
         (= (count review-tools) (count (set review-tools)))
         (every? (set review-tools) eta-mu.law.review-invocation/review-tools)
         (nonblank? submission-file)
         (or (not (contains? context :host-read-profile))
             (host-read-profile-valid? (:host-read-profile context))))))

(defn- event-error ([events expected-session] (event-error events expected-session "stop"))
  ([events expected-session terminal-reason]
   (let [sessions (set (map :session-id events))
        calls (filter #(= "tool_use" (:type %)) events)
        call-ids (map #(get-in % [:part :call-id]) calls)
        part-ids (map #(get-in % [:part :id]) calls)]
    (cond
      (not (and (vector? events) (seq events))) :missing-host-events
      (some #(not (and (map? %) (#{"step_start" "step_finish" "text" "tool_use"} (:type %))
                        (natural? (:timestamp %)) (nonblank? (:session-id %))
                        (map? (:part %)) (nonblank? (get-in % [:part :id]))
                        (= (:session-id %) (get-in % [:part :session-id]))
                        (= ({"step_start" "step-start" "step_finish" "step-finish"
                             "text" "text" "tool_use" "tool"} (:type %))
                           (get-in % [:part :type])))) events) :host-event-schema
      (not (apply <= (map :timestamp events))) :host-event-time-order
      (not= 1 (count sessions)) :host-session-binding
      (and expected-session (not= sessions #{expected-session})) :expected-session-binding
      (or (not (every? nonblank? call-ids)) (not= (count call-ids) (count (set call-ids)))
          (not= (count part-ids) (count (set part-ids)))) :host-call-cardinality
      (some #(not (and (= "tool" (get-in % [:part :type]))
                        (nonblank? (tool %)) (= "completed" (get-in % [:part :state :status]))
                        (map? (input %)) (not (get-in % [:part :state :error])))) calls) :host-tool-schema
      (not (and (= "step_finish" (:type (last events)))
                (= terminal-reason (get-in (last events) [:part :reason])))) :unterminated-host-trace
      :else nil))))

(defn- call-error [calls {:keys [pages review-tools]}]
  (let [page-by-id (into {} (map (juxt :id identity) pages))]
    (some
     (fn [call]
       (let [name (tool call) args (input call) result (output call) id (:id args)
             page (get page-by-id id)]
         (cond
           (= "invalid" name) :unavailable-host-tool
           (and (str/starts-with? name "review_") (not (contains? (set review-tools) name))) :unexposed-review-tool
           (and (str/starts-with? name "review_") (not (contains? eta-mu.law.review-invocation/review-tools name))) :unknown-review-tool
           (and (not (str/starts-with? name "review_"))
                (not (or (contains? host-tools name) (contains? (set review-tools) name)))) :unknown-host-tool
           (not (str/starts-with? name "review_")) (when-not (string? result) :host-tool-output)
           (not (and (map? result) (or (true? (:ok? result)) (false? (:ok? result))))) :review-tool-output
           (and (false? (:ok? result)) (not (nonblank? (:error result)))) :review-tool-output
           (and (#{"review_read_diff_chunk" "review_assess_diff_chunk"} name)
                (not (and (positive? id) page))) :page-id-binding
           (and (= "review_assess_diff_chunk" name) (not (nonblank? (:note args)))) :assessment-note-shape
           (and (= "review_record_evidence" name)
                (not (and ((set stages) (:stage args)) (nonblank? (:note args))))) :stage-input-shape
           (and (= "review_submit" name) (not (nonblank? (:summary args)))) :submission-call-shape
           (and (= "review_read_diff_chunk" name) (success? call)
                (not (and (map? (:chunk result))
                          (= (select-keys page [:id :start :end]) (select-keys (:chunk result) [:id :start :end]))
                          (string? (get-in result [:chunk :text]))
                          (= (- (:end page) (:start page)) (count (get-in result [:chunk :text])))))) :returned-page-binding
           (and (= "review_assess_diff_chunk" name) (success? call)
                (not (and (= id (:chunk-id result))
                          (map? (:coverage result)) (= (count pages) (get-in result [:coverage :chunks]))
                          (every? #(and (natural? %) (<= % (count pages)))
                                  [(get-in result [:coverage :delivered]) (get-in result [:coverage :assessed])])
                          (vector? (get-in result [:coverage :missing]))
                          (every? page-by-id (get-in result [:coverage :missing]))))) :returned-assessment-binding
           :else nil)))
     calls)))

(defn- host-read-receipt-error
  "Validate actual structured file display against the complete returned text.
   Preview and requested limit cannot witness content or EOF. Capped intermediate
   totals are per-receipt data, never a constant whole-file size."
  [call]
  (let [args (input call) path (:filePath args)
        metadata (get-in call [:part :state :metadata]) display (:display metadata)
        start (:lineStart display) end (:lineEnd display) total (:totalLines display)
        truncated? (:truncated display) text (:text display)]
    (cond
      (not (and (nonblank? path) (map? metadata)
                (= #{:preview :truncated :loaded :display} (set (keys metadata)))
                (string? (:preview metadata)) (vector? (:loaded metadata))
                (every? string? (:loaded metadata))
                (map? display)
                (= #{:type :path :text :lineStart :lineEnd :totalLines :truncated}
                   (set (keys display)))
                (= "file" (:type display)) (= path (:path display))
                (string? text) (positive? start) (natural? end) (natural? total)
                (<= end total)
                (or (<= start end) (= [1 0 0] [start end total]))
                (or (true? truncated?) (false? truncated?))
                (= truncated? (:truncated metadata)))) :host-read-receipt-schema

      ;; The current profile supports the observed text-file formatter without
      ;; loaded-instruction reminders. Such a suffix is source-supported, but
      ;; deliberately unestablished here rather than silently dropped.
      (seq (:loaded metadata)) :host-read-unsupported-shape

      (and (contains? args :offset)
           (not (and (positive? (:offset args)) (= start (:offset args))))) :host-read-range-binding

      :else
      (let [lines (if (= [1 0 0] [start end total]) [] (str/split text #"\n" -1))
            body (str "<path>" path "</path>\n<type>file</type>\n<content>\n"
                      (str/join "\n" (map-indexed #(str (+ start %1) ": " %2) lines))
                      "\n\n")
            suffix "\n</content>"
            trailers (if truncated?
                       [(str "(Showing lines " start "-" end " of " total
                             ". Use offset=" (inc end) " to continue.)")
                        (str "(Output capped at 50 KB. Showing lines " start "-" end
                             ". Use offset=" (inc end) " to continue.)")]
                       (when (= end total) [(str "(End of file - total " total " lines)")]))]
        (cond
          ;; OpenCode can clip a line while reporting truncated=false. The
          ;; identical literal suffix is ambiguous; neither case proves the
          ;; original unabridged line. No omitted bytes are reconstructed.
          (some #(str/ends-with? % "... (line truncated to 2000 chars)") lines)
          :host-read-clipped-content

          (not (and (= (count lines) (inc (- end start)))
                    (or (seq lines) (empty? text))
                    (some #(= (output call) (str body % suffix)) trailers)))
          :host-read-content-binding
          :else nil)))))

(defn- host-read-protocol-error
  "Fold all opened supporting-read chains, including calls after FIRST.
   Close each at actual EOF by whole-trace terminal. A completed file may be
   reread as a fresh chain from 1. No unobserved files or receipts are required."
  [calls]
  (loop [remaining (seq calls) opened {}]
    (if-let [call (first remaining)]
      (cond
        (= "read" (tool call))
        (let [args (input call) path (:filePath args)
              display (get-in call [:part :state :metadata :display])
              start (:lineStart display) pending (get opened path)
              error (or (host-read-receipt-error call)
                        (if pending
                          (when-not (and (contains? args :offset)
                                         (= pending (:offset args)) (= pending start))
                            :host-read-continuation)
                          (when-not (= 1 start) :host-read-start-offset)))]
          (if error error
            (recur (next remaining)
                   (if (:truncated display)
                     (assoc opened path (inc (:lineEnd display)))
                     (dissoc opened path)))))

        :else (recur (next remaining) opened))
      (when (seq opened) :host-read-unfinished))))

(defn last-read-violations
  "Original LAST predicate over ALL calls, including failed read/assessment
   calls and calls around resets. Never partition by host session or keep only
   the final assessment. Positions are complete host event positions."
  [calls]
  (let [last-read (into {} (for [c calls :when (= "review_read_diff_chunk" (tool c))]
                            [(:id (input c)) (:position c)]))]
    (vec (for [c calls :when (= "review_assess_diff_chunk" (tool c))
               :let [r (get last-read (:id (input c)))]
               :when (not (and r (< r (:position c))))]
           {:id (:id (input c)) :assessment-position (:position c)
            :last-read-position r}))))

(defn- witnessed-stale? [calls violations]
  (some (fn [{:keys [id assessment-position last-read-position]}]
          (and last-read-position (> last-read-position assessment-position)
               (some #(and (= assessment-position (:position %)) (success? %)) calls)
               (some #(and (= "review_read_diff_chunk" (tool %)) (success? %)
                           (= id (:id (input %))) (> (:position %) assessment-position)) calls)
               (some #(and (= "review_read_diff_chunk" (tool %)) (success? %)
                           (= id (:id (input %))) (< (:position %) assessment-position)) calls)))
        violations))

(defn- begin-admitted? [calls review-calls begins]
  (let [successful (filterv success? begins) first-begin (first successful)]
    (and (= 1 (count successful)) (= first-begin (first review-calls))
         (every? (fn [c]
                   (or (= c first-begin)
                       (and (false? (:ok? (output c))) (true? (:restart-required? (output c)))
                            (> (:position c) (:position first-begin))
                            (let [prior (filterv #(< (:position %) (:position c)) calls)]
                              (witnessed-stale? prior (last-read-violations prior))))))
                 begins))))

(defn- serialized-error [submission context calls]
  (when submission
    (let [pages (:pages context) notes (into {} (for [c calls :when (and (= "review_assess_diff_chunk" (tool c)) (success? c))]
                                                [(:id (input c)) (:note (input c))]))
          assessments (:input-assessments submission)]
      (cond
        (not (and (map? submission) (= "open-hax.github-review/v1" (:schema submission))
                  (#{"APPROVE" "COMMENT" "REQUEST_CHANGES"} (:event submission))
                  (nonblank? (:summary submission)) (vector? (:comments submission)))) :serialized-submission-schema
        (not= (:input-source context) (:input-source submission)) :serialized-source-binding
        (not (and (vector? assessments)
                  (= (mapv #(select-keys % [:id :start :end]) pages)
                     (mapv #(select-keys % [:id :start :end]) assessments))
                  (every? #(and (nonblank? (:note %)) (= (get notes (:id %)) (:note %))) assessments)
                  (= {:chunks (count pages) :delivered (count pages) :assessed (count pages) :missing []}
                     (:input-coverage submission)))) :serialized-assessment-binding
        :else nil))))

(defn- submit-bound? [submission context submits]
  (let [call (first submits) result (output call)]
    (and (= 1 (count submits)) (success? call)
         (= (:summary submission) (:summary (input call)))
         (= (:event submission) (:event result))
         (= (:submission-file context) (:file result))
         (= (count (:comments submission)) (:inline-comments result)))))

(defn- invocation-error
  "Shared canonical guards; the caller selects a terminal reason without
   rewriting any event. Full-review verify still requires stop."
  [events submission context calls review-calls begins submits stage-calls
   stage-names violations begin began terminal-reason]
  (cond
               (not (context-valid? context)) :trusted-context-unestablished
               :else (or (event-error events (:session-id context) terminal-reason)
                         (call-error calls context)
                         (when (and (seq review-calls) (not (begin-admitted? calls review-calls begins))) :begin-cardinality)
                         (when (and begin (not (and (= "deterministic" (:stage began)) (= stages (:stages began))
                                         (= (:input-source context) (:input-source began))
                                         (= (or (:end (last (:pages context))) 0) (get-in began [:diff-stats :bytes]))
                                         (= {:chunks (count (:pages context)) :delivered 0 :assessed 0
                                             :missing (mapv :id (:pages context))}
                                            (:input-coverage began))))) :begin-source-binding)
                         (when-not (and (<= (count stage-calls) 5)
                                         (= stage-names (vec (take (count stage-calls) stages)))
                                         (every? success? stage-calls)) :stage-order)
                         (when (> (count submits) 1) :submit-cardinality)
                         (when (and (some #(true? (:restart-required? (output %))) review-calls)
                                    (not (witnessed-stale? calls violations))) :restart-without-host-witness)
                         (serialized-error submission context calls)
                         (when (and submission (not (submit-bound? submission context submits))) :actual-submit-binding)
                         (when (contains? context :host-read-profile)
                           (host-read-protocol-error calls)))))

(defn- input-before-first-stage?
  "All expected pages and every observed input call must precede stage one.
   No recorded stage is an unfinished prefix; empty verified input is vacuous."
  [review-calls stage-calls context]
  (if-let [first-stage (first stage-calls)]
    (let [reads (filterv #(= "review_read_diff_chunk" (tool %)) review-calls)
          assessed (filterv #(= "review_assess_diff_chunk" (tool %)) review-calls)
          ids (set (map :id (:pages context)))]
      (and (= ids (set (map #(get (input %) :id) reads)))
           (= ids (set (map #(get (input %) :id) assessed)))
           (every? #(< (:position %) (:position first-stage)) (concat reads assessed))))
    true))

(defn verify
  "Consume shaped host events, optional actual submission, trusted full-input
   context. :stale-review-coverage is an observed cause, never retry authority.
   Missing/malformed/session/source/cardinality evidence is unestablished."
  [events submission context]
  (let [calls (vec (keep-indexed #(when (= "tool_use" (:type %2)) (assoc %2 :position (inc %1))) events))
        review-calls (filterv #(contains? review-tools (tool %)) calls)
        begins (filterv #(= "review_begin" (tool %)) review-calls)
        submits (filterv #(= "review_submit" (tool %)) review-calls)
        stage-calls (filterv #(= "review_record_evidence" (tool %)) review-calls)
        stage-names (mapv #(get (input %) :stage) stage-calls)
        violations (last-read-violations calls)
        begin (first (filter success? begins)) began (output begin)
        code (invocation-error events submission context calls review-calls begins
                               submits stage-calls stage-names violations begin began "stop")
        refuse (fn [kind reason] {:ok? false :reason-kind kind :code reason :violations violations})]
    (cond
      code (refuse :unestablished-review-trace code)
      (and (seq violations) (witnessed-stale? calls violations)) (refuse :stale-review-coverage :last-read-order)
      (seq violations) (refuse :unestablished-review-trace :read-assessment-not-established)
      (not (every? success? review-calls)) (refuse :unestablished-review-trace :failed-review-tool)
      (not (input-before-first-stage? review-calls stage-calls context))
      (refuse :unestablished-review-trace :stage-chronology)
      (and (nil? submission) (empty? submits))
      (refuse :missing-review-submit :healthy-unfinished-review)
      (not begin) (refuse :unestablished-review-trace :begin-not-established)
      (not= stages stage-names) (refuse :unestablished-review-trace :incomplete-five-stages)
      (not (and submission (= 1 (count submits))
                (= "review_submit" (tool (last review-calls))))) (refuse :unestablished-review-trace :submission-not-established)
      :else
      (let [submit (first submits)
            reads (filterv #(= "review_read_diff_chunk" (tool %)) review-calls)
            assessed (filterv #(= "review_assess_diff_chunk" (tool %)) review-calls)
            ids (set (map :id (:pages context)))]
        (cond
          (not (and (= ids (set (map #(get (input %) :id) reads)))
                    (= ids (set (map #(get (input %) :id) assessed))))) (refuse :unestablished-review-trace :incomplete-page-calls)
          (not (and (< (:position begin) (:position (first stage-calls)))
                    (< (:position (last stage-calls)) (:position submit)))) (refuse :unestablished-review-trace :stage-chronology)
          (not (submit-bound? submission context submits)) (refuse :unestablished-review-trace :actual-submit-binding)
          :else {:ok? true :reason-kind nil :code :verified-review-invocation
                 :session-id (:session-id (first events)) :review-call-count (count review-calls)
                 :page-count (count ids) :violations []
                 :accepted-invocation {:session-id (:session-id (first events))
                                       :submission-call-id (get-in submit [:part :call-id])
                                       :submission-position (:position submit)
                                       :submission-file (:submission-file context)
                                       :full-input-sha256 (:full-input-sha256 context)
                                       :page-count (count ids)}})))))

(defn classify-length-ended-review
  "Classify ONLY eligibility for a new complete invocation, never acceptance.
   Context includes boundary-verified :full-input-text. Custody is caller-trusted
   completed-child evidence and response/context snapshots before/after checking;
   data cannot authenticate the caller or independently establish native source.
   No attempt bound, invocation, retention or old review credit is granted."
  [events submission context custody]
  (if-not (and (vector? events) (context-valid? context))
    {:eligible? false :classification :unestablished-length-ended-review
     :code (if (vector? events) :trusted-context-unestablished :missing-host-events)
     :violations []}
    (let [calls (vec (keep-indexed #(when (= "tool_use" (:type %2))
                                    (assoc %2 :position (inc %1))) events))
          review-calls (filterv #(contains? review-tools (tool %)) calls)
          begins (filterv #(= "review_begin" (tool %)) review-calls)
          submits (filterv #(= "review_submit" (tool %)) review-calls)
          stage-calls (filterv #(= "review_record_evidence" (tool %)) review-calls)
          stage-names (mapv #(get (input %) :stage) stage-calls)
          violations (last-read-violations calls)
          begin (first (filter success? begins))
          reads (filterv #(= "review_read_diff_chunk" (tool %)) review-calls)
          assessed (filterv #(= "review_assess_diff_chunk" (tool %)) review-calls)
          ids (set (map :id (:pages context)))
          text (:full-input-text context)
          code (or (invocation-error events submission context calls review-calls
                                     begins submits stage-calls stage-names violations
                                     begin (output begin) "length")
                   (when-not (and (= "completed" (:invocation-state custody))
                                  (= 0 (:exit-code custody))
                                  (= "missing" (:submission-state custody)))
                     :completed-missing-submit-child-not-established)
                   (when-not (and (sha? 64 (:response-sha256 custody))
                                  (= (:response-sha256 custody)
                                     (:response-sha256-before custody)
                                     (:response-sha256-after custody))
                                  (= context (:context-before custody) (:context-after custody)))
                     :invocation-custody-changed)
                   (when (or (some? submission) (:submission-present? custody) (seq submits))
                     :submission-present)
                   (when-not (= (count events) (count (set (map #(get-in % [:part :id]) events))))
                     :host-part-cardinality)
                   (when-not (and (= 1 (count (filter #(and (= "step_finish" (:type %))
                                                          (= "length" (get-in % [:part :reason]))) events)))
                                  (every? #(contains? #{"stop" "tool-calls" "length"}
                                                     (get-in % [:part :reason]))
                                          (filter #(= "step_finish" (:type %)) events)))
                     :host-finish-reason)
                   (when (seq violations) :last-read-order)
                   (when-not (every? success? review-calls) :failed-review-tool)
                   (when-not begin :begin-not-established)
                   (when-not (false? (get-in (output begin) [:diff-stats :truncated?]))
                     :full-input-not-established)
                   (when-not (and (seq ids) (string? text)
                                  (= (count text) (:end (last (:pages context)))))
                     :full-input-not-established)
                   (when-not (and (= ids (set (map #(get (input %) :id) reads)))
                                  (= ids (set (map #(get (input %) :id) assessed))))
                     :incomplete-page-calls)
                   (when-not (every? #(let [{:keys [start end text]} (:chunk (output %))]
                                       (= text (subs (:full-input-text context) start end))) reads)
                     :returned-page-content)
                   (when-not (seq stage-calls) :first-stage-not-established)
                   (when-not (and (< (:position begin) (:position (first stage-calls)))
                                  (every? #(< (:position %) (:position (first stage-calls)))
                                          (concat reads assessed)))
                     :input-after-first-stage)
                   (when-not (= {:chunks (count ids) :delivered (count ids)
                                 :assessed (count ids) :missing []}
                                (:coverage (output (last assessed))))
                     :complete-assessment-coverage-not-established))]
      (if code
        {:eligible? false :classification :unestablished-length-ended-review
         :code code :violations violations}
        {:eligible? true :classification :length-ended-unfinished-review
         :code :new-complete-invocation-required :violations []
         :required-action :new-complete-review-invocation
         :session-id (:session-id (first events)) :page-count (count ids)
         :recorded-stages stage-names :terminal-position (count events)
         :terminal-reason "length" :response-sha256 (:response-sha256 custody)
         :full-input-sha256 (:full-input-sha256 context)}))))
