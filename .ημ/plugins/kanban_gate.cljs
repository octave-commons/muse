(ns plugins.kanban-gate
  "Mechanical review->done floor for Rheos-backed kanban boards, and a guard
   against bypassing it via a direct frontmatter edit.

   Ports the logic that used to live only in epiphany's .claude/hooks/
   (kanban-mcp-status-gate.sh + kanban-direct-edit-guard.sh, calling
   bin/kanban-done-gate) into a target-agnostic muse plugin, so the same
   check reaches every host this plugin is published to (Claude Code,
   OpenCode), not just Claude Code. See the epiphany chore this implements:
   chore-rebase-the-kanban-done-gate-on-rheos-parsing-a-shared-muse-hook-and-ledger-sourced-state.

   Card facts come from the running Rheos HTTP API (GET /api/task/:uuid/content),
   which is backed by rheos.backend.shape.content-parser -- the same parser
   Rheos itself uses -- instead of a third, hand-rolled re-parse of the
   card's markdown from disk.

   ASSUMPTION, unverified at time of writing: hook handlers may return a
   promise of the effect map, mirroring deftool's documented promise
   support. If the host boundary does not await hook results, the
   kanban-done-gate hook below will not block anything -- verify this at
   build/runtime and fix the boundary (or make the handler synchronous
   over a blocking fetch) if it doesn't hold."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.dsl :refer [defhook defplugin]]
            [promesa.core :as p]))

(def ^:private default-rheos-url "http://127.0.0.1:8791")

(defn- rheos-url []
  (or (some-> (unchecked-get (.-env js/process) "RHEOS_HTTP_URL"))
      default-rheos-url))

(defn- get-json!
  "GET `url`, resolve to {:ok :status :body}. `:body` is keywordized JSON,
   or {:raw text} if the response isn't JSON."
  [url]
  (-> (js/fetch url)
      (.then (fn [resp]
               (-> (.text resp)
                   (.then (fn [raw]
                            {:ok     (.-ok resp)
                             :status (.-status resp)
                             :body   (try
                                       (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                       (catch :default _ {:raw raw}))})))))
      (.catch (fn [err] {:ok false :status 0 :body {:raw (str err)}}))))

(defn- fetch-task-content!
  "GET the parsed card {:frontmatter :sections :sourcePath} from Rheos.
   `sections` is a vector of {:type \"body\"|\"comment\" :content str}."
  [uuid project]
  (get-json! (str (rheos-url) "/api/task/" uuid "/content?project=" (or project "epiphany"))))

(defn- section-text [sections type]
  (->> sections
       (filter #(= (:type %) type))
       (map :content)
       (str/join "\n\n")))

;; ---------------------------------------------------------------------------
;; Mechanical checks -- same three floors bin/kanban-done-gate enforced,
;; re-expressed over Rheos's parsed sections instead of a local re-parse.
;; ---------------------------------------------------------------------------

(def ^:private evidence-re
  #"(?i)\b\d+\s+tests?,\s*\d+\s+assertions?,\s*0\s+failures\b")

(def ^:private disposition-re
  #"(?i)\b(review|audit)\b[^\n]{0,200}\b(approve|approved|accept|accepted|lgtm)\b")

(def ^:private ep-command-re
  #"`ep\s+([a-z][a-z0-9-]*)`")

(defn- named-cli-commands [body-text]
  (->> (re-seq ep-command-re (or body-text ""))
       (map second)
       distinct))

(defn- dispatch-gate-config!
  "Read an optional per-repo `.ημ/kanban-done-gate.edn` at the worktree root
   naming the CLI dispatch file to check named `ep <word>` commands against
   (e.g. `{:dispatch-file \"src/epiphany/infra/main.clj\"}`). Absent this
   file, the CLI-dispatch check is skipped for that repo rather than
   assuming a path -- this plugin is published host-wide, not scoped to one
   project's source layout."
  [worktree]
  (p/let [path (bfs/join worktree ".ημ" "kanban-done-gate.edn")
          exists (bfs/exists? path)]
    (when exists
      (p/let [raw (bfs/read-text path)]
        (try (reader/read-string raw) (catch :default _ nil))))))

(defn- missing-dispatch-commands!
  "Given the config above and the card's body text, return the subset of
   named `ep <word>` commands that don't appear anywhere in the dispatch
   file's text. A loose substring check (not a real dispatch-table parse) --
   good enough to catch the \"command doesn't exist at all\" failure mode
   the 2026-07-12/13 audits found, not a syntax-aware guarantee."
  [worktree body-text]
  (p/let [config (dispatch-gate-config! worktree)]
    (if-not (:dispatch-file config)
      []
      (p/let [dispatch-path (bfs/join worktree (:dispatch-file config))
              dispatch-exists (bfs/exists? dispatch-path)]
        (if-not dispatch-exists
          (named-cli-commands body-text) ;; file named but missing -> every command is "missing"
          (p/let [dispatch-text (bfs/read-text dispatch-path)]
            (remove #(str/includes? dispatch-text %) (named-cli-commands body-text))))))))

(defn- gate-reasons!
  "Resolve to a (possibly empty) vector of blocking reasons for this card."
  [worktree {:keys [frontmatter sections]}]
  (let [body-text (section-text sections "body")
        comment-text (section-text sections "comment")]
    (p/let [missing (missing-dispatch-commands! worktree body-text)]
      (cond-> []
        (not (re-find evidence-re comment-text))
        (conj "no greppable \"<N> tests, <N> assertions, 0 failures\" line found in the card's comments")

        (not (re-find disposition-re comment-text))
        (conj "no explicit REVIEW/AUDIT disposition with an approve/accepted verdict found in the card's comments")

        (seq missing)
        (conj (str "card names CLI command(s) not found in " (:dispatch-file (or frontmatter {}) "the configured dispatch file") ": "
                   (str/join ", " (map #(str "`ep " % "`") missing))))))))

;; ---------------------------------------------------------------------------
;; Hooks
;; ---------------------------------------------------------------------------

(defn- kanban-status-update-call? [tool-name]
  (let [t (some-> tool-name str/lower-case)]
    (and t (or (str/includes? t "kanban_update_status")
               (str/includes? t "kanban-status-update")))))

(defhook kanban-done-gate
  {:id       :policy/kanban-done-gate
   :event    :tool/requested
   :priority 100}
  [input output]
  (when (kanban-status-update-call? (:tool input))
    (let [args (:args output)
          status (some-> (:status args) name str/lower-case)
          uuid (:uuid args)
          project (:project args)]
      (when (and (= status "done") uuid)
        (p/let [{:keys [ok body]} (fetch-task-content! uuid project)]
          (if-not ok
            {:effect :reject
             :message (str "kanban-gate: could not reach Rheos at " (rheos-url)
                           " to verify the review->done evidence floor for " uuid
                           " -- blocking rather than guessing. ("
                           (or (:raw body) "no detail") ")")}
            (p/let [reasons (gate-reasons! (:worktree input) body)]
              (when (seq reasons)
                {:effect :reject
                 :message (str "kanban-done-gate blocked this transition:\n- "
                               (str/join "\n- " reasons))}))))))))

(defn- edit-or-write-call? [tool-name]
  (contains? #{"edit" "write"} (some-> tool-name str/lower-case)))

(defn- kanban-card-path? [path]
  (and path
       (str/ends-with? path ".md")
       (re-find #"/docs/kanban/(stories|epics|chores)/" path)))

(defn- status-done-write? [args]
  (let [candidate (or (:new_string args) (:newString args)
                       (:content args) (:new-string args))]
    (boolean (some->> candidate (re-find #"(?im)^status:\s*\"?done\"?\s*$")))))

(defhook kanban-direct-edit-guard
  {:id       :policy/kanban-direct-edit-guard
   :event    :tool/requested
   :priority 100}
  [input output]
  (when (edit-or-write-call? (:tool input))
    (let [args (:args output)
          path (or (:file_path args) (:filePath args) (:path args))]
      (when (and (kanban-card-path? path) (status-done-write? args))
        {:effect :reject
         :message (str "This edits a kanban card frontmatter to status: done directly ("
                       path "), bypassing the review->done evidence gate. Use the kanban "
                       "status-update tool instead.")}))))

(defplugin plugin {:id :eta-mu/kanban-gate}
  kanban-done-gate
  kanban-direct-edit-guard)
