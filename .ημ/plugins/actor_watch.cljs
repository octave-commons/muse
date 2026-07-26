;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns plugins.actor-watch
  "Host-agnostic tools for resumable actor watches. Registering a watch never
   blocks the host turn; fulfillment is recorded and optionally delivered to a
   subscriber actor by eta-mu.actor.watch."
  (:require [eta-mu.actor.backend :as backend]
            [eta-mu.actor.watch :as watch]
            [eta-mu.domain.mailbox :as mailbox]
            [eta-mu.dsl :refer [deftool defplugin]]
            [promesa.core :as p]))

(defn- condition-map
  [{:keys [event_type from payload_contains min_count]}]
  (cond-> {}
    event_type       (assoc :event-type event_type)
    from             (assoc :from from)
    payload_contains (assoc :payload-contains payload_contains)
    min_count        (assoc :min-count min_count)))

(defn- watch-view
  [state]
  (cond-> (-> state
              (update :watch-id name)
              (update :actor-id name)
              (update :subscriber-id #(some-> % name)))
    (:event state) (update :event #(dissoc % :source))))

(deftool actor-watch
  {:id          :actor/watch
   :description "Register a durable, non-blocking mailbox watch and return
                 immediately. Fulfillment is appended to the watch actor's
                 ledger and optionally delivered to subscriber_id. With no
                 constraints, the next event after registration fulfills it."
   :args        [:map
                 [:actor_id :string]
                 [:subscriber_id {:optional true} :string]
                 [:event_type {:optional true} :string]
                 [:from {:optional true} :string]
                 [:payload_contains {:optional true} :map]
                 [:min_count {:optional true} :int]
                 [:include_existing {:optional true} :boolean]]
   :tags        #{:actor :actors}}
  [{:keys [actor_id subscriber_id include_existing] :as args} ctx]
  (let [condition (condition-map args)]
    (p/let [_     (backend/ensure!)
            state (watch/register!
                   (keyword actor_id)
                   condition
                   {:subscriber-id   (some-> subscriber_id keyword)
                    :include-existing include_existing
                    :session-id      (:session/id ctx)
                    :turn-id         (:message/id ctx)})]
      (assoc (watch-view state)
             :condition_description (mailbox/describe condition)))))

(deftool actor-watch-status
  {:id          :actor/watch_status
   :description "Read and re-evaluate a durable actor watch without blocking.
                 Returns pending, met, cancelled, or failed."
   :args        [:map [:watch_id :string]]
   :tags        #{:actor :actors}}
  [{:keys [watch_id]} _ctx]
  (p/let [_     (backend/ensure!)
          state (watch/evaluate! (keyword watch_id))]
    (watch-view state)))

(deftool actor-watch-cancel
  {:id          :actor/watch_cancel
   :description "Cancel a pending actor watch by appending a terminal event to
                 its ledger."
   :args        [:map [:watch_id :string]]
   :tags        #{:actor :actors}}
  [{:keys [watch_id]} _ctx]
  (p/let [_     (backend/ensure!)
          state (watch/cancel! (keyword watch_id))]
    (watch-view state)))

(defn init!
  "Resume pending durable watches when a generated target activates."
  []
  (p/let [_ (backend/ensure!)]
    (watch/resume-pending!)))

(defplugin plugin {:id :eta-mu/actor-watch :init init!}
  actor-watch
  actor-watch-status
  actor-watch-cancel)
