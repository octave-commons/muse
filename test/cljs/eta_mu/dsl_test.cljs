;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl-test
  "The authoring macros emit plain data and separated descriptors link into the
   legacy tool projection without changing existing deftool behavior."
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.dsl :as dsl
             :refer [defcapability defexposure defhook defimplementation
                     defplugin deftool]]))

(deftool echo
  {:id          :test/echo
   :description "Echo the input."
   :args        [:map [:value :string]]
   :tags        #{:test}}
  [{:keys [value]} _ctx]
  {:echoed value})

(defcapability lookup-capability
  {:id          :test/lookup
   :description "Look up one value."
   :input       [:map [:key :string]]
   :output      [:map [:value :string]]
   :effects     #{:storage/read}})

(defimplementation memory-lookup
  {:id           :test/lookup-memory
   :capability   :test/lookup
   :runtime      :cljs
   :dependencies #{:memory/store}
   :version      "1"}
  [{:keys [key]} _ctx]
  {:value (str "memory:" key)})

(defimplementation remote-lookup
  {:id           :test/lookup-remote
   :capability   :test/lookup
   :runtime      :cljs
   :dependencies #{:network/client}
   :version      "1"}
  [{:keys [key]} _ctx]
  {:value (str "remote:" key)})

(defexposure memory-lookup-tool
  {:id             :test/lookup
   :capability     :test/lookup
   :implementation :test/lookup-memory
   :target         :opencode
   :name           "lookup"
   :tags           #{:test}})

(defexposure remote-lookup-tool
  {:id             :test/lookup_remote
   :capability     :test/lookup
   :implementation :test/lookup-remote
   :target         :mcp
   :name           "lookup_remote"
   :tags           #{:test :network}})

(def linked-memory-lookup
  (dsl/link-tool lookup-capability memory-lookup memory-lookup-tool))

(def linked-remote-lookup
  (dsl/link-tool lookup-capability remote-lookup remote-lookup-tool))

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

(deftest deftool-emits-compatible-flat-data
  (is (= :tool (:ημ/kind echo)))
  (is (= :test/echo (:id echo)))
  (is (= "test_echo" (:name echo)))
  (is (fn? (:handler echo)))
  (is (= {:echoed "hi"} ((:handler echo) {:value "hi"} nil)))
  (is (= #{:test} (:tags echo)))
  (is (= #{:ημ/kind :id :name :description :args :handler :source :tags}
         (set (keys echo)))))

(deftest separated-descriptors-have-single-authorities
  (testing "semantic capability has no executable or host-facing fields"
    (is (= :capability (:ημ/kind lookup-capability)))
    (is (not (contains? lookup-capability :handler)))
    (is (not (contains? lookup-capability :name))))
  (testing "implementation owns the executable binding"
    (is (= :implementation (:ημ/kind memory-lookup)))
    (is (fn? (:handler memory-lookup)))
    (is (= :test/lookup (:capability memory-lookup))))
  (testing "exposure owns target presentation"
    (is (= :exposure (:ημ/kind memory-lookup-tool)))
    (is (= :opencode (:target memory-lookup-tool)))
    (is (= "lookup" (:name memory-lookup-tool)))
    (is (not (contains? memory-lookup-tool :handler)))))

(deftest one-capability-links-to-multiple-projections
  (is (= :test/lookup (:id linked-memory-lookup)))
  (is (= :test/lookup_remote (:id linked-remote-lookup)))
  (is (= #{:storage/read} (:effects linked-memory-lookup)))
  (is (= #{:memory/store} (:requires linked-memory-lookup)))
  (is (= #{:network/client} (:requires linked-remote-lookup)))
  (is (= {:value "memory:k"}
         ((:handler linked-memory-lookup) {:key "k"} nil)))
  (is (= {:value "remote:k"}
         ((:handler linked-remote-lookup) {:key "k"} nil))))

(deftest link-tool-rejects-crossed-references
  (is (thrown-with-msg? js/Error #"reference mismatch"
        (dsl/link-tool lookup-capability
                       memory-lookup
                       (assoc memory-lookup-tool
                              :implementation :test/lookup-remote)))))

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
