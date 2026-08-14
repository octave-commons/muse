(ns eta-mu.domain.review-test
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.review :as review]))

(def sample-diff
  (clojure.string/join
   "\n"
   ["diff --git a/src/example.js b/src/example.js"
    "--- a/src/example.js"
    "+++ b/src/example.js"
    "@@ -10,4 +10,5 @@ function example() {"
    " context"
    "-old"
    "+new"
    "+another"
    " tail"
    "diff --git a/src/other.js b/src/other.js"
    "--- a/src/other.js"
    "+++ b/src/other.js"
    "@@ -1 +1,2 @@"
    "+added"
    " kept"]))

(defn- begun []
  (review/begin sample-diff))

(defn- through-stage
  "Advance session to the given stage by recording evidence."
  [session stage]
  (loop [s session]
    (if (= (:stage s) stage)
      s
      (let [result (review/record-evidence s (:stage s) (str "note for " (name (:stage s))))]
        (assert (:ok? result) (:error result))
        (recur (:session result))))))

(deftest parse-diff-added-lines-indexes-only-added-head-lines
  (let [indexed (review/parse-diff-added-lines sample-diff)]
    (is (= #{11 12} (get indexed "src/example.js")))
    (is (= #{1} (get indexed "src/other.js")))
    (is (= 2 (count indexed)))))

(deftest parse-diff-added-lines-handles-empty-input
  (is (= {} (review/parse-diff-added-lines "")))
  (is (= {} (review/parse-diff-added-lines nil))))

(deftest begin-starts-at-deterministic-stage
  (let [session (begun)]
    (is (= :deterministic (:stage session)))
    (is (= 2 (get-in session [:diff-stats :files])))
    (is (false? (get-in session [:diff-stats :truncated?])))))

(deftest record-evidence-enforces-stage-order
  (let [session (begun)
        out-of-order (review/record-evidence session :map-change "nope")]
    (is (false? (:ok? out-of-order)))
    (is (re-find #"deterministic" (:error out-of-order))))
  (let [session (begun)
        ok (review/record-evidence session :deterministic "gates read")]
    (is (:ok? ok))
    (is (= :map-change (get-in ok [:session :stage])))))

(deftest propose-finding-validates-against-changed-lines
  (let [session (through-stage (begun) :generate-candidates)
        base {:id "f1" :severity "high" :category "semantic-regression"
              :claim "drops caller result" :path "src/example.js" :line 11
              :body "impact and fix" :confidence 0.9 :blocking true}
        {:keys [session] :as first-result} (review/propose-finding session base)]
    (is (:ok? first-result))
    (is (false? (:ok? (review/propose-finding session (assoc base :id "f2" :line 10)))))
    (is (false? (:ok? (review/propose-finding session (assoc base :id "f3" :path "src/absent.js")))))
    (is (false? (:ok? (review/propose-finding session base))))))

(deftest propose-finding-rejects-blocking-on-low-severity
  (let [session (through-stage (begun) :generate-candidates)
        result (review/propose-finding session {:id "f1" :severity "low" :category "test-gap"
                                                :claim "x" :path "src/example.js" :line 11
                                                :body "b" :confidence 0.9 :blocking true})]
    (is (false? (:ok? result)))
    (is (re-find #"critical or high" (:error result)))))

(deftest classify-finding-only-at-adversarial-validate
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.5 :blocking false})]
    (is (false? (:ok? (review/classify-finding session "f1" "confirmed" "too early"))))
    (let [session (through-stage session :adversarial-validate)
          ok (review/classify-finding session "f1" "rejected" "disproved by guard")]
      (is (:ok? ok))
      (is (= :rejected (get-in ok [:session :candidates "f1" :status]))))))

(deftest submission-requires-publish-stage-and-classified-candidates
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "high" :category "security"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.95 :blocking true})]
    (is (false? (:ok? (review/submission session "summary"))))
    (let [session (through-stage session :publish)]
      (is (false? (:ok? (review/submission session "summary")))))))

(deftest submission-derives-request-changes-from-blocking-confirmed
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "high" :category "security"
                                                           :claim "fails open" :path "src/example.js" :line 11
                                                           :body "fix it" :confidence 0.95 :blocking true})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "confirmed" "trace verified")
        session (through-stage session :publish)
        result (review/submission session "One blocking defect.")]
    (is (:ok? result))
    (is (= "REQUEST_CHANGES" (get-in result [:envelope :event])))
    (is (= [{:path "src/example.js" :line 11 :side "RIGHT"
             :severity "high" :blocking true :body "fix it"}]
           (get-in result [:envelope :comments])))))

(deftest submission-derives-approve-with-no-confirmed-findings
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 12
                                                           :body "b" :confidence 0.4 :blocking false})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "rejected" "not a defect")
        session (through-stage session :publish)
        result (review/submission session "Clean.")]
    (is (:ok? result))
    (is (= "APPROVE" (get-in result [:envelope :event])))
    (is (= [] (get-in result [:envelope :comments])))))

(deftest submission-rejects-underconfident-confirmations
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.5 :blocking false})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "confirmed" "plausible")
        session (through-stage session :publish)
        result (review/submission session "summary")]
    (is (false? (:ok? result)))
    (is (re-find #"confidence threshold" (:error result)))))
