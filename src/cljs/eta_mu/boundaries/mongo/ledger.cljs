(ns eta-mu.boundaries.mongo.ledger
  "Mongo-backed actor ledgers speaking @promethean-os/event-ledger's
   DOCUMENTED wire format: envelopes in the event_ledger collection with
   full-name keys (\"event/type\"), a monotonic ledger/seq from the
   _counters collection, TTL via expiresAt, and its index set.

   ANOMALY (2026-07-12): the event-ledger package's built dist drops
   keyword namespaces when writing (stores \"type\", not \"event/type\")
   and leaves ledger/seq null under mongodb driver ≥ 5 — its own
   documented indexes can't match what it stores. Until that's fixed
   upstream, this boundary appends directly in the documented format
   instead of calling the package.

   An actor's mailbox is the query {\"event/to.actor-id\" id} ordered by
   ledger/seq. Append-only discipline: -clear! never deletes — it
   appends a mailbox.cleared marker and reads resume after the latest
   marker. Actor metadata lives in actor_registry as an EDN string
   (:metaEdn) so keyword-valued metadata round-trips exactly; envelope
   payloads are BSON, so keyword *values* inside payloads come back as
   strings — that is the shared wire, not a bug."
  (:require [cljs.reader :as reader]
            [eta-mu.actor.envelope :as envelope]
            [eta-mu.actor.memory :as memory]
            [eta-mu.actor.store :as store]
            [promesa.core :as p]))

(def ledger-collection "event_ledger")
(def counters-collection "_counters")
(def registry-collection "actor_registry")
(def clear-marker "mailbox.cleared")
(def default-ttl-days 30)

;; ---------------------------------------------------------------------------
;; Wire conversion — full-name keyword keys ("event/type"), both ways.
;; ---------------------------------------------------------------------------

(defn- full-name [k]
  (str (symbol k)))

(defn ->wire [m]
  (clj->js m :keyword-fn full-name))

(defn wire->clj [obj]
  (js->clj obj :keywordize-keys true))

;; ---------------------------------------------------------------------------
;; Mailbox view
;; ---------------------------------------------------------------------------

(defn mailbox-view
  "Envelope maps for an actor's mailbox: everything after the latest
   clear marker, markers excluded, mongo bookkeeping stripped."
  [docs]
  (let [envelopes (mapv #(dissoc % :_id :ledger/seq :expiresAt
                                 :createdAt :updatedAt)
                        docs)
        last-clear (->> envelopes
                        (map-indexed vector)
                        (filter (fn [[_ e]] (= clear-marker (:event/type e))))
                        (map first)
                        last)]
    (cond->> envelopes
      last-clear (drop (inc last-clear))
      :always    (remove #(= clear-marker (:event/type %)))
      :always    vec)))

;; ---------------------------------------------------------------------------
;; Ledger writes (event-ledger's documented append pipeline)
;; ---------------------------------------------------------------------------

(defn- coll [db coll-name]
  (.collection ^js db coll-name))

(defn- next-seq!
  "Atomic monotonic sequence from the _counters collection. Handles both
   findOneAndUpdate return shapes (driver <5 wraps in {value}, ≥5 returns
   the document)."
  [db]
  (p/let [res (.findOneAndUpdate ^js (coll db counters-collection)
                                 #js {:_id "event_ledger"}
                                 #js {"$inc" #js {:seq 1}}
                                 #js {:upsert true :returnDocument "after"})]
    (let [doc (if (and res (js-in "value" res)) (unchecked-get res "value") res)]
      (when doc (unchecked-get doc "seq")))))

(defn- append!
  "Fill defaults, stamp seq/timestamps/TTL, insert. Resolves to the
   event id."
  [db envelope]
  (let [env (envelope/fill-defaults envelope)
        now (envelope/now-iso)]
    (p/let [seq-num (next-seq! db)
            doc     (-> env
                        (assoc :ledger/seq seq-num
                               :createdAt now
                               :updatedAt now)
                        ->wire)
            _       (unchecked-set doc "expiresAt"
                                   (js/Date. (+ (.getTime (js/Date.))
                                                (* default-ttl-days 24 60 60 1000))))
            _       (.insertOne ^js (coll db ledger-collection) doc)]
      (:event/id env))))

(defn- fetch-mailbox [db actor-id]
  (p/let [arr (-> (.find ^js (coll db ledger-collection)
                         #js {"event/to.actor-id" (name actor-id)})
                  (.sort #js {"ledger/seq" 1})
                  .toArray)]
    (mailbox-view (mapv wire->clj arr))))

(defn- find-meta [db actor-id]
  (p/let [doc (.findOne ^js (coll db registry-collection)
                        #js {:_id (name actor-id)})]
    (when doc
      (reader/read-string (unchecked-get doc "metaEdn")))))

;; ---------------------------------------------------------------------------
;; Store
;; ---------------------------------------------------------------------------

(defrecord MongoLedgerStore [db]

  store/IActorStore

  (-spawn! [_ actor-id opts]
    (p/let [existing (find-meta db actor-id)
            meta     (if existing
                       (merge existing opts)
                       (memory/spawn-meta actor-id opts))
            _        (.updateOne ^js (coll db registry-collection)
                                 #js {:_id (name actor-id)}
                                 #js {"$set" #js {:metaEdn   (pr-str meta)
                                                  :updatedAt (envelope/now-iso)}}
                                 #js {:upsert true})]
      actor-id))

  (-send! [_ from-id to-id envelope]
    (p/let [existing (find-meta db to-id)]
      (when-not existing
        (throw (ex-info "Actor not found" {:actor-id to-id})))
      (append! db (memory/routed-envelope from-id to-id envelope))))

  (-recv [_ actor-id opts]
    (p/let [mb (fetch-mailbox db actor-id)]
      (memory/recv-view mb opts)))

  (-actors [_]
    (p/let [arr (.toArray (.find ^js (coll db registry-collection) #js {}))]
      (mapv #(keyword (unchecked-get ^js % "_id")) arr)))

  (-actor-meta [_ actor-id]
    (find-meta db actor-id))

  (-registry [_]
    (p/let [arr (.toArray (.find ^js (coll db registry-collection) #js {}))]
      (into {} (map (fn [doc]
                      [(keyword (unchecked-get ^js doc "_id"))
                       (reader/read-string (unchecked-get ^js doc "metaEdn"))]))
            arr)))

  (-mailbox [_ actor-id]
    (fetch-mailbox db actor-id))

  (-clear! [_ actor-id]
    (p/let [_ (append! db (memory/routed-envelope
                           :system actor-id
                           {:event/type clear-marker
                            :payload    {:cleared-at (envelope/now-iso)}}))]
      nil)))

(defn setup-indexes!
  "event-ledger's documented index set. Idempotent."
  [db]
  (let [c (coll db ledger-collection)]
    (p/do
      (.createIndex ^js c #js {"event/id" 1} #js {:unique true})
      (.createIndex ^js c #js {"event/type" 1 "event/time" 1})
      (.createIndex ^js c #js {"causal/root" 1})
      (.createIndex ^js c #js {"session/id" 1})
      (.createIndex ^js c #js {"expiresAt" 1} #js {:expireAfterSeconds 0}))))

(defn make-mongo-store
  "Create a mongo ledger store over a connected db handle."
  [db]
  (p/let [_ (setup-indexes! db)]
    (->MongoLedgerStore db)))
