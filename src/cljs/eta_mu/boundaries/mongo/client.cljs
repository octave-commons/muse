(ns eta-mu.boundaries.mongo.client
  "MongoDB connection boundary. The driver is imported dynamically so
   plugin bundles never require mongodb to be installed unless a mongo
   ledger backend is actually configured. Credentials always come from
   the configured URI (ETA_MU_MONGO_URI) — never discovered."
  (:require [clojure.string :as str]
            [eta-mu.boundaries.node.import :as import]
            [promesa.core :as p]))

(def default-uri "mongodb://127.0.0.1:27017/?directConnection=true")

(defn env-uri
  "The configured connection URI, or the local default."
  []
  (let [v (unchecked-get (.-env js/process) "ETA_MU_MONGO_URI")]
    (if (and v (not (str/blank? v))) v default-uri)))

(defn connect!
  "Connect to MongoDB. Resolves to {:client c :db d}. opts:
   {:timeout-ms n} bounds server selection so a down mongod fails fast."
  ([uri db-name] (connect! uri db-name {}))
  ([uri db-name {:keys [timeout-ms]}]
   (p/let [mod    (import/load! "mongodb")
           Client (.-MongoClient ^js mod)
           client (Client. (or uri (env-uri))
                           (if timeout-ms
                             #js {:serverSelectionTimeoutMS timeout-ms}
                             #js {}))
           _      (.connect ^js client)]
     {:client client
      :db     (.db ^js client db-name)})))

(defn close!
  [{:keys [client]}]
  (when client
    (.close ^js client)))
