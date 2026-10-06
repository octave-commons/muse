(ns eta-mu.extern.review-invocation-test
  "JSON controls are explicitly synthetic, using observed OpenCode syntax;
   optional retained authentic DATA probes execute only the new owned code."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [eta-mu.extern.review-invocation :as extern]
            [eta-mu.law.review-invocation-test :as fixture]
            ["node:fs" :as fs]
            ["node:crypto" :as crypto]))

(defn host-event [event]
  (let [p (:part event) state (:state p)]
    (cond-> {:type (:type event) :timestamp (:timestamp event) :sessionID (:session-id event)
             :part (cond-> {:type (:type p) :id (:id p) :sessionID (:session-id p)}
                     (= "tool_use" (:type event))
                     (assoc :callID (:call-id p) :tool (:tool p)
                            :state {:status (:status state) :input (:input state)
                                    :output (if (str/starts-with? (:tool p) "review_")
                                              (js/JSON.stringify (clj->js (:output state)))
                                              (:output state))})
                     (:reason p) (assoc :reason (:reason p)))}
      (= "text" (:type event)) (assoc-in [:part :text] (:text p)))))

(defn json [x] (js/JSON.stringify (clj->js x)))
(defn response [events] (str (str/join "\n" (map #(json (host-event %)) events)) "\n"))
(defn context-json []
  (js/JSON.stringify
   (extern/prepare-review-invocation-context "diff" (json fixture/manifest)
                                             (clj->js (:review-tools fixture/context))
                                             (:submission-file fixture/context))))
(defn verify [raw]
  (js->clj (extern/verify-review-invocation raw (json fixture/submission) (context-json)) :keywordize-keys true))

(deftest public-api-has-positive-and-refusal-bindings
  (let [result (verify (response (fixture/trace)))]
    (is (:ok result))
    (is (nil? (:reason result)))
    (is (= "session-fixture" (get-in result [:acceptedInvocation :sessionID])))
    (is (= "call-9" (get-in result [:acceptedInvocation :submissionCallID])))
    (is (= 64 (count (get-in result [:acceptedInvocation :responseSha256]))))
    (is (= 64 (count (get-in result [:acceptedInvocation :submissionSha256])))))
  (let [events (fixture/append-calls (fixture/trace) [(fixture/tool-event 10 "review_read_diff_chunk" {:id 1} fixture/read-output)])
        result (verify (response events))]
    (is (= "stale-review-coverage" (:reason result)))
    (is (= (:reason result) (:reasonKind result)))
    (is (nil? (:acceptedInvocation result)))))

(deftest actual-controls-never-come-from-text-or-nested-tool-output
  (let [text-event {:type "text" :timestamp 9 :session-id "session-fixture"
                    :part {:id "text-part" :type "text" :session-id "session-fixture"
                           :text (response (fixture/append-calls (fixture/trace)
                                                                [(fixture/tool-event 10 "review_read_diff_chunk" {:id 1} fixture/read-output)]))}}
        events (vec (concat (pop (fixture/trace)) [text-event (fixture/terminal 10)]))]
    (is (:ok (verify (response events)))))
  (let [events (fixture/trace) raw (response events)
        nested (json {:type "text" :timestamp 1 :sessionID "session-fixture"
                      :part {:id "text-part" :type "text" :sessionID "session-fixture" :text raw}})]
    (is (= "unestablished-review-trace" (:reason (verify nested)))))
  (let [nested (response (fixture/append-calls (fixture/trace) [(fixture/tool-event 10 "review_read_diff_chunk" {:id 1} fixture/read-output)]))
        events (vec (concat (pop (fixture/trace))
                             [(fixture/tool-event 10 "bash" {:command "synthetic inert fixture, never executed"} nested)
                              (fixture/terminal 11)]))]
    (is (:ok (verify (response events))))))

(deftest malformed-json-duplicate-nonfinite-and-fatal-bytes-refuse
  (doseq [raw ["not JSON" "⚙ review_begin {}\n" "{}" "[]"
               "{\"type\":\"tool_use\",\"type\":\"text\"}"
               "{\"timestamp\":1e309}" "{\"timestamp\":NaN}"
               "{\"part\":{\"id\":\"a\",\"id\":\"b\"}}"
               (str (response (fixture/trace)) "trailing provider prose\n")
               (js/Uint8Array. #js [255])]]
    (let [result (verify raw)]
      (is (false? (:ok result)))
      (is (= "unestablished-review-trace" (:reason result)))
      (is (nil? (:acceptedInvocation result)))))
  (let [events (mapv host-event (fixture/trace))
        events (assoc-in events [2 :part :state :output] "{\"ok?\":true,\"ok?\":false}")]
    (is (= "unestablished-review-trace" (:reason (verify (str/join "\n" (map json events))))))))

(deftest context-preparation-binds-bytes-and-canonical-pages
  (let [context (js->clj (extern/prepare-review-invocation-context
                         (.encode (js/TextEncoder.) "diff") (json fixture/manifest)
                         (clj->js (:review-tools fixture/context)) "/fixture/submission.json")
                        :keywordize-keys true)]
    (is (= [{:id 1 :start 0 :end 4}] (:pages context)))
    (is (= 1 (:pageCount context)))
    (is (= (:full-input-sha256 fixture/context) (:fullInputSha256 context))))
  (doseq [[diff manifest] [["wrong" (json fixture/manifest)]
                           ["diff" (json (assoc-in fixture/manifest [:full_diff :bytes] 5))]
                           ["diff" "{\"schema\":1,\"schema\":2}"]
                           [(js/Uint8Array. #js [255]) (json fixture/manifest)]]]
    (is (thrown? js/Error (extern/prepare-review-invocation-context
                          diff manifest (clj->js (:review-tools fixture/context)) "/fixture/submission.json")))))

(deftest actual-byte-count-and-canonical-utf16-geometry-are-distinct
  (let [text "😀\n" bytes (.encode (js/TextEncoder.) text)
        digest (-> (js-invoke crypto "createHash" "sha256")
                   (js-invoke "update" bytes)
                   (js-invoke "digest" "hex"))
        manifest (assoc fixture/manifest :full_diff {:path "basehead.diff" :bytes 5 :sha256 digest})
        ctx (js->clj (extern/prepare-review-invocation-context bytes (json manifest)
                                                              (clj->js (:review-tools fixture/context)) "/fixture/submission.json")
                     :keywordize-keys true)]
    (is (= [{:id 1 :start 0 :end 3}] (:pages ctx)))
    (is (= digest (:fullInputSha256 ctx)))
    (is (= 5 (get-in ctx [:inputSource :full_diff :bytes])))))

(deftest eacces-host-error-with-no-output-cannot-promote-surviving-artifact
  (let [events (mapv host-event (fixture/append-calls (fixture/trace)
                                                     [(fixture/tool-event 10 "review_read_diff_chunk" {:id 1} nil)]))
        events (assoc-in events [9 :part :state]
                         {:status "error" :input {:id 1} :error "EACCES: actual fixture cleanup error"})
        result (verify (str/join "\n" (map json events)))]
    (is (false? (:ok result)))
    (is (= "unestablished-review-trace" (:reason result)))
    (is (nil? (:acceptedInvocation result)))))

(deftest empty-prepared-context-is-generic-validity-not-native-credit
  (let [{:keys [context submission events]} (fixture/empty-fixture)
        prepared (extern/prepare-review-invocation-context "" (json (:input-source context))
                                                           (clj->js (:review-tools context)) (:submission-file context))
        shape (js->clj prepared :keywordize-keys true)
        result (js->clj (extern/verify-review-invocation (response events) (json submission) prepared) :keywordize-keys true)]
    (is (= [] (:pages shape)))
    (is (= 0 (:pageCount shape)))
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (:fullInputSha256 shape)))
    (is (:ok result))
    (is (= 0 (get-in result [:acceptedInvocation :pageCount])))))

(deftest structured-healthy-omission-preserves-old-bounded-recovery-with-no-credit
  (let [text-event {:type "text" :timestamp 1 :session-id "session-fixture"
                    :part {:type "text" :id "text-only" :session-id "session-fixture"
                           :text "Normal incomplete model text, never interpreted as controls."}}
        raw (response [text-event (fixture/terminal 2)])
        result (js->clj (extern/verify-review-invocation raw nil (context-json)) :keywordize-keys true)]
    (is (false? (:ok result)))
    (is (= "missing-review-submit" (:reason result)))
    (is (nil? (:acceptedInvocation result))))
  (doseq [raw ["" "not JSON" "{\"type\":\"error\",\"timestamp\":1}\n"
               (response [(fixture/tool-event 1 "invalid" {} "unavailable") (fixture/terminal 2)])]]
    (let [result (js->clj (extern/verify-review-invocation raw nil (context-json)) :keywordize-keys true)]
      (is (= "unestablished-review-trace" (:reason result)))
      (is (nil? (:acceptedInvocation result)))))
  (let [raw (response [(fixture/tool-event 1 "review_begin" {} fixture/begin-output) (fixture/terminal 2)])
        result (js->clj (extern/verify-review-invocation raw "{}" (context-json)) :keywordize-keys true)]
    (is (= "unestablished-review-trace" (:reason result)))))

(deftest accepted-raw-byte-digests-match-node-crypto
  (let [raw-response (response (fixture/trace))
        raw-submission (json fixture/submission)
        digest (fn [text]
                 (-> (js-invoke crypto "createHash" "sha256")
                     (js-invoke "update" (.encode (js/TextEncoder.) text))
                     (js-invoke "digest" "hex")))]
    (doseq [encode [identity #(.encode (js/TextEncoder.) %)]]
      (let [result (js->clj (extern/verify-review-invocation
                            (encode raw-response) (encode raw-submission) (context-json))
                           :keywordize-keys true)]
        (is (:ok result))
        (is (= (digest raw-response) (get-in result [:acceptedInvocation :responseSha256])))
        (is (= (digest raw-submission) (get-in result [:acceptedInvocation :submissionSha256])))))))

(deftest optional-authentic-retained-data-probes
  (when-let [packet (.. js/process -env -REVIEW_INVOCATION_DATA_PACKET)]
    (let [text (.readFileSync fs (str packet "/actual18904-TEXT.txt"))]
      (is (= "unestablished-review-trace" (:reason (verify text))))
      (is (nil? (:acceptedInvocation (verify text)))))
    (let [raw (.readFileSync fs (str packet "/qualified342-host-response.txt"))
          sub (.readFileSync fs (str packet "/qualified342-submission.json"))
          diff (.readFileSync fs (str packet "/qualified342-full.diff"))
          manifest (.readFileSync fs (str packet "/qualified342-input-manifest.json"))
          registry (vec (remove str/blank? (str/split-lines (.readFileSync fs (str packet "/qualified342-registry.txt") "utf8"))))
          ctx (extern/prepare-review-invocation-context diff manifest (clj->js registry)
                                                        "/home/runner/work/eta-mu/eta-mu/.opencode/review-evidence/submission.json")
          result (js->clj (extern/verify-review-invocation raw sub ctx) :keywordize-keys true)]
      ;; Retained historical shape probe only: no new native qualification or
      ;; trusted-context authority inferred from this test's submission data.
      (is (:ok result))
      (is (= 11 (get-in result [:acceptedInvocation :pageCount]))))))
