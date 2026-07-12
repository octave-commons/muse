(ns eta-mu.actor.memory
  "In-memory actor store backed by atoms.
   Each actor-id maps to a vector of envelopes."
  (:require [eta-mu.actor.store :as store]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- now-iso []
  (.toISOString (js/Date.)))

(defn- ensure-id [envelope]
  (if (:event/id envelope)
    envelope
    (assoc envelope :event/id (str (random-uuid)))))

(defn- ensure-time [envelope]
  (if (:event/time envelope)
    envelope
    (assoc envelope :event/time (now-iso))))

(defn fill-defaults
  "Fill event/id, event/time, delivery/mode on an envelope."
  [envelope]
  (-> envelope
      ensure-id
      ensure-time
      (assoc :delivery/mode (or (:delivery/mode envelope) "tell"))))

;; ---------------------------------------------------------------------------
;; Atom store
;; ---------------------------------------------------------------------------

(defrecord MemoryStore [actors ;; atom: {actor-id {:meta {...} :mailbox [envelope ...]}}
                        ]

  store/IActorStore

  (-spawn! [_ actor-id opts]
    (swap! actors update actor-id
           (fn [existing]
             (if existing
               (update existing :meta merge opts)
               {:meta (merge {:actor-id (name actor-id)
                              :actor-kind (or (:kind opts) "actor")
                              :spawned-at (now-iso)}
                             opts)
                :mailbox []})))
    actor-id)

  (-send! [_ from-id to-id envelope]
    (let [envelope (-> envelope
                       fill-defaults
                       (assoc :event/from {:actor-id (name from-id)
                                           :actor-kind (or (:actor-kind envelope) "actor")}
                              :event/to {:actor-id (name to-id)
                                         :actor-kind "actor"}))]
      (when-not (get @actors to-id)
        (throw (ex-info "Actor not found" {:actor-id to-id})))
      (swap! actors update-in [to-id :mailbox] conj envelope)
      (:event/id envelope)))

  (-recv [_ actor-id {:keys [since-id limit filter-type] :or {limit 100}}]
    (let [{:keys [mailbox]} (get @actors actor-id)]
      (when mailbox
        (let [start-idx (if since-id
                          (let [idx (->> mailbox
                                         (map-indexed vector)
                                         (filter (fn [[_ e]] (= since-id (:event/id e))))
                                         ffirst)]
                            (if idx (inc idx) 0))
                          0)
              filtered (cond->> (subvec mailbox start-idx)
                         filter-type
                         (filter #(= filter-type (:event/type %))))]
          (vec (take limit filtered))))))

  (-actors [_]
    (vec (keys @actors)))

  (-actor-meta [_ actor-id]
    (:meta (get @actors actor-id)))

  (-mailbox [_ actor-id]
  (get-in @actors [actor-id :mailbox]))

  (-clear! [_ actor-id]
    (swap! actors assoc-in [actor-id :mailbox] []))
  )

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn make-memory-store
  "Create a new in-memory actor store."
  []
  (->MemoryStore (atom {})))
