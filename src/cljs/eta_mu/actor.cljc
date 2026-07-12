(ns eta-mu.actor
  "Public API for the actor system.
   Uses a mutable store reference (atom) instead of dynamic binding for CLJS compat."
  (:require [eta-mu.actor.store :as store]
            [eta-mu.actor.memory :as mem]))

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
;; Core operations
;; ---------------------------------------------------------------------------

(defn spawn!
  "Register a new actor. Returns the actor-id."
  ([actor-id] (spawn! actor-id {}))
  ([actor-id opts]
   (store/-spawn! (the-store) actor-id opts)))

(defn send!
  "Send an envelope from one actor to another.
   Returns the event-id."
  [from-id to-id envelope]
  (store/-send! (the-store) from-id to-id envelope))

(defn recv
  "Read messages from an actor's mailbox.
   opts: {:since-id \"...\" :limit 100 :filter-type \"event.type\"}"
  ([actor-id] (recv actor-id {}))
  ([actor-id opts]
   (store/-recv (the-store) actor-id opts)))

(defn actors
  "Return all registered actor-ids."
  []
  (store/-actors (the-store)))

(defn actor-meta
  "Return the metadata map for an actor."
  [actor-id]
  (store/-actor-meta (the-store) actor-id))

(defn mailbox
  "Return the raw mailbox for an actor."
  [actor-id]
  (store/-mailbox (the-store) actor-id))

(defn clear!
  "Clear an actor's mailbox."
  [actor-id]
  (store/-clear! (the-store) actor-id))

;; ---------------------------------------------------------------------------
;; Convenience: send a simple message
;; ---------------------------------------------------------------------------

(defn tell!
  "Send a simple payload message between actors.
   event-type is a string like \"phase.observation\" or \"muse.command\"."
  [from-id to-id event-type payload]
  (send! from-id to-id
         {:event/type event-type
          :payload    payload}))

(defn ask!
  "Send a request and expect a response. delivery/mode = \"ask\"."
  [from-id to-id event-type payload]
  (send! from-id to-id
         {:event/type    event-type
          :delivery/mode "ask"
          :payload       payload}))
