(ns eta-mu.domain.receipts
  "Pure receipt-ledger logic: single-line EDN event serialization, record
   building, and validation. No I/O — the plugin injects lines and clocks."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(def required-keys
  [:ts :kind :repo :origin :owner :dod :pi :host :manifest :refs])

(def optional-keys
  [:note :tests :decisions :drift])

(def known-kinds
  #{:push-truth :artifact-hash :test-run :build :decision :drift
    :catalog :observation :field-impact :truth :refutation :adjudication})

;; ---------------------------------------------------------------------------
;; Field normalization
;; ---------------------------------------------------------------------------

(defn clean-field
  "Collapse whitespace/newlines to single spaces; fall back when blank."
  ([value] (clean-field value "none"))
  ([value fallback]
   (let [s (-> (str (or value ""))
               (str/replace #"\r?\n+" " ")
               (str/replace #"\s+" " ")
               str/trim)]
     (if (str/blank? s) fallback s))))

(defn normalize-kind
  "Coerce a kind string/keyword to a known kind keyword; throws on unknown."
  [value fallback]
  (let [raw  (clean-field (if (keyword? value) (name value) value)
                          (name fallback))
        kind (keyword (str/replace raw #"^:" ""))]
    (if (contains? known-kinds kind)
      kind
      (throw (ex-info (str "Unknown receipt kind: " kind)
                      {:kind kind :known (sort known-kinds)})))))

;; ---------------------------------------------------------------------------
;; EDN event format
;; ---------------------------------------------------------------------------

(defn edn-event
  "Serialize a receipt event map to a single-line EDN string.
   Optional keys are omitted when nil/blank."
  [m]
  (let [base (reduce (fn [acc k] (assoc acc k (get m k)))
                     {} required-keys)
        with-opts (reduce (fn [acc k]
                            (let [v (get m k)]
                              (if (and v (not (str/blank? (str v))))
                                (assoc acc k v)
                                acc)))
                          base optional-keys)]
    (str/replace (pr-str with-opts) #"\n" " ")))

(defn parse-edn-event
  "Parse a single EDN line back to a map.
   Returns nil on parse failure, non-map result, or blank input."
  [line]
  (when (and line (not (str/blank? (str line))))
    #?(:clj (try
              (let [result (edn/read-string (str line))]
                (when (map? result) result))
              (catch Exception _ nil))
       :cljs (try
               (let [result (reader/read-string (str line))]
                 (when (map? result) result))
               (catch :default _ nil)))))

;; ---------------------------------------------------------------------------
;; Record building
;; ---------------------------------------------------------------------------

(defn build-record
  "Build a full receipt record from sparse tool params.
   `now` is the caller's ISO timestamp (injected for purity)."
  [params repo-root now]
  (let [{:keys [ts kind origin owner dod pi host manifest refs]} params
        record {:ts       (clean-field ts now)
                :kind     (normalize-kind kind :observation)
                :repo     repo-root
                :origin   (clean-field origin "opencode")
                :owner    (clean-field owner "receipt-river")
                :dod      (clean-field dod (or owner "receipt-river"))
                :pi       (clean-field pi "eta-mu")
                :host     (clean-field host "local")
                :manifest (clean-field manifest "none")
                :refs     (clean-field refs "none")}]
    (reduce (fn [acc k]
              (let [value (clean-field (get params k) "")]
                (if (str/blank? value) acc (assoc acc k value))))
            record
            optional-keys)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def ^:private iso-ts-pattern
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$")

(defn validate-event
  "Validate one ledger line. Returns {:ok :line-number :event :errors :line}."
  [line line-number]
  (let [event  (parse-edn-event line)
        errors (cond-> []
                 (nil? event) (conj "invalid EDN event"))
        errors (if-not event
                 errors
                 (as-> errors errs
                   (into errs (keep (fn [k]
                                      (when-not (get event k)
                                        (str "missing required key: " (name k))))
                                    required-keys))
                   (cond-> errs
                     (and (:kind event)
                          (not (contains? known-kinds (:kind event))))
                     (conj (str "unknown kind: " (:kind event)))

                     (and (:ts event)
                          (not (re-find iso-ts-pattern (str (:ts event)))))
                     (conj (str "invalid ts: " (:ts event))))))]
    {:ok          (empty? errors)
     :line-number line-number
     :event       event
     :errors      errors
     :line        line}))

(defn validate-lines
  "Validate a seq of ledger lines (1-indexed).
   Returns {:ok :count :failures [...]}."
  [lines]
  (let [rows     (map-indexed (fn [i line] (validate-event line (inc i))) lines)
        failures (vec (remove :ok rows))]
    {:ok       (empty? failures)
     :count    (count lines)
     :failures failures
     :last     (last rows)}))
