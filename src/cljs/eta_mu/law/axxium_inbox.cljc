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
