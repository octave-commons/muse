(ns eta-mu.extern.review-invocation-test
  "JSON controls are explicitly synthetic, using observed OpenCode syntax;
   optional retained authentic DATA probes execute only the new owned code."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [eta-mu.extern.review-invocation :as extern]
            [eta-mu.law.review-invocation-test :as fixture]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.shape.review-input :as input-shape]
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

(defn- prepare-native-context [raw manifest tools file]
  (try (extern/prepare-review-invocation-context raw manifest tools file)
       (catch :default _ nil)))

(defn- prepare-v2-context [raw manifest tools file trusted]
  ;; Native call intentionally supplies an additional caller-held context to the
  ;; existing compiled function. The predecessor ignores it, then rejects v2 at
  ;; its actual admission boundary; no CLJS arity dispatch or new export is used.
  (.call extern/prepare-review-invocation-context nil raw manifest tools file (clj->js trusted)))

(deftest ordinary-v2-public-preparation-refuses-coherent-stale-originals
  (let [{:keys [source view trusted] :as held} (fixture/ordinary-v2-fixture)
        tools (clj->js (:review-tools fixture/context))
        file "/fixture/submission.json"]
    (is (some? (prepare-v2-context view (json source) tools file trusted))
        "unchanged held originals remain legal before testing replacement refusal")
    (doseq [{candidate :source carrier :view label :label}
            (fixture/ordinary-v2-coherent-stale-candidates held)]
      (is (thrown? js/Error (prepare-v2-context carrier (json candidate) tools file trusted))
          (str label " must refuse despite matching current producer coordinates")))
    (is (thrown? js/Error
                 (prepare-v2-context view (json source) tools file (dissoc trusted :input_source)))
        "public preparation requires independently held original identity")))

(deftest ordinary-v2-preparation-uses-existing-api-and-original-byte-identities
  (let [{:keys [source view trusted]} (fixture/ordinary-v2-fixture)
        tools (clj->js (:review-tools fixture/context))
        bytes (js/Buffer.from view "utf8")]
    ;; No new symbol or wrong-arity call is used. The predecessor's actual safe
    ;; unsupported-v2 refusal is caught so RED reports admission/identity failures.
    (doseq [raw [view bytes (js/Uint8Array. bytes)]]
      (let [prepared (try (prepare-v2-context raw (json source) tools "/fixture/submission.json" trusted)
                          (catch :default _ nil))
            ctx (js->clj prepared
                        :keywordize-keys true)]
        (is (some? ctx) "complete structurally valid v2 must be admitted by existing preparation")
        (is (= source (:inputSource ctx)))
        (is (= (get-in source [:view :sha256]) (:fullInputSha256 ctx)))
        (is (= (get-in source [:view :page_count]) (:pageCount ctx)))))
    (doseq [[raw manifest] [[(.subarray bytes 0 (dec (.-length bytes))) (json source)]
                            [(js/Uint8Array. #js [255]) (json source)]
                            [view (str "{\"schema\":\"other\"," (subs (json source) 1))]
                            [view (str "{\"ignored\":1e309," (subs (json source) 1))]]]
      (is (thrown? js/Error (prepare-v2-context raw manifest tools "/fixture/submission.json" trusted))))
    (doseq [raw [view bytes (js/Uint8Array. bytes)]]
      (is (thrown? js/Error (extern/prepare-review-invocation-context raw (json source) tools "/fixture/submission.json"))
          "ordinary-v2 without an independent caller context must refuse")
      (doseq [changed [(assoc-in trusted [:producer :head_sha] (apply str (repeat 40 "f")))
                       (assoc-in trusted [:content :authorization] "unauthorized")
                       (assoc-in trusted [:content :authorization] "unknown")
                       (assoc-in trusted [:content :classification] "sensitive")
                       (assoc-in trusted [:content :credential_canary] true)]]
        (is (thrown? js/Error (prepare-v2-context raw (json source) tools "/fixture/submission.json" changed))))))
  ;; Independent producer/content enforcement cannot be inferred from these
  ;; predecessor refusals: all v2 is presently rejected, even the positive neighbor.
  (doseq [source [fixture/manifest fixture/native-manifest fixture/rejection-manifest fixture/cohort-manifest]]
    (is (some? (prepare-native-context "diff" (json source)
                                       (clj->js (:review-tools fixture/context)) "/fixture/submission.json")))))

(deftest ordinary-v2-public-preparation-refuses-self-consistent-blank-coordinates
  (let [{:keys [source view trusted]} (fixture/ordinary-v2-fixture)
        tools (clj->js (:review-tools fixture/context))
        file "/fixture/submission.json"
        prepared (prepare-v2-context view (json source) tools file trusted)]
    ;; The existing five-argument native API admits this valid neighbor. Changed
    ;; metadata still uses intact originals and a separately supplied caller map.
    (is (some? prepared))
    (is (= source (:inputSource (js->clj prepared :keywordize-keys true))))
    (doseq [field [:repository :workflow_path :workflow_ref :run_id
                   :run_attempt :job_id :artifact_id]
            blank [" " "\t\n"]]
      (let [candidate (assoc-in source [:producer field] blank)
            caller (-> trusted (assoc-in [:producer field] blank)
                       (assoc :input_source candidate))]
        (is (thrown? js/Error (prepare-v2-context view (json candidate) tools file caller))
            (str "matching nonempty blank producer " field " " (pr-str blank)))))
    (doseq [blank [" " "\t\n"]]
      (is (thrown? js/Error
                   (prepare-v2-context view (json source) tools file
                                       (assoc trusted :reviewer_run_attempt blank)))
          (str "nonempty blank reviewer attempt " (pr-str blank))))
    ;; Empty-string refusal already passes :min 1 and supplies no causal credit.
    (is (thrown? js/Error
                 (prepare-v2-context view (json (assoc-in source [:producer :repository] "")) tools file
                                      (-> trusted (assoc-in [:producer :repository] "")
                                          (assoc :input_source (assoc-in source [:producer :repository] ""))))))))

(deftest ordinary-v2-counts-real-pages-before-complete-materialization
  (let [tools (clj->js (:review-tools fixture/context))
        materialize input-shape/diff-chunks]
    (doseq [[lines expected] [[131072 1024] [131073 1025]]]
      (let [{:keys [source view trusted]} (fixture/ordinary-v2-fixture ["" "" (.repeat "\n" lines)])
            claimed (assoc-in source [:view :page_count] 1024)
            calls (atom 0)]
        (with-redefs [input-shape/diff-chunks (fn [text] (swap! calls inc) (materialize text))]
          (if (= expected 1024)
            (let [ctx (js->clj (prepare-v2-context view (json claimed) tools "/fixture/submission.json" trusted)
                              :keywordize-keys true)]
              (is (= 1024 (:pageCount ctx)))
              (is (= 1 @calls)))
            (do
              (is (thrown? js/Error (prepare-v2-context view (json claimed) tools "/fixture/submission.json" trusted)))
              (is (zero? @calls) "actual page 1025 must refuse before collecting complete pages"))))))))

(deftest ordinary-v2-byte-boundaries-and-original-reconstruction
  (let [tools (clj->js (:review-tools fixture/context))]
    (doseq [[index limit unit repetitions] [[0 4194304 "x" 4194304]
                                          [1 262144 "é" 131072]
                                          [2 4194304 "x" 4194304]]]
      (let [text (.repeat unit repetitions)
            {:keys [source view trusted]} (fixture/ordinary-v2-fixture (assoc ["" "" ""] index text))]
        (is (= limit (get-in source [:components index :bytes])))
        (is (some? (prepare-v2-context view (json source) tools "/fixture/submission.json" trusted)))
        (let [{over-source :source over-view :view over-trusted :trusted}
              (fixture/ordinary-v2-fixture (assoc ["" "" ""] index (str text "x")))]
          (is (thrown? js/Error (prepare-v2-context over-view (json over-source) tools "/fixture/submission.json" over-trusted))))))
    (doseq [texts [["" "{}\r\n" ""]
                   [(str (.repeat "x" 8191) "😀\r\n") "{}\r\n" "\uFEFFtail ημ 😀\r\n"]]]
      (let [{:keys [source view trusted]} (fixture/ordinary-v2-fixture texts)
            bytes (js/Buffer.from view "utf8")
            ctx (js->clj (prepare-v2-context bytes (json source) tools "/fixture/submission.json" trusted)
                         :keywordize-keys true)]
        (is (= (:components source) (:components ctx)))
        (is (= (:view source) (:view ctx)))
        (is (= view (apply str (map #(subs view (:start %) (:end %)) (:pages ctx)))))
        (doseq [[component original] (map vector (:components ctx) texts)]
          (let [[bs be] (:byte_span component) [ts te] (:text_span component)]
            (is (= original (subs view ts te)))
            (is (= (.toString (js/Buffer.from original "utf8") "hex")
                   (.toString (.subarray bytes bs be) "hex")))))))))

(deftest ordinary-v2-explicit-profile-and-self-consistent-predecessor-refuse
  (let [{:keys [trusted]} (fixture/ordinary-v2-fixture)
        {old-source :source old-view :view} (fixture/ordinary-v2-fixture ["older diff" "{}" "older log"])
        old-head (apply str (repeat 40 "f"))
        old-source (-> old-source (assoc :head_sha old-head) (assoc-in [:producer :head_sha] old-head))
        tools (clj->js (:review-tools fixture/context))]
    (is (invocation/ordinary-v2-contract-valid? old-source))
    (is (thrown? js/Error (prepare-v2-context old-view (json old-source) tools "/fixture/submission.json" trusted)))
    (is (thrown? js/Error (prepare-v2-context "diff" (json fixture/manifest) tools "/fixture/submission.json" trusted))
        "candidate cannot downgrade the caller-selected v2 entry to legacy")
    (let [source (:source (fixture/ordinary-v2-fixture)) view (:view (fixture/ordinary-v2-fixture))
          manifest (json source) cap 9437184
          padded (str manifest (.repeat " " (- cap (count manifest))))]
      (is (some? (prepare-v2-context view padded tools "/fixture/submission.json" trusted)))
      (is (thrown? js/Error (prepare-v2-context view (str padded " ") tools "/fixture/submission.json" trusted))))
    (let [legacy (str (json fixture/manifest) (.repeat " " 9437185))]
      (is (some? (prepare-native-context "diff" legacy tools "/fixture/submission.json"))
          "derived large legacy metadata retains its existing unbounded policy"))))

(deftest prepared-v2-cannot-admit-generic-whole-view-assessment-as-an-invocation
  (let [{:keys [source view trusted]} (fixture/ordinary-v2-fixture)
        source (assoc source :provenance (:provenance fixture/manifest))
        trusted (assoc trusted :input_source source)
        file (:submission-file fixture/context)
        tools (clj->js (:review-tools fixture/context))
        prepared (prepare-v2-context view (json source) tools file trusted)
        end (count view)
        events (-> (fixture/trace)
                   (assoc-in [0 :part :state :output :input-source] source)
                   (assoc-in [0 :part :state :output :diff-stats :bytes] end)
                   (assoc-in [1 :part :state :output :chunk] {:id 1 :start 0 :end end :text view}))
        submission (assoc fixture/submission :input-source source
                          :input-assessments [{:id 1 :start 0 :end end :note "whole-page assessment"}])
        result (js->clj (extern/verify-review-invocation (response events) (json submission) prepared)
                       :keywordize-keys true)]
    (is (some? prepared) "transport must admit the valid independently bound originals")
    (is (false? (:ok result)))
    (is (= "trusted-context-unestablished" (:code result)))
    (is (nil? (:acceptedInvocation result)))
    ;; Positive neighbor: the same complete eight-tool/five-stage generic trace
    ;; retains legitimate legacy behavior. Only the explicit input contract varies.
    (let [legacy-source (assoc fixture/manifest :full_diff (fixture/byte-descriptor "basehead.diff" view))
          legacy-context (extern/prepare-review-invocation-context view (json legacy-source) tools file)
          legacy-events (assoc-in events [0 :part :state :output :input-source] legacy-source)
          legacy-submission (assoc submission :input-source legacy-source)
          legacy (js->clj (extern/verify-review-invocation (response legacy-events) (json legacy-submission) legacy-context)
                         :keywordize-keys true)]
      (is (:ok legacy)))))

(deftest native-byte-preparation-preserves-bom-only-and-prefixed-page-geometry
  (doseq [text ["\uFEFF" "\uFEFForiginal context ημ 😀\n" "\uFEFF\uFEFFcontext"]]
    (let [bytes (js/Buffer.from text "utf8")
          digest (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))
          manifest (assoc fixture/native-manifest :full_input
                          {:path "actionability-input.txt" :bytes (.-length bytes) :sha256 digest})
          tools (clj->js (:review-tools fixture/context))]
      (doseq [raw [text bytes (js/Uint8Array. bytes)]]
        (let [prepared (prepare-native-context raw (json manifest) tools "/fixture/submission.json")
              ctx (js->clj prepared :keywordize-keys true)]
          (is (some? prepared))
          (is (= [{:id 1 :start 0 :end (count text)}] (:pages ctx)))
          (is (= 1 (:pageCount ctx)))
          (is (= digest (:fullInputSha256 ctx)))
          (is (= manifest (:inputSource ctx))))))))

(deftest native-context-preparation-and-terminal-carrier-share-the-existing-api
  (let [{:keys [context submission events]} (fixture/native-fixture)
        prepared (prepare-native-context
                  (js/Buffer.from "diff" "utf8") (json fixture/native-manifest)
                  (clj->js (:review-tools context)) (:submission-file context))
        shape (js->clj prepared :keywordize-keys true)
        result (js->clj (extern/verify-review-invocation (response events) (json submission) prepared)
                        :keywordize-keys true)]
    (is (= fixture/native-manifest (:inputSource shape)))
    (is (= [{:id 1 :start 0 :end 4}] (:pages shape)))
    (is (:ok result))
    (is (= 1 (get-in result [:acceptedInvocation :pageCount])))
    (doseq [source [(assoc fixture/native-manifest :context_sha256 (apply str (repeat 64 "f")))
                    (assoc fixture/native-manifest :proposal_id 72)
                    (assoc-in fixture/native-manifest [:target :thread_id] "PRRT_other")]]
      (let [changed (prepare-native-context "diff" (json source)
                                                             (clj->js (:review-tools context)) (:submission-file context))
            result (js->clj (extern/verify-review-invocation (response events) (json submission) changed)
                            :keywordize-keys true)]
        (is (false? (:ok result)))
        (is (nil? (:acceptedInvocation result)))))
    (let [changed (js->clj prepared :keywordize-keys true)
          result (js->clj (extern/verify-review-invocation (response events) (json submission)
                                                         (json (assoc changed :fullDiff "prefix")))
                          :keywordize-keys true)]
      (is (false? (:ok result)))
      (is (nil? (:acceptedInvocation result))))))

(deftest native-byte-preparation-rejects-incomplete-or-mixed-input
  (let [text "complete native context ημ 😀\n"
        bytes (js/Buffer.from text "utf8")
        digest (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))
        manifest (assoc fixture/native-manifest :full_input {:path "actionability-input.txt" :bytes (.-length bytes) :sha256 digest})
        tools (clj->js (:review-tools fixture/context))]
    (doseq [raw [text bytes (js/Uint8Array. bytes)]]
      (let [ctx (js->clj (prepare-native-context raw (json manifest) tools "/fixture/submission.json")
                        :keywordize-keys true)]
        (is (= (count text) (get-in ctx [:pages 0 :end])))
        (is (= (.-length bytes) (get-in ctx [:inputSource :full_input :bytes])))
        (is (= digest (:fullInputSha256 ctx)))))
    (doseq [[raw source] [[(.subarray bytes 0 10) manifest]
                          [text (assoc-in manifest [:full_input :bytes] (count text))]
                          [text (assoc manifest :full_diff (:full_diff fixture/manifest))]
                          [text (assoc-in manifest [:full_input :path] "basehead.diff")]
                          [(js/Uint8Array. #js [255]) manifest]
                          ["" (assoc manifest :full_input {:path "actionability-input.txt" :bytes 0
                                                          :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})]]]
      (is (thrown? js/Error (extern/prepare-review-invocation-context raw (json source) tools "/fixture/submission.json"))))))

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

;; Candidate controls append without changing any original fixture/assertion.
(defn length-context-js []
  (let [ctx (extern/prepare-review-invocation-context "diff" (json fixture/manifest)
                                                    (clj->js (:review-tools fixture/context))
                                                    (:submission-file fixture/context))]
    (js/Object.assign ctx #js {:fullDiff "diff" :sessionID "session-fixture"})))

(defn length-custody-js [raw ctx]
  (let [digest (-> (js-invoke crypto "createHash" "sha256")
                   (js-invoke "update" raw) (js-invoke "digest" "hex"))]
    #js {:invocationState "completed" :exitCode 0 :submissionState "missing"
         :responseSha256Before digest :responseSha256After digest
         :contextBefore ctx :contextAfter ctx}))

(defn classify-length
  ([raw] (let [ctx (length-context-js)]
           (classify-length raw nil ctx (length-custody-js raw ctx))))
  ([raw sub ctx custody]
   (js->clj (extern/classify-length-ended-review raw sub ctx custody) :keywordize-keys true)))

(deftest length-boundary-binds-actual-bytes-and-has-no-acceptance-fields
  (let [raw (response (fixture/length-events)) ctx (length-context-js)]
    (doseq [bytes [raw (.encode (js/TextEncoder.) raw)]
            raw-context [ctx (js/JSON.stringify ctx)]
            custody [identity js/JSON.stringify]]
      (let [result (classify-length bytes nil raw-context (custody (length-custody-js bytes ctx)))
            strict (js->clj (extern/verify-review-invocation bytes nil ctx) :keywordize-keys true)]
        (is (:eligible result))
        (is (= "length-ended-unfinished-review" (:classification result)))
        (is (= "new-complete-review-invocation" (:requiredAction result)))
        (is (= 5 (:terminalPosition result)))
        (is (= "length" (:terminalReason result)))
        (is (= (aget (length-custody-js bytes ctx) "responseSha256Before") (:responseSha256 result)))
        (is (every? #(not (contains? result %)) [:ok :acceptedInvocation :approval :maxAttempts]))
        (is (false? (:ok strict)))
        (is (= "unterminated-host-trace" (:code strict)))))))

(deftest length-boundary-refuses-untrusted-or-changing-custody-and-full-input
  (let [raw (response (fixture/length-events)) ctx (length-context-js)
        c (js->clj (length-custody-js raw ctx) :keywordize-keys true)]
    (doseq [custody [nil "{}" "{\"exitCode\":0,\"exitCode\":1}"
                    (json (assoc c :exitCode 1))
                    (json (assoc c :invocationState "started"))
                    (json (assoc c :submissionState "unknown"))
                    (json (assoc c :responseSha256After (apply str (repeat 64 "f"))))
                    (json (assoc-in c [:contextAfter :submissionFile] "/changed"))
                    (json (assoc-in c [:contextBefore :sessionID] "other"))
                    (json (assoc-in c [:contextAfter :inputSource :head_sha] (apply str (repeat 40 "f"))))]]
      (is (false? (:eligible (classify-length raw nil ctx custody)))))
    (doseq [context [(context-json) "{}"
                    (json (assoc (js->clj ctx :keywordize-keys true) :fullDiff "dirt"))
                    (json (assoc-in (js->clj ctx :keywordize-keys true) [:pages 0 :end] 3))
                    (json (assoc-in (js->clj ctx :keywordize-keys true) [:inputSource :full_diff :bytes] 5))]]
      (is (false? (:eligible (classify-length raw nil context (json c))))))
    (doseq [sub ["{}" "false" (json fixture/submission) "malformed" "null"]]
      (is (false? (:eligible (classify-length raw sub ctx (json c))))))))

(deftest length-boundary-never-recovers-from-prose-malformed-events-or-failed-HOST
  (doseq [raw ["" "not JSON" "[]" "{}" "{\"type\":\"error\",\"timestamp\":1}"
               "{\"type\":\"step_finish\",\"type\":\"step_finish\"}"
               "{\"timestamp\":1e309}" (js/Uint8Array. #js [255])
               (response [(fixture/terminal 1)])
               (str (response (fixture/length-events)) "model prose\n")]]
    (is (false? (:eligible (classify-length raw)))))
  (let [events (mapv host-event (fixture/length-events))
        bad (assoc-in events [1 :part :state] {:status "error" :input {:id 1} :error "EACCES"})]
    (is (false? (:eligible (classify-length (str/join "\n" (map json bad)))))))
  (let [raw (response (fixture/length-events))
        nested (json {:type "text" :timestamp 1 :sessionID "session-fixture"
                      :part {:id "text-only" :type "text" :sessionID "session-fixture" :text raw}})]
    (is (false? (:eligible (classify-length nested)))))
  (let [events (mapv host-event (fixture/length-events))
        dup (assoc-in events [2 :part :state :output] "{\"ok?\":true,\"ok?\":false}")]
    (is (false? (:eligible (classify-length (str/join "\n" (map json dup))))))))

(deftest length-boundary-preserves-earlier-stop-but-requires-the-actual-final-length
  (let [events (fixture/ordered-fixture-events
                (concat (pop (fixture/length-events)) [(fixture/terminal 6)
                                                       (assoc-in (fixture/terminal 7) [:part :reason] "length")]))
        raw (response events)]
    (is (:eligible (classify-length raw)))
    (is (= "length" (:terminalReason (classify-length raw))))
    (is (false? (:eligible (classify-length (response (pop events))))))
    (is (false? (:eligible (classify-length (response (assoc-in events [4 :part :reason] "length"))))))))


(deftest rejection-carrier-uses-unchanged-prepare-and-verify-exports
  (let [{:keys [context submission events]} (fixture/rejection-fixture)
        prepared (prepare-native-context (js/Buffer.from "diff" "utf8")
                                         (json fixture/rejection-manifest)
                                         (clj->js (:review-tools context)) (:submission-file context))
        result (js->clj (extern/verify-review-invocation (response events) (json submission) prepared)
                        :keywordize-keys true)]
    (is (some? prepared))
    (is (= fixture/rejection-manifest (:inputSource (js->clj prepared :keywordize-keys true))))
    (is (true? (:ok result)))
    (is (= 1 (get-in result [:acceptedInvocation :pageCount])))
    (is (nil? (:agreement result)))
    (doseq [source [(assoc fixture/rejection-manifest :proposal_id 72)
                    (assoc fixture/rejection-manifest :context_sha256 (apply str (repeat 64 "f")))
                    (assoc-in fixture/rejection-manifest [:target :thread_id] "PRRT_other")]]
      (let [changed (prepare-native-context "diff" (json source)
                                            (clj->js (:review-tools context)) (:submission-file context))
            refused (js->clj (extern/verify-review-invocation (response events) (json submission) changed)
                            :keywordize-keys true)]
        (is (false? (:ok refused)))
        (is (nil? (:acceptedInvocation refused)))))))

(deftest rejection-raw-bytes-preserve-bom-and-refuse-mixed-or-corrupt-input
  (let [tools (clj->js (:review-tools fixture/context))]
    (doseq [text ["\uFEFF" "\uFEFForiginal proposal ημ 😀\n" "\uFEFF\uFEFFcontext"]]
      (let [bytes (js/Buffer.from text "utf8")
            digest (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))
            source (assoc fixture/rejection-manifest :full_input
                          {:path "rejection-assessment-input.txt" :bytes (.-length bytes) :sha256 digest})]
        (doseq [raw [text bytes (js/Uint8Array. bytes)]]
          (let [prepared (prepare-native-context raw (json source) tools "/fixture/submission.json")
                ctx (js->clj prepared :keywordize-keys true)]
            (is (some? prepared))
            (is (= [{:id 1 :start 0 :end (count text)}] (:pages ctx)))
            (is (= digest (:fullInputSha256 ctx)))
            (is (= source (:inputSource ctx)))))))
    (doseq [[raw source] [["prefix" fixture/rejection-manifest]
                          ["diff" (assoc fixture/rejection-manifest :full_diff (:full_diff fixture/manifest))]
                          ["diff" (assoc fixture/rejection-manifest :purpose "actionability")]
                          ["diff" (assoc-in fixture/rejection-manifest [:full_input :path] "actionability-input.txt")]
                          [(js/Uint8Array. #js [255]) fixture/rejection-manifest]
                          ["" (assoc-in fixture/rejection-manifest [:full_input :bytes] 0)]]]
      (is (thrown? js/Error (extern/prepare-review-invocation-context raw (json source) tools "/fixture/submission.json"))))))


(deftest cohort-carrier-uses-the-existing-prepare-and-verify-exports
  (let [{:keys [context submission events]} (fixture/cohort-fixture)
        prepared (prepare-native-context (js/Buffer.from "diff" "utf8")
                                         (json fixture/cohort-manifest)
                                         (clj->js (:review-tools context)) (:submission-file context))
        result (js->clj (extern/verify-review-invocation (response events) (json submission) prepared)
                        :keywordize-keys true)]
    (is (some? prepared))
    (is (= fixture/cohort-manifest (:inputSource (js->clj prepared :keywordize-keys true))))
    (is (true? (:ok result)))
    (is (= 1 (get-in result [:acceptedInvocation :pageCount])))
    (is (every? #(not (contains? result %)) [:agreement :preservation :qualified :settled :approval]))
    (doseq [source [(assoc fixture/cohort-manifest :proposal_id 72)
                    (assoc fixture/cohort-manifest :context_sha256 (apply str (repeat 64 "f")))
                    (assoc-in fixture/cohort-manifest [:target :cohort_targets 1 1] 9)]]
      (let [changed (prepare-native-context "diff" (json source)
                                           (clj->js (:review-tools context)) (:submission-file context))
            refused (js->clj (extern/verify-review-invocation (response events) (json submission) changed)
                            :keywordize-keys true)]
        (is (false? (:ok refused)))
        (is (nil? (:acceptedInvocation refused)))))))

(deftest cohort-raw-byte-custody-refuses-short-hash-purpose-target-and-json-substitution
  (let [text "\uFEFFcomplete cohort body ημ 😀\n"
        bytes (js/Buffer.from text "utf8")
        digest (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))
        source (assoc fixture/cohort-manifest :full_input
                      {:path "positive-cohort-assessment-input.txt" :bytes (.-length bytes) :sha256 digest})
        tools (clj->js (:review-tools fixture/context))]
    (doseq [raw [text bytes (js/Uint8Array. bytes)]]
      (let [prepared (prepare-native-context raw (json source) tools "/fixture/submission.json")
            ctx (js->clj prepared :keywordize-keys true)]
        (is (some? prepared))
        (is (= [{:id 1 :start 0 :end (count text)}] (:pages ctx)))
        (is (= digest (:fullInputSha256 ctx)))
        (is (= source (:inputSource ctx)))))
    (doseq [[raw changed] [[(.subarray bytes 0 10) source]
                           [text (assoc-in source [:full_input :bytes] (count text))]
                           [text (assoc-in source [:full_input :sha256] (apply str (repeat 64 "f")))]
                           [text (assoc source :purpose "rejection-assessment")]
                           [text (assoc-in source [:target :cohort_targets] [42])]
                           [text (assoc-in source [:target :root_comment_id] 7)]
                           [text (assoc-in source [:full_input :path] "rejection-assessment-input.txt")]
                           [(js/Uint8Array. #js [255]) source]]]
      (is (thrown? js/Error (extern/prepare-review-invocation-context raw (json changed) tools "/fixture/submission.json"))))
    (doseq [manifest [(str "{\"purpose\":\"rejection-assessment\"," (subs (json source) 1))
                      (str "{\"ignored\":{\"x\":1,\"x\":2}," (subs (json source) 1))
                      (str "\uFEFF" (json source))]]
      (is (thrown? js/Error (extern/prepare-review-invocation-context bytes manifest tools "/fixture/submission.json"))))))
