(ns eta-mu.domain.mailbox-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.domain.mailbox :as dmail]))

(deftest submap-test
  (testing "flat and nested submap matching"
    (is (dmail/submap? {} {:a 1}))
    (is (dmail/submap? {:a 1} {:a 1 :b 2}))
    (is (not (dmail/submap? {:a 2} {:a 1})))
    (is (dmail/submap? {:a {:b 1}} {:a {:b 1 :c 2}}))
    (is (not (dmail/submap? {:a {:b 9}} {:a {:b 1}})))))

(deftest event-pred-test
  (let [e {:event/type "task.stdout"
           :event/from {:actor-id "task.node.abc"}
           :payload    {:line "hello" :stream "stdout"}}]
    (testing "each constraint matches independently"
      (is ((dmail/event-pred {:event-type "task.stdout"}) e))
      (is ((dmail/event-pred {:from "task.node.abc"}) e))
      (is ((dmail/event-pred {:payload-contains {:stream "stdout"}}) e)))
    (testing "constraints AND together"
      (is ((dmail/event-pred {:event-type "task.stdout"
                              :payload-contains {:line "hello"}}) e))
      (is (not ((dmail/event-pred {:event-type "task.stdout"
                                   :from "someone-else"}) e))))
    (testing "the empty condition matches everything"
      (is ((dmail/event-pred {}) e)))))

(deftest mailbox-met-test
  (let [mb [{:event/type "a" :payload {:n 1}}
            {:event/type "b" :payload {:n 2}}]]
    (testing "min-count against mailbox size"
      (is (dmail/mailbox-met? {:min-count 2} mb))
      (is (not (dmail/mailbox-met? {:min-count 3} mb))))
    (testing "event constraint as existential match"
      (is (dmail/mailbox-met? {:event-type "b"} mb))
      (is (not (dmail/mailbox-met? {:event-type "c"} mb))))
    (testing "count and event constraints AND together"
      (is (dmail/mailbox-met? {:min-count 2 :event-type "a"} mb))
      (is (not (dmail/mailbox-met? {:min-count 3 :event-type "a"} mb))))))

(deftest describe-test
  (is (= "any event" (dmail/describe {})))
  (is (= "event-type=\"x\"" (dmail/describe {:event-type "x"}))))
