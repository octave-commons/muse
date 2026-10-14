(ns eta-mu.domain.review
  "Pure state machine and laws for the evidence-first pull-request reviewer.

   The reviewer never publishes text. It drives a review session through the
   fixed stages via tool calls; each call is validated here. The only output
   artifact is a machine-written submission envelope
   (schema open-hax.github-review/v1) produced by `submission`.

   No I/O lives in this namespace."
  (:require [clojure.string :as str]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.shape.git-diff-path :as diff-path]
            [eta-mu.shape.review-input :as input-shape]))

;; ---------------------------------------------------------------------------
;; Stages
;; ---------------------------------------------------------------------------

(def stages
  "The one bounded pass every review makes."
  [:deterministic :map-change :generate-candidates :adversarial-validate :publish])

(def severities #{"critical" "high" "medium" "low"})
(def categories #{"semantic-regression" "security" "contract" "state-transition" "test-gap"})
(def classifications #{"confirmed" "rejected" "needs-human"})

(def confirmation-confidence-threshold 0.85)
(def max-inline-comments 50)

;; ---------------------------------------------------------------------------
;; Diff indexing (added lines on the PR head side)
;; ---------------------------------------------------------------------------

(defn- parse-hunk-header [row]
  (when-let [[_ new-start] (re-find #"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@" row)]
    (parse-long new-start)))

(defn parse-diff-added-lines
  "Parse unified diff text into {path #{added-line-numbers}} for the PR head
   side. Only `+` content lines inside hunks count; file headers do not."
  [diff-text]
  (if (str/blank? diff-text)
    {}
    (loop [rows     (str/split diff-text #"\n")
           path     nil
           new-line nil
           acc      {}]
      (if-let [row (first rows)]
        (cond
          (str/starts-with? row "diff --git ")
          (recur (rest rows) nil nil acc)

          (and (nil? new-line) (str/starts-with? row "+++ "))
          (recur (rest rows) (diff-path/normalize (subs row 4)) nil acc)

          (str/starts-with? row "@@ ")
          (recur (rest rows) path (parse-hunk-header row) acc)

          (or (nil? path) (nil? new-line))
          (recur (rest rows) path new-line acc)

          (str/starts-with? row "\\ No newline at end of file")
          (recur (rest rows) path new-line acc)

          (str/starts-with? row "+")
          (recur (rest rows) path (inc new-line)
                 (update acc path (fnil conj #{}) new-line))

          (str/starts-with? row "-")
          (recur (rest rows) path new-line acc)

          :else
          (recur (rest rows) path (inc new-line) acc))
        acc))))

;; ---------------------------------------------------------------------------
;; Session
;; ---------------------------------------------------------------------------

(def diff-chunks
  "Existing public pagination API, shared with transport shaping; state unchanged."
  input-shape/diff-chunks)

(defn begin
  "Start over original diff, or separately prepared ordinary-v2 components.
   The caller/byte edge owns admission; support never contributes locations."
  ([diff-text]
  (let [changed (parse-diff-added-lines diff-text)]
    {:stage         :deterministic
     :evidence      []
     :candidates    {}
     :candidate-order []
     :diff-chunks (diff-chunks diff-text)
     :delivered-chunks #{}
     :assessed-chunks {}
     :invalidated-chunks #{}
     :changed-lines changed
     :diff-stats    {:files          (count (re-seq #"(?m)^diff --git " (or diff-text "")))
                     :bytes          (count (or diff-text ""))
                     :truncated?     (boolean (and diff-text (re-find #"(?m)^\[eta-mu review\] diff truncated at [0-9]+ bytes \(was [0-9]+\)\.$" diff-text)))}}))
  ([text {:keys [manifest components view raw-diff] :as prepared}]
   (let [pages (input-shape/component-chunks manifest text)]
     (when-not (and (= text (:text prepared))
                   (= raw-diff (subs text 0 (second (:text_span (first components)))))
                   (invocation/component-pages-valid? manifest components view pages))
       (throw (ex-info "Prepared component geometry differs from original input" {})))
     (assoc (begin raw-diff) :diff-chunks pages :input-source manifest
            :components components :view view :empty-assessments {}))))

(defn- err [msg] {:ok? false :error msg})

(defn- non-blank [s] (and (string? s) (not (str/blank? s))))

(defn input-coverage
  "Delivery and assessment are separate observations, never proof by metadata."
  [session]
  (invocation/component-coverage (:diff-chunks session) (:components session)
                                 (:delivered-chunks session)
                                 (set (keys (:assessed-chunks session)))
                                 (set (keys (:empty-assessments session)))))

(defn restart-required?
  "Whether an admitted session has a latched restart or reread violation."
  [session]
  (boolean (or (:restart-error session) (seq (:invalidated-chunks session)))))

(defn reject-restart
  "Latch refusal of a second admission without discarding session history."
  [session]
  (let [message "A review session has already been admitted in this invocation. Start a fresh invocation; this session cannot be restarted."]
    {:ok? false :session (assoc session :restart-error message)
     :restart-required? true :error message}))

(defn read-diff-chunk
  "Deliver one immutable input page. A read after assessment permanently
   invalidates that page and requires a fresh invocation."
  [session id]
  (if-let [chunk (when (and (integer? id) (pos? id)) (get (:diff-chunks session) (dec id)))]
    (let [invalidate? (or (contains? (:assessed-chunks session) id)
                          (contains? (:invalidated-chunks session) id))
          session (cond-> (update session :delivered-chunks conj id)
                    invalidate? (update :invalidated-chunks conj id)
                    invalidate? (update :assessed-chunks dissoc id))]
      {:ok? true :session session :chunk chunk
       :restart-required? (restart-required? session)})
    (err "Unknown diff chunk; use the chunk count returned by review_begin.")))

(defn- chronology-error [session]
  (or (:restart-error session)
      (when (seq (:invalidated-chunks session))
        "A diff chunk was read after assessment. Start a fresh invocation; this session's chronology cannot be restored.")))

(defn assess-diff-chunk
  "Record a substantive model assessment only after delivery, while the
   session has no latched restart or read-after-assessment violation and
   before the first deterministic evidence advances the stage."
  ([session id note] (assess-diff-chunk session id note nil))
  ([session id note assessment]
  (let [v2? (some? (:components session))
        empty? (= "empty-component" (:kind assessment))
        page (when (and (integer? id) (pos? id)) (get (:diff-chunks session) (dec id)))]
  (cond
    (restart-required? session) (err (chronology-error session))
    (and (not (and v2? empty? (nil? id)))
         (not (contains? (:delivered-chunks session) id))) (err "Read the diff chunk before assessing it.")
    (not (non-blank note)) (err "Explain the changed-hunk assessment in a non-empty note.")
    (not= :deterministic (:stage session))
    (err "Assess every diff chunk before recording deterministic evidence; post-stage reassessment is refused.")
    (and v2? (not (if empty?
                    (and (nil? id) (invocation/empty-assessment-valid? (:components session) assessment))
                    (invocation/page-assessment-valid? page assessment))))
    (err "Assessment must match the actual component, payload, role and spans.")
    :else
    (let [state (if (and v2? empty?)
                  (assoc-in session [:empty-assessments (get-in assessment [:component-ref :role])]
                            {:note note :assessment assessment})
                  (assoc-in session [:assessed-chunks id]
                            (if v2? {:note note :assessment assessment} note)))]
      (cond-> {:ok? true :session state :chunk-id id :coverage (input-coverage state)}
        v2? (assoc :assessment assessment)))))))

(defn- full-input-error [session]
  (cond
    (restart-required? session)
    (chronology-error session)

    (get-in session [:diff-stats :truncated?])
    "The staged input is a truncated preview. Recover the full immutable diff before publishing."

    (some #(and (:empty? %) (not (:empty-observed? %)))
          (vals (:roles (input-coverage session))))
    "Empty input components require explicit observations before publishing."

    (seq (:missing (input-coverage session)))
    (str "Unassessed full-input chunks remain: " (str/join ", " (:missing (input-coverage session)))
         ". Read and assess every changed hunk before publishing.")))

(defn record-evidence
  "Record a note and advance only after complete input before deterministic.
   Preserve the later adversarial coverage check and all submission guards."
  [session stage note]
  (let [current (:stage session)
        input-error (when (contains? #{:deterministic :adversarial-validate} stage)
                      (full-input-error session))]
    (cond
      (not (contains? (set stages) stage))
      (err (str "Unknown stage " stage "; stages are " (str/join ", " (map name stages)) "."))

      (not= stage current)
      (err (str "Review is at stage " (name current) ", not " (name stage)
                ". Record evidence for the current stage before advancing."))

      (not (non-blank note))
      (err "Evidence note must be a non-empty string.")

      input-error
      (err input-error)

      :else
      (let [next-idx (inc (.indexOf stages current))]
        {:ok?     true
         :session (-> session
                      (update :evidence conj {:stage stage :note note})
                      (assoc :stage (get stages next-idx current)))}))))

;; ---------------------------------------------------------------------------
;; Findings
;; ---------------------------------------------------------------------------

(defn propose-finding
  "Register a candidate finding. The location is validated against the staged
   diff immediately: only added lines on the PR head side are reviewable."
  [session {:keys [id severity category claim path line body confidence blocking] :as _finding}]
  (let [stage (:stage session)]
    (cond
      (= stage :publish)
      (err "Findings cannot be proposed at the :publish stage; classify existing candidates or submit.")

      (not (non-blank id))
      (err "Finding id must be a non-empty string.")

      (contains? (:candidates session) id)
      (err (str "Duplicate finding id " id "."))

      (not (contains? severities severity))
      (err "Severity must be critical, high, medium, or low.")

      (not (contains? categories category))
      (err (str "Category must be one of " (str/join ", " (sort categories)) "."))

      (not (non-blank claim))
      (err "Claim must be a non-empty string: one precise behavioral claim.")

      (not (non-blank path))
      (err "Path must be a non-empty repository path.")

      (or (not (integer? line)) (< line 1))
      (err "Line must be a positive integer in the PR head.")

      (not (non-blank body))
      (err "Body must be a non-empty string: impact, evidence, corrective direction.")

      (not (and (number? confidence) (<= 0.0 confidence 1.0)))
      (err "Confidence must be a number between 0.0 and 1.0.")

      (and blocking (not (contains? #{"critical" "high"} severity)))
      (err "Only critical or high findings may be blocking.")

      (not (contains? (:changed-lines session) path))
      (err (str path " has no added lines in the staged pull-request diff; findings attach to changed lines only."))

      (not (contains? (get (:changed-lines session) path) line))
      (err (str path ":" line " is not an added line in the pull-request diff."))

      :else
      {:ok?     true
       :session (-> session
                    (assoc-in [:candidates id]
                              {:id         id
                               :severity   severity
                               :category   category
                               :claim      claim
                               :path       path
                               :line       line
                               :body       body
                               :confidence confidence
                               :blocking   (boolean blocking)
                               :status     :pending})
                    (update :candidate-order conj id))})))

(defn classify-finding
  "Classify a pending candidate as confirmed, rejected, or needs-human.
   Legal from :adversarial-validate onward (but before submission): a reviewer
   may record its stage evidence before finishing every classification."
  [session id status rationale]
  (cond
    (not (contains? #{:adversarial-validate :publish} (:stage session)))
    (err (str "Classification requires the :adversarial-validate stage or later; review is at "
              (name (:stage session)) "."))

    (not (contains? classifications status))
    (err "Status must be confirmed, rejected, or needs-human.")

    (not (contains? (:candidates session) id))
    (err (str "No candidate finding with id " id "."))

    (not (non-blank rationale))
    (err "Rationale must be a non-empty string explaining the classification.")

    :else
    {:ok?     true
     :session (-> session
                  (assoc-in [:candidates id :status] (keyword status))
                  (assoc-in [:candidates id :rationale] rationale))}))

;; ---------------------------------------------------------------------------
;; Submission
;; ---------------------------------------------------------------------------

(defn- ordered-candidates [session]
  (map (:candidates session) (:candidate-order session)))

(defn submission
  "Apply the publication laws and produce the open-hax.github-review/v1
   envelope. The review event is derived from the classified findings, never
   asserted by the reviewer."
  [session summary]
  (let [candidates (ordered-candidates session)
        pending    (filter #(= :pending (:status %)) candidates)
        confirmed  (filter #(= :confirmed (:status %)) candidates)
        underconfident (filter #(< (:confidence %) confirmation-confidence-threshold) confirmed)
        locations  (frequencies (map (juxt :path :line) confirmed))
        duplicated (keep (fn [[loc n]] (when (> n 1) loc)) locations)
        input-error (full-input-error session)]
    (cond
      (not= (:stage session) :publish)
      (err (str "Review cannot be submitted at stage " (name (:stage session))
                "; record evidence for every stage first."))

      (not (non-blank summary))
      (err "Summary must be a non-empty string; it becomes the GitHub review body.")

      input-error
      (err input-error)

      (seq pending)
      (err (str "Unclassified candidates remain: "
                (str/join ", " (map :id pending))
                ". Classify every candidate before submitting."))

      (seq underconfident)
      (err (str "Confirmed findings below the "
                confirmation-confidence-threshold
                " confidence threshold: "
                (str/join ", " (map :id underconfident))
                ". Reclassify them as needs-human or rejected, or raise confidence with evidence."))

      (seq duplicated)
      (err (str "Confirmed findings share a location "
                (str/join ", " (map (fn [[p l]] (str p ":" l)) duplicated))
                ". GitHub accepts one inline comment per line; reclassify the weaker finding as rejected and fold its content into the stronger one's body."))

      (> (count confirmed) max-inline-comments)
      (err (str "At most " max-inline-comments " inline findings may be published; "
                (count confirmed) " are confirmed. Reclassify the weakest."))

      :else
      (let [confirmed (sort-by (juxt :path :line) confirmed)
            blocking? (some :blocking confirmed)
            event     (cond blocking?         "REQUEST_CHANGES"
                            (seq confirmed)   "COMMENT"
                            :else             "APPROVE")
            comments  (mapv (fn [{:keys [path line severity blocking body]}]
                              {:path     path
                               :line     line
                               :side     "RIGHT"
                               :severity severity
                               :blocking (boolean blocking)
                               :body     body})
                            confirmed)]
        {:ok?      true
         :envelope (cond-> {:schema  "open-hax.github-review/v1"
                    :event   event
                    :summary summary
                    :input-source (:input-source session)
                    :input-coverage (input-coverage session)
                    :input-assessments (mapv #(merge (select-keys % [:id :start :end])
                                                     (if (:components session)
                                                       (get (:assessed-chunks session) (:id %))
                                                       {:note (get (:assessed-chunks session) (:id %))}))
                                             (:diff-chunks session))
                    :comments comments}
                     (:components session)
                     (assoc :empty-component-assessments
                            (mapv #(get (:empty-assessments session) (:role %))
                                  (filter #(zero? (:bytes %)) (:components session)))))}))))

(defn status
  "Read-only projection of a session for the reviewer."
  [session]
  {:stage            (:stage session)
   :stages           stages
   :evidence-count   (count (:evidence session))
   :diff-stats       (:diff-stats session)
   :input-coverage   (input-coverage session)
   :restart-required? (restart-required? session)
   :invalidated-chunks (vec (sort (:invalidated-chunks session)))
   :candidates       (mapv #(select-keys % [:id :severity :category :path :line :status :confidence])
                           (ordered-candidates session))})
