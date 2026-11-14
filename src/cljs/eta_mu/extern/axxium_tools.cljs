;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.extern.axxium-tools
  "Stdio and plugin adapters read one configured actor's local bootstrap key.
   Only the pinned Axxium origin receives the credential."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [eta-mu.law.axxium-inbox :as law]))

(defn- fail! [message]
  (throw (ex-info message {})))

(defn- read-key! [filename]
  (when-not (and (string? filename) (path/isAbsolute filename))
    (fail! "Set AXXIUM_BOOTSTRAP_FILE to an absolute owner-only key path."))
  (try
    (let [flags (bit-or (.-O_RDONLY fs/constants) (.-O_NOFOLLOW fs/constants)
                        (.-O_NONBLOCK fs/constants))
          fd (fs/openSync filename flags)]
      (try
        (let [info (fs/fstatSync fd)]
          (when-not (and (.isFile info) (= (.-uid info) (.getuid js/process))
                         (= 384 (bit-and (.-mode info) 511))
                         (<= 36 (.-size info) 8192))
            (fail! "Invalid private key file."))
          (let [key (str/trim (fs/readFileSync fd "utf8"))]
            (when-not (re-matches #"axb_[A-Za-z0-9_-]{43}" key)
              (fail! "Invalid bootstrap key."))
            key))
        (finally (fs/closeSync fd))))
    (catch :default _
      (fail! "Axxium bootstrap must be a valid key in an owner-owned mode 0600 file."))))

(defn connection!
  "Return the configured HTTPS origin, required actor and mailbox identities,
   and in-memory authorization. Never return the map from an agent tool."
  []
  (let [env (.-env js/process)
        origin (aget env "AXXIUM_ORIGIN")
        actor-did (aget env "AXXIUM_ACTOR_DID")
        mailbox-did (or (aget env "AXXIUM_MAILBOX_DID") actor-did)]
    (when-not (and (string? origin) (re-matches #"https://[A-Za-z0-9.-]+(?::[0-9]+)?" origin)
                   (law/valid-did? actor-did)
                   (str/starts-with? actor-did
                                     (str "did:web:" (str/replace (subs origin 8) ":" "%3A")
                                          ":actors:")))
      (fail! "Set AXXIUM_ORIGIN and AXXIUM_ACTOR_DID to the authorized deployment and actor."))
    (when-not (law/valid-did? mailbox-did)
      (fail! "Set AXXIUM_MAILBOX_DID to a valid mailbox DID."))
    {:origin origin :actor-did actor-did :mailbox-did mailbox-did
     :authorization (str "Bearer " (read-key! (aget env "AXXIUM_BOOTSTRAP_FILE")))}))

(defn message-body!
  "Decode the JSON object without losing slash-qualified or overlapping keys."
  [raw]
  (try
    (let [body (js->clj (js/JSON.parse raw))]
      (if (map? body) body (fail! "Axxium message body must be a JSON object.")))
    (catch :default _
      (fail! "Axxium message body must be a JSON object."))))

(defn- github-diagnostic [payload]
  (when-let [data (some-> payload .-github)]
    (let [status (.-http_status data)
          provider-id (.-request_id data)
          kind (.-target_kind data)
          phase (.-phase data)]
      (not-empty
       (cond-> {}
         (and (integer? status) (<= 100 status 599))
         (assoc :http-status status)
         (and (string? provider-id)
              (re-matches #"[A-Fa-f0-9]{1,16}(?::[A-Fa-f0-9]{1,16}){2,7}" provider-id))
         (assoc :provider-request-id (str/upper-case provider-id))
         (#{"issue" "pull-request"} kind) (assoc :target-kind kind)
         (= "comment-post" phase) (assoc :phase phase))))))

(defn- diagnostic-suffix [{:keys [http-status provider-request-id]}]
  (str (when http-status (str " GitHub HTTP " http-status "."))
       (when provider-request-id (str " GitHub request ID " provider-request-id "."))))

(defn- unknown-write!
  ([request-id] (unknown-write! request-id nil))
  ([request-id diagnostic]
   (throw (ex-info (str "Axxium write outcome is unknown. Reuse the same request ID to reconcile the result."
                        (diagnostic-suffix diagnostic))
                   (cond-> {:reason :write-outcome-unknown :request-id request-id}
                     diagnostic (assoc :github diagnostic))))))

(defn- ^:async response-payload! [response request-id]
  (try
    (await (.json response))
    (catch :default error
      (if request-id (unknown-write! request-id) (throw error)))))

(defn- ^:async actor-rpc!
  [{:keys [origin actor-did authorization]} route tool args timeout request-id]
  (let [options #js {:method "POST" :redirect "error"
                     :signal (js/AbortSignal.timeout timeout)
                     :headers #js {"Content-Type" "application/json"
                                   "Authorization" authorization
                                   "X-Axxium-Actor" actor-did}
                     :body (js/JSON.stringify #js {:tool tool :arguments (pr-str args)})}
        response (try
                   (await (js/fetch (str origin route) options))
                   (catch :default error
                     (if request-id (unknown-write! request-id) (throw error))))
        status (.-status response)]
    ;; Drive can recheck actor authorization after a provider upload.
    (when (and request-id
               (or (<= 500 status 599)
                   (and (= 401 status) (= "drive_put" tool))))
      (unknown-write! request-id))
    (case status
      200 (let [edn (some-> (await (response-payload! response request-id)) .-edn)]
            (if (string? edn)
              edn
              (if request-id (unknown-write! request-id) (fail! "Invalid Axxium response."))))
      400 (let [payload (await (response-payload! response request-id))
                edn (some-> payload .-edn)
                code (when (string? edn) (second (re-matches #"\{:error :([a-z-]+)\}" edn)))
                diagnostic (when (= "github_comment" tool) (github-diagnostic payload))]
            (when (and request-id (or (nil? code)
                                      (#{"comment-outcome-unknown" "reconciliation-required"} code)))
              (unknown-write! request-id diagnostic))
            (when (and (= "github_comment" tool) (= "comment-provider-rejected" code))
              (throw (ex-info (str "GitHub rejected the comment." (diagnostic-suffix diagnostic))
                              (cond-> {:reason :comment-provider-rejected}
                                diagnostic (assoc :github diagnostic)))))
            (fail! (case code
                     "not-connected" "No Google Drive grant is connected for this actor."
                     "not-found" "The resource is unavailable to this actor."
                     "forbidden" "This Axxium actor cannot access the resource."
                     "exists" "A Drive resource with this name already exists."
                     "exists-untracked" "Drive has a same-name file without an Axxium record."
                     "idempotency-conflict" "This request ID was used for different content."
                     "comment-request-conflict" "This request ID was used for different content."
                     "unknown-tool" "This Axxium operation has not been deployed."
                     "Axxium rejected the request.")))
      401 (fail! "Axxium refused this actor credential.")
      403 (fail! "This Axxium actor has no authority for the request.")
      404 (fail! "The Axxium actor route has not been deployed.")
      503 (fail! "The Axxium actor route is unavailable or disabled.")
      (fail! "The Axxium request did not complete."))))

(defn drive-read!
  "Read the actor's registered Drive resources without converting EDN values."
  [tool argument]
  (let [args (case tool
               "drive_list" (cond-> {} argument (assoc :drive/ref argument))
               "drive_get" {:resource/id argument}
               (fail! "Unsupported Axxium Drive read."))]
    (actor-rpc! (connection!) "/api/drive/agent" tool args 20000 nil)))

(defn drive-put!
  "Create an actor-owned file using the candidate create-only contract."
  [{:keys [file_name content_json request_id event_refs]}]
  (let [value (try
                (js->clj (js/JSON.parse content_json))
                (catch :default _ (fail! "Drive content must be valid JSON.")))
        args (cond-> {:drive/name file_name :drive/value value
                      :drive/idempotency-key request_id}
               (seq event_refs) (assoc :drive/refs event_refs))]
    (actor-rpc! (connection!) "/api/drive/agent" "drive_put" args 60000 request_id)))

(defn github-comment!
  "Post an actor-attributed GitHub conversation comment without returning a token."
  [{:keys [repository number body request_id]}]
  (let [{:keys [actor-did] :as connection} (connection!)]
    (actor-rpc! connection "/api/identity/agent" "github_comment"
                {:actor/did actor-did :credential/label "github-app"
                 :github/repo repository :github/number number :github/body body
                 :github/request-id request_id}
                45000 request_id)))
