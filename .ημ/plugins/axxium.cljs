;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns plugins.axxium
  "Portable Axxium tools for Muse targets."
  (:require [eta-mu.dsl :refer [defplugin deftool]]
            [eta-mu.extern.axxium-stream :as stream]
            [eta-mu.extern.axxium-tools :as axxium]))

(deftool drive-list
  {:id :axxium/drive_list
   :description "List Drive resources registered to your Axxium actor, optionally for one event reference."
   :args [:map [:event_ref {:optional true} [:string {:min 1 :max 256}]]]
   :effects #{:network/read}}
  [{:keys [event_ref]} _]
  (axxium/drive-read! "drive_list" event_ref))

(deftool drive-get
  {:id :axxium/drive_get
   :description "Read the EDN value of a Drive resource registered to your Axxium actor."
   :args [:map [:resource_id [:string {:min 1 :max 256}]]]
   :effects #{:network/read}}
  [{:keys [resource_id]} _]
  (axxium/drive-read! "drive_get" resource_id))

(deftool drive-put
  {:id :axxium/drive_put
   :description "Create an actor-owned Drive file from JSON text. Keep the same request ID when recovering an uncertain outcome."
   :args [:map [:file_name :string] [:content_json :string] [:request_id :string]
               [:event_refs {:optional true} [:vector :string]]]
   :effects #{:network/write}}
  [args _]
  (axxium/drive-put! args))

(deftool github-comment
  {:id :axxium/github_comment
   :description "Post one authorized issue or pull-request conversation comment using this actor's GitHub App. Supply a unique UUID request ID."
   :args [:map [:repository :string] [:number :int] [:body :string] [:request_id :string]]
   :effects #{:network/write}}
  [args _]
  (axxium/github-comment! args))

(defn- mailbox-call! [tool args]
  (let [{:keys [origin] :as connection} (axxium/connection!)]
    (stream/call! (assoc connection :endpoint (str origin "/api/mailbox/mcp")) tool args)))

(deftool whoami
  {:id :axxium/whoami
   :description "Check the authenticated Axxium mailbox actor for this harness."
   :args [:map]
   :effects #{:network/read}}
  [_ _]
  (mailbox-call! "whoami" {}))

(deftool discover-actors
  {:id :axxium/discover_actors
   :description "Discover reachable mailbox actors under the same human owner."
   :args [:map [:query {:optional true} :string]]
   :effects #{:network/read}}
  [args _]
  (mailbox-call! "discover_actors" args))

(deftool read-inbox
  {:id :axxium/read_inbox
   :description "Read unacknowledged messages in this actor's mailbox once."
   :args [:map]
   :effects #{:network/read}}
  [_ _]
  (mailbox-call! "read_inbox" {}))

(deftool acknowledge-message
  {:id :axxium/acknowledge_message
   :description "Acknowledge a single processed Axxium message by its exact message ID."
   :args [:map [:message_id :string]]
   :effects #{:network/write}}
  [args _]
  (mailbox-call! "acknowledge_message" args))

(deftool send-message
  {:id :axxium/send_message
   :description "Send an authorized message to a discovered mailbox DID with a unique idempotency key and a JSON object encoded as text."
   :args [:map [:to :string] [:idempotency_key :string] [:body_json :string]]
   :effects #{:network/write}}
  [{:keys [to idempotency_key body_json]} _]
  (mailbox-call! "send_message" {:to to :idempotency_key idempotency_key
                                  :body (axxium/message-body! body_json)}))

(defplugin plugin {:id :eta-mu/axxium}
  whoami
  discover-actors
  read-inbox
  acknowledge-message
  send-message
  drive-list
  drive-get
  drive-put
  github-comment)
