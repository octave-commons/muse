(ns eta-mu.actor.monitor-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]
            [eta-mu.actor.monitor :as monitor]
            [promesa.core :as p]))

(use-fixtures :each
  {:before #(actor/init-store! (mem/make-memory-store))})

(defn- run-async
  [done body]
  (-> (body)
      (p/catch (fn [e] (is false (str "unexpected rejection: " e))))
      (p/finally (fn [_ _] (done)))))

(deftest already-met-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :inbox)
                _ (actor/spawn! :peer)
                _ (actor/tell! :peer :inbox "job.done" {:ok true})
                r (monitor/monitor :inbox {:event-type "job.done"} 1000)]
          (testing "an event already in the ledger resolves immediately"
            (is (:met r))
            (is (= "job.done" (get-in r [:event :event/type])))))))))

(deftest wake-on-later-event-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :inbox)
                _ (actor/spawn! :peer)
                _ (js/setTimeout
                   #(actor/tell! :peer :inbox "job.done" {:n 7}) 300)
                r (monitor/monitor :inbox {:event-type "job.done"
                                           :payload-contains {:n 7}} 5000)]
          (testing "monitor wakes when the matching event lands"
            (is (:met r))
            (is (= 7 (get-in r [:event :payload :n])))))))))

(deftest timeout-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :quiet)
                r (monitor/monitor :quiet {:event-type "never"} 700)]
          (testing "an unmet condition times out with met=false"
            (is (not (:met r)))))))))

(deftest min-count-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :inbox)
                _ (actor/spawn! :peer)
                _ (js/setTimeout
                   (fn []
                     (p/do (actor/tell! :peer :inbox "a" {})
                           (actor/tell! :peer :inbox "b" {}))) 300)
                r (monitor/monitor :inbox {:min-count 2} 5000)]
          (testing "count threshold met after later appends"
            (is (:met r))
            (is (= 2 (:count r)))))))))
