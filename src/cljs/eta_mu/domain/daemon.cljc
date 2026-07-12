(ns eta-mu.domain.daemon
  "Pure decisions for the eta-mu daemon: which filesystem events matter,
   and what a config tree asks to happen when it changes. No I/O — the
   daemon (eta-mu.daemon.core) supplies parsed EDN and executes plans."
  (:require [clojure.string :as str]))

(def eta-mu-dir-name ".ημ")

(def config-root-rel
  "Where a tree's opencode root lives, relative to its .ημ dir."
  "config/opencode/root.edn")

(def watch-prefixes
  "Change paths (relative to the .ημ dir) the daemon reacts to."
  ["config/" "plugins/"])

(def source-extensions #{"edn" "cljc" "cljs" "clj"})

(defn- extension [path]
  (let [base (last (str/split path #"/"))
        i    (str/last-index-of base ".")]
    (when (and i (pos? i))
      (subs base (inc i)))))

(defn relevant-change?
  "Does a change at rel-path (relative to a .ημ dir) warrant action?"
  [rel-path]
  (boolean
   (and rel-path
        (some #(str/starts-with? rel-path %) watch-prefixes)
        (contains? source-extensions (extension rel-path)))))

(defn skip-dir?
  "Directories discovery never descends into."
  [dir-name]
  (contains? #{"node_modules" ".git" ".shadow-cljs" "target"
               "dist" "dist-dev" "dist-daemon" ".opencode"}
             dir-name))

(defn expand-home
  "Expand a leading ~ against the supplied home directory."
  [home path]
  (cond
    (= path "~")                  home
    (str/starts-with? path "~/") (str home (subs path 1))
    :else                         path))

(defn repo-root
  "The repo a .ημ dir configures (its parent directory)."
  [eta-mu-dir]
  (let [i (str/last-index-of eta-mu-dir "/")]
    (if (and i (pos? i)) (subs eta-mu-dir 0 i) eta-mu-dir)))

(defn plan-actions
  "What a changed tree asks for, from its parsed root.edn (nil when the
   tree has no opencode config). Returns a vector of actions:
     {:action :render :out <abs path> :source <.ημ dir>}
     {:action :exec   :command [...] :cwd <repo root>}"
  [{:keys [home eta-mu-dir root]}]
  (let [{:keys [emit build]} root]
    (cond-> []
      (:path emit)
      (conj {:action :render
             :out    (expand-home home (:path emit))
             :source eta-mu-dir})

      (seq build)
      (conj {:action  :exec
             :command (vec build)
             :cwd     (repo-root eta-mu-dir)}))))
