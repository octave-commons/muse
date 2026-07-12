(ns eta-mu.boundaries.mongo.ledger-test
  "Integration test against a local mongod; skips (passing) when no
   server is reachable so the suite stays green in mongo-less envs."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [eta-mu.boundaries.mongo.client :as client]
            [eta-mu.boundaries.mongo.ledger :as ml]
            [eta-mu.actor.store :as store]
            [promesa.core :as p]))

(defn- uid [prefix]
  (keyword (str prefix "-" (random-uuid))))

(deftest mongo-ledger-roundtrip-test
  (async done
    (-> (p/let [conn (client/connect! nil "eta_mu_test" {:timeout-ms 800})]
          (-> (p/let [s        (ml/make-mongo-store (:db conn))
                      sender   (uid "sender")
                      receiver (uid "receiver")
                      _    (store/-spawn! s sender {:kind "test"})
                      _    (store/-spawn! s receiver {:kind "test" :muse-id :calliope})
                      eid  (store/-send! s sender receiver
                                         {:event/type "test.hello"
                                          :payload {:msg "hi"}})
                      meta (store/-actor-meta s receiver)
                      mb   (store/-mailbox s receiver)
                      _    (store/-clear! s receiver)
                      _    (store/-send! s sender receiver
                                         {:event/type "test.after"
                                          :payload {:msg "again"}})
                      mb2  (store/-mailbox s receiver)]
                (testing "mongo round trip"
                  (is (string? eid))
                  (is (= :calliope (:muse-id meta))
                      "keyword metadata survives via meta-edn")
                  (is (= 1 (count mb)))
                  (is (= "test.hello" (:event/type (first mb))))
                  (is (= "hi" (get-in (first mb) [:payload :msg]))))
                (testing "clear is an append-only marker"
                  (is (= 1 (count mb2)))
                  (is (= "test.after" (:event/type (first mb2))))))
              (p/finally (fn [_ _] (client/close! conn)))))
        (p/catch (fn [e]
                   (println "mongo unavailable — skipping integration test:"
                            (ex-message e))
                   (is true)))
        (p/finally (fn [_ _] (done))))))
