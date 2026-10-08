(ns eta-mu.extern.review-invocation-host-read-test
  "Public selected-profile JSON round trips, using synthetic native receipts."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [eta-mu.extern.review-invocation :as extern]
            [eta-mu.extern.review-invocation-test :as public-fixture]
            [eta-mu.law.review-invocation-test :as fixture]
            [eta-mu.law.review-invocation-host-read-test :as host]))

(def profile
  #js {:id "eta-mu.restricted-host-read/v1"
       :sourceSha256 "2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7"})

(defn prepared-context []
  (extern/prepare-review-invocation-context-with-host-read-profile
   "diff" (public-fixture/json fixture/manifest)
   (clj->js (:review-tools fixture/context))
   (:submission-file fixture/context) profile))

(defn raw-response [events]
  (str (str/join "\n"
                 (map (fn [event]
                        (public-fixture/json
                         (cond-> (public-fixture/host-event event)
                           (get-in event [:part :state :metadata])
                           (assoc-in [:part :state :metadata]
                                     (get-in event [:part :state :metadata])))))
                      events)) "\n"))

(defn verify [events context]
  (js->clj (extern/verify-review-invocation
            (raw-response events) (public-fixture/json fixture/submission) context)
           :keywordize-keys true))

(deftest selected-prepare-output-round-trips-through-public-verification
  (let [context (prepared-context)
        selected (aget context "hostReadProfile")]
    (is (= ["id" "sourceSha256"] (vec (.sort (js/Object.keys selected)))))
    (is (= (js->clj profile) (js->clj selected)))
    (doseq [wire [context (js/JSON.stringify context)
                  (.encode (js/TextEncoder.) (js/JSON.stringify context))]]
      (let [result (verify (fixture/trace) wire)]
        (is (:ok result))
        (is (= "verified-review-invocation" (:code result)))
        (is (= "session-fixture" (get-in result [:acceptedInvocation :sessionID])))))))

(deftest selected-public-verification-enforces-supporting-read-closure
  (let [context (prepared-context)
        complete (host/with-reads [(host/complete-read 40)])
        contiguous (host/with-reads [(host/read-receipt 40 1 ["a" "b"] 4 true 1 false)
                                    (host/read-receipt 41 3 ["c" "d"] 4 false 3 false)])]
    (is (:ok (verify complete context)))
    (is (:ok (verify contiguous context)))
    (doseq [[events code]
            [[(host/with-reads [(host/read-receipt 40 1 ["a" "b"] 4 true 1 false)])
              "host-read-unfinished"]
             [(host/with-reads [(host/read-receipt 40 735 ["partial"] 735 false 735 false)])
              "host-read-start-offset"]]]
      (let [result (verify events context)]
        (is (false? (:ok result)))
        (is (= code (:code result)))
        (is (nil? (:acceptedInvocation result)))))))

(deftest malformed-profile-wire-keys-cannot-acquire-public-acceptance
  (doseq [selected [{:id "eta-mu.restricted-host-read/v1"
                    :source-sha256 "2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7"}
                   {:id "eta-mu.restricted-host-read/v1" :sourceSha256 "wrong"}]]
    (let [context (js/JSON.parse (js/JSON.stringify (prepared-context)))]
      (aset context "hostReadProfile" (clj->js selected))
      (let [result (verify (fixture/trace) context)]
        (is (false? (:ok result)))
        (is (= "structured-input-unestablished" (:code result)))
        (is (nil? (:acceptedInvocation result)))))))

(deftest selected-public-length-route-retains-profile-and-refuses-open-reads
  (let [context (js/Object.assign (prepared-context)
                                  #js {:fullDiff "diff" :sessionID "session-fixture"})
        events (fixture/length-events)
        with-read (fn [read]
                    (fixture/ordered-fixture-events
                     (concat (pop events) [read (last events)])))]
    (doseq [[calls eligible]
            [[(with-read (host/complete-read 40)) true]
             [(with-read (host/read-receipt 40 1 ["a" "b"] 4 true 1 false)) false]]]
      (let [raw (raw-response calls)
            result (public-fixture/classify-length
                    raw nil context (public-fixture/length-custody-js raw context))]
        (is (= eligible (:eligible result)))
        (is (not (contains? result :acceptedInvocation)))
        (is (not (contains? result :approval)))))))


(defn prepare-with-raw-profile [raw-profile]
  (extern/prepare-review-invocation-context-with-host-read-profile
   "diff" (public-fixture/json fixture/manifest)
   (clj->js (:review-tools fixture/context))
   (:submission-file fixture/context) raw-profile))

(deftest raw-profile-object-json-and-utf8-keep-supporting-read-enforcement
  (let [encoded (js/JSON.stringify profile)
        complete (host/with-reads [(host/complete-read 40)])
        unfinished (host/with-reads
                    [(host/read-receipt 40 1 ["a" "b"] 4 true 1 false)])]
    (doseq [wire [profile encoded (.encode (js/TextEncoder.) encoded)]]
      (let [context (prepare-with-raw-profile wire)
            refused (verify unfinished context)]
        (is (:ok (verify complete context)))
        (is (false? (:ok refused)))
        (is (= "host-read-unfinished" (:code refused)))))))

(deftest invalid-raw-profile-preparation-has-one-safe-typed-refusal
  (let [digest "2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7"
        duplicate (str "{\"id\":\"eta-mu.restricted-host-read/v1\","
                       "\"id\":\"eta-mu.restricted-host-read/v1\","
                       "\"sourceSha256\":\"" digest "\"}")]
    (doseq [wire [nil #js []
                  #js {:id "eta-mu.restricted-host-read/v1"
                       :sourceSha256 digest :extra true}
                  #js {:id "eta-mu.restricted-host-read/v1"
                       :source-sha256 digest}
                  #js {:id "unsupported-profile" :sourceSha256 digest}
                  #js {:id "eta-mu.restricted-host-read/v1"
                       :sourceSha256 "wrong"}
                  "{" "null" "[]" duplicate
                  (js/Uint8Array. #js [255])
                  (.encode (js/TextEncoder.) "{")]]
      (let [error (try (prepare-with-raw-profile wire) nil
                       (catch :default e e))]
        (is (instance? js/Error error))
        (is (= "unestablished-review-trace: invalid restricted HOST read context"
               (.-message error)))))))
