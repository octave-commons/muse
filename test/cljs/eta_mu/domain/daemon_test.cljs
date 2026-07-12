(ns eta-mu.domain.daemon-test
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.daemon :as daemon]))

(deftest relevant-change-filters
  (testing "config and plugin sources count"
    (is (daemon/relevant-change? "config/opencode/root.edn"))
    (is (daemon/relevant-change? "config/opencode/plugins/actors.edn"))
    (is (daemon/relevant-change? "plugins/actors.cljc"))
    (is (daemon/relevant-change? "plugins/websearch.cljs")))
  (testing "state churn does not"
    (is (not (daemon/relevant-change? "Π_STATE.sexp")))
    (is (not (daemon/relevant-change? "Π_LAST.md")))
    (is (not (daemon/relevant-change? "state/daemon/log.jsonl")))
    (is (not (daemon/relevant-change? "config/opencode/root.edn~")))
    (is (not (daemon/relevant-change? nil)))))

(deftest expand-home-expands
  (is (= "/home/err/.config/x" (daemon/expand-home "/home/err" "~/.config/x")))
  (is (= "/home/err" (daemon/expand-home "/home/err" "~")))
  (is (= "/abs/path" (daemon/expand-home "/home/err" "/abs/path"))))

(deftest repo-root-is-parent
  (is (= "/home/err/spaces/muse"
         (daemon/repo-root "/home/err/spaces/muse/.ημ"))))

(deftest plan-actions-from-root-edn
  (testing "emit renders to the expanded path"
    (is (= [{:action :render
             :out "/home/err/.config/opencode/opencode.jsonc"
             :source "/home/err/.ημ"}]
           (daemon/plan-actions
            {:home "/home/err"
             :eta-mu-dir "/home/err/.ημ"
             :root {:emit {:path "~/.config/opencode/opencode.jsonc"}}}))))
  (testing "build execs in the repo root"
    (is (= [{:action :exec
             :command ["shadow-cljs" "release" "opencode-plugin"]
             :cwd "/home/err/spaces/muse"}]
           (daemon/plan-actions
            {:home "/home/err"
             :eta-mu-dir "/home/err/spaces/muse/.ημ"
             :root {:build ["shadow-cljs" "release" "opencode-plugin"]}}))))
  (testing "no opencode config → no actions"
    (is (= [] (daemon/plan-actions
               {:home "/home/err" :eta-mu-dir "/x/.ημ" :root nil})))))
