(ns eta-mu.boundaries.node.ledger
  "File-backed actor ledgers: one append-only EDN-lines file per actor
   under a ledger root (default .eta-mu/ledgers), receipts.edn style.
   Implements the actor-store contract; also provides mailbox watching
   (the file analogue of event-ledger's change-stream watchers).

   Layout:
     <root>/actors.edn        registry, one line per (re)spawn, last wins
     <root>/<actor-id>.edn    the actor's mailbox ledger"
  (:require [clojure.string :as str]
            [eta-mu.actor.envelope :as envelope]
            [eta-mu.actor.memory :as memory]
            [eta-mu.actor.store :as store]
            [eta-mu.boundaries.node.fs :as nfs]
            [eta-mu.boundaries.node.watch :as watch]))

(def default-root ".eta-mu/ledgers")

(defn- safe-file-name [actor-id]
  (str/replace (name actor-id) #"[^A-Za-z0-9._-]" "_"))

(defn- mailbox-path [root actor-id]
  (nfs/join root (str (safe-file-name actor-id) ".edn")))

(defn- registry-path [root]
  (nfs/join root "actors.edn"))

(defn- read-registry
  "Replay the registry lines: {actor-id meta}, later lines merge over
   earlier ones (a respawn updates metadata, never erases history)."
  [root]
  (reduce (fn [acc {:actor/keys [id meta]}]
            (if id
              (update acc (keyword id) merge meta)
              acc))
          {}
          (envelope/parse-lines (nfs/read-lines (registry-path root)))))

(defn- read-mailbox [root actor-id]
  (envelope/parse-lines (nfs/read-lines (mailbox-path root actor-id))))

;; ---------------------------------------------------------------------------
;; Store
;; ---------------------------------------------------------------------------

(defrecord LedgerStore [root]

  store/IActorStore

  (-spawn! [_ actor-id opts]
    (let [known (read-registry root)
          meta  (if-let [existing (get known (keyword (name actor-id)))]
                  (merge existing opts)
                  (memory/spawn-meta actor-id opts))]
      (nfs/append-line! (registry-path root)
                        (envelope/->line {:actor/id   (name actor-id)
                                          :actor/meta meta
                                          :ts         (envelope/now-iso)}))
      ;; Touch the mailbox so the ledger exists (and is watchable)
      ;; from the moment the actor does.
      (let [p (mailbox-path root actor-id)]
        (when-not (nfs/exists? p)
          (nfs/write-text! p "")))
      actor-id))

  (-send! [_ from-id to-id envelope]
    (when-not (get (read-registry root) (keyword (name to-id)))
      (throw (ex-info "Actor not found" {:actor-id to-id})))
    (let [envelope (memory/routed-envelope from-id to-id envelope)]
      (nfs/append-line! (mailbox-path root to-id) (envelope/->line envelope))
      (:event/id envelope)))

  (-recv [_ actor-id opts]
    (memory/recv-view (read-mailbox root actor-id) opts))

  (-actors [_]
    (vec (keys (read-registry root))))

  (-actor-meta [_ actor-id]
    (get (read-registry root) (keyword (name actor-id))))

  (-registry [_]
    (read-registry root))

  (-mailbox [_ actor-id]
    (read-mailbox root actor-id))

  (-clear! [_ actor-id]
    (nfs/write-text! (mailbox-path root actor-id) "")))

(defn make-ledger-store
  "Create (and ensure) a file-ledger store rooted at `root`."
  ([] (make-ledger-store default-root))
  ([root]
   (nfs/ensure-dir! root)
   (->LedgerStore root)))

;; ---------------------------------------------------------------------------
;; Watching (file analogue of event-ledger's watch-ledger / watch-once)
;; ---------------------------------------------------------------------------

(defn watch-mailbox!
  "Call (on-event envelope) for every event appended to actor-id's
   mailbox after this call. Returns a zero-arg close function."
  [{:keys [root]} actor-id on-event]
  (let [path (mailbox-path root actor-id)
        file (last (str/split path #"/"))
        seen (atom (count (envelope/parse-lines (nfs/read-lines path))))
        emit! (fn []
                (let [events (envelope/parse-lines (nfs/read-lines path))
                      n      @seen]
                  (when (> (count events) n)
                    (reset! seen (count events))
                    (doseq [e (subvec events n)]
                      (on-event e)))))]
    (watch/watch-dir! root
                      (fn [rel] (when (= rel file) (emit!)))
                      (fn [_reason] nil))))

(defn watch-once
  "Resolve with the first appended event matching (pred envelope), or
   nil after timeout-ms. The file analogue of event-ledger's watch-once."
  [store actor-id pred timeout-ms]
  (js/Promise.
   (fn [resolve _]
     (let [done   (atom false)
           close! (atom (fn []))
           finish (fn [v]
                    (when (compare-and-set! done false true)
                      (@close!)
                      (resolve v)))]
       (reset! close!
               (watch-mailbox! store actor-id
                               (fn [e] (when (pred e) (finish e)))))
       (js/setTimeout #(finish nil) timeout-ms)))))
