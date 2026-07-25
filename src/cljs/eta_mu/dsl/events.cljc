(ns eta-mu.dsl.events
  "Canonical event registry. Hooks are defined against these event names.
   Each target boundary translates canonical → host-native events.

   Canonical events are target-agnostic. The DSL author writes:

     (defhook my-hook {:event :tool/requested} ...)

   and each boundary maps it to the host-native event:
     - OpenCode: :tool.execute.before
     - Claude:   \"PreToolUse\"

   Events that share a name across hosts use the canonical name directly
   when it matches the host-native form (e.g. :file/changed).")

;; ---------------------------------------------------------------------------
;; Canonical → host-native mapping
;; ---------------------------------------------------------------------------

(def ^:private event-map
  "canonical-keyword → {:opencode keyword, :claude string}"
  {:session/open         {:opencode :session.created
                          :claude   "SessionStart"}
   :session/closed       {:opencode :session.deleted
                          :claude   "SessionEnd"}
   :tool/requested       {:opencode :tool.execute.before
                          :claude   "PreToolUse"}
   :tool/succeeded       {:opencode :tool.execute.after
                          :claude   "PostToolUse"}
   :permission/requested {:opencode :permission.asked
                          :claude   "PermissionRequest"}
   :permission/resolved  {:opencode :permission.replied
                          :claude   "PermissionDenied"}
   :context/compacting   {:opencode :experimental.session.compacting
                          :claude   "PreCompact"}
   :context/compacted    {:opencode :session.compacted
                          :claude   "PostCompact"}
   :file/changed         {:opencode :file.watcher.updated
                          :claude   "FileChanged"}})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn for-host
  "Translate a canonical event keyword to the host-native form.
   Returns the host-native keyword (OpenCode) or string (Claude).
   Throws if the canonical event is unknown."
  [canonical host]
  (let [m (get event-map canonical)]
    (when-not m
      (throw (ex-info (str "Unknown canonical event: " canonical)
                      {:canonical canonical
                       :known     (keys event-map)})))
    (case host
      :opencode (:opencode m)
      :claude   (:claude m)
      (throw (ex-info (str "Unknown host: " host) {:host host})))))

(defn all-canonical
  "Return the set of all canonical event keywords."
  []
  (set (keys event-map)))

(defn canonical-for?
  "Is `event` a canonical event keyword?"
  [event]
  (contains? event-map event))
