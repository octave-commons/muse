(ns eta-mu.daemon.build
  "Shadow-cljs build hook for the daemon target: the :esm output is ESM
   but muse has no root package.json, so node needs a {:type \"module\"}
   marker next to the emitted module."
  (:require [clojure.java.io :as io]))

(defn emit-package-json
  "Build hook (:flush stage)."
  {:shadow.build/stage :flush}
  [build-state & _]
  (let [{:keys [output-dir]} (:shadow.build/config build-state)
        f (io/file output-dir "package.json")]
    (io/make-parents f)
    (spit f "{\n  \"type\": \"module\"\n}\n"))
  build-state)
