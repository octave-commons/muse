(ns eta-mu.actor.backend
  "Chooses and initializes the actor-store backend from the environment,
   and provides backend-generic mailbox watching.

     ETA_MU_LEDGER_BACKEND  file (default) | mongo | memory
     ETA_MU_LEDGER_ROOT     file-ledger root (default .eta-mu/ledgers)
     ETA_MU_MONGO_URI       connection string (never discovered)
     ETA_MU_MONGO_DB        database name (default eta_mu)"
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as memory]
            [eta-mu.boundaries.mongo.client :as mongo.client]
            [eta-mu.boundaries.mongo.ledger :as mongo.ledger]
            [eta-mu.boundaries.node.fs :as nfs]
            [eta-mu.boundaries.node.ledger :as ledger]
            [promesa.core :as p]))

(defonce ^:private ready (atom nil))

(defn- create! []
  (case (or (nfs/env "ETA_MU_LEDGER_BACKEND") "file")
    "memory" (p/resolved (memory/make-memory-store))
    "mongo"  (p/let [conn (mongo.client/connect!
                           nil
                           (or (nfs/env "ETA_MU_MONGO_DB") "eta_mu"))]
               (mongo.ledger/make-mongo-store (:db conn)))
    (p/resolved (ledger/make-ledger-store
                 (or (nfs/env "ETA_MU_LEDGER_ROOT") ledger/default-root)))))

(defn ensure!
  "Resolve to the active store, initializing from the environment on
   first call. Idempotent across plugins sharing the bundle."
  []
  (or @ready
      (reset! ready
              (p/let [s (create!)]
                (actor/init-store! s)
                s))))

(def poll-interval-ms 500)

(defn- poll-watch
  "Generic watch: poll the mailbox for events appended after this call,
   resolve with the first matching one or nil at timeout."
  [actor-id pred timeout-ms]
  (js/Promise.
   (fn [resolve _]
     (-> (p/let [mb (actor/mailbox actor-id)]
           (let [seen     (atom (count mb))
                 finished (atom false)
                 interval (atom nil)
                 finish   (fn [v]
                            (when (compare-and-set! finished false true)
                              (js/clearInterval @interval)
                              (resolve v)))]
             (reset! interval
                     (js/setInterval
                      (fn []
                        (-> (p/let [mb (actor/mailbox actor-id)]
                              (let [n   @seen
                                    new (when (> (count mb) n) (drop n mb))]
                                (reset! seen (count mb))
                                (when-let [hit (first (filter pred new))]
                                  (finish hit))))
                            (p/catch (fn [_] nil))))
                      poll-interval-ms))
             (js/setTimeout #(finish nil) timeout-ms)))
         (p/catch (fn [_] (resolve nil)))))))

(defn watch-once
  "Resolve with the first event appended to actor-id's mailbox matching
   pred, or nil after timeout-ms. File stores watch the filesystem;
   other backends poll."
  [actor-id pred timeout-ms]
  (p/let [store (ensure!)]
    (if (instance? ledger/LedgerStore store)
      (ledger/watch-once store actor-id pred timeout-ms)
      (poll-watch actor-id pred timeout-ms))))
