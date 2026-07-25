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

(defn- pump-line!
  "Append chunk to the stream's buffer, emitting every completed line."
  [bufs stream on-line chunk]
  (let [buf   (swap! (get bufs stream) str chunk)
        ;; limit -1 keeps trailing empties: "one\n" is a complete line
        ;; plus an empty remainder, not a line still being buffered.
        parts (str/split buf #"\r?\n" -1)]
    (doseq [line (butlast parts)]
      (on-line stream line))
    (reset! (get bufs stream) (last parts))))

(defn- flush-stream!
  "Emit a stream's trailing unterminated line, if any."
  [bufs stream on-line]
  (let [rest-buf @(get bufs stream)]
    (when-not (str/blank? rest-buf)
      (on-line stream rest-buf))))

(defn spawn-lines!
  "Spawn [cmd & args] streaming: on-line is called with (:stdout|:stderr
   line) for every completed line, including a trailing unterminated one.
   Returns {:pid int :kill (fn []) :exit promise-of {:code :signal :error?}}."
  [command {:keys [cwd on-line]}]
  (let [child (cp/spawn (first command)
                        (clj->js (rest command))
                        #js {:cwd cwd :shell false
                             :stdio #js ["ignore" "pipe" "pipe"]})
        bufs  {:stdout (atom "") :stderr (atom "")}]
    (.on (.-stdout child) "data" #(pump-line! bufs :stdout on-line (str %)))
    (.on (.-stderr child) "data" #(pump-line! bufs :stderr on-line (str %)))
    {:pid  (.-pid child)
     :kill (fn [] (.kill child "SIGTERM"))
     :exit (js/Promise.
            (fn [resolve _reject]
              (.on child "error"
                   (fn [e]
                     (resolve {:code -1 :signal nil :error (str (.-message e))})))
              (.on child "close"
                   (fn [code signal]
                     (flush-stream! bufs :stdout on-line)
                     (flush-stream! bufs :stderr on-line)
                     (resolve {:code (or code -1) :signal signal})))))}))
