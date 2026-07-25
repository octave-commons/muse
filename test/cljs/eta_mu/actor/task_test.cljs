(ns eta-mu.actor.task-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]
            [eta-mu.actor.task :as task]
            [eta-mu.domain.task :as dtask]
            [promesa.core :as p]))

(use-fixtures :each
  {:before #(actor/init-store! (mem/make-memory-store))})

(defn- run-async
  [done body]
  (-> (body)
      (p/catch (fn [e] (is false (str "unexpected rejection: " e))))
      (p/finally (fn [_ _] (done)))))

(deftest spawn-lines-fan-out-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :watcher)
               {:keys [task-id pid exit]}
               (task/spawn-task!
                ["node" "-e" "console.log('one');console.error('two');console.log('three')"]
                {:subscribers [:watcher]})
               _      (p/promise exit)
               box    (actor/mailbox :watcher)
               ledger (actor/mailbox task-id)]
         (testing "process ran"
           (is (pos? pid)))
         (testing "every line lands in the subscriber mailbox"
           (is (= ["one" "three"]
                  (into [] (comp (filter #(= dtask/stdout-type (:event/type %)))
                                 (map #(get-in % [:payload :line])))
                        box)))
           (is (= ["two"]
                  (into [] (comp (filter #(= dtask/stderr-type (:event/type %)))
                                 (map #(get-in % [:payload :line])))
                        box))))
         (testing "the task ledger records lifecycle, threaded under started"
           (let [started (first (filter #(= dtask/started-type (:event/type %)) ledger))
                 exited  (first (filter #(= dtask/exited-type (:event/type %)) ledger))]
             (is (some? started))
             (is (some? exited))
             (is (= (:event/id started) (:causal/root exited)))
             (is (every? #(= (:event/id started) (:causal/root %)) box))
             (is (:ok (:payload exited)))
             (is (= 2 (get-in exited [:payload :stdout-lines])))
             (is (= 1 (get-in exited [:payload :stderr-lines])))))
         (testing "live status reports the exit"
           (is (= :exited (:status (task/status task-id))))))))))

(deftest kill-via-control-message-test
  (async done
    (run-async done
      (fn []
        (p/let [{:keys [task-id exit]}
               (task/spawn-task! ["node" "-e" "setInterval(function(){},1000)"])
               _      (actor/tell! :test/driver task-id "task.kill" {})
               res    (p/promise exit)
               ledger (actor/mailbox task-id)]
         (testing "the control poll picks up task.kill and terminates"
           (is (= "SIGTERM" (:signal res))))
         (testing "the exited event records the kill"
           (let [exited (first (filter #(= dtask/exited-type (:event/type %)) ledger))]
             (is (:killed (:payload exited)))
             (is (not (:ok (:payload exited)))))))))))

(deftest subscribe-via-control-message-test
  (async done
    (run-async done
      (fn []
        (p/let [_ (actor/spawn! :late-watcher)
               {:keys [task-id exit]}
               (task/spawn-task!
                ["node" "-e" "setTimeout(function(){console.log('tail')},700)"])
               _   (actor/tell! :test/driver task-id "task.subscribe"
                                {:subscriber "late-watcher"})
               _   (p/promise exit)
               box (actor/mailbox :late-watcher)]
         (testing "a subscriber added mid-flight receives later lines"
           (is (= ["tail"]
                  (into [] (comp (filter #(= dtask/stdout-type (:event/type %)))
                                 (map #(get-in % [:payload :line])))
                        box)))))))))
