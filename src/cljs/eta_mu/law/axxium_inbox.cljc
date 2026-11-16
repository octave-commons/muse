;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.law.axxium-inbox
  "The narrow MCP contract for wake hints from a bound Axxium inbox."
  (:require [malli.core :as m]))

(def protocol-version "2025-06-18")
(def inbox-uri "axxium://mailbox/inbox")

(def ^:private did
  [:and :string [:re #"^did:[a-z0-9]+:[A-Za-z0-9._:%-]{1,400}$"]])

(defn valid-did? [value]
  (m/validate did value))

(def connection-options
  [:map
   [:endpoint :string]
   [:actor-did did]
   [:mailbox-did did]
   [:authorization [:and :string [:re #"^Bearer axb_[A-Za-z0-9_-]{1,256}$"]]]])

(def start-options
  (conj connection-options [:on-wake! fn?] [:on-close! {:optional true} fn?]))

(def ^:private message-id
  [:and :string [:re #"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"]])

(def wait-pattern
  [:map {:closed true}
   [:sender did]
   [:kind [:string {:min 1 :max 128}]]
   [:run [:string {:min 1 :max 128}]]
   [:reply_to {:optional true} message-id]
   [:timeout_ms [:int {:min 1 :max 30000}]]])

(defn valid-wait?
  "Validate a bounded, exact match request before opening a mailbox session."
  [pattern]
  (m/validate wait-pattern pattern))

(defn inbox-messages?
  "Require unique, usable message IDs before establishing or comparing a baseline."
  [messages]
  (and (vector? messages)
       (every? #(and (map? %) (m/validate message-id (get % "message_id"))) messages)
       (= (count messages) (count (into #{} (map #(get % "message_id")) messages)))))

(defn message-matches?
  "Check exact actor and correlation fields on a native inbox envelope."
  [mailbox-did {:keys [sender kind run reply_to]} message]
  (let [body (get message "message_body")]
    (and (= sender (get message "message_from"))
         (= mailbox-did (get message "message_to"))
         (map? body)
         (= kind (get body "kind"))
         (= run (get body "run"))
         (or (nil? reply_to) (= reply_to (get body "reply_to"))))))

(defn valid-connection?
  "Validate the configured identities and bootstrap authorization."
  [options]
  (m/validate connection-options options))

(defn valid-start?
  "Validate a connection's wake and optional close callbacks."
  [options]
  (m/validate start-options options))

(defn bound-inbox?
  "Check the verified session projection for the expected mailbox and resource."
  [endpoint mailbox-did response]
  (and (= mailbox-did (get response "did"))
       (= endpoint (get response "aud"))
       (= "actor-node" (get response "method"))))

(defn inbox-update?
  "Admit only an exact id-less update for the subscribed resource."
  [notice]
  (= {"jsonrpc" "2.0"
      "method" "notifications/resources/updated"
      "params" {"uri" inbox-uri}}
     notice))
