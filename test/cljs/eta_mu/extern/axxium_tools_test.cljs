;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.extern.axxium-tools-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [async deftest is]]
            [eta-mu.extern.axxium-tools :as tools]))

(def ^:private origin "https://axxium.test")
(def ^:private actor "did:web:axxium.test:actors:fixture")
(def ^:private fixture-key (str "axb_" (.repeat "0" 43)))

(defn- configure-fixture! []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "muse-axxium-"))
        file (path/join dir "bootstrap")
        env (.-env js/process)
        config {"AXXIUM_ORIGIN" origin "AXXIUM_ACTOR_DID" actor
                "AXXIUM_MAILBOX_DID" actor "AXXIUM_BOOTSTRAP_FILE" file}
        old-env (into {} (map (fn [key] [key (aget env key)]) (keys config)))]
    (fs/writeFileSync file fixture-key #js {:mode 384})
    (doseq [[key value] config] (aset env key value))
    {:file file :env env
     :cleanup! (fn []
                 (doseq [[key value] old-env]
                   (if (nil? value) (js-delete env key) (aset env key value)))
                 (fs/rmSync dir #js {:recursive true :force true}))}))

(deftest actor-connection-validates-dids-and-owner-only-bootstrap-file
  (let [{:keys [file env cleanup!]} (configure-fixture!)
        getuid (.-getuid js/process)
        invalid-key #"valid key in an owner-owned mode 0600 file"]
    (try
      (is (= {:origin origin :actor-did actor :mailbox-did actor
              :authorization (str "Bearer " fixture-key)}
             (tools/connection!)))
      (aset env "AXXIUM_ACTOR_DID" "did:web:other.test:actors:fixture")
      (is (thrown-with-msg? js/Error #"AXXIUM_ACTOR_DID" (tools/connection!)))
      (aset env "AXXIUM_ACTOR_DID" actor)
      (aset env "AXXIUM_MAILBOX_DID" "did:PLC:fixture")
      (is (thrown-with-msg? js/Error #"AXXIUM_MAILBOX_DID" (tools/connection!)))
      (aset env "AXXIUM_MAILBOX_DID" actor)
      (fs/chmodSync file 420)
      (is (thrown-with-msg? js/Error invalid-key (tools/connection!)))
      (fs/chmodSync file 384)
      (try
        (set! (.-getuid js/process) (constantly (inc (getuid))))
        (is (thrown-with-msg? js/Error invalid-key (tools/connection!)))
        (finally (set! (.-getuid js/process) getuid)))
      (let [link (str file "-link")]
        (fs/symlinkSync file link)
        (aset env "AXXIUM_BOOTSTRAP_FILE" link)
        (is (thrown-with-msg? js/Error invalid-key (tools/connection!))))
      (aset env "AXXIUM_BOOTSTRAP_FILE" file)
      (fs/writeFileSync file "axb_short")
      (is (thrown-with-msg? js/Error invalid-key (tools/connection!)))
      (finally (cleanup!)))))

(defn- response [status edn]
  (js/Response.
   (js/JSON.stringify #js {:edn edn})
   #js {:status status :headers #js {"content-type" "application/json"}}))

(defn- response-with-diagnostic [edn diagnostic]
  (js/Response.
   (js/JSON.stringify #js {:edn edn :github (clj->js diagnostic)})
   #js {:status 400 :headers #js {"content-type" "application/json"}}))

(defn- ^:async get-error! [call!]
  (try
    (await (call!))
    nil
    (catch :default error error)))

(defn- ^:async verify-actor-rpc! []
  (let [{:keys [cleanup!]} (configure-fixture!)
        original-fetch js/fetch
        original-timeout (.-timeout js/AbortSignal)
        reply (atom #(response 200 "{:result :fixture}"))
        calls (atom [])
        request-id "fixture-request-id"
        read! #(tools/drive-read! "drive_list" nil)
        drive! #(tools/drive-put! {:file_name "fixture.edn" :content_json "{\"value\":1}"
                                  :request_id request-id})
        github! #(tools/github-comment! {:repository "fixture/repo" :number 1
                                         :body "fixture comment" :request_id request-id})]
    (try
      (set! js/globalThis.fetch
            (fn [url options]
              (swap! calls conj [url options])
              (@reply (.-signal options))))
      (doseq [[call! route tool] [[read! "/api/drive/agent" "drive_list"]
                                  [drive! "/api/drive/agent" "drive_put"]
                                  [github! "/api/identity/agent" "github_comment"]]]
        (is (= "{:result :fixture}" (await (call!))))
        (let [[url options] (peek @calls)]
          (is (= (str origin route) url))
          (is (= actor (aget (.-headers options) "X-Axxium-Actor")))
          (is (= (str "Bearer " fixture-key) (aget (.-headers options) "Authorization")))
          (is (= "error" (.-redirect options)))
          (is (= tool (.-tool (js/JSON.parse (.-body options)))))))
      (doseq [[status edn message] [[400 "{:error :not-connected}" #"No Google Drive grant"]
                                    [400 nil #"Axxium rejected the request"]
                                    [401 "" #"refused this actor credential"]
                                    [403 "" #"no authority"]
                                    [404 "" #"has not been deployed"]
                                    [503 "" #"unavailable or disabled"]]]
        (reset! reply #(response status edn))
        (is (re-find message (.-message (await (get-error! read!))))))
      (set! (.-timeout js/AbortSignal) #(original-timeout 1))
      (doseq [[call! key outcome]
              [[drive! ":drive/idempotency-key" "reconciliation-required"]
               [github! ":github/request-id" "comment-outcome-unknown"]]
              send! [(fn [_] (throw (js/Error. "network fixture")))
                     (fn [signal]
                       (js/Promise.
                        (fn [_ reject]
                          (.addEventListener signal "abort" #(reject (.-reason signal)))
                          (js/setTimeout #(reject (js/Error. "fixture timeout did not abort")) 50))))
                     (fn [_] (response 503 ""))
                     (fn [_] (response 200 nil))
                     (fn [_] (js/Response. "truncated" #js {:status 400}))
                     (fn [_] (response 400 "unrecognized"))
                     (fn [_] (response 400 (str "{:error :" outcome "}")))]]
        (reset! reply send!)
        (let [before (count @calls)
              error (await (get-error! call!))
              [_ options] (peek @calls)
              payload (js/JSON.parse (.-body options))]
          (is (= (inc before) (count @calls)) "The adapter issues one request only")
          (is (= {:reason :write-outcome-unknown :request-id request-id} (ex-data error)))
          (is (re-find #"Reuse the same request ID" (.-message error)))
          (is (.includes (.-arguments payload) (str key " " (pr-str request-id))))))
      (reset! reply #(response 401 ""))
      (is (= {:reason :write-outcome-unknown :request-id request-id}
             (ex-data (await (get-error! drive!)))))
      (is (re-find #"refused this actor credential" (.-message (await (get-error! github!)))))
      (doseq [[call! code] [[drive! "idempotency-conflict"] [github! "comment-request-conflict"]]]
        (reset! reply #(response 400 (str "{:error :" code "}")))
        (is (re-find #"used for different content" (.-message (await (get-error! call!))))))
      (finally
        (set! (.-timeout js/AbortSignal) original-timeout)
        (set! js/globalThis.fetch original-fetch)
        (cleanup!)))))

(deftest actor-rpc-qualifies-read-errors-and-uncertain-writes
  (async done
    (-> (verify-actor-rpc!)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(defn- ^:async verify-github-diagnostics! []
  (let [{:keys [cleanup!]} (configure-fixture!)
        original-fetch js/fetch
        request-id "c1111111-1111-4111-8111-111111111111"
        tool! #(tools/github-comment! {:repository "fixture/repo" :number 1
                                        :body "fixture comment" :request_id request-id})
        diagnostic {"http_status" 403 "request_id" "a3a:20:1B"
                    "target_kind" "pull-request" "phase" "comment-post"}
        invalid {"http_status" 2000 "request_id" "unexpected value\nprovider response"
                 "target_kind" "unknown" "phase" "unknown" "message" "untrusted message"}
        expected {:http-status 403 :provider-request-id "A3A:20:1B"
                  :target-kind "pull-request" :phase "comment-post"}
        calls (atom [])
        reply (atom nil)]
    (try
      (set! js/globalThis.fetch
            (fn [_ options]
              (swap! calls conj (.-arguments (js/JSON.parse (.-body options))))
              @reply))
      (doseq [[code incoming reason visible]
              [["comment-provider-rejected" diagnostic :comment-provider-rejected expected]
               ["comment-outcome-unknown" diagnostic :write-outcome-unknown expected]
               ["comment-provider-rejected" nil :comment-provider-rejected nil]
               ["comment-outcome-unknown" invalid :write-outcome-unknown nil]
               ["comment-outcome-unknown" {"http_status" 502} :write-outcome-unknown {:http-status 502}]]]
        (let [before (count @calls)]
          (reset! reply (response-with-diagnostic (str "{:error :" code "}") incoming))
          (let [error (await (get-error! tool!))
                message (.-message error)
                unknown? (= :write-outcome-unknown reason)]
            (is (= (inc before) (count @calls)))
            (is (.includes (peek @calls) request-id))
            (is (= (cond-> {:reason reason}
                     unknown? (assoc :request-id request-id)
                     visible (assoc :github visible))
                   (ex-data error)))
            (is (re-find (if unknown? #"Reuse the same request ID" #"GitHub rejected the comment") message))
            (is (= (boolean (:http-status visible)) (.includes message "GitHub HTTP")))
            (is (= (boolean (:provider-request-id visible)) (.includes message "GitHub request ID A3A:20:1B")))
            (is (not (.includes message "untrusted message")))
            (is (not (.includes message "unexpected value"))))))
      (let [edn "{:result {:github/target-kind :issue, :github/http-status 201, :github/provider-request-id \"AB:CD:EF\"}}"]
        (reset! reply (response 200 edn))
        (is (= edn (await (tool!)))))
      (finally
        (set! js/globalThis.fetch original-fetch)
        (cleanup!)))))

(deftest github-comment-reports-only-validated-provider-diagnostics
  (async done
    (-> (verify-github-diagnostics!)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
