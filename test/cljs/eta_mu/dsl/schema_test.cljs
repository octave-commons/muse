;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl.schema-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.dsl.schema :as schema]
            [malli.core :as m]))

(deftest schema-expr-test
  (testing "keywords and vector expressions are valid"
    (is (m/validate schema/schema-expr :string))
    (is (m/validate schema/schema-expr [:map [:query :string]]))
    (is (m/validate schema/schema-expr [:or :string :int]))))

;; ---------------------------------------------------------------------------
;; Separated descriptors
;; ---------------------------------------------------------------------------

(deftest capability-valid-test
  (let [capability {:ημ/kind :capability
                    :id :research/search
                    :description "Search public sources."
                    :input [:map [:query :string]]
                    :output [:map [:results [:vector :map]]]
                    :effects #{:network/search}
                    :errors [{:id :search/unavailable}]}]
    (is (m/validate schema/capability capability))
    (is (not (contains? capability :handler)))
    (is (not (contains? capability :name)))))

(deftest capability-rejects-executable-fusion-test
  (testing "handler cannot substitute for the required semantic input"
    (is (not (m/validate schema/capability
                         {:id :research/search
                          :description "Search."
                          :handler 'my.ns/search})))))

(deftest implementation-valid-test
  (let [implementation {:ημ/kind :implementation
                        :id :research/search-cljs
                        :capability :research/search
                        :runtime :cljs
                        :handler 'my.ns/search
                        :dependencies #{:http/client}
                        :version "1"}]
    (is (m/validate schema/implementation implementation))))

(deftest implementation-requires-handler-test
  (is (not (m/validate schema/implementation
                       {:id :research/search-cljs
                        :capability :research/search
                        :runtime :cljs}))))

(deftest exposure-valid-test
  (let [exposure {:ημ/kind :exposure
                  :id :research/search
                  :capability :research/search
                  :implementation :research/search-cljs
                  :target :opencode
                  :name "research_search"
                  :tags #{:research}}]
    (is (m/validate schema/exposure exposure))
    (is (not (contains? exposure :handler)))))

(deftest exposure-requires-selected-implementation-test
  (is (not (m/validate schema/exposure
                       {:id :research/search
                        :capability :research/search
                        :target :opencode}))))

;; ---------------------------------------------------------------------------
;; Legacy linked tool / hook / plugin
;; ---------------------------------------------------------------------------

(deftest tool-valid-test
  (let [tool {:id          :research/search
              :description "Search public sources."
              :args        [:map [:query :string]]
              :handler     'my.ns/search}]
    (is (m/validate schema/tool tool))))

(deftest tool-valid-optional-fields-test
  (let [tool {:id          :research/search
              :description "Search public sources."
              :args        [:map [:query :string]]
              :handler     'my.ns/search}]
    (is (m/validate schema/tool tool))
    (is (not (:name tool)))
    (is (not (:capability tool)))))

(deftest tool-missing-required-test
  (is (not (m/validate schema/tool {:id :research/search}))))

(deftest tool-keyword-args-test
  (is (m/validate schema/tool
                  {:id :research/search
                   :description "Search."
                   :args :map
                   :handler 'my.ns/search})))

(deftest hook-valid-test
  (is (m/validate schema/hook
                  {:id :policy/protect-env
                   :event :tool.execute.before
                   :priority 100
                   :handler 'my.ns/protect!})))

(deftest hook-invalid-event-test
  (is (not (m/validate schema/hook
                       {:id :policy/test
                        :event "tool.execute.before"
                        :handler 'my.ns/test!}))))

(deftest plugin-valid-with-separated-descriptors-test
  (let [capability {:id :research/search
                    :description "Search."
                    :input [:map]}
        implementation {:id :research/search-cljs
                        :capability :research/search
                        :runtime :cljs
                        :handler 'my.ns/search}
        exposure {:id :research/search
                  :capability :research/search
                  :implementation :research/search-cljs
                  :target :opencode}
        plugin {:id :plugin/research
                :entries [capability implementation exposure]}]
    (is (m/validate schema/plugin plugin))))

(deftest plugin-empty-test
  (is (m/validate schema/plugin {:id :plugin/empty})))

(deftest registry-valid-test
  (let [registry {:tools [{:id :test/tool
                           :description "Test."
                           :args [:map]
                           :handler 'my.ns/test}]
                  :hooks []
                  :capabilities [{:id :test/tool
                                  :description "Test."
                                  :input [:map]}]
                  :implementations [{:id :test/tool-cljs
                                     :capability :test/tool
                                     :runtime :cljs
                                     :handler 'my.ns/test}]
                  :exposures [{:id :test/tool
                               :capability :test/tool
                               :implementation :test/tool-cljs
                               :target :opencode}]}]
    (is (m/validate schema/registry registry))))

(deftest registry-empty-test
  (is (m/validate schema/registry {:tools [] :hooks []})))

(deftest profile-valid-test
  (let [profiles {:dev  {:allow #{:research/*}
                         :audit :verbose}
                  :ci   {:allow #{:git/*}
                         :deny #{:browser/*}}
                  :prod {:allow #{:capability/*}}}]
    (is (m/validate schema/profiles profiles))))
