(ns eta-mu.boundaries.node.proc
  "Child-process boundary. The only namespace allowed to touch
   node:child_process. Commands are vectors of strings; never a shell."
  (:refer-clojure :exclude [run!])
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(defn run!
  "Run [cmd & args] in `cwd`. Resolves (never rejects) to
   {:ok bool :code int :out string :err string}."
  [command {:keys [cwd]}]
  (js/Promise.
   (fn [resolve _reject]
     (let [child (cp/spawn (first command)
                           (clj->js (rest command))
                           #js {:cwd cwd :shell false
                                :stdio #js ["ignore" "pipe" "pipe"]})
           out   (atom [])
           err   (atom [])]
       (.on (.-stdout child) "data" #(swap! out conj (str %)))
       (.on (.-stderr child) "data" #(swap! err conj (str %)))
       (.on child "error"
            (fn [e]
              (resolve {:ok false :code -1 :out ""
                        :err (str (.-message e))})))
       (.on child "close"
            (fn [code]
              (resolve {:ok   (zero? code)
                        :code code
                        :out  (str/join @out)
                        :err  (str/join @err)})))))))
