(ns eta-mu.domain.task-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.domain.task :as task]))

(deftest task-id-test
  (let [id-fn (constantly "abcdef12-3456-7890-abcd-ef1234567890")]
    (testing "id is task.<cmd-slug>.<short-uuid>"
      (is (= :task.node.abcdef12 (task/task-id id-fn ["node" "-e" "1"]))))
    (testing "path and case are slugged away"
      (is (= :task.my-tool.abcdef12 (task/task-id id-fn ["/usr/bin/My_Tool!"]))))
    (testing "unusable command names fall back to proc"
      (is (= :task.proc.abcdef12 (task/task-id id-fn ["///"]))))))

(deftest initial-state-test
  (let [state (task/initial-state ["watcher" :other])]
    (testing "subscribers normalize to a keyword set"
      (is (= #{:watcher :other} (:subscribers state))))
    (testing "fresh task is running"
      (is (= :running (:status state)))
      (is (not (task/terminal? state))))))

(deftest count-line-test
  (let [state (-> (task/initial-state [])
                  (task/count-line :stdout)
                  (task/count-line :stdout)
                  (task/count-line :stderr))]
    (testing "lines are accounted per stream"
      (is (= 2 (:stdout-lines state)))
      (is (= 1 (:stderr-lines state))))))

(deftest control-effect-test
  (testing "subscribe/unsubscribe carry the subscriber"
    (is (= {:subscribe :w}
           (task/control-effect {:event/type "task.subscribe"
                                 :payload    {:subscriber "w"}})))
    (is (= {:unsubscribe :w}
           (task/control-effect {:event/type "task.unsubscribe"
                                 :payload    {:subscriber "w"}}))))
  (testing "kill needs no payload"
    (is (= {:kill true} (task/control-effect {:event/type "task.kill"}))))
  (testing "malformed and non-control envelopes are nil"
    (is (nil? (task/control-effect {:event/type "task.subscribe" :payload {}})))
    (is (nil? (task/control-effect {:event/type "task.stdout"})))))

(deftest apply-control-test
  (let [state (task/initial-state ["a"])]
    (testing "subscribe adds, unsubscribe removes"
      (is (= #{:a :b} (:subscribers (task/apply-control state {:subscribe :b}))))
      (is (= #{} (:subscribers (task/apply-control state {:unsubscribe :a})))))
    (testing "kill flags the request"
      (is (:kill-requested (task/apply-control state {:kill true}))))))

(deftest exited-payload-test
  (let [state (-> (task/initial-state [])
                  (task/count-line :stdout))]
    (testing "clean zero exit is ok"
      (is (:ok (task/exited-payload state {:code 0 :signal nil})))
      (is (= 1 (:stdout-lines (task/exited-payload state {:code 0 :signal nil})))))
    (testing "nonzero exit is not ok"
      (is (not (:ok (task/exited-payload state {:code 1 :signal nil})))))
    (testing "a killed task is never ok, even at code 0"
      (let [killed (task/apply-control state {:kill true})]
        (is (not (:ok (task/exited-payload killed {:code 0 :signal nil}))))
        (is (:killed (task/exited-payload killed {:code 0 :signal nil})))))))
