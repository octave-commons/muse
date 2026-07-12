(ns eta-mu.opencode.config-test
  "Tests the EDN config → registry pipeline against the real
   .ημ/config/opencode tree (embedded at compile time) linked against the
   real plugin resources."
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.dsl.compile :as dsl.compile]
            [eta-mu.dsl.normalize :as dsl.normalize]
            [eta-mu.dsl.profile :as dsl.profile]
            [eta-mu.opencode.config :as config]
            [plugins.actors :as actors]
            [plugins.receipt-river :as receipt-river]
            [plugins.session-mycology :as session-mycology]
            [plugins.websearch :as websearch]))

(def loaded (config/load-config ".ημ/config/opencode/root.edn"))

(def resource-table
  {'plugins.actors/plugin           actors/plugin
   'plugins.receipt-river/plugin    receipt-river/plugin
   'plugins.session-mycology/plugin session-mycology/plugin
   'plugins.websearch/plugin        websearch/plugin})

(deftest load-config-embeds-tree
  (is (= :eta-mu/actors (get-in loaded [:root :id])))
  (is (= :dev (get-in loaded [:root :profile])))
  (is (= (count (get-in loaded [:root :imports]))
         (count (:fragments loaded)))))

(deftest fragment-classification
  (is (contains? (config/profiles loaded) :dev))
  (is (contains? (config/profiles loaded) :ci))
  (is (seq (config/permissions loaded)))
  (is (= ['plugins.actors/plugin
          'plugins.receipt-river/plugin
          'plugins.session-mycology/plugin
          'plugins.websearch/plugin]
         (config/resources loaded))))

(deftest exposure-linking
  (let [registry (config/apply-exposure loaded resource-table)]
    (testing "all exposed tools have real function handlers"
      (is (= 15 (count (:tools registry))))
      (is (every? fn? (map :handler (:tools registry)))))
    (testing "plugin init carried into the registry"
      (is (= [actors/init!] (:inits registry))))
    (testing "default names follow the namespace_name convention"
      (let [names (set (map :name (:tools registry)))]
        (is (contains? names "muse_spawn"))
        (is (contains? names "phase_list_active"))
        (is (contains? names "receipt_river"))
        (is (contains? names "session_mycology"))
        ;; the opencode config renames :web/search to avoid the host's
        ;; built-in websearch tool
        (is (contains? names "websearch_openhax"))))))

(deftest exposure-filters-by-pattern
  (let [config' (assoc loaded :fragments
                       [{:path "plugins/actors.edn"
                         :data {:resource 'plugins.actors/plugin
                                :expose   [:muse/*]}}])
        registry (config/apply-exposure config' resource-table)]
    (is (= #{:muse/spawn :muse/phases}
           (set (map :id (:tools registry)))))))

(deftest exposure-overrides-win
  (let [config' (assoc loaded :fragments
                       [{:path "plugins/actors.edn"
                         :data {:resource 'plugins.actors/plugin
                                :expose   [:muse/spawn]
                                :overrides {:muse/spawn {:name "spawn_muse"}}}}])
        registry (config/apply-exposure config' resource-table)]
    (is (= ["spawn_muse"] (mapv :name (:tools registry))))))

(deftest missing-resource-throws
  (is (thrown-with-msg? js/Error #"not loaded"
        (config/apply-exposure loaded {}))))

(deftest full-pipeline-compiles
  (let [adapter (->> (config/apply-exposure loaded resource-table)
                     (dsl.profile/apply-profile (config/active-profile loaded))
                     dsl.normalize/validate-registry!
                     dsl.compile/compile-adapter)]
    (is (= 15 (count (:tools adapter))))
    (is (every? string? (map :name (:tools adapter))))
    (is (= 1 (count (:inits adapter))))))

(deftest ci-profile-restricts
  (let [registry (->> (config/apply-exposure loaded resource-table)
                      (dsl.profile/apply-profile (get (config/profiles loaded) :ci)))]
    (is (= #{:actor/list :receipt/river}
           (set (map :id (:tools registry)))))))

(deftest ci-profile-denies-network-effects
  (testing "websearch declares :network/search and ci denies it"
    (let [registry (->> (config/apply-exposure loaded resource-table)
                        (dsl.profile/apply-profile
                         (assoc (get (config/profiles loaded) :ci)
                                :allow #{:web/* :receipt/*})))]
      (is (= [:receipt/river] (mapv :id (:tools registry)))))))
