(ns eta-mu.actor-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]))

(use-fixtures :each
  (fn [f]
    (actor/init-store! (mem/make-memory-store))
    (f)))

;; ---------------------------------------------------------------------------
;; spawn
;; ---------------------------------------------------------------------------

(deftest spawn-test
  (testing "spawning an actor registers it"
    (actor/spawn! :test/actor {:kind "test"})
    (is (some #(= :test/actor %) (actor/actors)))))

(deftest spawn-idempotent-test
  (testing "spawning the same actor twice updates metadata"
    (actor/spawn! :idem {:kind "v1"})
    (actor/spawn! :idem {:kind "v2"})
    (is (= 1 (count (actor/actors))))))

;; ---------------------------------------------------------------------------
;; send / recv
;; ---------------------------------------------------------------------------

(deftest send-recv-test
  (testing "message appears in recipient mailbox"
    (actor/spawn! :sender)
    (actor/spawn! :receiver)
    (let [eid (actor/tell! :sender :receiver "test.hello" {:msg "hi"})]
      (is (string? eid))
      (let [messages (actor/recv :receiver)]
        (is (= 1 (count messages)))
        (is (= "test.hello" (:event/type (first messages))))
        (is (= {:msg "hi"} (:payload (first messages))))))))

(deftest send-fails-to-unknown-actor-test
  (testing "sending to unknown actor throws"
    (actor/spawn! :sender)
    (is (thrown-with-msg? js/Error #"Actor not found"
          (actor/tell! :sender :ghost "test.x" {})))))

;; ---------------------------------------------------------------------------
;; recv options
;; ---------------------------------------------------------------------------

(deftest recv-limit-test
  (testing "limit caps returned messages"
    (actor/spawn! :a)
    (actor/spawn! :b)
    (dotimes [i 5]
      (actor/tell! :a :b "test.msg" {:i i}))
    (is (= 2 (count (actor/recv :b {:limit 2}))))))

(deftest recv-filter-type-test
  (testing "filter-type selects by event/type"
    (actor/spawn! :a)
    (actor/spawn! :b)
    (actor/tell! :a :b "alpha" {})
    (actor/tell! :a :b "beta" {})
    (actor/tell! :a :b "alpha" {})
    (is (= 2 (count (actor/recv :b {:filter-type "alpha"}))))
    (is (= 1 (count (actor/recv :b {:filter-type "beta"}))))))

(deftest recv-since-id-test
  (testing "since-id returns messages after a given event"
    (actor/spawn! :a)
    (actor/spawn! :b)
    (let [eid1 (actor/tell! :a :b "test" {:n 1})
          _    (actor/tell! :a :b "test" {:n 2})
          _    (actor/tell! :a :b "test" {:n 3})]
      (let [after (actor/recv :b {:since-id eid1})]
        (is (= 2 (count after)))
        (is (= 2 (:n (:payload (first after)))))))))

;; ---------------------------------------------------------------------------
;; mailbox / clear
;; ---------------------------------------------------------------------------

(deftest mailbox-raw-test
  (testing "mailbox returns the raw vector"
    (actor/spawn! :x)
    (actor/tell! :a :x "test" {})
    (is (= 1 (count (actor/mailbox :x))))))

(deftest clear-test
  (testing "clear empties the mailbox"
    (actor/spawn! :x)
    (actor/tell! :a :x "test" {})
    (actor/clear! :x)
    (is (empty? (actor/mailbox :x)))))

;; ---------------------------------------------------------------------------
;; tell! / ask!
;; ---------------------------------------------------------------------------

(deftest tell-test
  (testing "tell sets delivery mode to tell"
    (actor/spawn! :a)
    (actor/spawn! :b)
    (actor/tell! :a :b "test" {})
    (is (= "tell" (:delivery/mode (first (actor/recv :b)))))))

(deftest ask-test
  (testing "ask sets delivery mode to ask"
    (actor/spawn! :a)
    (actor/spawn! :b)
    (actor/ask! :a :b "test" {})
    (is (= "ask" (:delivery/mode (first (actor/recv :b)))))))
