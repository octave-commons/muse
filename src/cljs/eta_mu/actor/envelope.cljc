(ns eta-mu.actor.envelope
  "The envelope: the one record shape every actor ledger speaks,
   mirroring @open-hax/event-ledger's Malli schema so EDN-file
   ledgers, Mongo ledgers, and epiphany-side consumers stay compatible.

   Also owns the wire format for file ledgers: one single-line EDN map
   per event, append-only, receipts.edn style."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(defn now-iso []
  #?(:clj  (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn new-id []
  (str (random-uuid)))

;; ---------------------------------------------------------------------------
;; Defaults (same fields event-ledger's append pipeline fills)
;; ---------------------------------------------------------------------------

(defn- with-default [envelope k f]
  (if (get envelope k) envelope (assoc envelope k (f))))

(defn fill-defaults
  "Fill event/id, event/time, causal/root, session/id, delivery/mode."
  [envelope]
  (-> envelope
      (with-default :event/id new-id)
      (with-default :event/time now-iso)
      (with-default :causal/root new-id)
      (with-default :session/id new-id)
      (with-default :delivery/mode (constantly "tell"))))

(defn stamp-route
  "Stamp sender and recipient actor descriptors onto an envelope."
  [envelope from-id from-kind to-id to-kind]
  (assoc envelope
         :event/from {:actor-id (name from-id)
                      :actor-kind (or from-kind "actor")}
         :event/to   {:actor-id (name to-id)
                      :actor-kind (or to-kind "actor")}))

;; ---------------------------------------------------------------------------
;; File-ledger wire format
;; ---------------------------------------------------------------------------

(defn ->line
  "Serialize an envelope to a single-line EDN string."
  [envelope]
  (str/replace (pr-str envelope) #"\n" " "))

(defn parse-line
  "Parse one ledger line; nil when blank or unreadable."
  [line]
  (when-not (str/blank? line)
    (try
      #?(:clj  (edn/read-string line)
         :cljs (reader/read-string line))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn parse-lines
  "Parse many ledger lines, dropping unreadable ones."
  [lines]
  (into [] (keep parse-line) lines))
