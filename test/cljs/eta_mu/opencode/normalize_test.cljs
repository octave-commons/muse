(ns eta-mu.opencode.normalize-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.opencode.normalize :as normalize]
            [eta-mu.opencode.schema :as schema]
            [malli.core :as m]))

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
      (is (= :tool (:opencode/kind result)))
      (is (= :research/search (:id result)))
      (is (= "search" (:name result)))
      (is (= 'my.ns/search (:handler result))))))

(deftest normalize-tool-custom-name-test
  (testing "custom name is preserved"
    (let [result (normalize/normalize-entry
                  [:tool {:id          :research/search
                          :name        "custom_name"
                          :description "Search."
                          :args        [:map]}
                   'my.ns/search])]
      (is (= "custom_name" (:name result))))))

(deftest normalize-hook-test
  (testing "hiccup hook form normalizes to canonical map"
    (let [result (normalize/normalize-entry
                  [:hook {:id       :policy/test
                          :event    :tool.execute.before
                          :priority 50}
                   'my.ns/test!])]
      (is (= :hook (:opencode/kind result)))
      (is (= 50 (:priority result)))
      (is (= :tool.execute.before (:event result))))))

(deftest normalize-hook-default-priority-test
  (testing "default priority is 0"
    (let [result (normalize/normalize-entry
                  [:hook {:id    :policy/test
                          :event :tool.execute.before}
                   'my.ns/test!])]
      (is (= 0 (:priority result))))))

(deftest normalize-plugin-test
  (testing "hiccup plugin form normalizes recursively"
    (let [result (normalize/normalize
                  [:plugin {:id :plugin/research}
                   [:tool {:id          :research/search
                           :description "Search."
                           :args        [:map]}
                    'my.ns/search]
                   [:hook {:id    :policy/audit
                           :event :tool.execute.after}
                    'my.ns/audit!]])]
      (is (= :plugin (:opencode/kind result)))
      (is (= 2 (count (:entries result))))
      (is (= :tool (:opencode/kind (first (:entries result)))))
      (is (= :hook (:opencode/kind (second (:entries result))))))))

(deftest normalize-unknown-tag-test
  (testing "unknown tag throws"
    (is (thrown-with-msg? js/Error #"Unknown DSL tag"
          (normalize/normalize-entry [:bogus {}])))))

;; ---------------------------------------------------------------------------
;; merge-fragments
;; ---------------------------------------------------------------------------

(deftest merge-fragments-test
  (testing "multiple fragments merge into a registry"
    (let [tool1 {:opencode/kind :tool
                 :id :a/tool
                 :name "a"
                 :description "A"
                 :args [:map]
                 :handler 'a/handler}
          tool2 {:opencode/kind :tool
                 :id :b/tool
                 :name "b"
                 :description "B"
                 :args [:map]
                 :handler 'b/handler}
          hook1 {:opencode/kind :hook
                 :id :c/hook
                 :event :tool.execute.before
                 :priority 0
                 :handler 'c/handler}
          result (normalize/merge-fragments [tool1] [tool2 hook1])]
      (is (= 2 (count (:tools result))))
      (is (= 1 (count (:hooks result)))))))

;; ---------------------------------------------------------------------------
;; duplicate-ids
;; ---------------------------------------------------------------------------

(deftest duplicate-ids-test
  (testing "finds duplicate IDs"
    (is (= [:a/tool] (normalize/duplicate-ids
                       [{:id :a/tool} {:id :b/hook} {:id :a/tool}]))))
  (testing "no duplicates returns empty"
    (is (empty? (normalize/duplicate-ids
                 [{:id :a/tool} {:id :b/hook}])))))

;; ---------------------------------------------------------------------------
;; validate-registry!
;; ---------------------------------------------------------------------------

(deftest validate-registry-duplicates-test
  (testing "throws on duplicate tool IDs"
    (is (thrown-with-msg? js/Error #"Duplicate"
          (normalize/validate-registry!
           {:tools [{:id :a/tool} {:id :a/tool}]
             :hooks []})))))

(deftest validate-registry-clean-test
  (testing "passes on unique IDs"
    (let [registry {:tools [{:id :a/tool}] :hooks [{:id :b/hook}]}]
      (is (= registry (normalize/validate-registry! registry))))))
