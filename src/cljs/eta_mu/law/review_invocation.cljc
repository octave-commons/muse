(ns eta-mu.law.review-invocation
  "Pure closure of one complete HOST tool trace. This is not all INPUT49,
   authentication, or authority to invoke a model. GPL-3.0-or-later."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr]))

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

(def ordinary-v2-limits
  "Fixed ordinary-v2 limits; manifests cannot increase them. V1/native unchanged."
  {:raw-diff-bytes 4194304 :summary-bytes 262144 :log-bytes 4194304
   :view-bytes 9437184 :pages 1024})

(def ordinary-v2-registry
  "Named transport contracts, independent of runtime admission. Coordinates and
   typed authorization alone do not authenticate a producer. Spans are half-open;
   byte spans count UTF-8 bytes, text spans count UTF-16 units. Empty roles remain."
  {::V2Span [:tuple [:int {:min 0 :max 9437184}] [:int {:min 0 :max 9437184}]]
   ::V2Descriptor [:map [:path :string] [:bytes [:int {:min 0 :max 9437184}]]
                   [:sha256 [:string {:min 64 :max 64}]]]
   ::V2Component [:map [:role :string] [:path :string]
                  [:bytes [:int {:min 0 :max 9437184}]]
                  [:sha256 [:string {:min 64 :max 64}]]
                  [:byte_span [:ref ::V2Span]] [:text_span [:ref ::V2Span]]]
   ::V2RawDiff [:and [:ref ::V2Component]
               [:map [:role [:= "raw-diff"]] [:path [:= "basehead.diff"]]
                [:bytes [:int {:min 0 :max 4194304}]]]]
   ::V2Summary [:and [:ref ::V2Component]
               [:map [:role [:= "summary"]] [:path [:= "summary.json"]]
                [:bytes [:int {:min 0 :max 262144}]]]]
   ::V2Log [:and [:ref ::V2Component]
           [:map [:role [:= "log"]] [:path [:= "deterministic.log"]]
            [:bytes [:int {:min 0 :max 4194304}]]]]
   ::V2View [:map [:bytes [:int {:min 0 :max 9437184}]]
             [:sha256 [:string {:min 64 :max 64}]]
             [:text_units [:int {:min 0 :max 9437184}]]
             [:page_count [:int {:min 0 :max 1024}]]]
   ::V2Producer [:map
                 [:repository [:string {:min 1}]]
                 [:head_sha [:string {:min 40 :max 64}]]
                 [:base_sha [:string {:min 40 :max 64}]]
                 [:diff_base_sha [:string {:min 40 :max 64}]]
                 [:workflow_path [:string {:min 1}]]
                 [:workflow_ref [:string {:min 1}]]
                 [:workflow_sha [:string {:min 40 :max 64}]]
                 [:run_id [:string {:min 1}]] [:run_attempt [:string {:min 1}]]
                 [:job_id [:string {:min 1}]] [:artifact_id [:string {:min 1}]]
                 [:artifact_zip_sha256 [:string {:min 64 :max 64}]]]
   ::OrdinaryV2 [:map
                 [:schema [:= "open-hax.review-input/v2"]]
                 [:base_sha [:string {:min 40 :max 64}]]
                 [:head_sha [:string {:min 40 :max 64}]]
                 [:diff_base_sha [:string {:min 40 :max 64}]]
                 [:full_diff [:ref ::V2Descriptor]]
                 [:components [:tuple [:ref ::V2RawDiff] [:ref ::V2Summary] [:ref ::V2Log]]]
                 [:view [:ref ::V2View]] [:producer [:ref ::V2Producer]]]
   ::V2TrustedContext [:map
                       [:producer [:ref ::V2Producer]]
                       [:reviewer_run_attempt [:string {:min 1}]]
                       [:content [:map
                                  [:authorization [:enum "public" "explicit"]]
                                  [:classification [:= "authorized"]]
                                  [:credential_canary [:= false]]]]]})

(defn ordinary-v2-validator [schema-name]
  (m/validator [:ref schema-name]
               {:registry (mr/composite-registry m/default-registry ordinary-v2-registry)}))

(def ^:private v2-shape-valid? (ordinary-v2-validator ::OrdinaryV2))
(def ^:private v2-trust-shape-valid? (ordinary-v2-validator ::V2TrustedContext))

(defn ordinary-v2-trusted-context-valid?
  "Typed caller context can select the bounded v2 entry before reading candidate
   metadata. Its native observations/authentication remain the caller's duty."
  [context]
  (boolean (and (v2-trust-shape-valid? context)
                (every? #(oid? (get (:producer context) %))
                        [:base_sha :head_sha :diff_base_sha :workflow_sha])
                (sha? 64 (get-in context [:producer :artifact_zip_sha256])))))

(defn ordinary-v2-contract-valid?
  "Describe descriptor integrity, not byte verification or native origin. Runtime
   adapters still must verify originals before admitting this contract."
  [source]
  (boolean
   (and (v2-shape-valid? source)
        (every? #(oid? (get source %)) [:base_sha :head_sha :diff_base_sha])
        (every? #(oid? (get (:producer source) %))
                [:base_sha :head_sha :diff_base_sha :workflow_sha])
        (= (select-keys source [:base_sha :head_sha :diff_base_sha])
           (select-keys (:producer source) [:base_sha :head_sha :diff_base_sha]))
        (= (:full_diff source) (select-keys (first (:components source)) [:path :bytes :sha256]))
        (sha? 64 (get-in source [:producer :artifact_zip_sha256]))
        (sha? 64 (get-in source [:view :sha256]))
        (loop [components (:components source) byte-start 0 text-start 0]
          (if-let [component (first components)]
            (let [[bs be] (:byte_span component) [ts te] (:text_span component)]
              (and (sha? 64 (:sha256 component)) (= bs byte-start) (= ts text-start)
                   (= (- be bs) (:bytes component)) (<= ts te)
                   (<= (- te ts) (:bytes component))
                   (recur (next components) be te)))
            (and (= byte-start (get-in source [:view :bytes]))
                 (= text-start (get-in source [:view :text_units]))))))))

(defn ordinary-v2-origin-bound?
  "Match a manifest to separately supplied trusted caller data. This pure law
   cannot establish where the caller obtained that data. Producing and reviewing
   attempts remain distinct; unchanged, independently bound earlier output is legal."
  [source trusted-context]
  (boolean
   (and (ordinary-v2-contract-valid? source)
        (ordinary-v2-trusted-context-valid? trusted-context)
        (= (:producer source) (:producer trusted-context)))))
(defn- success? [call] (true? (get-in call [:part :state :output :ok?])))
(defn- tool [call] (get-in call [:part :tool]))
(defn- input [call] (get-in call [:part :state :input]))
(defn- output [call] (get-in call [:part :state :output]))

(defn- provenance? [p]
  (and (map? p) (every? #(nonblank? (get p %))
                        [:repository :pull_request :run_id :run_attempt :workflow_ref])
       (oid? (:workflow_sha p))))

(defn- cohort-target? [target]
  (let [members (:cohort_targets target)]
    (and (map? target)
         (nonblank? (:repository target))
         (boolean (re-matches #"[^/\s]+/[^/\s]+" (:repository target)))
         (every? #(nonblank? (get target %)) [:repository_id :pr_id])
         (every? #(positive? (get target %)) [:repository_database_id :pr_number])
         (not-any? #(contains? target %) [:thread_id :root_comment_id])
         (vector? members) (seq members)
         ;; Validate rows before sorting/projecting: malformed scalars must refuse.
         (every? #(and (vector? %) (= 2 (count %))
                       (nonblank? (first %)) (positive? (second %))) members)
         (= members (vec (sort members)))
         (= (count members) (count (set members)))
         (= (count members) (count (set (map first members))))
         (= (count members) (count (set (map second members)))))))

(defn input-source-valid?
  "Validate the fixed transport manifest without projecting its descriptor.
   Coordinates establish shape only, not native authenticity or admission.
   Preserve ordinary empty input and complete SHA40-or-SHA64 commit OIDs."
  [source]
  (let [diff (:full_diff source) full (:full_input source) target (:target source)]
    (boolean
     (or
      (and (= "open-hax.review-input/v2" (:schema source))
           (ordinary-v2-contract-valid? source))
      (and (map? source) (= "open-hax.review-input/v1" (:schema source))
           (every? #(oid? (get source %)) [:base_sha :head_sha :diff_base_sha])
           (map? diff) (= "basehead.diff" (:path diff))
           (integer? (:bytes diff)) (<= 0 (:bytes diff)) (sha? 64 (:sha256 diff)))
      (and (map? source)
           (or (and (= "open-hax.actionability-input/v1" (:schema source))
                    (= "actionability" (:purpose source))
                    (= "actionability-input.txt" (:path full)))
               (and (= "open-hax.rejection-assessment-input/v1" (:schema source))
                    (= "rejection-assessment" (:purpose source))
                    (= "rejection-assessment-input.txt" (:path full)))
               (and (= "open-hax.positive-cohort-assessment-input/v1" (:schema source))
                    (= "positive-cohort-assessment" (:purpose source))
                    (= "positive-cohort-assessment-input.txt" (:path full))))
           (not (contains? source :full_diff))
           (oid? (:head_sha source)) (positive? (:proposal_id source))
           (every? #(sha? 64 (get source %))
                   [:context_sha256 :proposal_body_sha256 :source_inventory_sha256])
           (if (= "open-hax.positive-cohort-assessment-input/v1" (:schema source))
             (cohort-target? target)
             (and (map? target)
                  (not (contains? target :cohort_targets))
                  (every? #(nonblank? (get target %)) [:repository_id :pr_id :thread_id])
                  (every? #(positive? (get target %)) [:repository_database_id :pr_number :root_comment_id])))
           (provenance? (:provenance source))
           (map? full)
           (positive? (:bytes full)) (sha? 64 (:sha256 full)))))))

(defn context-valid?
  "Validate caller-bound, canonically prepared geometry; this is not native
   source authority. Preserve the existing manifest's SHA40-or-SHA64 grammar."
  [{:keys [input-source pages page-count full-input-sha256 review-tools submission-file]}]
  (boolean (and (input-source-valid? input-source) (provenance? (:provenance input-source))
       (or (and (= "open-hax.review-input/v1" (:schema input-source))
                (natural? (get-in input-source [:full_diff :bytes]))
                (= (get-in input-source [:full_diff :sha256]) full-input-sha256))
           (and (#{"open-hax.actionability-input/v1"
                   "open-hax.rejection-assessment-input/v1"
                   "open-hax.positive-cohort-assessment-input/v1"} (:schema input-source))
                (= (get-in input-source [:full_input :sha256]) full-input-sha256)))
       (vector? pages)
       (or (= "open-hax.review-input/v1" (:schema input-source)) (boolean (seq pages)))
       (= (count pages) page-count)
       (loop [remaining pages id 1 start 0]
         (if-let [page (first remaining)]
           (and (map? page) (= id (:id page)) (= start (:start page))
                (natural? (:start page)) (positive? (:end page)) (< start (:end page))
                (recur (next remaining) (inc id) (:end page)))
           true))
       (vector? review-tools) (every? nonblank? review-tools)
       (= (count review-tools) (count (set review-tools)))
       (every? (set review-tools) eta-mu.law.review-invocation/review-tools)
       (nonblank? submission-file))))

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
                         (when (and submission (not (submit-bound? submission context submits))) :actual-submit-binding))))

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
