(ns plugins.edn-ledger
  "Generic tools for append-only EDN event ledgers (one event map per line),
   e.g. corpus ingest ledgers like fork_tales_v2/ledgers/ingest.edn.
   Pure logic lives in eta-mu.domain.edn-ledger; fs via the node boundary."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.edn-ledger :as ledger]
            [eta-mu.domain.repo :as repo]
            [eta-mu.dsl :refer [deftool defplugin]]))

(defn- find-git-root [start]
  (repo/find-git-root bfs/join bfs/dirname bfs/exists? start))

(defn- resolve-ledger-path
  "Absolute :path wins; a relative :path resolves against :repo, then the
   session worktree, then the session directory. Must end in .edn."
  [{:keys [path repo]} ctx]
  (when-not (and (string? path) (str/ends-with? path ".edn"))
    (throw (ex-info "edn_ledger requires a :path ending in .edn" {:path path})))
  (if (str/starts-with? path "/")
    path
    (let [base (or (when repo (or (find-git-root repo) repo))
                   (:worktree ctx)
                   (:directory ctx))]
      (bfs/join base path))))

(defn- clamp [n fallback lo hi]
  (if (number? n) (max lo (min hi (long n))) fallback))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- status [file]
  (let [lines (bfs/read-lines file)]
    {:action "status"
     :file file
     :exists (bfs/exists? file)
     :count (count lines)
     :last (last lines)}))

(defn- tail [file n]
  (let [lines (bfs/tail-lines file n)]
    {:action "tail"
     :file file
     :requested n
     :returned (count lines)
     :events (ledger/parse-lines lines)}))

(defn- count-events [file]
  (let [events (ledger/parse-lines (bfs/read-lines file))]
    {:action "count"
     :file file
     :total (count events)
     :by-type (ledger/count-by-type events)}))

(defn- query [file {:keys [type contains] :as params} limit]
  (let [events (ledger/parse-lines (bfs/read-lines file))
        matches (ledger/query-events events {:type type :contains contains :limit limit})]
    {:action "query"
     :file file
     :type type
     :contains contains
     :total (count events)
     :returned (count matches)
     :events matches}))

(defn- append [file params]
  (let [fields (cond
                 (string? (:event params)) (reader/read-string (:event params))
                 (map? (:data params)) (:data params)
                 :else (throw (ex-info "append requires :event (EDN map string) or :data (map)" {})))]
    (when-not (map? fields)
      (throw (ex-info "append payload must be an EDN map" {:got (pr-str fields)})))
    (let [event (ledger/build-event fields bfs/now-iso #(str (random-uuid)))
          line (ledger/edn-line event)]
      (bfs/append-line! file line)
      {:action "append"
       :file file
       :line line
       :event event})))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(deftool edn-ledger
  {:id          :edn/ledger
   :name        "edn_ledger"
   :description "Work with append-only EDN event ledgers (one event map per line): status, append, tail, query, count. Append injects :event/id and :ts defaults. Never edits past lines; never log secrets."
   :args        [:map
                 [:action [:enum "status" "append" "tail" "query" "count"]]
                 [:path :string]
                 [:repo {:optional true} :string]
                 [:event {:optional true} :string]
                 [:type {:optional true} :string]
                 [:contains {:optional true} :string]
                 [:lines {:optional true} :int]
                 [:limit {:optional true} :int]]
   :tags        #{:ledger :edn :audit}}
  [{:keys [action lines limit] :as params} ctx]
  (let [file (resolve-ledger-path params ctx)]
    (case action
      "status" (status file)
      "tail"   (tail file (clamp lines 20 1 2000))
      "count"  (count-events file)
      "query"  (query file params (clamp limit 50 1 2000))
      "append" (append file params))))

(defplugin plugin {:id :eta-mu/edn-ledger}
  edn-ledger)
