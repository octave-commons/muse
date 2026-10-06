(ns eta-mu.boundaries.claude-test
  "Invoke generated shell wrappers against real Node processes and literal path arguments."
  (:require ["node:child_process" :as process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :as test]
            [eta-mu.boundaries.claude :as claude]))

(test/deftest wrapper-preserves-spaces-apostrophes-and-shell-syntax
  (let [directory (fs/mkdtempSync (path/join (os/tmpdir) "muse-hook-"))
        entry (path/join directory "adapter ' literal $(touch expanded) `touch expanded-too`.cjs")
        script (path/join directory "generated.sh")
        event "tool/requested"
        input "{\"tool_name\":\"Read\"}"]
    (try
      (fs/writeFileSync entry "const fs=require('node:fs');process.stdout.write(JSON.stringify({args:process.argv.slice(1),input:fs.readFileSync(0,'utf8')}));")
      (fs/writeFileSync script (claude/hook-script-source entry event))
      (let [^js result (process/spawnSync "bash" #js [script] #js {:cwd directory :input input :encoding "utf8" :timeout 10000})]
        (test/is (= 0 (.-status result)) (str (.-stderr result)))
        (let [received (js->clj (js/JSON.parse (.-stdout result)) :keywordize-keys true)]
          (test/is (= [entry "--hook" event] (:args received)))
          (test/is (= input (:input received))))
        (test/is (not (fs/existsSync (path/join directory "expanded"))))
        (test/is (not (fs/existsSync (path/join directory "expanded-too")))))
      (finally (fs/rmSync directory #js {:recursive true :force true})))))
