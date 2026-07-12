(ns eta-mu.domain.receipts-test
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.receipts :as receipts]))

(def sample-event
  {:ts      "2026-04-19T05:00:00.000Z"
   :kind    :observation
   :repo    "/home/user/projects/eta-mu"
   :origin  "pi"
   :owner   "receipt-river"
   :dod     "receipt-river"
   :pi      "0.63.1"
   :host    "local"
   :manifest "none"
   :refs    "none"})

;; ============================================================
;; EDN event format
;; ============================================================

(deftest edn-event-produces-valid-edn
  (testing "round-trips through parse-edn-event"
    (let [line (receipts/edn-event sample-event)
          back (receipts/parse-edn-event line)]
      (is (string? line))
      (is (map? back))
      (is (= (:kind sample-event) (:kind back)))
      (is (= (:repo sample-event) (:repo back)))
      (is (= (:ts sample-event)   (:ts back))))))

(deftest edn-event-is-single-line
  (let [line (receipts/edn-event sample-event)]
    (is (not (.includes line "\n")))))

(deftest edn-event-contains-required-keys
  (let [line (receipts/edn-event sample-event)]
    (doseq [k receipts/required-keys]
      (is (.includes line (name k))))))

(deftest edn-event-kind-is-keyword
  (let [back (receipts/parse-edn-event (receipts/edn-event sample-event))]
    (is (keyword? (:kind back)))))

(deftest parse-edn-event-returns-nil-on-garbage
  (is (nil? (receipts/parse-edn-event "not edn at all {{{")))
  (is (nil? (receipts/parse-edn-event "")))
  (is (nil? (receipts/parse-edn-event nil))))

(deftest parse-edn-event-returns-nil-on-non-map
  (is (nil? (receipts/parse-edn-event "[1 2 3]")))
  (is (nil? (receipts/parse-edn-event "42"))))

(deftest edn-event-optional-keys-omitted-when-nil
  (let [line (receipts/edn-event sample-event)]
    (is (not (.includes line ":note")))
    (is (not (.includes line ":tests")))
    (is (not (.includes line ":decisions")))
    (is (not (.includes line ":drift")))))

(deftest edn-event-optional-keys-present-when-supplied
  (let [e    (assoc sample-event :note "fixed the thing" :tests "42 pass 0 fail")
        back (receipts/parse-edn-event (receipts/edn-event e))]
    (is (= "fixed the thing" (:note back)))
    (is (= "42 pass 0 fail" (:tests back)))))

;; ============================================================
;; Record building
;; ============================================================

(deftest build-record-defaults
  (let [record (receipts/build-record {:action "append" :note "hi"}
                                      "/repo" "2026-07-11T00:00:00.000Z")]
    (is (= :observation (:kind record)))
    (is (= "/repo" (:repo record)))
    (is (= "opencode" (:origin record)))
    (is (= "2026-07-11T00:00:00.000Z" (:ts record)))
    (is (= "hi" (:note record)))
    (is (nil? (:tests record)))))

(deftest build-record-normalizes-kind
  (let [record (receipts/build-record {:kind ":test-run"}
                                      "/repo" "2026-07-11T00:00:00.000Z")]
    (is (= :test-run (:kind record)))))

(deftest build-record-rejects-unknown-kind
  (is (thrown-with-msg? js/Error #"Unknown receipt kind"
        (receipts/build-record {:kind "bogus"} "/repo" "now"))))

;; ============================================================
;; Validation
;; ============================================================

(deftest validate-lines-accepts-valid-ledger
  (let [lines [(receipts/edn-event sample-event)
               (receipts/edn-event (assoc sample-event :kind :decision))]
        result (receipts/validate-lines lines)]
    (is (:ok result))
    (is (= 2 (:count result)))))

(deftest validate-lines-reports-failures
  (let [lines [(receipts/edn-event sample-event)
               "garbage {{{"
               (receipts/edn-event (dissoc sample-event :owner))]
        result (receipts/validate-lines lines)]
    (is (not (:ok result)))
    (is (= 2 (count (:failures result))))
    (is (= [2 3] (mapv :line-number (:failures result))))))

(deftest validate-event-flags-bad-ts
  (let [line (receipts/edn-event (assoc sample-event :ts "not-a-date"))
        result (receipts/validate-event line 1)]
    (is (not (:ok result)))
    (is (some #(.includes % "invalid ts") (:errors result)))))
