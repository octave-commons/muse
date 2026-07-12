(ns plugins.receipt-river
  "Append-only per-repo receipts.edn ledger for multi-step work.
   Ported from pseudo/receipt_river.cljs — pure logic lives in
   eta-mu.domain.receipts / eta-mu.domain.repo; fs via the node boundary."
  (:require [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.receipts :as receipts]
            [eta-mu.domain.repo :as repo]
            [eta-mu.dsl :refer [deftool defplugin]]))

(def receipt-file-name "receipts.edn")

(defn- find-git-root [start]
  (repo/find-git-root bfs/join bfs/dirname bfs/exists? start))

(defn- resolve-repo-root
  "Explicit :repo param wins, then the session worktree, then directory."
  [{:keys [repo]} ctx]
  (or (when repo (or (find-git-root repo) repo))
      (:worktree ctx)
      (:directory ctx)))

(defn- receipt-file [repo-root]
  (bfs/join repo-root receipt-file-name))

(defn- clamp [n fallback lo hi]
  (if (number? n) (max lo (min hi (long n))) fallback))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- status [repo-root file]
  (let [lines (bfs/read-lines file)]
    {:action "status"
     :repo   repo-root
     :file   file
     :exists (bfs/exists? file)
     :count  (count lines)
     :last   (last lines)}))

(defn- tail [repo-root file n]
  (let [lines (bfs/tail-lines file n)]
    {:action   "tail"
     :repo     repo-root
     :file     file
     :requested n
     :returned (count lines)
     :tail     lines}))

(defn- validate [repo-root file n]
  (if-not (bfs/exists? file)
    {:action "validate" :repo repo-root :file file
     :ok false :count 0 :failures [{:line-number 0 :errors ["file does not exist"]}]}
    (let [result (receipts/validate-lines (bfs/tail-lines file n))]
      {:action   "validate"
       :repo     repo-root
       :file     file
       :ok       (:ok result)
       :count    (:count result)
       :failures (mapv #(select-keys % [:line-number :errors])
                       (:failures result))})))

(defn- append [repo-root file params action]
  (let [record (receipts/build-record params repo-root (bfs/now-iso))
        line   (receipts/edn-event record)]
    (bfs/append-line! file line)
    {:action action
     :repo   repo-root
     :file   file
     :line   line
     :record record}))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(deftool receipt-river
  {:id          :receipt/river
   :name        "receipt_river"
   :description "Maintain an append-only per-repo receipts.edn ledger: bootstrap, append, tail, validate, and inspect receipt state. Never edit past lines; never log secrets."
   :args        [:map
                 [:action [:enum "status" "bootstrap" "append" "tail" "validate"]]
                 [:repo {:optional true} :string]
                 [:kind {:optional true} :string]
                 [:lines {:optional true} :int]
                 [:origin {:optional true} :string]
                 [:owner {:optional true} :string]
                 [:dod {:optional true} :string]
                 [:host {:optional true} :string]
                 [:manifest {:optional true} :string]
                 [:refs {:optional true} :string]
                 [:note {:optional true} :string]
                 [:tests {:optional true} :string]
                 [:decisions {:optional true} :string]
                 [:drift {:optional true} :string]]
   :tags        #{:receipt :ledger :audit}}
  [{:keys [action lines] :as params} ctx]
  (let [repo-root (resolve-repo-root params ctx)
        file      (receipt-file repo-root)]
    (case action
      "status"   (status repo-root file)
      "tail"     (tail repo-root file (clamp lines 20 1 2000))
      "validate" (validate repo-root file (clamp lines 200 1 2000))
      ("append" "bootstrap") (append repo-root file params action))))

(defplugin plugin {:id :eta-mu/receipt-river}
  receipt-river)
