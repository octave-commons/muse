(ns eta-mu.domain.websearch
  "Pure request/response shaping for the Open Hax websearch proxy."
  (:require [clojure.string :as str]))

(def default-model "openai/gpt-5.3-codex")
(def default-max-chars 524288)
(def default-max-lines 2000)

(defn endpoint
  "Normalize a proxy base URL to the websearch endpoint."
  [base-url]
  (str (str/replace (or base-url "") #"/+$" "") "/api/tools/websearch"))

(defn request-body
  "Build the proxy request payload from tool params."
  [{:keys [query numResults searchContextSize allowedDomains model]} default-model']
  (cond-> {:query query
           :model (or model default-model')}
    numResults        (assoc :numResults numResults)
    searchContextSize (assoc :searchContextSize searchContextSize)
    allowedDomains    (assoc :allowedDomains allowedDomains)))

(defn format-sources
  "Markdown source list from [{:url ... :title ...} ...]; nil when empty."
  [sources]
  (when (seq sources)
    (str "\n\nSources:\n"
         (->> sources
              (filter #(string? (:url %)))
              (map #(if-let [title (:title %)]
                      (str "- [" title "](" (:url %) ")")
                      (str "- " (:url %))))
              (str/join "\n")))))

(defn truncate-tail
  "Keep the trailing max-lines/max-chars of text.
   Returns {:content :truncated :total-lines :output-lines
            :total-chars :output-chars}."
  [text max-chars max-lines]
  (let [lines       (str/split (or text "") #"\n" -1)
        total-lines (count lines)
        total-chars (count text)]
    (if (and (<= total-chars max-chars) (<= total-lines max-lines))
      {:content text
       :truncated false
       :total-lines total-lines
       :output-lines total-lines
       :total-chars total-chars
       :output-chars total-chars}
      (let [output-lines (min max-lines total-lines)
            content (str/join "\n" (take-last output-lines lines))
            content (if (<= (count content) max-chars)
                      content
                      (subs content (- (count content) max-chars)))]
        {:content content
         :truncated true
         :total-lines total-lines
         :output-lines output-lines
         :total-chars total-chars
         :output-chars (count content)}))))

(defn shape-response
  "Combine proxy output + sources, truncating when oversized.
   Returns a structured tool result: search text in :output, ancillary
   facts under :metadata."
  [{:keys [output sources responseId backend]} endpoint' model]
  (let [combined (str (or output "") (or (format-sources sources) ""))
        trunc    (truncate-tail combined default-max-chars default-max-lines)
        content  (if (:truncated trunc)
                   (str (:content trunc)
                        "\n\n[Output truncated: " (:output-lines trunc) " of "
                        (:total-lines trunc) " lines kept ("
                        (:output-chars trunc) " of " (:total-chars trunc) " chars)]")
                   (:content trunc))]
    {:output content
     :metadata (cond-> {:backend  (or backend "openai")
                        :endpoint endpoint'
                        :model    model
                        :sources-count (count (or sources []))
                        :truncated (:truncated trunc)}
                 responseId (assoc :response-id responseId))}))
