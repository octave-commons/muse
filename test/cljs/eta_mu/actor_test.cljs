(ns eta-mu.actor-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]
            [promesa.core :as p]))

(use-fixtures :each
  {:before #(actor/init-store! (mem/make-memory-store))})

(defn- run-async
  "Drive a promise-returning test body under cljs.test async."
  [done body]
  (-> (body)
      (p/catch (fn [e] (is false (str "unexpected rejection: " e))))
      (p/finally (fn [_ _] (done)))))

;; ---------------------------------------------------------------------------
;; spawn
;; ---------------------------------------------------------------------------

(deftest spawn-test
  (async done
    (run-async done
      #(p/let [_   (actor/spawn! :test/actor {:kind "test"})
               ids (actor/actors)]
         (testing "spawning an actor registers it"
           (is (some #{:test/actor} ids)))))))

(deftest spawn-idempotent-test
  (async done
    (run-async done
      #(p/let [_   (actor/spawn! :idem {:kind "v1"})
               _   (actor/spawn! :idem {:kind "v2"})
               ids (actor/actors)]
         (testing "spawning the same actor twice updates metadata"
           (is (= 1 (count ids))))))))

;; ---------------------------------------------------------------------------
;; send / recv
;; ---------------------------------------------------------------------------

(deftest send-recv-test
  (async done
    (run-async done
      #(p/let [_        (actor/spawn! :sender)
               _        (actor/spawn! :receiver)
               eid      (actor/tell! :sender :receiver "test.hello" {:msg "hi"})
               messages (actor/recv :receiver)]
         (testing "message appears in recipient mailbox"
           (is (string? eid))
           (is (= 1 (count messages)))
           (is (= "test.hello" (:event/type (first messages))))
           (is (= {:msg "hi"} (:payload (first messages)))))))))

(deftest send-fails-to-unknown-actor-test
  (async done
    (-> (p/let [_ (actor/spawn! :sender)]
          (actor/tell! :sender :ghost "test.x" {}))
        (p/then (fn [_] (is false "sending to unknown actor should reject")))
        (p/catch (fn [e]
                   (testing "sending to unknown actor rejects"
                     (is (re-find #"Actor not found" (ex-message e))))))
        (p/finally (fn [_ _] (done))))))

;; ---------------------------------------------------------------------------
;; recv options
;; ---------------------------------------------------------------------------

(deftest recv-limit-test
  (async done
    (run-async done
      #(p/let [_  (actor/spawn! :a)
               _  (actor/spawn! :b)
               _  (p/all (for [i (range 5)]
                           (actor/tell! :a :b "test.msg" {:i i})))
               ms (actor/recv :b {:limit 2})]
         (testing "limit caps returned messages"
           (is (= 2 (count ms))))))))

(deftest recv-filter-type-test
  (async done
    (run-async done
      #(p/let [_      (actor/spawn! :a)
               _      (actor/spawn! :b)
               _      (actor/tell! :a :b "alpha" {})
               _      (actor/tell! :a :b "beta" {})
               _      (actor/tell! :a :b "alpha" {})
               alphas (actor/recv :b {:filter-type "alpha"})
               betas  (actor/recv :b {:filter-type "beta"})]
         (testing "filter-type selects by event/type"
           (is (= 2 (count alphas)))
           (is (= 1 (count betas))))))))

(deftest recv-since-id-test
  (async done
    (run-async done
      #(p/let [_     (actor/spawn! :a)
               _     (actor/spawn! :b)
               eid1  (actor/tell! :a :b "test" {:n 1})
               _     (actor/tell! :a :b "test" {:n 2})
               _     (actor/tell! :a :b "test" {:n 3})
               after (actor/recv :b {:since-id eid1})]
         (testing "since-id returns messages after a given event"
           (is (= 2 (count after)))
           (is (= 2 (:n (:payload (first after))))))))))

;; ---------------------------------------------------------------------------
;; mailbox / clear
;; ---------------------------------------------------------------------------

(deftest mailbox-raw-test
  (async done
    (run-async done
      #(p/let [_  (actor/spawn! :x)
               _  (actor/tell! :a :x "test" {})
               mb (actor/mailbox :x)]
         (testing "mailbox returns the raw ledger"
           (is (= 1 (count mb))))))))

(deftest clear-test
  (async done
    (run-async done
      #(p/let [_  (actor/spawn! :x)
               _  (actor/tell! :a :x "test" {})
               _  (actor/clear! :x)
               mb (actor/mailbox :x)]
         (testing "clear empties the mailbox"
           (is (empty? mb)))))))

;; ---------------------------------------------------------------------------
;; tell! / ask!
;; ---------------------------------------------------------------------------

(deftest tell-test
  (async done
    (run-async done
      #(p/let [_  (actor/spawn! :a)
               _  (actor/spawn! :b)
               _  (actor/tell! :a :b "test" {})
               ms (actor/recv :b)]
         (testing "tell sets delivery mode to tell"
           (is (= "tell" (:delivery/mode (first ms)))))))))

(deftest ask-test
  (async done
    (run-async done
      #(p/let [_  (actor/spawn! :a)
               _  (actor/spawn! :b)
               _  (actor/ask! :a :b "test" {})
               ms (actor/recv :b)]
         (testing "ask sets delivery mode to ask"
           (is (= "ask" (:delivery/mode (first ms)))))))))
