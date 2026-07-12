(ns eta-mu.dsl-test
  "The authoring macros emit plain :ημ/kind data."
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.dsl :as dsl :refer [deftool defhook defplugin]]))

(deftool echo
  {:id          :test/echo
   :description "Echo the input."
   :args        [:map [:value :string]]
   :tags        #{:test}}
  [{:keys [value]} _ctx]
  {:echoed value})

(defhook guard
  {:id       :test/guard
   :event    :tool.execute.before
   :priority 5}
  [{:keys [value]} _ctx]
  (when (= value "no")
    {:effect :reject :message "denied"}))

(defplugin plugin {:id :test/plugin}
  echo
  guard)

(deftest deftool-emits-data
  (is (= :tool (:ημ/kind echo)))
  (is (= :test/echo (:id echo)))
  (is (= "test_echo" (:name echo)))
  (is (fn? (:handler echo)))
  (is (= {:echoed "hi"} ((:handler echo) {:value "hi"} nil)))
  (is (= #{:test} (:tags echo))))

(deftest defhook-emits-data
  (is (= :hook (:ημ/kind guard)))
  (is (= 5 (:priority guard)))
  (is (= :reject (:effect ((:handler guard) {:value "no"} nil))))
  (is (nil? ((:handler guard) {:value "yes"} nil))))

(deftest defplugin-collects-entries
  (is (= :plugin (:ημ/kind plugin)))
  (is (= :test/plugin (:id plugin)))
  (is (= [echo guard] (:entries plugin))))

(deftest default-name-convention
  (testing "namespaced ids join with underscore"
    (is (= "phase_list_active" (dsl/default-name :phase/list_active)))
    (is (= "muse_spawn" (dsl/default-name :muse/spawn))))
  (testing "bare ids pass through"
    (is (= "spawn" (dsl/default-name :spawn)))))
