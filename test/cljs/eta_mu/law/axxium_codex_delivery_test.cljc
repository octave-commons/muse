;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.law.axxium-codex-delivery-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [eta-mu.law.axxium-codex-delivery :as delivery]))

;; Synthetic adapter assertions, not live identity or queue proof.
(def ^:private bound
  {:origin "https://axxium.test"
   :actor-did "did:web:axxium.test:actors:codex"
   :mailbox-did "did:web:axxium.test:actors:codex"
   :host-id "fixture-host"
   :thread-id "11111111-1111-4111-8111-111111111111"
   :generation 1})

(def ^:private proposal
  {:approved-binding bound :observed-binding bound :binding-current? true
   :target-state :idle :submission-id "fixture-submission-1"
   :attempt-state :initial :reconciled? false
   :capability {:mode :atomic-queue :verified? true :no-steer? true
                :no-start? true :idempotent? true :reconcilable? true}})

(deftest queue-only-positive-controls
  (testing "atomic enqueue does not steer or start either known target state"
    (doseq [state [:idle :active]]
      (is (true? (delivery/queue-admissible? (assoc proposal :target-state state))))))
  (testing "only a conclusively rejected attempt can be retried"
    (is (true? (delivery/queue-admissible?
                (assoc proposal :attempt-state :rejected :reconciled? true))))))

(deftest exact-binding-and-generation-are-required
  (doseq [[field value] [[:origin "https://other.test"]
                         [:actor-did "did:web:axxium.test:actors:other"]
                         [:mailbox-did "did:web:axxium.test:actors:other"]
                         [:host-id "other-host"]
                         [:thread-id "22222222-2222-4222-8222-222222222222"]
                         [:generation 2]]]
    (is (false? (delivery/queue-admissible?
                 (assoc-in proposal [:observed-binding field] value)))
        (str "changed binding coordinate " field)))
  (is (false? (delivery/queue-admissible? (assoc proposal :binding-current? false))))
  (doseq [field (keys bound)]
    (is (false? (delivery/queue-admissible?
                 (update proposal :approved-binding dissoc field)))))
  (is (false? (delivery/queue-admissible?
               (-> proposal
                   (assoc-in [:approved-binding :generation] 0)
                   (assoc-in [:observed-binding :generation] 0))))))

(deftest unknown-and-sensitive-target-states-stay-held
  (doseq [state [:pending :approval :compacting :unknown nil "idle"]]
    (is (false? (delivery/queue-admissible? (assoc proposal :target-state state))))))

(deftest queue-capability-cannot-be-inferred-from-send-or-exit-zero
  (doseq [mode [:send-now :cli-dispatch :unverified]]
    (is (false? (delivery/queue-admissible?
                 (assoc-in proposal [:capability :mode] mode)))))
  (doseq [field [:verified? :no-steer? :no-start? :idempotent? :reconcilable?]]
    (is (false? (delivery/queue-admissible?
                 (assoc-in proposal [:capability field] false))))))

(deftest ambiguous-restart-custody-cannot-authorize-redispatch
  (doseq [state [:accepted :uncertain]
          reconciled? [true false]]
    (is (false? (delivery/queue-admissible?
                 (assoc proposal :attempt-state state :reconciled? reconciled?)))))
  (is (false? (delivery/queue-admissible?
               (assoc proposal :attempt-state :rejected :reconciled? false)))))

(deftest malformed-or-expanded-proposals-cannot-count-as-queue-admission
  (doseq [value [nil {} [] '(:x 1 :y) '(1 2 3) '(1) [1 2 3] 42 "invalid"
                 (assoc proposal :submission-id " ")
                 (dissoc proposal :submission-id)
                 (assoc proposal :mailbox-ack true)
                 (assoc-in proposal [:observed-binding :owner-root] "inferred")]]
    (is (false? (delivery/queue-admissible? value)))))
