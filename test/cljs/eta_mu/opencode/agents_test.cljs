(ns eta-mu.opencode.agents-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eta-mu.opencode.settings :as settings]))

(defn- parse [v]
  (js->clj (js/JSON.parse (settings/render-value v))))

(deftest docs-full-json-example
  (is (= {"$schema" "https://opencode.ai/config.json"
          "agent"
          {"build" {"mode" "primary"
                    "model" "anthropic/claude-sonnet-4-20250514"
                    "prompt" "{file:./prompts/build.txt}"
                    "permission" {"edit" "allow" "bash" "allow"}}
           "plan" {"mode" "primary"
                   "model" "anthropic/claude-haiku-4-20250514"
                   "permission" {"edit" "deny" "bash" "deny"}}
           "code-reviewer" {"description" "Reviews code for best practices and potential issues"
                            "mode" "subagent"
                            "model" "anthropic/claude-sonnet-4-20250514"
                            "prompt" "You are a code reviewer. Focus on security, performance, and maintainability."
                            "permission" {"edit" "deny"}}}}
         (parse {:$schema "https://opencode.ai/config.json"
                 :agent {:build {:mode :primary
                                 :model "anthropic/claude-sonnet-4-20250514"
                                 :prompt "{file:./prompts/build.txt}"
                                 :permission {:edit :allow :bash :allow}}
                         :plan {:mode :primary
                                :model "anthropic/claude-haiku-4-20250514"
                                :permission {:edit :deny :bash :deny}}
                         :code-reviewer {:description "Reviews code for best practices and potential issues"
                                         :mode :subagent
                                         :model "anthropic/claude-sonnet-4-20250514"
                                         :prompt "You are a code reviewer. Focus on security, performance, and maintainability."
                                         :permission {:edit :deny}}}}))))

(deftest docs-description
  (is (= {"agent" {"review" {"description" "Reviews code for best practices and potential issues"}}}
         (parse {:agent {:review {:description "Reviews code for best practices and potential issues"}}}))))

(deftest docs-temperature
  (is (= {"agent" {"plan" {"temperature" 0.1}
                   "creative" {"temperature" 0.8}}}
         (parse {:agent {:plan {:temperature 0.1}
                         :creative {:temperature 0.8}}})))
  (is (= {"agent" {"analyze" {"temperature" 0.1
                              "prompt" "{file:./prompts/analysis.txt}"}
                   "build" {"temperature" 0.3}
                   "brainstorm" {"temperature" 0.7
                                 "prompt" "{file:./prompts/creative.txt}"}}}
         (parse {:agent {:analyze {:temperature 0.1
                                   :prompt "{file:./prompts/analysis.txt}"}
                         :build {:temperature 0.3}
                         :brainstorm {:temperature 0.7
                                      :prompt "{file:./prompts/creative.txt}"}}}))))

(deftest docs-max-steps
  (is (= {"agent" {"quick-thinker" {"description" "Fast reasoning with limited iterations"
                                    "prompt" "You are a quick thinker. Solve problems with minimal steps."
                                    "steps" 5}}}
         (parse {:agent {:quick-thinker {:description "Fast reasoning with limited iterations"
                                         :prompt "You are a quick thinker. Solve problems with minimal steps."
                                         :steps 5}}}))))

(deftest docs-disable
  (is (= {"agent" {"review" {"disable" true}}}
         (parse {:agent {:review {:disable true}}}))))

(deftest docs-prompt-file-reference
  (is (= {"agent" {"review" {"prompt" "{file:./prompts/code-review.txt}"}}}
         (parse {:agent {:review {:prompt "{file:./prompts/code-review.txt}"}}}))))

(deftest docs-model-override
  (is (= {"agent" {"plan" {"model" "anthropic/claude-haiku-4-20250514"}}}
         (parse {:agent {:plan {:model "anthropic/claude-haiku-4-20250514"}}}))))

(deftest docs-deprecated-tools
  (is (= {"tools" {"write" true "bash" true}
          "agent" {"plan" {"tools" {"write" false "bash" false}}}}
         (parse {:tools {:write true :bash true}
                 :agent {:plan {:tools {:write false :bash false}}}})))
  (testing "wildcards"
    (is (= {"agent" {"readonly" {"tools" {"mymcp_*" false "write" false "edit" false}}}}
           (parse {:agent {:readonly {:tools {:mymcp_* false :write false :edit false}}}})))))

(deftest docs-permission-keys
  (is (= {"permission" {"read" "allow"
                        "edit" "ask"
                        "glob" "allow"
                        "grep" "allow"
                        "list" "allow"
                        "bash" "deny"
                        "task" "allow"
                        "external_directory" "deny"
                        "todowrite" "allow"
                        "webfetch" "deny"
                        "websearch" "deny"
                        "lsp" "allow"
                        "skill" "allow"
                        "question" "ask"
                        "doom_loop" "allow"}}
         (parse {:permission {:read :allow
                              :edit :ask
                              :glob :allow
                              :grep :allow
                              :list :allow
                              :bash :deny
                              :task :allow
                              :external_directory :deny
                              :todowrite :allow
                              :webfetch :deny
                              :websearch :deny
                              :lsp :allow
                              :skill :allow
                              :question :ask
                              :doom_loop :allow}}))))

(deftest docs-per-agent-permission-override
  (is (= {"permission" {"edit" "deny"}
          "agent" {"build" {"permission" {"edit" "ask"}}}}
         (parse {:permission {:edit :deny}
                 :agent {:build {:permission {:edit :ask}}}}))))

(deftest docs-bash-command-permissions
  (testing "specific commands"
    (is (= {"agent" {"build" {"permission" {"bash" {"git push" "ask"
                                                    "grep *" "allow"}}}}}
           (parse {:agent {:build {:permission {:bash {"git push" :ask
                                                       "grep *" :allow}}}}}))))
  (testing "glob pattern"
    (is (= {"agent" {"build" {"permission" {"bash" {"git *" "ask"}}}}}
           (parse {:agent {:build {:permission {:bash {"git *" :ask}}}}}))))
  (testing "* wildcard first, specific rules after"
    (let [doc (settings/render-value {:agent {:build {:permission {:bash {:* :ask
                                                                          "git status *" :allow}}}}})
          parsed (js->clj (js/JSON.parse doc))]
      (is (= {"agent" {"build" {"permission" {"bash" {"*" "ask"
                                                      "git status *" "allow"}}}}}
             parsed))
      (is (< (.indexOf doc "\"*\"") (.indexOf doc "\"git status *\""))
          "wildcard rule renders before the specific rule"))))

(deftest docs-mode
  (is (= {"agent" {"review" {"mode" "subagent"}}}
         (parse {:agent {:review {:mode :subagent}}})))
  (is (= #{"primary" "subagent" "all"}
         (set (map #(get-in (js->clj (js/JSON.parse (settings/render-value {:agent {:r {:mode %}}})))
                            ["agent" "r" "mode"])
                   [:primary :subagent :all])))))

(deftest docs-hidden
  (is (= {"agent" {"internal-helper" {"mode" "subagent" "hidden" true}}}
         (parse {:agent {:internal-helper {:mode :subagent :hidden true}}}))))

(deftest docs-task-permissions
  (is (= {"agent" {"orchestrator" {"mode" "primary"
                                   "permission" {"task" {"*" "deny"
                                                         "orchestrator-*" "allow"
                                                         "code-reviewer" "ask"}}}}}
         (parse {:agent {:orchestrator {:mode :primary
                                        :permission {:task {:* :deny
                                                            :orchestrator-* :allow
                                                            :code-reviewer :ask}}}}}))))

(deftest docs-color
  (is (= {"agent" {"creative" {"color" "#ff6b6b"}
                   "code-reviewer" {"color" "accent"}}}
         (parse {:agent {:creative {:color "#ff6b6b"}
                         :code-reviewer {:color :accent}}}))))

(deftest docs-top-p
  (is (= {"agent" {"brainstorm" {"top_p" 0.9}}}
         (parse {:agent {:brainstorm {:top_p 0.9}}}))))

(deftest docs-additional-provider-passthrough
  (is (= {"agent" {"deep-thinker" {"description" "Agent that uses high reasoning effort for complex problems"
                                   "model" "openai/gpt-5"
                                   "reasoningEffort" "high"
                                   "textVerbosity" "low"}}}
         (parse {:agent {:deep-thinker {:description "Agent that uses high reasoning effort for complex problems"
                                        :model "openai/gpt-5"
                                        :reasoningEffort "high"
                                        :textVerbosity "low"}}}))))

(deftest docs-example-agents
  (testing "documentation agent frontmatter as JSON"
    (is (= {"agent" {"docs-writer" {"description" "Writes and maintains project documentation"
                                    "mode" "subagent"
                                    "permission" {"bash" "deny"}}}}
           (parse {:agent {:docs-writer {:description "Writes and maintains project documentation"
                                         :mode :subagent
                                         :permission {:bash :deny}}}}))))
  (testing "security auditor frontmatter as JSON"
    (is (= {"agent" {"security-auditor" {"description" "Performs security audits and identifies vulnerabilities"
                                         "mode" "subagent"
                                         "permission" {"edit" "deny"}}}}
           (parse {:agent {:security-auditor {:description "Performs security audits and identifies vulnerabilities"
                                              :mode :subagent
                                              :permission {:edit :deny}}}}))))
  (testing "markdown review agent with bash glob permissions"
    (is (= {"agent" {"review" {"description" "Code review without edits"
                               "mode" "subagent"
                               "permission" {"edit" "deny"
                                             "bash" {"*" "ask"
                                                     "git diff" "allow"
                                                     "git log*" "allow"
                                                     "grep *" "allow"}
                                             "webfetch" "deny"}}}}
           (parse {:agent {:review {:description "Code review without edits"
                                    :mode :subagent
                                    :permission {:edit :deny
                                                 :bash {:* :ask
                                                        "git diff" :allow
                                                        "git log*" :allow
                                                        "grep *" :allow}
                                                 :webfetch :deny}}}})))))

(deftest layered-fragments-reconfigure-built-ins
  (testing "later fragments deep-merge into built-in agent reconfiguration"
    (is (= {"agent" {"plan" {"permission" {"edit" "deny" "bash" "deny"}
                             "temperature" 0.1
                             "model" "anthropic/claude-haiku-4-20250514"}}}
           (parse (settings/merged
                   [{:agent {:plan {:permission {:edit :deny :bash :deny}}}}
                    {:agent {:plan {:temperature 0.1}}}
                    {:agent {:plan {:model "anthropic/claude-haiku-4-20250514"}}}])))))
  (testing "later fragments override earlier scalar values"
    (is (= {"agent" {"build" {"temperature" 0.3}}}
           (parse (settings/merged
                   [{:agent {:build {:temperature 0.5}}}
                    {:agent {:build {:temperature 0.3}}}])))))
  (testing "instruction vectors accumulate across layers in import order"
    (is (= {"instructions" ["~/.config/opencode/AGENTS.md" "docs/CONVENTIONS.md"]}
           (parse (settings/merged
                   [{:instructions ["~/.config/opencode/AGENTS.md"]}
                     {:instructions ["docs/CONVENTIONS.md"]}]))))))

(deftest render-jsonc-document-with-agents-is-valid-json
  (let [doc (settings/render-jsonc
             (settings/merged [{:$schema "https://opencode.ai/config.json"
                                :agent {:plan {:mode :primary
                                               :permission {:edit :deny :bash :deny}}}}])
             {:source ".ημ/config/opencode"})
        json-part (str/join "\n" (rest (str/split-lines doc)))]
    (is (str/starts-with? doc "// Generated by the eta-mu daemon"))
    (is (= {"agent" {"plan" {"mode" "primary"
                             "permission" {"bash" "deny" "edit" "deny"}}}
            "$schema" "https://opencode.ai/config.json"}
           (js->clj (js/JSON.parse json-part))))))
