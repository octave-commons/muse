;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.actor.watch
  "Ledger-backed, non-blocking actor watches.

   Registration returns immediately. A background watcher observes the target
   mailbox, appends terminal state to the watch actor's own ledger, and
   optionally notifies a subscriber actor. Pending watches are resumable from
   the actor registry when a target plugin starts."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.envelope :as envelope]
            [eta-mu.actor.monitor :as monitor]
            [eta-mu.domain.watch :as domain]
            [promesa.core :as p]))

(def watch-window-ms 1000)
(def terminal-lock-poll-ms 5)

(defonce ^:private active-watches (atom #{}))
(defonce ^:private terminal-claims (atom #{}))

(declare start!)

(defn- ->actor-id
  [value]
  (when value
    (if (keyword? value) value (keyword value))))

(defn- terminal-event
  [events]
  (last
   (filter
    #(contains? #{"watch.met" "watch.cancelled" "watch.failed"}
                (:event/type %))
    events)))

(defn- terminal-status
  [event]
  (case (:event/type event)
    "watch.met" "met"
    "watch.cancelled" "cancelled"
    "watch.failed" "failed"
    nil))

(defn snapshot
  "Resolve the current durable watch state. A terminal ledger event takes
   precedence over a stale registry projection after an interrupted write."
  [watch-id]
  (p/let [meta (actor/actor-meta watch-id)]
    (when-not meta
      (throw (ex-info "Watch not found" {:watch-id watch-id})))
    (p/let [events (actor/mailbox watch-id)]
      (let [terminal (terminal-event events)
            payload  (:payload terminal)]
        (cond-> {:watch-id        watch-id
                 :actor-id        (->actor-id (:watch/target meta))
                 :subscriber-id   (->actor-id (:watch/subscriber meta))
                 :status          (or (terminal-status terminal)
                                      (:watch/status meta)
                                      "pending")
                 :condition       (:watch/condition meta)
                 :cursor          (:watch/cursor meta)
                 :registration-id (:watch/registration-id meta)}
          (:count payload) (assoc :count (:count payload))
          (:event payload) (assoc :event (:event payload))
          (:message payload) (assoc :message (:message payload)))))))

(defn- try-claim-terminal!
  [watch-id]
  (loop []
    (let [claimed @terminal-claims]
      (cond
        (contains? claimed watch-id)
        false

        (compare-and-set! terminal-claims claimed (conj claimed watch-id))
        true

        :else
        (recur)))))

(defn- acquire-terminal!
  "Resolve after acquiring the process-local terminal transition claim. Every
   terminal path re-checks the durable ledger after acquiring it, so an
   interrupted prior transition is reconciled rather than duplicated."
  [watch-id]
  (js/Promise.
   (fn [resolve _reject]
     (letfn [(attempt []
               (if (try-claim-terminal! watch-id)
                 (resolve true)
                 (js/setTimeout attempt terminal-lock-poll-ms)))]
       (attempt)))))

(defn- with-terminal-lock
  [watch-id transition]
  (p/let [_ (acquire-terminal! watch-id)]
    (-> (p/let [result (transition)] result)
        (p/finally (fn [_ _]
                     (swap! terminal-claims disj watch-id))))))

(defn- reconcile-terminal!
  [watch-id meta terminal]
  (let [status (terminal-status terminal)]
    (p/let [_ (when (and status (not= status (:watch/status meta)))
                (actor/spawn! watch-id
                              {:watch/status       status
                               :watch/completed-at (envelope/now-iso)}))]
      (snapshot watch-id))))

(defn- terminal-envelope
  [meta event-type payload]
  (let [event-id (envelope/new-id)]
    {:event-id event-id
     :envelope (cond->
                {:event/id      event-id
                 :event/type    event-type
                 :causal/root   (:watch/registration-id meta)
                 :causal/parent (:watch/registration-id meta)
                 :delivery/mode "stream"
                 :delivery/id   (:watch/id meta)
                 :payload       payload}
                 (:watch/session-id meta)
                 (assoc :session/id (:watch/session-id meta))
                 (:watch/turn-id meta)
                 (assoc :turn/id (:watch/turn-id meta)))}))

(defn- notify-subscriber!
  [watch-id meta terminal-id payload]
  (when-let [subscriber-id (->actor-id (:watch/subscriber meta))]
    (actor/send!
     watch-id subscriber-id
     (cond->
      {:event/type    "watch.met"
       :causal/root   (:watch/registration-id meta)
       :causal/parent terminal-id
       :delivery/mode "tell"
       :delivery/id   (:watch/id meta)
       :payload       payload}
       (:watch/session-id meta)
       (assoc :session/id (:watch/session-id meta))
       (:watch/turn-id meta)
       (assoc :turn/id (:watch/turn-id meta))))))

(defn- fulfill!
  [watch-id result]
  (with-terminal-lock
   watch-id
   (fn []
     (p/let [meta   (actor/actor-meta watch-id)
             events (actor/mailbox watch-id)]
       (if-let [terminal (terminal-event events)]
         (reconcile-terminal! watch-id meta terminal)
         (if (not= "pending" (:watch/status meta))
           (snapshot watch-id)
           (let [payload (cond-> {:watch/id (:watch/id meta)
                                  :actor/id (:watch/target meta)
                                  :count    (:count result)}
                           (:event result) (assoc :event (:event result)))
                 {:keys [event-id envelope]}
                 (terminal-envelope meta "watch.met" payload)]
             (p/let [_ (actor/send! watch-id watch-id envelope)
                     _ (actor/spawn! watch-id
                                     {:watch/status       "met"
                                      :watch/completed-at (envelope/now-iso)})
                     _ (notify-subscriber! watch-id meta event-id payload)]
               (snapshot watch-id)))))))))

(defn- fail!
  [watch-id error]
  (with-terminal-lock
   watch-id
   (fn []
     (p/let [meta   (actor/actor-meta watch-id)
             events (when meta (actor/mailbox watch-id))]
       (cond
         (nil? meta)
         nil

         (terminal-event events)
         (reconcile-terminal! watch-id meta (terminal-event events))

         (not= "pending" (:watch/status meta))
         nil

         :else
         (let [payload {:watch/id (:watch/id meta)
                        :actor/id (:watch/target meta)
                        :message  (or (.-message error) (str error))}
               {:keys [envelope]}
               (terminal-envelope meta "watch.failed" payload)]
           (p/let [_ (actor/send! watch-id watch-id envelope)
                   _ (actor/spawn! watch-id
                                   {:watch/status       "failed"
                                    :watch/completed-at (envelope/now-iso)})]
             (snapshot watch-id))))))))

(defn evaluate!
  "Evaluate one pending watch against the current target mailbox. Terminal
   writes are serialized per watch-id and re-check the durable ledger before
   appending, preserving the exactly-once terminal-event invariant."
  [watch-id]
  (p/let [state (snapshot watch-id)]
    (if (domain/terminal? (:status state))
      state
      (p/let [events (actor/mailbox (:actor-id state))]
        (let [result (domain/evaluate (:condition state)
                                     (:cursor state)
                                     events)]
          (if (= "met" (:status result))
            (fulfill! watch-id result)
            state))))))

(defn- run-loop!
  [watch-id]
  (p/let [state (evaluate! watch-id)]
    (if (= "pending" (:status state))
      (p/let [_ (monitor/monitor (:actor-id state) {} watch-window-ms)]
        (run-loop! watch-id))
      state)))

(defn start!
  "Start background evaluation for a watch once. Returns immediately."
  [watch-id]
  (when-not (contains? @active-watches watch-id)
    (swap! active-watches conj watch-id)
    (-> (run-loop! watch-id)
        (p/catch (fn [error] (fail! watch-id error)))
        (p/finally (fn [_ _]
                     (swap! active-watches disj watch-id)))))
  watch-id)

(defn register!
  "Create a durable watch actor and start background evaluation.

   opts: {:subscriber-id keyword?
          :include-existing boolean?
          :session-id string?
          :turn-id string?}"
  [target-id condition {:keys [subscriber-id include-existing session-id turn-id]}]
  (p/let [target-meta     (actor/actor-meta target-id)
          subscriber-meta (when subscriber-id (actor/actor-meta subscriber-id))]
    (when-not target-meta
      (throw (ex-info "Target actor not found" {:actor-id target-id})))
    (p/let [_      (when (and subscriber-id (nil? subscriber-meta))
                     (actor/spawn! subscriber-id {:kind "watch-subscriber"}))
            events (actor/mailbox target-id)]
      (let [watch-id        (keyword (str "watch." (random-uuid)))
            watch-id-string (name watch-id)
            registration-id (envelope/new-id)
            watch-cursor    (domain/cursor events include-existing)
            metadata        {:kind                  "watch"
                             :watch/id              watch-id-string
                             :watch/status          "pending"
                             :watch/target          (name target-id)
                             :watch/subscriber      (some-> subscriber-id name)
                             :watch/condition       condition
                             :watch/cursor          watch-cursor
                             :watch/registration-id registration-id
                             :watch/session-id      session-id
                             :watch/turn-id         turn-id}
            registration    (cond->
                             {:event/id      registration-id
                              :event/type    "watch.registered"
                              :delivery/mode "stream"
                              :delivery/id   watch-id-string
                              :payload       {:watch/id   watch-id-string
                                              :actor/id   (name target-id)
                                              :subscriber (some-> subscriber-id name)
                                              :condition  condition
                                              :cursor     watch-cursor}}
                              session-id (assoc :session/id session-id)
                              turn-id    (assoc :turn/id turn-id))]
        (p/let [_ (actor/spawn! watch-id metadata)
                _ (actor/send! watch-id watch-id registration)]
          (start! watch-id)
          (snapshot watch-id))))))

(defn cancel!
  "Append watch cancellation. Background evaluation observes the terminal
   registry projection and stops without fulfilling the watch."
  [watch-id]
  (with-terminal-lock
   watch-id
   (fn []
     (p/let [meta   (actor/actor-meta watch-id)
             events (actor/mailbox watch-id)]
       (when-not meta
         (throw (ex-info "Watch not found" {:watch-id watch-id})))
       (if-let [terminal (terminal-event events)]
         (reconcile-terminal! watch-id meta terminal)
         (if (domain/terminal? (:watch/status meta))
           (snapshot watch-id)
           (let [payload {:watch/id (:watch/id meta)
                          :actor/id (:watch/target meta)}
                 {:keys [envelope]}
                 (terminal-envelope meta "watch.cancelled" payload)]
             (p/let [_ (actor/send! watch-id watch-id envelope)
                     _ (actor/spawn! watch-id
                                     {:watch/status       "cancelled"
                                      :watch/completed-at (envelope/now-iso)})]
               (snapshot watch-id)))))))))

(defn resume-pending!
  "Restart background evaluation for durable pending watches."
  []
  (p/let [registry (actor/registry)]
    (let [pending (into []
                        (keep (fn [[watch-id meta]]
                                (when (and (= "watch" (:kind meta))
                                           (= "pending" (:watch/status meta)))
                                  watch-id)))
                        registry)]
      (doseq [watch-id pending]
        (start! watch-id))
      {:resumed (count pending)})))
