(ns eta-mu.daemon.core
  "The eta-mu daemon: watches every .ημ tree it can discover and, when a
   tree's config or plugin sources change, does what that tree's
   config/opencode/root.edn asks —

     :emit  {:path \"~/.config/opencode/opencode.jsonc\"}  → render the
            merged :settings fragments to that path
     :build [\"shadow-cljs\" \"release\" \"opencode-plugin\"] → run the
            command in the repo root

   Effect orchestration only: decisions live in eta-mu.domain.daemon,
   rendering in eta-mu.opencode.settings, host access in boundaries."
  (:require [cljs.reader :as reader]
            [eta-mu.boundaries.node.fs :as nfs]
            [eta-mu.boundaries.node.proc :as proc]
            [eta-mu.boundaries.node.watch :as watch]
            [eta-mu.domain.daemon :as domain]
            [eta-mu.opencode.config :as config]
            [eta-mu.opencode.settings :as settings]))

(def debounce-ms 400)
(def rescan-ms (* 5 60 1000))

(def scan-specs
  "[root max-depth] pairs, ~ expanded against the home dir."
  [["~" 1] ["~/spaces" 3] ["~/devel" 4]])

(defonce state
  (atom {:watched {}    ;; eta-mu-dir → close fn
         :timers  {}    ;; eta-mu-dir → debounce timer
         :busy    #{}   ;; cwds with a build running
         :queued  #{}})) ;; cwds needing a re-run after the current build

;; ---------------------------------------------------------------------------
;; Logging
;; ---------------------------------------------------------------------------

(defn- log! [event]
  (let [entry (assoc event :ts (nfs/now-iso))]
    (js/console.log (pr-str entry))
    (nfs/append-jsonl! (nfs/join (nfs/home-dir) ".eta-mu" "state" "daemon" "log.jsonl")
                       entry)))

;; ---------------------------------------------------------------------------
;; Runtime config reading
;; ---------------------------------------------------------------------------

(defn- read-edn [path]
  (when-let [text (nfs/read-text path)]
    (try
      (reader/read-string text)
      (catch :default e
        (log! {:kind :edn-error :path path :error (str e)})
        nil))))

(defn- read-tree
  "Runtime analogue of eta-mu.opencode.config/read-config: the root.edn
   plus its :imports, in the {:root … :fragments […]} shape the config
   accessors expect. nil when the tree has no opencode config."
  [eta-mu-dir]
  (let [dir (nfs/join eta-mu-dir "config" "opencode")]
    (when-let [root (read-edn (nfs/join dir "root.edn"))]
      {:root root
       :fragments (mapv (fn [rel]
                          {:path rel :data (read-edn (nfs/join dir rel))})
                        (:imports root))})))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- render! [tree {:keys [out source]}]
  (let [fragments (config/settings-fragments tree)]
    (if (empty? fragments)
      (log! {:kind :render-skipped :out out
             :reason "tree has :emit but no :settings fragments"})
      (let [doc (settings/render-jsonc (settings/merged fragments)
                                       {:source source})]
        (if (nfs/write-text-if-changed! out doc)
          (log! {:kind :render :out out :source source})
          (log! {:kind :render-unchanged :out out}))))))

(defn- exec! [{:keys [command cwd] :as action}]
  (if (contains? (:busy @state) cwd)
    (do (swap! state update :queued conj cwd)
        (log! {:kind :build-queued :cwd cwd}))
    (do (swap! state update :busy conj cwd)
        (log! {:kind :build-start :cwd cwd :command command})
        (.then (proc/run! command {:cwd cwd})
               (fn [{:keys [ok code err]}]
                 (log! (cond-> {:kind (if ok :build-ok :build-fail)
                                :cwd cwd :code code}
                         (not ok) (assoc :err (subs (str err) 0 (min 2000 (count (str err)))))))
                 (swap! state update :busy disj cwd)
                 (when (contains? (:queued @state) cwd)
                   (swap! state update :queued disj cwd)
                   (exec! action)))))))

(defn- process!
  "Re-read a tree and run what it asks for. Boot-time convergence passes
   exec? false so a daemon restart re-renders cheap targets but does not
   kick off builds."
  [eta-mu-dir {:keys [exec?] :or {exec? true}}]
  (when-let [tree (read-tree eta-mu-dir)]
    (doseq [action (domain/plan-actions {:home (nfs/home-dir)
                                         :eta-mu-dir eta-mu-dir
                                         :root (:root tree)})]
      (case (:action action)
        :render (render! tree action)
        :exec   (when exec? (exec! action))))))

;; ---------------------------------------------------------------------------
;; Watching
;; ---------------------------------------------------------------------------

(defn- schedule! [eta-mu-dir rel-path]
  (when (domain/relevant-change? rel-path)
    (when-let [timer (get-in @state [:timers eta-mu-dir])]
      (js/clearTimeout timer))
    (swap! state assoc-in [:timers eta-mu-dir]
           (js/setTimeout
            (fn []
              (swap! state update :timers dissoc eta-mu-dir)
              (process! eta-mu-dir {}))
            debounce-ms))))

(defn- discover []
  (let [home (nfs/home-dir)]
    (into []
          (comp (mapcat (fn [[root depth]]
                          (nfs/find-dirs (domain/expand-home home root)
                                         domain/eta-mu-dir-name
                                         {:max-depth depth
                                          :skip? domain/skip-dir?})))
                (distinct))
          scan-specs)))

(declare watch-one!)

(defn- on-watcher-end
  "A watcher errored or closed on its own: log why, forget it, and
   re-establish after a short delay (the dir may be mid-rename)."
  [dir reason]
  (log! {:kind :watcher-died :dir dir :reason reason})
  (swap! state update :watched dissoc dir)
  (js/setTimeout
   (fn []
     (when (and (nfs/exists? dir)
                (not (contains? (:watched @state) dir)))
       (watch-one! dir)
       (log! {:kind :rewatch :dir dir})))
   1000))

(defn- watch-one! [dir]
  (let [close! (watch/watch-dir! dir
                                 (partial schedule! dir)
                                 (partial on-watcher-end dir))]
    (swap! state assoc-in [:watched dir] close!)))

(defn- rescan!
  "Discover .ημ dirs; watch new ones, drop watchers whose dir vanished."
  []
  (let [found   (set (discover))
        watched (set (keys (:watched @state)))]
    (doseq [dir (sort (remove watched found))]
      (watch-one! dir)
      (log! {:kind :watch :dir dir}))
    (doseq [dir (remove found watched)]
      (when-not (nfs/exists? dir)
        (when-let [close! (get-in @state [:watched dir])]
          (close!))
        (swap! state update :watched dissoc dir)
        (log! {:kind :unwatch :dir dir})))))

(defn init
  "Daemon entrypoint: watch everything, converge render targets, keep
   rescanning for new trees."
  []
  (log! {:kind :daemon-start :scan scan-specs})
  (rescan!)
  (doseq [dir (keys (:watched @state))]
    (process! dir {:exec? false}))
  (js/setInterval rescan! rescan-ms)
  (log! {:kind :daemon-ready :watching (count (:watched @state))}))
