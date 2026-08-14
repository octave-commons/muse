(ns eta-mu.domain.review
  "Pure state machine and laws for the evidence-first pull-request reviewer.

   The reviewer never publishes text. It drives a review session through the
   fixed stages via tool calls; each call is validated here. The only output
   artifact is a machine-written submission envelope
   (schema open-hax.github-review/v1) produced by `submission`.

   No I/O lives in this namespace."
  (:require [clojure.string :as str]))

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
          (str/starts-with? row "+++ ")
          (let [p (str/trim (subs row 4))
                p (if (str/starts-with? p "b/") (subs p 2) p)]
            (recur (rest rows) (when-not (= p "/dev/null") p) nil acc))

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

(defn begin
  "Start a review session over staged diff text."
  [diff-text]
  (let [changed (parse-diff-added-lines diff-text)]
    {:stage         :deterministic
     :evidence      []
     :candidates    {}
     :candidate-order []
     :changed-lines changed
     :diff-stats    {:files          (count changed)
                     :bytes          (count (or diff-text ""))
                     :truncated?     (boolean (and diff-text (str/includes? diff-text "[eta-mu review] diff truncated")))}}))

(defn- err [msg] {:ok? false :error msg})

(defn- non-blank [s] (and (string? s) (not (str/blank? s))))

(defn record-evidence
  "Record a note for the current stage and advance to the next stage."
  [session stage note]
  (let [current (:stage session)]
    (cond
      (not (contains? (set stages) stage))
      (err (str "Unknown stage " stage "; stages are " (str/join ", " (map name stages)) "."))

      (not= stage current)
      (err (str "Review is at stage " (name current) ", not " (name stage)
                ". Record evidence for the current stage before advancing."))

      (not (non-blank note))
      (err "Evidence note must be a non-empty string.")

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
        duplicated (keep (fn [[loc n]] (when (> n 1) loc)) locations)]
    (cond
      (not= (:stage session) :publish)
      (err (str "Review cannot be submitted at stage " (name (:stage session))
                "; record evidence for every stage first."))

      (not (non-blank summary))
      (err "Summary must be a non-empty string; it becomes the GitHub review body.")

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
         :envelope {:schema  "open-hax.github-review/v1"
                    :event   event
                    :summary summary
                    :comments comments}}))))

(defn status
  "Read-only projection of a session for the reviewer."
  [session]
  {:stage            (:stage session)
   :stages           stages
   :evidence-count   (count (:evidence session))
   :diff-stats       (:diff-stats session)
   :candidates       (mapv #(select-keys % [:id :severity :category :path :line :status :confidence])
                           (ordered-candidates session))})
