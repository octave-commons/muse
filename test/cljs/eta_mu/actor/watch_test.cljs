;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.actor.watch-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as memory]
            [eta-mu.actor.watch :as watch]
            [promesa.core :as p]))

(use-fixtures :each
  {:before #(actor/init-store! (memory/make-memory-store))})

(defn- run-async
  [done body]
  (-> (body)
      (p/catch (fn [error]
                 (is false (str "unexpected rejection: " error))))
      (p/finally (fn [_ _] (done)))))

(defn- event-of-type
  [event-type events]
  (first (filter #(= event-type (:event/type %)) events)))

(defn- wait-for-status
  [watch-id expected timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (letfn [(step []
              (p/let [state (watch/snapshot watch-id)]
                (cond
                  (= expected (:status state))
                  state

                  (< (js/Date.now) deadline)
                  (js/Promise.
                   (fn [resolve _]
                     (js/setTimeout #(resolve (step)) 25)))

                  :else
                  (throw (ex-info "Timed out waiting for watch status"
                                  {:watch-id watch-id
                                   :expected expected
                                   :actual (:status state)})))))]
      (step))))

(deftest nonblocking-watch-fulfillment-test
  (async done
    (run-async
     done
     (fn []
       (p/let [_       (actor/spawn! :target)
               _       (actor/spawn! :subscriber)
               pending (watch/register!
                        :target
                        {:event-type "job.done"
                         :payload-contains {:ok true}}
                        {:subscriber-id :subscriber
                         :session-id "session-1"
                         :turn-id "turn-1"})
               _       (actor/tell! :worker :target "job.done" {:ok true})
               met     (wait-for-status (:watch-id pending) "met" 2000)
               watch-events (actor/mailbox (:watch-id pending))
               subscriber-events (actor/mailbox :subscriber)]
         (testing "registration returns a resumable pending watch"
           (is (= "pending" (:status pending)))
           (is (keyword? (:watch-id pending)))
           (is (= :target (:actor-id pending))))
         (testing "later fulfillment is durable and delivered"
           (is (= "met" (:status met)))
           (is (= "job.done" (get-in met [:event :event/type])))
           (is (= "session-1" (:session/id (event-of-type "watch.met" watch-events))))
           (is (= "turn-1" (:turn/id (event-of-type "watch.met" watch-events)))))
         (let [registered (event-of-type "watch.registered" watch-events)
               fulfilled  (event-of-type "watch.met" watch-events)
               notice     (event-of-type "watch.met" subscriber-events)]
           (testing "watch events preserve causal and delivery identity"
             (is (= (:event/id registered) (:causal/root fulfilled)))
             (is (= (:event/id registered) (:causal/parent fulfilled)))
             (is (= (:event/id fulfilled) (:causal/parent notice)))
             (is (= (name (:watch-id pending)) (:delivery/id fulfilled)))
             (is (= (name (:watch-id pending)) (:delivery/id notice))))))))))

(deftest cancellation-is-terminal-test
  (async done
    (run-async
     done
     (fn []
       (p/let [_         (actor/spawn! :target)
               pending   (watch/register! :target {:event-type "later"} {})
               cancelled (watch/cancel! (:watch-id pending))
               _         (actor/tell! :worker :target "later" {})
               final     (watch/snapshot (:watch-id pending))
               events    (actor/mailbox (:watch-id pending))]
         (is (= "cancelled" (:status cancelled)))
         (is (= "cancelled" (:status final)))
         (is (some? (event-of-type "watch.cancelled" events)))
         (is (nil? (event-of-type "watch.met" events))))))))

(deftest include-existing-can-fulfill-on-registration-test
  (async done
    (run-async
     done
     (fn []
       (p/let [_       (actor/spawn! :target)
               _       (actor/tell! :worker :target "already" {:n 1})
               pending (watch/register! :target
                                        {:event-type "already"}
                                        {:include-existing true})
               met     (wait-for-status (:watch-id pending) "met" 2000)]
         (is (contains? #{"pending" "met"} (:status pending)))
         (is (= "met" (:status met)))
         (is (= "already" (get-in met [:event :event/type]))))))))
