(ns eta-mu.boundaries.node.import
  "Lazy module loading that works from both output flavors: :esm builds
   (plugin, daemon) use dynamic import(); CJS builds (:node-test) use
   require, which loads ESM packages fine on Node ≥ 22.12."
  (:require [promesa.core :as p]
            [shadow.esm :refer [dynamic-import]]))

(defn load!
  "Resolve to the module named by spec, in either module system."
  [spec]
  (if (exists? js/require)
    (p/do (js/require spec))
    (dynamic-import spec)))
