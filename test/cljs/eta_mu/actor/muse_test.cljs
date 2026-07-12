(ns eta-mu.actor.muse-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.actor.memory :as mem]))

(use-fixtures :each
  (fn [f]
    (actor/init-store! (mem/make-memory-store))
    (f)))

;; ---------------------------------------------------------------------------
;; Muse lifecycle
;; ---------------------------------------------------------------------------

(deftest spawn-muse-test
  (testing "muse is registered as an actor"
    (muse/spawn-muse! :muse/alice)
    (is (some #(= :muse/alice %) (actor/actors)))))

(deftest spawn-phase-test
  (testing "phase is spawned under a muse"
    (muse/spawn-muse! :muse/bob)
    (let [phase-id (muse/spawn-phase! :muse/bob :discover)]
      (is (some #(= phase-id %) (actor/actors)))
      (is (re-matches #"[^.]+\.discover\.\d+" (name phase-id))))))

(deftest spawn-multiple-phases-test
  (testing "multiple phases get distinct sequence numbers"
    (muse/spawn-muse! :muse/carol)
    (let [p1 (muse/spawn-phase! :muse/carol :discover)
          p2 (muse/spawn-phase! :muse/carol :discover)]
      (is (not= p1 p2))
      (is (re-matches #".*\.1" (name p1)))
      (is (re-matches #".*\.2" (name p2))))))

;; ---------------------------------------------------------------------------
;; Phase listing
;; ---------------------------------------------------------------------------

(deftest list-phases-test
  (testing "list-phases returns all phases for a muse"
    (muse/spawn-muse! :muse/dave)
    (muse/spawn-phase! :muse/dave :discover)
    (muse/spawn-phase! :muse/dave :scheme)
    (is (= 2 (count (muse/list-phases :muse/dave))))))

(deftest list-active-test
  (testing "active phases have messages"
    (muse/spawn-muse! :muse/eve)
    (let [p1 (muse/spawn-phase! :muse/eve :discover)
          p2 (muse/spawn-phase! :muse/eve :scheme)]
      (actor/tell! :muse/eve p1 "test" {})
      (is (= 1 (count (muse/list-active :muse/eve))))
      (is (= [p1] (muse/list-active :muse/eve))))))

(deftest list-idle-test
  (testing "idle phases have empty mailboxes"
    (muse/spawn-muse! :muse/frank)
    (let [p1 (muse/spawn-phase! :muse/frank :discover)
          p2 (muse/spawn-phase! :muse/frank :scheme)]
      (actor/tell! :muse/frank p1 "test" {})
      (is (= [p2] (muse/list-idle :muse/frank))))))

;; ---------------------------------------------------------------------------
;; Message operations
;; ---------------------------------------------------------------------------

(deftest tail-test
  (testing "tail returns last N messages"
    (muse/spawn-muse! :muse/grace)
    (let [p (muse/spawn-phase! :muse/grace :discover)]
      (dotimes [i 5]
        (actor/tell! :muse/grace p "test" {:i i}))
      (is (= 3 (count (muse/tail p 3))))
      (is (= 4 (:i (:payload (last (muse/tail p 3)))))))))

(deftest head-test
  (testing "head returns first N messages"
    (muse/spawn-muse! :muse/hank)
    (let [p (muse/spawn-phase! :muse/hank :discover)]
      (dotimes [i 5]
        (actor/tell! :muse/hank p "test" {:i i}))
      (is (= 2 (count (muse/head p 2))))
      (is (= 0 (:i (:payload (first (muse/head p 2)))))))))

(deftest filter-events-test
  (testing "filter-events selects by event type"
    (muse/spawn-muse! :muse/ida)
    (let [p (muse/spawn-phase! :muse/ida :discover)]
      (actor/tell! :muse/eve p "phase.observation" {:note "saw something"})
      (actor/tell! :muse/eve p "phase.evidence" {:proof "found it"})
      (actor/tell! :muse/eve p "phase.observation" {:note "another"})
      (is (= 2 (count (muse/observations p))))
      (is (= 1 (count (muse/evidence p)))))))

(deftest command-test
  (testing "command sends a muse.command.* message"
    (muse/spawn-muse! :muse/jake)
    (let [p (muse/spawn-phase! :muse/jake :discover)]
      (muse/command! :muse/jake p :begin {:task "find stuff"})
      (let [msgs (actor/recv p)]
        (is (= 1 (count msgs)))
        (is (= "muse.command.begin" (:event/type (first msgs))))))))
