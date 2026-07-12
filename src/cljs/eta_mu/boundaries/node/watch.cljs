(ns eta-mu.boundaries.node.watch
  "Filesystem watching boundary. The only namespace allowed to touch
   fs.watch. Callbacks receive plain strings; recursive watches rely on
   inotify support in Node ≥ 20 on Linux."
  (:require ["node:fs" :as fs]))

(defn watch-dir!
  "Recursively watch `dir`, calling (on-change rel-path) for events that
   carry a filename and (on-end reason) once if the watcher errors or
   closes. Returns a zero-arg close function."
  [dir on-change on-end]
  (let [ended   (atom false)
        end!    (fn [reason]
                  (when (compare-and-set! ended false true)
                    (on-end reason)))
        watcher (fs/watch dir #js {:recursive true :persistent true}
                          (fn [_event filename]
                            (when filename
                              (on-change (str filename)))))]
    (.on watcher "error" (fn [e] (end! (str e))))
    (.on watcher "close" (fn [] (end! "close")))
    (fn close! []
      (reset! ended true)
      (.close watcher))))
