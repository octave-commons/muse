;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.boundaries.node.proc-test
  (:require [cljs.test :refer [async deftest is]]
            [eta-mu.boundaries.node.proc :as proc]))

(defn- ^:async verify-spawn! []
  (let [command [(.-execPath js/process) "-e" "process.stdout.write('spawned')"]
        expected {:ok true :code 0 :out "spawned" :err ""}
        controller (js/AbortController.)]
    (is (= expected (await (proc/run! command {}))))
    (is (= expected (await (proc/run! command {:signal (.-signal controller)}))))))

(deftest child-process-starts-with-and-without-signal
  (async done
    (-> (verify-spawn!)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
