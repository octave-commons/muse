(ns eta-mu.boundaries.fetch
  "HTTP boundary over the platform fetch. Takes and returns plain CLJS
   data; the returned promise resolves to {:status :ok :body} where :body
   is keywordized JSON (or {:raw text} when the response is not JSON)."
  (:require [clojure.string :as str]))

(defn env
  "Read an environment variable (first present of the given names)."
  [& names]
  (some #(let [v (unchecked-get (.-env js/process) %)]
           (when (and v (not (str/blank? v))) v))
        names))

(defn post-json!
  "POST a CLJS map as JSON. Resolves to {:ok :status :status-text :body}."
  [url {:keys [headers body]}]
  (-> (js/fetch url
                #js {:method "POST"
                     :headers (clj->js (merge {"Content-Type" "application/json"}
                                              headers))
                     :body (js/JSON.stringify (clj->js body))})
      (.then (fn [resp]
               (-> (.text resp)
                   (.then (fn [raw]
                            {:ok          (.-ok resp)
                             :status      (.-status resp)
                             :status-text (.-statusText resp)
                             :body (try
                                     (js->clj (js/JSON.parse raw)
                                              :keywordize-keys true)
                                     (catch :default _ {:raw raw}))})))))))
