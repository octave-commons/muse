(ns eta-mu.actor.memory
  "In-memory actor store backed by an atom. The reference
   implementation of the store contract; ledger-backed stores
   (EDN files, Mongo) must behave identically."
  (:require [eta-mu.actor.envelope :as envelope]
            [eta-mu.actor.store :as store]))

(defn recv-view
  "Shared mailbox read semantics: everything after since-id, optionally
   filtered by event type, limited. Pure — ledger stores reuse it."
  [mailbox {:keys [since-id limit filter-type] :or {limit 100}}]
  (when mailbox
    (let [start-idx (if since-id
                      (let [idx (->> mailbox
                                     (map-indexed vector)
                                     (filter (fn [[_ e]] (= since-id (:event/id e))))
                                     ffirst)]
                        (if idx (inc idx) 0))
                      0)
          filtered (cond->> (subvec (vec mailbox) start-idx)
                     filter-type
                     (filter #(= filter-type (:event/type %))))]
      (vec (take limit filtered)))))

(defn spawn-meta
  "Shared spawn metadata shape."
  [actor-id opts]
  (merge {:actor-id   (name actor-id)
          :actor-kind (or (:kind opts) "actor")
          :spawned-at (envelope/now-iso)}
         opts))

(defn routed-envelope
  "Shared send semantics: fill defaults and stamp the route."
  [from-id to-id envelope]
  (-> envelope
      envelope/fill-defaults
      (envelope/stamp-route from-id nil to-id nil)))

;; ---------------------------------------------------------------------------
;; Atom store
;; ---------------------------------------------------------------------------

(defrecord MemoryStore [actors] ;; atom: {actor-id {:meta {...} :mailbox [envelope ...]}}

  store/IActorStore

  (-spawn! [_ actor-id opts]
    (swap! actors update actor-id
           (fn [existing]
             (if existing
               (update existing :meta merge opts)
               {:meta (spawn-meta actor-id opts)
                :mailbox []})))
    actor-id)

  (-send! [_ from-id to-id envelope]
    (let [envelope (routed-envelope from-id to-id envelope)]
      (when-not (get @actors to-id)
        (throw (ex-info "Actor not found" {:actor-id to-id})))
      (swap! actors update-in [to-id :mailbox] conj envelope)
      (:event/id envelope)))

  (-recv [_ actor-id opts]
    (recv-view (get-in @actors [actor-id :mailbox]) opts))

  (-actors [_]
    (vec (keys @actors)))

  (-actor-meta [_ actor-id]
    (:meta (get @actors actor-id)))

  (-registry [_]
    (into {} (map (fn [[id {:keys [meta]}]] [id meta])) @actors))

  (-mailbox [_ actor-id]
    (get-in @actors [actor-id :mailbox]))

  (-clear! [_ actor-id]
    (swap! actors assoc-in [actor-id :mailbox] [])))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn make-memory-store
  "Create a new in-memory actor store."
  []
  (->MemoryStore (atom {})))
