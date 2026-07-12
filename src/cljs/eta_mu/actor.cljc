(ns eta-mu.actor
  "Public API for the actor system. Every operation returns a promise
   regardless of backend — in-memory, EDN-file ledgers, or Mongo — so
   callers compose with promesa and never care which store is active."
  (:require [eta-mu.actor.memory :as mem]
            [eta-mu.actor.store :as store]
            [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; Store reference (atom-wrapped for CLJS dynamic binding compat)
;; ---------------------------------------------------------------------------

(defonce store-ref (atom nil))

(defn init-store!
  "Initialize the actor store. Call once at startup."
  ([]
   (init-store! (mem/make-memory-store)))
  ([s]
   (reset! store-ref s)
   s))

(defn the-store []
  (or @store-ref
      (let [s (mem/make-memory-store)]
        (reset! store-ref s)
        s)))

;; ---------------------------------------------------------------------------
;; Core operations — each normalized to a promise (sync stores may
;; return plain values or throw; p/do captures both).
;; ---------------------------------------------------------------------------

(defn spawn!
  "Register a new actor. Resolves to the actor-id."
  ([actor-id] (spawn! actor-id {}))
  ([actor-id opts]
   (p/do (store/-spawn! (the-store) actor-id opts))))

(defn send!
  "Send an envelope from one actor to another: the envelope is appended
   to the recipient's ledger, to be read when they get around to it.
   Resolves to the event-id."
  [from-id to-id envelope]
  (p/do (store/-send! (the-store) from-id to-id envelope)))

(defn recv
  "Read messages from an actor's mailbox.
   opts: {:since-id \"...\" :limit 100 :filter-type \"event.type\"}"
  ([actor-id] (recv actor-id {}))
  ([actor-id opts]
   (p/do (store/-recv (the-store) actor-id opts))))

(defn actors
  "Resolves to all registered actor-ids."
  []
  (p/do (store/-actors (the-store))))

(defn actor-meta
  "Resolves to the metadata map for an actor."
  [actor-id]
  (p/do (store/-actor-meta (the-store) actor-id)))

(defn registry
  "Resolves to {actor-id meta-map} for every registered actor."
  []
  (p/do (store/-registry (the-store))))

(defn mailbox
  "Resolves to the full mailbox ledger for an actor."
  [actor-id]
  (p/do (store/-mailbox (the-store) actor-id)))

(defn clear!
  "Clear an actor's mailbox."
  [actor-id]
  (p/do (store/-clear! (the-store) actor-id)))

;; ---------------------------------------------------------------------------
;; Convenience: send a simple message
;; ---------------------------------------------------------------------------

(defn tell!
  "Send a simple payload message between actors.
   event-type is a string like \"phase.observation\" or \"muse.influence\"."
  [from-id to-id event-type payload]
  (send! from-id to-id
         {:event/type event-type
          :payload    payload}))

(defn ask!
  "Send a request that expects a response. delivery/mode = \"ask\".
   Note: still a ledger append — the recipient answers on their own
   time. Policies decide which actors may use this mode at all."
  [from-id to-id event-type payload]
  (send! from-id to-id
         {:event/type    event-type
          :delivery/mode "ask"
          :payload       payload}))
