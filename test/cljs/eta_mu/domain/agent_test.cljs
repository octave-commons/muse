(ns eta-mu.domain.agent-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.domain.agent :as dagent]))

(deftest run-cmd-test
  (testing "bare prompt"
    (is (= ["opencode" "run" "do the thing"]
           (dagent/run-cmd "do the thing" {}))))
  (testing "flags precede the prompt"
    (is (= ["opencode" "run" "--agent" "plan" "--model" "anthropic/claude-fable-5"
            "--session" "ses_x" "review this"]
           (dagent/run-cmd "review this" {:agent   "plan"
                                          :model   "anthropic/claude-fable-5"
                                          :session "ses_x"}))))
  (testing "continue flag"
    (is (= ["opencode" "run" "--continue" "and then?"]
           (dagent/run-cmd "and then?" {:continue true})))))

(deftest agent-slug-test
  (is (= "plan" (dagent/agent-slug {:agent "plan"})))
  (is (= "agent" (dagent/agent-slug {}))))
