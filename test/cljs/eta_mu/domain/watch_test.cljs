;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.domain.watch-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.domain.watch :as watch]))

(def existing
  [{:event/id "e-1" :event/type "job.started" :payload {}}
   {:event/id "e-2" :event/type "job.done" :payload {:n 1}}])

(deftest future-only-cursor-test
  (let [cursor (watch/cursor existing false)]
    (testing "existing matching events do not fulfill a future-only watch"
      (is (= "pending"
             (:status (watch/evaluate {:event-type "job.done"}
                                      cursor
                                      existing)))))
    (testing "a later matching event fulfills it"
      (let [later (conj existing
                        {:event/id "e-3"
                         :event/type "job.done"
                         :payload {:n 2}})
            result (watch/evaluate {:event-type "job.done"
                                    :payload-contains {:n 2}}
                                   cursor
                                   later)]
        (is (= "met" (:status result)))
        (is (= "e-3" (get-in result [:event :event/id])))))))

(deftest include-existing-test
  (let [cursor (watch/cursor existing true)
        result (watch/evaluate {:event-type "job.done"}
                               cursor
                               existing)]
    (is (= "met" (:status result)))
    (is (= "e-2" (get-in result [:event :event/id])))))

(deftest count-condition-test
  (let [cursor (watch/cursor existing false)]
    (is (= "pending"
           (:status (watch/evaluate {:min-count 3} cursor existing))))
    (is (= "met"
           (:status (watch/evaluate {:min-count 3}
                                    cursor
                                    (conj existing
                                          {:event/id "e-3"
                                           :event/type "anything"
                                           :payload {}})))))))

(deftest empty-condition-means-next-event-test
  (let [cursor (watch/cursor existing false)]
    (is (= "pending" (:status (watch/evaluate {} cursor existing))))
    (is (= "met"
           (:status (watch/evaluate {}
                                    cursor
                                    (conj existing
                                          {:event/id "e-3"
                                           :event/type "later"
                                           :payload {}})))))))
