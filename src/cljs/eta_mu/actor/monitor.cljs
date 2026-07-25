(ns eta-mu.actor.monitor
  "Condition-triggered mailbox watching: block until an actor's
   mailbox meets a declarative condition or a timeout passes. The
   monitor_mailbox cell — decisions in eta-mu.domain.mailbox.

   Watches by polling through the eta-mu.actor API so it always sees
   the store the caller initialized, whatever the backend."
  (:require [eta-mu.actor :as actor]
            [eta-mu.domain.mailbox :as dmail]
            [promesa.core :as p]))

(def poll-interval-ms 250)

(defn- poll-until
  "Poll actor-id's mailbox until (check mailbox) returns a result, or
   resolve (final mailbox) at timeout. check and final are pure."
  [actor-id check final timeout-ms]
  (js/Promise.
   (fn [resolve _]
     (let [finished (atom false)
           interval (atom nil)
           finish   (fn [v]
                      (when (compare-and-set! finished false true)
                        (js/clearInterval @interval)
                        (resolve v)))
           sample!  (fn [on-miss]
                      (-> (p/let [mb (actor/mailbox actor-id)]
                            (if-let [hit (check mb)]
                              (finish hit)
                              (on-miss mb)))
                          (p/catch (fn [_] (on-miss [])))))]
       (reset! interval (js/setInterval #(sample! (fn [_] nil))
                                        poll-interval-ms))
       (js/setTimeout #(sample! (fn [mb] (finish (final mb)))) timeout-ms)
       (sample! (fn [_] nil))))))

(defn monitor
  "Watch actor-id's mailbox until the condition is met or timeout-ms
   passes. Resolves to {:met bool :event envelope? :count int?}.

   Count thresholds and events already in the ledger resolve
   immediately; a pure event condition on a quiet mailbox waits for
   the next matching append. An empty condition means the next append,
   whatever it is."
  [actor-id condition timeout-ms]
  (p/let [mb (actor/mailbox actor-id)]
    (let [pred (dmail/event-pred condition)
          n0   (count mb)]
      (cond
        ;; count thresholds (with optional event constraints): whole-mailbox
        (:min-count condition)
        (if (dmail/mailbox-met? condition mb)
          {:met true :count n0}
          (poll-until actor-id
                      #(when (dmail/mailbox-met? condition %)
                         {:met true :count (count %)})
                      #(hash-map :met (dmail/mailbox-met? condition %)
                                 :count (count %))
                      timeout-ms))

        ;; event constraints: an event already in the ledger satisfies it
        (and (dmail/event-condition? condition) (some pred mb))
        {:met true :event (some #(when (pred %) %) mb) :count n0}

        ;; otherwise: wait for the next append matching the condition
        ;; (the empty condition's pred matches anything)
        :else
        (poll-until actor-id
                    (fn [mb]
                      (when-let [hit (some #(when (pred %) %) (drop n0 mb))]
                        {:met true :event hit}))
                    (constantly {:met false})
                    timeout-ms)))))
