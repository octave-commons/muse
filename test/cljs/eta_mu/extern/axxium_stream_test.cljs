;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.extern.axxium-stream-test
  (:require ["node:http" :as http]
            [cljs.test :refer [async deftest is]]
            [eta-mu.extern.axxium-stream :as stream]
            [eta-mu.law.axxium-inbox :as law]))

(def ^:private fixture-actor "did:web:axxium.test:actors:codex")
(def ^:private fixture-mailbox "did:plc:axxiummailboxtestexample")
(def ^:private fixture-auth "Bearer axb_fixture_key")
(def ^:private fixture-session "read-only-test-session")
(def ^:private fixture-message (.repeat "é" 70000))
(def ^:private expected-hint
  {:kind :axxium.inbox/updated :actor-did fixture-actor :mailbox-did fixture-mailbox})

(defn- rpc! [response request-id result]
  (.writeHead response 200 #js {"Content-Type" "application/json"
                                "Mcp-Session-Id" fixture-session})
  (.end response (js/JSON.stringify #js {:jsonrpc "2.0" :id request-id :result result})))

(defn- update-frame [resource]
  (str "data: " (js/JSON.stringify
                 #js {:jsonrpc "2.0" :method "notifications/resources/updated"
                      :params #js {:uri resource}}) "\r\n\r\n"))

(defn- handle-post! [response method body endpoint sse]
  (let [id (get body "id")]
    (case method
      "initialize"
      (rpc! response id #js {:protocolVersion law/protocol-version
                             :capabilities #js {:resources #js {:subscribe true}}})

      "notifications/initialized"
      (.end (.writeHead response 202))

      "whoami"
      (rpc! response id #js {:structuredContent
                            #js {:did fixture-mailbox :aud endpoint :method "actor-node"}})

      "read_inbox"
      (rpc! response id #js {:structuredContent #js {:messages #js [fixture-message]}})

      "resources/subscribe"
      (do
        (is (= {"uri" law/inbox-uri} (get body "params")))
        (is (some? @sse) "GET must open before the subscription")
        (rpc! response id #js {})
        (let [frame (update-frame law/inbox-uri)]
          (.write @sse (str ": heartbeat\n\n" (update-frame "axxium://mailbox/other")
                           (subs frame 0 (dec (count frame)))))
          (js/setImmediate (fn [] (.write @sse (subs frame (dec (count frame))))))))

      (do
        (is false (str "Unexpected method: " method))
        (.end (.writeHead response 400))))))

(defn- fixture-server [endpoint sse seen]
  (http/createServer
   (fn [request response]
     (let [headers (.-headers request)]
       (is (= fixture-auth (aget headers "authorization")))
       (is (= fixture-actor (aget headers "x-axxium-actor")))
       (is (= "/api/mailbox/mcp" (.-url request)))
       (if (= "GET" (.-method request))
         (do
           (swap! seen conj "GET")
           (is (= fixture-session (aget headers "mcp-session-id")))
           (is (= law/protocol-version (aget headers "mcp-protocol-version")))
           (is (= "text/event-stream" (aget headers "accept")))
           (reset! sse response)
           (.writeHead response 200 #js {"Content-Type" "text/event-stream; charset=utf-8"})
           (.write response ": connected\n\n"))
         (let [text (atom "")]
           (.setEncoding request "utf8")
           (.on request "data" #(swap! text str %))
           (.on request "end"
                (fn []
                  (let [body (js->clj (js/JSON.parse @text))
                        method (if (= "tools/call" (get body "method"))
                                 (get-in body ["params" "name"])
                                 (get body "method"))]
                    (swap! seen conj method)
                    (when-not (= "initialize" method)
                      (is (= fixture-session (aget headers "mcp-session-id")))
                      (is (= law/protocol-version (aget headers "mcp-protocol-version"))))
                    (handle-post! response method body @endpoint sse))))))))))

(defn- ^:async verify-stream! []
  (let [endpoint (atom nil)
        sse (atom nil)
        seen (atom [])
        server (fixture-server endpoint sse seen)
        controller (js/AbortController.)
        hints (atom [])
        terminal (atom [])
        deadline (js/setTimeout #(.abort controller) 5000)]
    (try
      (await (js/Promise.
              (fn [resolve reject]
                (.once server "error" reject)
                (.listen server 0 "127.0.0.1" resolve))))
      (reset! endpoint (str "http://127.0.0.1:" (.-port (.address server)) "/api/mailbox/mcp"))
      (let [opts {:endpoint @endpoint :actor-did fixture-actor :mailbox-did fixture-mailbox
                  :authorization fixture-auth :signal (.-signal controller)}
            denied (try
                     (await (stream/start! (assoc opts :mailbox-did "did:plc:other" :on-wake! identity)))
                     :unexpected-success
                     (catch :default error (:reason (ex-data error))))]
        (is (= :mailbox-binding-mismatch denied))
        (is (= ["initialize" "notifications/initialized" "whoami"] @seen))
        (is (nil? @sse))
        (reset! seen [])
        (is (= {"messages" [fixture-message]}
               (await (stream/call! opts "read_inbox" {}))))
        (is (= ["initialize" "notifications/initialized" "whoami" "read_inbox"] @seen))
        (reset! seen [])
        (let [{:keys [done close!]}
              (await (stream/start!
                      (assoc opts :on-wake! (fn [hint]
                                              (swap! hints conj hint)
                                              (.abort controller))
                                  :on-close! #(swap! terminal conj %))))]
          (is (= :aborted (await done)))
          (close!)
          (is (= [expected-hint] @hints))
          (is (= [:aborted] @terminal))
          (is (= ["initialize" "notifications/initialized" "whoami" "GET" "resources/subscribe"] @seen))))
      (finally
        (js/clearTimeout deadline)
        (.abort controller)
        (.closeAllConnections server)
        (.close server)))))

(deftest verified-inbox-emits-only-a-pinned-wake-hint
  (async done
    (-> (verify-stream!)
        (.catch (fn [error] (is false (str error " " (pr-str (ex-data error))))))
        (.finally done))))
