;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl.normalize-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.dsl :as dsl]
            [eta-mu.dsl.normalize :as normalize]))

;; ---------------------------------------------------------------------------
;; normalize-entry
;; ---------------------------------------------------------------------------

(deftest normalize-tool-test
  (testing "hiccup tool form normalizes to canonical map"
    (let [result (normalize/normalize-entry
                  [:tool {:id          :research/search
                          :description "Search."
                          :args        [:map [:query :string]]}
                   'my.ns/search])]
      (is (= :tool (:ημ/kind result)))
      (is (= :research/search (:id result)))
      (is (= "research_search" (:name result)))
      (is (= 'my.ns/search (:handler result))))))

(deftest normalize-tool-custom-name-test
  (let [result (normalize/normalize-entry
                [:tool {:id          :research/search
                        :name        "custom_name"
                        :description "Search."
                        :args        [:map]}
                 'my.ns/search])]
    (is (= "custom_name" (:name result)))))

(deftest normalize-hook-test
  (let [result (normalize/normalize-entry
                [:hook {:id       :policy/test
                        :event    :tool.execute.before
                        :priority 50}
                 'my.ns/test!])]
    (is (= :hook (:ημ/kind result)))
    (is (= 50 (:priority result)))
    (is (= :tool.execute.before (:event result)))))

(deftest normalize-hook-default-priority-test
  (let [result (normalize/normalize-entry
                [:hook {:id    :policy/test
                        :event :tool.execute.before}
                 'my.ns/test!])]
    (is (= 0 (:priority result)))))

(deftest normalize-plugin-test
  (let [result (normalize/normalize
                [:plugin {:id :plugin/research}
                 [:tool {:id          :research/search
                         :description "Search."
                         :args        [:map]}
                  'my.ns/search]
                 [:hook {:id    :policy/audit
                         :event :tool.execute.after}
                  'my.ns/audit!]])]
    (is (= :plugin (:ημ/kind result)))
    (is (= 2 (count (:entries result))))
    (is (= :tool (:ημ/kind (first (:entries result)))))
    (is (= :hook (:ημ/kind (second (:entries result)))))))

(deftest normalize-unknown-tag-test
  (is (thrown-with-msg? js/Error #"Unknown DSL tag"
        (normalize/normalize-entry [:bogus {}]))))

;; ---------------------------------------------------------------------------
;; merge-fragments and descriptor linking
;; ---------------------------------------------------------------------------

(deftest merge-fragments-test
  (let [tool1 {:ημ/kind :tool
               :id :a/tool
               :name "a"
               :description "A"
               :args [:map]
               :handler 'a/handler}
        tool2 {:ημ/kind :tool
               :id :b/tool
               :name "b"
               :description "B"
               :args [:map]
               :handler 'b/handler}
        hook1 {:ημ/kind :hook
               :id :c/hook
               :event :tool.execute.before
               :priority 0
               :handler 'c/handler}
        result (normalize/merge-fragments [tool1] [tool2 hook1])]
    (is (= 2 (count (:tools result))))
    (is (= 1 (count (:hooks result))))))

(deftest separated-descriptors-link-through-plugin-test
  (let [capability (dsl/capability
                    {:id :research/search
                     :description "Search."
                     :input [:map [:query :string]]
                     :effects #{:network/search}})
        implementation (dsl/implementation
                        {:id :research/search-cljs
                         :capability :research/search
                         :runtime :cljs
                         :handler 'my.ns/search
                         :dependencies #{:http/client}})
        exposure (dsl/exposure
                  {:id :research/search
                   :capability :research/search
                   :implementation :research/search-cljs
                   :target :opencode
                   :name "research_search"
                   :tags #{:research}})
        plugin {:ημ/kind :plugin
                :id :plugin/research
                :entries [exposure implementation capability]}
        registry (normalize/merge-fragments plugin)
        tool (first (:tools registry))]
    (testing "descriptor order does not matter"
      (is (= 1 (count (:capabilities registry))))
      (is (= 1 (count (:implementations registry))))
      (is (= 1 (count (:exposures registry))))
      (is (= 1 (count (:tools registry)))))
    (testing "the flat projection carries selected semantics and runtime"
      (is (= :research/search (:id tool)))
      (is (= 'my.ns/search (:handler tool)))
      (is (= #{:network/search} (:effects tool)))
      (is (= #{:http/client} (:requires tool)))
      (is (= :research/search (:ημ/exposure-id tool))))
    (testing "descriptor linking is idempotent"
      (is (= registry (normalize/link-descriptors registry))))))

(deftest one-capability-can-link-multiple-exposures-test
  (let [capability (dsl/capability
                    {:id :data/read
                     :description "Read data."
                     :input [:map [:key :string]]})
        memory (dsl/implementation
                {:id :data/read-memory
                 :capability :data/read
                 :runtime :cljs
                 :handler 'data/read-memory})
        remote (dsl/implementation
                {:id :data/read-remote
                 :capability :data/read
                 :runtime :cljs
                 :handler 'data/read-remote})
        local-tool (dsl/exposure
                    {:id :data/read
                     :capability :data/read
                     :implementation :data/read-memory
                     :target :opencode})
        remote-tool (dsl/exposure
                     {:id :data/read_remote
                      :capability :data/read
                      :implementation :data/read-remote
                      :target :mcp})
        registry (normalize/merge-fragments
                  [capability memory remote local-tool remote-tool])]
    (is (= #{:data/read :data/read_remote}
           (set (map :id (:tools registry)))))
    (is (= #{'data/read-memory 'data/read-remote}
           (set (map :handler (:tools registry)))))))

(deftest unknown-descriptor-reference-throws-test
  (let [exposure (dsl/exposure
                  {:id :missing/tool
                   :capability :missing/capability
                   :implementation :missing/implementation
                   :target :opencode})]
    (is (thrown-with-msg? js/Error #"unknown descriptor"
          (normalize/merge-fragments exposure)))))

;; ---------------------------------------------------------------------------
;; duplicate IDs and validation
;; ---------------------------------------------------------------------------

(deftest duplicate-ids-test
  (is (= [:a/tool] (normalize/duplicate-ids
                     [{:id :a/tool} {:id :b/hook} {:id :a/tool}])))
  (is (empty? (normalize/duplicate-ids
               [{:id :a/tool} {:id :b/hook}]))))

(deftest validate-registry-duplicates-test
  (is (thrown-with-msg? js/Error #"Duplicate"
        (normalize/validate-registry!
         {:tools [{:id :a/tool} {:id :a/tool}]
          :hooks []}))))

(deftest validate-registry-clean-test
  (let [registry {:tools [{:id :a/tool}] :hooks [{:id :b/hook}]}]
    (is (= registry (normalize/validate-registry! registry)))))
