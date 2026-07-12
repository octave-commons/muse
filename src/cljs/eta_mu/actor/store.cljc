(ns eta-mu.actor.store
  "In-memory actor mailbox store.
   Events are envelopes conforming to the event-ledger schema.
   Each actor has an append-only mailbox (vector of envelopes in an atom)."
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Envelope schema (mirrors event-ledger without Mongo)
;; ---------------------------------------------------------------------------

(def from-to-schema
  [:map
   [:actor-id :string]
   [:actor-kind :string]])

(def envelope-schema
  [:map
   [:event/id {:optional true} :string]
   [:event/type :string]
   [:event/time {:optional true} :string]
   [:event/from {:optional true} from-to-schema]
   [:event/to {:optional true} from-to-schema]
   [:causal/root {:optional true} :string]
   [:causal/parent {:optional true} :string]
   [:session/id {:optional true} :string]
   [:turn/id {:optional true} :string]
   [:delivery/mode {:optional true} [:enum "tell" "ask" "stream" "ack-required"]]
   [:payload {:optional true} :map]])

;; ---------------------------------------------------------------------------
;; Store protocol
;; ---------------------------------------------------------------------------

(defprotocol IActorStore
  (-spawn! [store actor-id opts] "Register an actor. Returns the actor-id.")
  (-send! [store from-id to-id envelope] "Append envelope to to-id's mailbox.")
  (-recv [store actor-id opts] "Read messages from actor's mailbox.")
  (-actors [store] "Return all registered actor-ids.")
  (-actor-meta [store actor-id] "Return actor metadata map.")
  (-mailbox [store actor-id] "Return the raw mailbox vector for an actor.")
  (-clear! [store actor-id] "Clear an actor's mailbox."))
