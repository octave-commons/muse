(ns eta-mu.boundaries.claude-test
  "Invoke generated shell wrappers against real Node processes and literal path arguments."
  (:require ["@modelcontextprotocol/sdk/client/index.js" :refer [Client]]
            ["@modelcontextprotocol/sdk/inMemory.js" :refer [InMemoryTransport]]
            ["node:child_process" :as process]
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


(defn- ^:async verify-inbox-channel! []
  (let [adapter {:tools [] :inits []}
        server (claude/render-server adapter {:name "test" :version "1"} true)
        client (Client. #js {:name "fixture" :version "1"})
        transports (.createLinkedPair InMemoryTransport)
        received (atom [])
        subscription (atom nil)
        stop! (claude/attach-inbox-channel!
               server
               (fn [signal on-wake!]
                 (reset! subscription {:signal signal :on-wake! on-wake!})))
        notice-handler (fn [message] (swap! received conj (js->clj message)))]
    (test/is (nil? @subscription))
    ;; Intercept only at the in-memory wire, before the generic client discards
    ;; an unknown notification. Claude's vendor-specific client is not involved.
    (set! (.-onmessage (aget transports 0)) notice-handler)
    (try
      (await (.connect server (aget transports 1)))
      (await (.connect client (aget transports 0)))
      (test/is (some? (aget (.-experimental (.getServerCapabilities client)) "claude/channel")))
      (test/is (nil? (aget (.-experimental (.getServerCapabilities client)) "claude/channel/permission")))
      (let [{:keys [signal on-wake!]} @subscription
            hint {:kind :axxium.inbox/updated :actor-did "did:web:fixture:actors:claude"
                  :mailbox-did "did:plc:fixture" :body "untrusted"}]
        (test/is (fn? on-wake!))
        (await (on-wake! hint))
        (let [notices (filter #(= "notifications/claude/channel" (get % "method")) @received)]
          (test/is (= [{"jsonrpc" "2.0"
                        "method" "notifications/claude/channel"
                        "params" {"content" "Axxium inbox updated."
                                  "meta" {"actor_did" "did:web:fixture:actors:claude"
                                          "mailbox_did" "did:plc:fixture"}}}]
                      (vec notices))))
        (stop!)
        (test/is (true? (.-aborted signal)))
        (let [count-before (count @received)]
          (await (on-wake! hint))
          (test/is (= count-before (count @received)))))
      (finally
        (stop!)
        (await (.close client))
        (await (.close server))))))

(test/deftest opted-in-channel-notifies-after-mcp-initialization
  (test/async done
    (letfn [(^:async run! []
              (try
                (await (verify-inbox-channel!))
                (catch :default error (test/is false (str error)))
                (finally (done))))]
      (run!))))
