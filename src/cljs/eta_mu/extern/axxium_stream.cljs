;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.extern.axxium-stream
  "Authenticated Axxium MCP tools, payload-free wake hints and opt-in inbox waits."
  (:require [clojure.string :as str]
            [eta-mu.law.axxium-inbox :as law]))

;; Inbox responses may hold multiple messages. Axxium caps its mailbox
;; ledger at 2 MiB. Wake notices carry only a resource URI.
(def ^:private max-rpc-bytes (* 2 1024 1024))
(def ^:private max-sse-chars 65536)
(def ^:private handshake-timeout-ms 10000)

(defn- require! [condition reason]
  (when-not condition
    (throw (ex-info "Axxium inbox stream refused" {:reason reason}))))

(defn- checked-url [endpoint]
  (let [url (try (js/URL. endpoint)
                 (catch :default _ nil))]
    (require! (and url
                   (= "/api/mailbox/mcp" (.-pathname url))
                   (empty? (.-search url))
                   (empty? (.-hash url))
                   (empty? (.-username url))
                   (empty? (.-password url))
                   (or (= "https:" (.-protocol url))
                       (and (= "http:" (.-protocol url))
                            (= "127.0.0.1" (.-hostname url)))))
              :invalid-endpoint)
    url))

(defn- ^:async request! [url signal headers method session body]
  (let [options #js {:method method :redirect "error" :signal signal}]
    (set! (.-headers options)
          (clj->js (cond-> (assoc headers "accept" (if body "application/json, text/event-stream"
                                                       "text/event-stream"))
                     session (assoc "mcp-session-id" session "mcp-protocol-version" law/protocol-version)
                     body (assoc "content-type" "application/json"))))
    (when body
      (set! (.-body options) (js/JSON.stringify (clj->js body))))
    (await (js/fetch url options))))

(defn- media-type [response]
  (some-> (.get (.-headers response) "content-type") (str/split #";" 2) first str/trim str/lower-case))

(defn- text-reader [response]
  (.getReader (.pipeThrough (.-body response) (js/TextDecoderStream. "utf-8" #js {:fatal true}))))

(defn- ^:async read-json! [response]
  (require! (and (= 200 (.-status response)) (.-body response)
                 (= "application/json" (media-type response)))
            :invalid-rpc-response)
  (let [reader (.getReader (.-body response))
        decoder (js/TextDecoder. "utf-8" #js {:fatal true})]
    (try
      (loop [text "" byte-count 0]
        (let [chunk (await (.read reader))]
          (if (.-done chunk)
            (js->clj (js/JSON.parse (str text (.decode decoder))))
            (let [bytes (.-value chunk)
                  next-count (+ byte-count (.-byteLength bytes))]
              (require! (<= next-count max-rpc-bytes) :rpc-response-too-large)
              (recur (str text (.decode decoder bytes #js {:stream true})) next-count)))))
      (finally (.releaseLock reader)))))

(defn- ^:async rpc! [url signal headers session id method params]
  (let [body {"jsonrpc" "2.0" "id" id "method" method "params" params}
        response (await (request! url signal headers "POST" session body))
        decoded (await (read-json! response))]
    (require! (and (= "2.0" (get decoded "jsonrpc"))
                   (= id (get decoded "id"))
                   (map? (get decoded "result"))
                   (not (contains? decoded "error")))
              :rpc-rejected)
    {:result (get decoded "result")
     :session (.get (.-headers response) "mcp-session-id")}))

(defn- notice [frame]
  (let [data (->> (str/split-lines frame)
                  (keep (fn [line]
                          (when (str/starts-with? line "data:")
                            (str/replace-first (subs line 5) #"^ " ""))))
                  (str/join "\n"))]
    (when-not (str/blank? data)
      (try (js->clj (js/JSON.parse data))
           (catch :default _ nil)))))

(defn- ^:async deliver-frames! [text signal on-wake! hint]
  ;; Axxium emits LF-delimited SSE. Also accept CRLF, including a split separator.
  (let [frames (str/split text #"\r?\n\r?\n" -1)
        pending (peek frames)]
    (doseq [frame (pop frames)]
      (require! (<= (count frame) max-sse-chars) :stream-frame-too-large)
      (when (and (not (.-aborted signal)) (law/inbox-update? (notice frame)))
        (await (on-wake! hint))))
    ;; A delimiter split across chunks contributes up to three pending characters.
    (require! (<= (- (count pending) (count (re-find #"(?:\r?\n\r?|\r)$" pending)))
                  max-sse-chars)
              :stream-frame-too-large)
    pending))

(defn- ^:async receive! [response signal on-wake! hint]
  (let [reader (text-reader response)]
    (try
      (loop [pending ""]
        (let [chunk (await (.read reader))]
          (when-not (or (.-done chunk) (.-aborted signal))
            (recur (await (deliver-frames! (str pending (.-value chunk)) signal on-wake! hint))))))
      (finally (.releaseLock reader)))))

(defn- ^:async open-session! [url signal headers mailbox-did stream?]
  (let [{:keys [result session]}
        (await (rpc! url signal headers nil 1 "initialize"
                     {"protocolVersion" law/protocol-version
                      "clientInfo" {"name" "muse-axxium" "version" "1"}
                      "capabilities" {}}))]
    (require! (and (string? session) (re-matches #"[A-Za-z0-9_-]{1,128}" session)
                   (= law/protocol-version (get result "protocolVersion")))
              :session-unavailable)
    (when stream?
      (require! (true? (get-in result ["capabilities" "resources" "subscribe"]))
                :subscription-unavailable))
    (let [ready (await (request! url signal headers "POST" session
                                 {"jsonrpc" "2.0" "method" "notifications/initialized"}))]
      (require! (= 202 (.-status ready)) :initialization-rejected))
    (let [{:keys [result]} (await (rpc! url signal headers session 2 "tools/call"
                                      {"name" "whoami" "arguments" {}}))
          identity (get result "structuredContent")]
      (require! (and (not (get result "isError")) (law/bound-inbox? (.-href url) mailbox-did identity))
                :mailbox-binding-mismatch)
      {:session session :identity identity})))

(defn- ^:async subscription! [url signal headers session id]
  (let [response (await (request! url signal headers "GET" session nil))]
    (require! (not= 409 (.-status response)) :stream-in-use)
    (require! (and (= 200 (.-status response)) (.-body response)
                   (= "text/event-stream" (media-type response)))
              :stream-unavailable)
    (let [{:keys [result]} (await (rpc! url signal headers session id "resources/subscribe"
                                       {"uri" law/inbox-uri}))]
      (require! (empty? result) :subscription-rejected))
    response))

(def ^:private tool-names
  #{"whoami" "discover_actors" "resolve_address" "read_inbox" "acknowledge_message" "send_message"})

(defn- connect-options [signal]
  (let [controller (js/AbortController.)
        close! #(.abort controller)
        cleanup! #(when signal (.removeEventListener signal "abort" close!))]
    (when signal
      (if (.-aborted signal)
        (close!)
        (.addEventListener signal "abort" close! #js {:once true})))
    {:signal (.-signal controller) :close! close! :cleanup! cleanup!}))

(defn ^:async call!
  "Run a fixed Axxium mailbox tool once after verifying the exact bound identity.
   Returns the tool's structuredContent. Tool failures expose no remote error text."
  [{:keys [endpoint actor-did mailbox-did authorization signal] :as options} tool-name params]
  (require! (law/valid-connection? options) :invalid-options)
  (require! (and (tool-names tool-name) (map? params)) :invalid-tool)
  (let [url (checked-url endpoint)
        {local-signal :signal :keys [close! cleanup!]} (connect-options signal)
        headers {"authorization" authorization "x-axxium-actor" actor-did}
        timeout (js/setTimeout close! handshake-timeout-ms)]
    (try
      (let [{:keys [session identity]} (await (open-session! url local-signal headers mailbox-did false))]
        (if (= "whoami" tool-name)
          identity
          (let [{:keys [result]} (await (rpc! url local-signal headers session 3 "tools/call"
                                            {"name" tool-name "arguments" params}))
                data (get result "structuredContent")]
            (require! (and (not (get result "isError")) (map? data)) :tool-rejected)
            data)))
      (catch :default error
        (throw (ex-info "Axxium mailbox tool failed"
                        {:reason (or (:reason (ex-data error))
                                     (if (.-aborted local-signal) :aborted :request-failed))})))
      (finally
        (js/clearTimeout timeout)
        (close!)
        (cleanup!)))))

(defn- ^:async read-inbox! [url signal headers session id]
  (let [{:keys [result]} (await (rpc! url signal headers session id "tools/call"
                                     {"name" "read_inbox" "arguments" {}}))
        messages (get-in result ["structuredContent" "messages"])]
    (require! (and (not (get result "isError")) (law/inbox-messages? messages))
              :invalid-inbox-response)
    messages))

(defn ^:async wait-for-message!
  "Wait for one new message matching an exact sender and correlation fields.
   Take a baseline before subscribing and read once more after subscription
   to cover the gap. Then read only on a valid inbox update. The deadline
   includes setup. No mail is sent or acknowledged."
  [{:keys [endpoint actor-did mailbox-did authorization signal] :as options}
   {:keys [timeout_ms] :as pattern}]
  (require! (law/valid-connection? options) :invalid-options)
  (require! (law/valid-wait? pattern) :invalid-wait-pattern)
  (let [url (checked-url endpoint)
        {local-signal :signal :keys [close! cleanup!]} (connect-options signal)
        headers {"authorization" authorization "x-axxium-actor" actor-did}
        deadline (+ (js/performance.now) timeout_ms)
        timed-out? (volatile! false)
        matched (volatile! nil)
        expire! (fn []
                  (vreset! timed-out? true)
                  (close!))
        check-time! (fn []
                      (when (>= (js/performance.now) deadline)
                        (expire!))
                      (require! (not (.-aborted local-signal)) :aborted))
        timeout (js/setTimeout expire! timeout_ms)]
    (try
      (let [terminal
            (try
              (let [{:keys [session]} (await (open-session! url local-signal headers mailbox-did true))
                    rpc-id (volatile! 2)
                    read! (^:async fn []
                            (check-time!)
                            (let [messages (await (read-inbox! url local-signal headers session (vswap! rpc-id inc)))]
                              (check-time!)
                              messages))
                    seen (volatile! (into #{} (map #(get % "message_id")) (await (read!))))
                    response (await (subscription! url local-signal headers session (vswap! rpc-id inc)))
                    observe! (^:async fn [_]
                               (let [messages (await (read!))]
                                 (when-not (.-aborted local-signal)
                                   (let [matches (filterv
                                                  #(and (not (contains? @seen (get % "message_id")))
                                                        (law/message-matches? mailbox-did pattern %))
                                                  messages)]
                                     (vswap! seen into (map #(get % "message_id") messages))
                                     (require! (<= (count matches) 1) :ambiguous-match)
                                     (when-let [message (first matches)]
                                       (vreset! matched
                                                (select-keys message ["message_id" "message_from"
                                                                      "message_to" "message_body"]))
                                       (close!))))))]
                ;; Complete the one catch-up read before handling buffered
                ;; notices. Reads and message selection are always serial.
                (await (observe! nil))
                (when-not (.-aborted local-signal)
                  (await (receive! response local-signal observe! nil)))
                "stream-closed")
              (catch :default error
                (let [reason (:reason (ex-data error))]
                  (cond
                    (.-aborted local-signal) nil
                    (= reason :stream-in-use) "stream-in-use"
                    :else (throw (ex-info "Axxium mailbox wait failed"
                                          {:reason (or reason :request-failed)}))))))]
        (cond
          @matched {:status "matched" :message @matched}
          @timed-out? {:status "timeout"}
          (.-aborted local-signal) {:status "cancelled"}
          :else {:status terminal}))
      (finally
        (js/clearTimeout timeout)
        (close!)
        (cleanup!)))))

(defn ^:async start!
  "Start one explicitly authorized connection. Configure :endpoint, :actor-did,
   :mailbox-did, :authorization and :on-wake!. :signal may abort startup or the
   running connection and :on-close! may observe one terminal reason. Returns
   {:close! fn :done promise} after the exact inbox is subscribed. The callback
   receives only a local binding hint and runs serially. No retries or replay."
  [{:keys [endpoint actor-did mailbox-did authorization signal on-wake! on-close!] :as options}]
  (require! (law/valid-start? options) :invalid-options)
  (let [url (checked-url endpoint)
        {local-signal :signal :keys [close! cleanup!]} (connect-options signal)
        headers {"authorization" authorization "x-axxium-actor" actor-did}
        hint {:kind :axxium.inbox/updated :actor-did actor-did :mailbox-did mailbox-did}
        timeout (js/setTimeout close! handshake-timeout-ms)]
    (try
      (let [{:keys [session]} (await (open-session! url local-signal headers mailbox-did true))
            response (await (subscription! url local-signal headers session 3))
            drain! (^:async fn []
                     (let [reason (try
                                    (await (receive! response local-signal on-wake! hint))
                                    (if (.-aborted local-signal) :aborted :stream-closed)
                                    (catch :default error
                                      (if (.-aborted local-signal) :aborted
                                          (if (= :stream-frame-too-large (:reason (ex-data error)))
                                            :stream-frame-too-large
                                            :receiver-failed)))
                                    (finally
                                      (close!)
                                      (cleanup!)))]
                       (when on-close! (await (on-close! reason)))
                       reason))]
        (require! (not (.-aborted local-signal)) :aborted)
        {:close! close! :done (drain!)})
      (catch :default error
        (let [reason (or (:reason (ex-data error))
                         (if (.-aborted local-signal) :aborted :startup-failed))]
          (close!)
          (cleanup!)
          (throw (ex-info "Axxium inbox stream failed to start" {:reason reason}))))
      (finally (js/clearTimeout timeout)))))
