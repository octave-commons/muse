(ns eta-mu.actor.muse-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]
            [eta-mu.actor.muse :as muse]
            [promesa.core :as p]))

(use-fixtures :each
  {:before #(actor/init-store! (mem/make-memory-store))})

(defn- run-async [done body]
  (-> (body)
      (p/catch (fn [e] (is false (str "unexpected rejection: " e))))
      (p/finally (fn [_ _] (done)))))

;; ---------------------------------------------------------------------------
;; Muse lifecycle
;; ---------------------------------------------------------------------------

(deftest spawn-muse-test
  (async done
    (run-async done
      #(p/let [_   (muse/spawn-muse! :muse/alice)
               ids (actor/actors)]
         (testing "muse is registered as an actor"
           (is (some #{:muse/alice} ids)))))))

(deftest spawn-phase-test
  (async done
    (run-async done
      #(p/let [_        (muse/spawn-muse! :muse/bob)
               phase-id (muse/spawn-phase! :muse/bob :discover)
               ids      (actor/actors)]
         (testing "phase is spawned under a muse"
           (is (some #{phase-id} ids))
           (is (re-matches #"[^.]+\.discover\.\d+" (name phase-id))))))))

(deftest spawn-multiple-phases-test
  (async done
    (run-async done
      #(p/let [_  (muse/spawn-muse! :muse/carol)
               p1 (muse/spawn-phase! :muse/carol :discover)
               p2 (muse/spawn-phase! :muse/carol :discover)]
         (testing "multiple phases get distinct sequence numbers"
           (is (not= p1 p2))
           (is (re-matches #".*\.1" (name p1)))
           (is (re-matches #".*\.2" (name p2))))))))

;; ---------------------------------------------------------------------------
;; Phase listing
;; ---------------------------------------------------------------------------

(deftest list-phases-test
  (async done
    (run-async done
      #(p/let [_      (muse/spawn-muse! :muse/dave)
               _      (muse/spawn-phase! :muse/dave :discover)
               _      (muse/spawn-phase! :muse/dave :scheme)
               phases (muse/list-phases :muse/dave)]
         (testing "list-phases returns all phases for a muse"
           (is (= 2 (count phases))))))))

(deftest list-active-test
  (async done
    (run-async done
      #(p/let [_      (muse/spawn-muse! :muse/eve)
               p1     (muse/spawn-phase! :muse/eve :discover)
               _p2    (muse/spawn-phase! :muse/eve :scheme)
               _      (actor/tell! :muse/eve p1 "test" {})
               active (muse/list-active :muse/eve)]
         (testing "active phases have messages"
           (is (= [p1] active)))))))

(deftest list-idle-test
  (async done
    (run-async done
      #(p/let [_    (muse/spawn-muse! :muse/frank)
               p1   (muse/spawn-phase! :muse/frank :discover)
               p2   (muse/spawn-phase! :muse/frank :scheme)
               _    (actor/tell! :muse/frank p1 "test" {})
               idle (muse/list-idle :muse/frank)]
         (testing "idle phases have empty ledgers"
           (is (= [p2] idle)))))))

;; ---------------------------------------------------------------------------
;; Observation
;; ---------------------------------------------------------------------------

(deftest tail-test
  (async done
    (run-async done
      #(p/let [_    (muse/spawn-muse! :muse/grace)
               p    (muse/spawn-phase! :muse/grace :discover)
               _    (p/all (for [i (range 5)]
                             (actor/tell! :muse/grace p "test" {:i i})))
               last3 (muse/tail p 3)]
         (testing "tail returns last N events"
           (is (= 3 (count last3)))
           (is (= 4 (:i (:payload (last last3))))))))))

(deftest head-test
  (async done
    (run-async done
      #(p/let [_     (muse/spawn-muse! :muse/hank)
               p     (muse/spawn-phase! :muse/hank :discover)
               _     (p/all (for [i (range 5)]
                              (actor/tell! :muse/hank p "test" {:i i})))
               first2 (muse/head p 2)]
         (testing "head returns first N events"
           (is (= 2 (count first2)))
           (is (= 0 (:i (:payload (first first2))))))))))

(deftest filter-events-test
  (async done
    (run-async done
      #(p/let [_    (muse/spawn-muse! :muse/ida)
               p    (muse/spawn-phase! :muse/ida :discover)
               _    (actor/tell! :muse/ida p "phase.observation" {:note "saw something"})
               _    (actor/tell! :muse/ida p "phase.evidence" {:proof "found it"})
               _    (actor/tell! :muse/ida p "phase.observation" {:note "another"})
               obs  (muse/observations p)
               evid (muse/evidence p)]
         (testing "filter-events selects by event type"
           (is (= 2 (count obs)))
           (is (= 1 (count evid))))))))

;; ---------------------------------------------------------------------------
;; Influence
;; ---------------------------------------------------------------------------

(deftest influence-test
  (async done
    (run-async done
      #(p/let [_    (muse/spawn-muse! :muse/jake)
               p    (muse/spawn-phase! :muse/jake :discover)
               _    (muse/influence! :muse/jake p :begin {:task "find stuff"})
               msgs (actor/recv p)]
         (testing "influence appends a muse.influence.* record"
           (is (= 1 (count msgs)))
           (is (= "muse.influence.begin" (:event/type (first msgs))))
           (is (= "tell" (:delivery/mode (first msgs)))))))))
