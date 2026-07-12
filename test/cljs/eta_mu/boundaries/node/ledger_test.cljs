(ns eta-mu.boundaries.node.ledger-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [eta-mu.actor :as actor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.boundaries.node.ledger :as ledger]
            [promesa.core :as p]))

(defn- fresh-root []
  (str "target/ledger-test/" (random-uuid)))

(defn- fresh-store! []
  (actor/init-store! (ledger/make-ledger-store (fresh-root))))

(defn- run-async [done body]
  (-> (body)
      (p/catch (fn [e] (is false (str "unexpected rejection: " e))))
      (p/finally (fn [_ _] (done)))))

;; ---------------------------------------------------------------------------
;; Round trip
;; ---------------------------------------------------------------------------

(deftest send-recv-roundtrip-test
  (async done
    (fresh-store!)
    (run-async done
      #(p/let [_  (actor/spawn! :sender)
               _  (actor/spawn! :receiver)
               _  (actor/tell! :sender :receiver "test.hello" {:msg "hi"})
               ms (actor/recv :receiver)]
         (testing "events survive the file round trip"
           (is (= 1 (count ms)))
           (is (= "test.hello" (:event/type (first ms))))
           (is (= {:msg "hi"} (:payload (first ms))))
           (is (= "sender" (get-in (first ms) [:event/from :actor-id]))))))))

(deftest send-to-unknown-rejects-test
  (async done
    (fresh-store!)
    (-> (p/let [_ (actor/spawn! :sender)]
          (actor/tell! :sender :ghost "test.x" {}))
        (p/then (fn [_] (is false "should reject")))
        (p/catch (fn [e] (is (re-find #"Actor not found" (ex-message e)))))
        (p/finally (fn [_ _] (done))))))

;; ---------------------------------------------------------------------------
;; Durability: a second store over the same root sees everything
;; ---------------------------------------------------------------------------

(deftest ledger-persists-across-stores-test
  (async done
    (let [root (str "target/ledger-test/" (random-uuid))]
      (actor/init-store! (ledger/make-ledger-store root))
      (run-async done
        #(p/let [_  (actor/spawn! :muse/a {:kind "muse"})
                 _  (actor/spawn! :muse/a.discover.1 {:kind "phase" :muse-id :muse/a})
                 _  (actor/tell! :muse/a :muse/a.discover.1 "phase.observation" {:n 1})]
           ;; reopen the same root as if a later session
           (actor/init-store! (ledger/make-ledger-store root))
           (p/let [ids  (actor/actors)
                   meta (actor/actor-meta :muse/a.discover.1)
                   mb   (actor/mailbox :muse/a.discover.1)]
             (testing "registry and mailboxes are durable"
               (is (= 2 (count ids)))
               (is (= "phase" (:kind meta)))
               (is (= 1 (count mb)))
               (is (= {:n 1} (:payload (first mb)))))))))))

;; ---------------------------------------------------------------------------
;; recv options + clear parity with the memory store
;; ---------------------------------------------------------------------------

(deftest recv-options-test
  (async done
    (fresh-store!)
    (run-async done
      #(p/let [_      (actor/spawn! :a)
               _      (actor/spawn! :b)
               eid1   (actor/tell! :a :b "alpha" {:n 1})
               _      (actor/tell! :a :b "beta" {:n 2})
               _      (actor/tell! :a :b "alpha" {:n 3})
               alphas (actor/recv :b {:filter-type "alpha"})
               after  (actor/recv :b {:since-id eid1})
               _      (actor/clear! :b)
               empty-mb (actor/mailbox :b)]
         (testing "filter/since/clear behave like the memory store"
           (is (= 2 (count alphas)))
           (is (= 2 (count after)))
           (is (empty? empty-mb)))))))

;; ---------------------------------------------------------------------------
;; Muse layer on top of file ledgers
;; ---------------------------------------------------------------------------

(deftest muse-over-ledger-test
  (async done
    (fresh-store!)
    (run-async done
      #(p/let [_      (muse/spawn-muse! :calliope)
               p1     (muse/spawn-phase! :calliope :discover)
               _      (muse/influence! :calliope p1 :begin {:quest "find the anchors"})
               active (muse/list-active :calliope)
               msgs   (actor/recv p1)]
         (testing "muse influence lands in the phase's durable ledger"
           (is (= [p1] active))
           (is (= "muse.influence.begin" (:event/type (first msgs)))))))))

;; ---------------------------------------------------------------------------
;; Watching
;; ---------------------------------------------------------------------------

(deftest watch-once-resolves-on-append-test
  (async done
    (let [store (ledger/make-ledger-store (fresh-root))]
      (actor/init-store! store)
      (run-async done
        #(p/let [_ (actor/spawn! :watcher)
                 _ (actor/spawn! :other)]
           (let [waiting (ledger/watch-once store :watcher
                                            (fn [e] (= "wake.up" (:event/type e)))
                                            3000)]
             (p/let [_ (actor/tell! :other :watcher "noise" {})
                     _ (actor/tell! :other :watcher "wake.up" {:reason "test"})
                     hit waiting]
               (testing "watcher resolves with the first matching event"
                 (is (some? hit))
                 (is (= "wake.up" (:event/type hit)))
                 (is (= {:reason "test"} (:payload hit)))))))))))

(deftest watch-once-times-out-test
  (async done
    (let [store (ledger/make-ledger-store (fresh-root))]
      (actor/init-store! store)
      (run-async done
        #(p/let [_   (actor/spawn! :lonely)
                 hit (ledger/watch-once store :lonely (constantly true) 300)]
           (testing "watcher resolves nil when nothing arrives"
             (is (nil? hit))))))))
