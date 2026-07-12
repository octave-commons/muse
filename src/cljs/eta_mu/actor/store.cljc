(ns eta-mu.actor.store
  "The actor-store contract. Events are envelopes conforming to the
   event-ledger schema (@promethean-os/event-ledger); each actor owns an
   append-only mailbox ledger.

   Store operations MAY return promises (Mongo) or plain values (memory,
   EDN files). eta-mu.actor normalizes every public API call to a
   promise, so callers never care which backend is underneath.")

;; ---------------------------------------------------------------------------
;; Envelope schema (mirrors event-ledger's schema.cljs)
;; ---------------------------------------------------------------------------

(def from-to-schema
  [:map
   [:actor-id :string]
   [:actor-kind :string]
   [:actor-node {:optional true} :string]])

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
   [:delivery/id {:optional true} :string]
   [:payload {:optional true} :map]
   [:contracts {:optional true} [:vector :string]]
   [:expectations {:optional true} :map]])

;; ---------------------------------------------------------------------------
;; Store protocol
;; ---------------------------------------------------------------------------

(defprotocol IActorStore
  (-spawn! [store actor-id opts] "Register an actor. Returns the actor-id.")
  (-send! [store from-id to-id envelope] "Append envelope to to-id's mailbox ledger. Returns the event-id.")
  (-recv [store actor-id opts] "Read messages from actor's mailbox. opts: {:since-id :limit :filter-type}.")
  (-actors [store] "Return all registered actor-ids.")
  (-actor-meta [store actor-id] "Return actor metadata map.")
  (-registry [store] "Return {actor-id meta-map} for every registered actor.")
  (-mailbox [store actor-id] "Return the full mailbox vector for an actor.")
  (-clear! [store actor-id] "Clear an actor's mailbox."))
