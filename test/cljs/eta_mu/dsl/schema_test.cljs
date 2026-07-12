(ns eta-mu.dsl.schema-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.dsl.schema :as schema]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Schema expression validation (used for :args, :input, :output)
;; ---------------------------------------------------------------------------

(deftest schema-expr-keyword-test
  (testing "keyword is a valid schema expression"
    (is (m/validate schema/schema-expr :string))
    (is (m/validate schema/schema-expr :int))
    (is (m/validate schema/schema-expr :boolean))))

(deftest schema-expr-vector-test
  (testing "vector is a valid schema expression"
    (is (m/validate schema/schema-expr [:map [:query :string]]))
    (is (m/validate schema/schema-expr [:vector :string]))
    (is (m/validate schema/schema-expr [:or :string :int]))))

;; ---------------------------------------------------------------------------
;; Tool schema validation
;; ---------------------------------------------------------------------------

(deftest tool-valid-test
  (testing "a valid tool definition"
    (let [tool {:id          :research/search
                :description "Search public sources."
                :args        [:map [:query :string]]
                :handler     'my.ns/search}]
      (is (m/validate schema/tool tool)))))

(deftest tool-valid-optional-fields-test
  (testing "optional fields can be omitted"
    (let [tool {:id          :research/search
                :description "Search public sources."
                :args        [:map [:query :string]]
                :handler     'my.ns/search}]
      (is (m/validate schema/tool tool))
      (is (not (:name tool)))
      (is (not (:capability tool))))))

(deftest tool-missing-required-test
  (testing "missing required fields fail validation"
    (let [tool {:id :research/search}]
      (is (not (m/validate schema/tool tool))))))

(deftest tool-keyword-args-test
  (testing "args can be a simple keyword"
    (let [tool {:id          :research/search
                :description "Search."
                :args        :map
                :handler     'my.ns/search}]
      (is (m/validate schema/tool tool)))))

;; ---------------------------------------------------------------------------
;; Hook schema validation
;; ---------------------------------------------------------------------------

(deftest hook-valid-test
  (testing "a valid hook definition"
    (let [hook {:id       :policy/protect-env
                :event    :tool.execute.before
                :priority 100
                :handler  'my.ns/protect!}]
      (is (m/validate schema/hook hook)))))

(deftest hook-invalid-event-test
  (testing "non-keyword hook event fails (vocabulary is target-validated)"
    (let [hook {:id      :policy/test
                :event   "tool.execute.before"
                :handler 'my.ns/test!}]
      (is (not (m/validate schema/hook hook))))))

(deftest hook-default-priority-test
  (testing "priority is optional"
    (let [hook {:id      :policy/test
                :event   :tool.execute.before
                :handler 'my.ns/test!}]
      (is (m/validate schema/hook hook)))))

;; ---------------------------------------------------------------------------
;; Plugin schema validation
;; ---------------------------------------------------------------------------

(deftest plugin-valid-test
  (testing "a valid plugin with tools and hooks"
    (let [plugin {:id    :plugin/research
                  :tools [{:id          :research/search
                           :description "Search."
                           :args        [:map [:query :string]]
                           :handler     'my.ns/search}]
                  :hooks [{:id      :policy/audit
                           :event   :tool.execute.after
                           :handler 'my.ns/audit!}]}]
      (is (m/validate schema/plugin plugin)))))

(deftest plugin-empty-test
  (testing "plugin with no tools or hooks is valid"
    (let [plugin {:id :plugin/empty}]
      (is (m/validate schema/plugin plugin)))))

;; ---------------------------------------------------------------------------
;; Registry schema validation
;; ---------------------------------------------------------------------------

(deftest registry-valid-test
  (testing "a valid registry"
    (let [registry {:id    :my/registry
                    :tools [{:id          :test/tool
                             :description "Test."
                             :args        [:map]
                             :handler     'my.ns/test}]
                    :hooks []}]
      (is (m/validate schema/registry registry)))))

(deftest registry-empty-test
  (testing "registry with empty tools is valid"
    (let [registry {:id :my/registry :tools [] :hooks []}]
      (is (m/validate schema/registry registry)))))

;; ---------------------------------------------------------------------------
;; Profile schema validation
;; ---------------------------------------------------------------------------

(deftest profile-valid-test
  (testing "valid profile rule"
    (let [profiles {:dev  {:allow #{:research/*}
                           :audit :verbose}
                    :ci   {:allow #{:git/*}
                           :deny #{:browser/*}}
                    :prod {:allow #{:capability/*}}}]
      (is (m/validate schema/profiles profiles)))))
