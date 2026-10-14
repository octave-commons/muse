(ns eta-mu.extern.review-invocation
  "Decode the actual OpenCode --format json host shape, never model prose.
   Parent exports verify-review-invocation as verifyReviewInvocation in CJS.
   Caller owns terminal EOF, raw hashes/source custody/retry authorization.
   GPL-3.0-or-later."
  (:require [clojure.string :as str]
            [eta-mu.extern.json :as json]
            [eta-mu.extern.review-input :as input-edge]
            [eta-mu.law.review-invocation :as law]
            [eta-mu.shape.review-input :as input-shape]
            [eta-mu.domain.review :as review]
            ["node:crypto" :as crypto]))

(defn- invalid! [] (throw (ex-info "Unestablished structured input" {})))

(defonce ^:private prepared-v2-contexts (js/WeakMap.))

(def ^:private prepared-context-keys
  [:input-source :pages :page-count :full-input-sha256 :review-tools :submission-file
   :components :view :component-pages])

(defn- raw-text [raw]
  (json/decode-text raw))

(defn- raw-sha256 [raw]
  (let [text (raw-text raw)
        bytes (if (string? raw) (.encode (js/TextEncoder.) text) raw)]
    (-> (js-invoke crypto "createHash" "sha256")
        (js-invoke "update" bytes)
        (js-invoke "digest" "hex"))))

(defn prepare-ordinary-v2-originals
  "Retained entry point delegates to the shared lower original-byte adapter."
  [source raw-originals trusted-context]
  (input-edge/prepare-ordinary-v2-originals source raw-originals trusted-context))

(def strict-json
  "Shared duplicate-member/nonfinite-rejecting JSON boundary."
  json/strict-json)

(defn- decode-event [event]
  (when-not (map? event) (invalid!))
  (let [part (:part event) state (:state part) name (:tool part)
        review? (and (string? name) (str/starts-with? name "review_"))]
    {:type (:type event) :timestamp (:timestamp event) :session-id (:sessionID event)
     :part {:type (:type part) :id (:id part) :session-id (:sessionID part)
            :call-id (:callID part) :tool name :reason (:reason part)
            :state {:status (:status state) :input (:input state) :error (:error state)
                    :output (if review?
                              (let [s (:output state)]
                                (when-not (and (string? s) (not (str/blank? s))) (invalid!))
                                (strict-json s))
                              (:output state))}}}))

(defn- context-from-input [raw-diff source tools file]
  (let [
        text (raw-text raw-diff)
        bytes (if (string? raw-diff) (.encode (js/TextEncoder.) text) raw-diff)
        digest (-> (js-invoke crypto "createHash" "sha256")
                   (js-invoke "update" bytes)
                   (js-invoke "digest" "hex"))
        full (input-shape/input-descriptor source)
        pages (mapv #(select-keys % [:id :start :end]) (review/diff-chunks text))
        ctx {:input-source source :pages pages :page-count (count pages)
             :full-input-sha256 digest :review-tools tools :submission-file file}]
    (when-not (and (= (.-length bytes) (:bytes full))
                   (= digest (:sha256 full))
                   (law/context-valid? ctx))
      (invalid!))
    ctx))

(defn- prepare-context [raw-full-diff raw-manifest raw-review-tools submission-file trusted-context v2?]
  (try
    (when-not (js/Array.isArray raw-review-tools) (invalid!))
    (when (and v2? (not (law/ordinary-v2-trusted-context-valid? trusted-context))) (invalid!))
    (let [manifest-bytes (if v2? (input-edge/bounded-bytes raw-manifest (:view-bytes law/ordinary-v2-limits)) raw-manifest)
          source (strict-json (raw-text manifest-bytes))
          tools (js->clj raw-review-tools)
          ctx (if v2?
                (do
                  (when-not (= "open-hax.review-input/v2" (:schema source)) (invalid!))
                  (let [shaped (input-edge/prepare-v2-carrier raw-full-diff source trusted-context)
                        component-pages (input-shape/component-chunks source (:text shaped))
                        aggregate-pages (if (<= (count (filter #(pos? (:bytes %)) (:components source))) 1)
                                          component-pages (input-shape/diff-chunks (:text shaped)))]
                    (when-not (and (vector? tools) (every? string? tools)
                                    (= (count tools) (count (set tools)))
                                    (every? (set tools) law/review-tools)
                                    (string? submission-file) (not (str/blank? submission-file)))
                      (invalid!))
                    {:input-source source
                     :pages (mapv #(select-keys % [:id :start :end]) aggregate-pages)
                     :page-count (:page-count shaped) :full-input-sha256 (get-in shaped [:view :sha256])
                     :review-tools tools :submission-file submission-file
                     :components (:components shaped) :view (:view shaped)
                     :component-pages component-pages
                     :input-context trusted-context}))
                (do
                  (when (= "open-hax.review-input/v2" (:schema source)) (invalid!))
                  (context-from-input raw-full-diff source tools submission-file)))
          result (clj->js (cond-> {:inputSource (:input-source ctx) :pages (:pages ctx) :pageCount (:page-count ctx)
                       :fullInputSha256 (:full-input-sha256 ctx) :reviewTools (:review-tools ctx)
                       :submissionFile (:submission-file ctx)}
                (:components ctx) (assoc :components (:components ctx) :view (:view ctx)
                                         :componentPages (:component-pages ctx)
                                         :inputContext (:input-context ctx))))]
        ;; A local preparation witness, not native authentication. Immutable
        ;; CLJS data binds every protected field on the actual returned object.
        ;; Serialized/foreign copies cannot supply this in-process observation.
        (when v2? (.set prepared-v2-contexts result ctx))
        result)
    (catch :default _
      (throw (js/Error. "unestablished-review-trace: invalid full-input context")))))

(defn prepare-review-invocation-context
  "CJS prepareReviewInvocationContext(input, manifest, JS tools[], file[, context]).
   Four arguments preserve v1/native policy. Five select bounded ordinary-v2;
   independently supplied native JS producer/content context is mandatory before
   decoding candidate bytes. The caller authenticates native origin and retains
   the actual returned object in supervisor memory. V2 serialized copies do
   not establish local preparation. Invocation credit requires actual
   component delivery, matching structured assessment and all existing guards."
  ([raw-full-diff raw-manifest raw-review-tools submission-file]
   (prepare-context raw-full-diff raw-manifest raw-review-tools submission-file nil false))
  ([raw-full-diff raw-manifest raw-review-tools submission-file raw-trusted-context]
   (try
     (prepare-context raw-full-diff raw-manifest raw-review-tools submission-file
                      (js->clj raw-trusted-context :keywordize-keys true) true)
     (catch :default _
       (throw (js/Error. "unestablished-review-trace: invalid full-input context"))))))

(defn- decode-context [raw]
  (let [witness (.get prepared-v2-contexts raw)
        ctx (if (or (string? raw) (instance? js/Uint8Array raw))
              (strict-json (raw-text raw)) (js->clj raw :keywordize-keys true))
        prepared (cond-> {:input-source (:inputSource ctx) :pages (:pages ctx)
                  :page-count (:pageCount ctx) :full-input-sha256 (:fullInputSha256 ctx)
                  :review-tools (:reviewTools ctx) :submission-file (:submissionFile ctx)}
                   (= "open-hax.review-input/v2" (get-in ctx [:inputSource :schema]))
                   (assoc :components (:components ctx) :view (:view ctx)
                                             :component-pages (:componentPages ctx)))
    ;; Optional full bytes are verified again. Without them this must be the
    ;; actual prepare export's result retained by the trusted caller; data
    ;; alone cannot authenticate its source or prove its preparation.
        validated
    (if (= "open-hax.review-input/v2" (get-in prepared [:input-source :schema]))
      (do
        (when-not (and witness (= prepared (select-keys witness prepared-context-keys))
                       (= (:inputContext ctx) (:input-context witness)))
          (invalid!))
        (if (contains? ctx :fullDiff)
          (let [raw-full (if (string? (:fullDiff ctx)) (:fullDiff ctx) (aget raw "fullDiff"))
                shaped (input-edge/prepare-v2-carrier raw-full (:input-source witness) (:input-context witness))]
            (when-not (and (= (:componentPages ctx)
                             (input-shape/component-chunks (:inputSource ctx) (:text shaped)))
                          (= (:pages ctx) (mapv #(select-keys % [:id :start :end])
                                               (input-shape/diff-chunks (:text shaped)))))
              (invalid!))
            ;; The length classifier consumes this same validated owned text;
            ;; it must not decode a mutable native carrier again afterward.
            (assoc prepared :full-input-text (:text shaped)))
          prepared))
      (do
        (when (contains? ctx :fullDiff)
          (when-not (= prepared (context-from-input (:fullDiff ctx) (:inputSource ctx)
                                                   (:reviewTools ctx) (:submissionFile ctx)))
            (invalid!)))
        prepared))]
    (assoc validated :session-id (:sessionID ctx))))

(defn verify-review-invocation
  "CJS API: rawResponseText/rawSubmission accept strings or UTF8 byte arrays;
   rawSubmission may be null (missing). expectedFullInputContext must be caller
   trusted: V2 requires the actual prepare-review-invocation-context object,
   with its unchanged preparation snapshot, retained in supervisor
   memory, optional fullDiff bytes for revalidation, and
   optional sessionID. Returns plain JS {ok, reasonKind, code, violations,...}.
   The prepare export derives canonical geometry and hash-binds actual bytes
   to the manifest. This verifier cannot authenticate a caller or context
   provenance; final independent Git/source/byte validation remains required.
   A JS string cannot attest prior byte decoding; prefer fatal-decoded bytes."
  [raw-response raw-submission expected-context]
  (try
    (let [text (raw-text raw-response)
          lines (remove str/blank? (str/split-lines text))
          events (mapv #(decode-event (strict-json %)) lines)
          submission (when (some? raw-submission) (strict-json (raw-text raw-submission)))
          result (law/verify events submission (decode-context expected-context))]
      (clj->js {:ok (:ok? result)
                :reason (some-> (:reason-kind result) name)
                :reasonKind (some-> (:reason-kind result) name)
                :code (name (:code result)) :violations (:violations result)
                :sessionID (:session-id result) :pageCount (:page-count result)
                :reviewCallCount (:review-call-count result)
                :acceptedInvocation (when-let [accepted (:accepted-invocation result)]
                                      (cond-> {:sessionID (:session-id accepted)
                                       :submissionCallID (:submission-call-id accepted)
                                       :submissionPosition (:submission-position accepted)
                                       :submissionFile (:submission-file accepted)
                                       :responseSha256 (raw-sha256 raw-response)
                                       :submissionSha256 (raw-sha256 raw-submission)
                                       :fullInputSha256 (:full-input-sha256 accepted)
                                       :pageCount (:page-count accepted)}
                                        (:components accepted)
                                        (assoc :components (:components accepted) :view (:view accepted)
                                               :componentPages (:component-pages accepted))))}))
    (catch :default _
      #js {:ok false :reason "unestablished-review-trace" :reasonKind "unestablished-review-trace"
           :acceptedInvocation nil
           :code "structured-input-unestablished" :violations #js []})))

(defn- decode-length-context [raw]
  (let [ctx (if (or (string? raw) (instance? js/Uint8Array raw))
              (strict-json (raw-text raw)) (js->clj raw :keywordize-keys true))]
    ;; Unlike generic verify, this narrow classifier requires the actual full
    ;; bytes/text as well as a prepared context. decode-context revalidates its
    ;; digest, byte count and canonical pages via context-from-input.
    (when-not (contains? ctx :fullDiff) (invalid!))
    (let [decoded (decode-context raw)]
      (if (= "open-hax.review-input/v2" (get-in decoded [:input-source :schema]))
        decoded
        (assoc decoded :full-input-text (raw-text (:fullDiff ctx)))))))

(defn classify-length-ended-review
  "Raw response/submission/context have the
   verify API's syntax, but context MUST include fullDiff for exact revalidation.
   rawCustody is caller-trusted JSON or JS data with invocationState, exitCode,
   submissionState, responseSha256Before/After and contextBefore/After (each with
   fullDiff). V2 requires actual registered preparation objects in the caller's
   native context snapshots; serialized custody cannot establish that memory.
   Actual response digest is derived here; all admission policy is law.
   Returns {eligible, classification, code, violations,...}, with no full-review
   ok, acceptedInvocation or approval fields. Caller independently authenticates
   source and custody, retains the first failure, composes the shared MAX2 bound,
   and runs a fresh complete review normally. This API invokes nothing."
  [raw-response raw-submission expected-context raw-custody]
  (try
    (let [events (mapv #(decode-event (strict-json %))
                       (remove str/blank? (str/split-lines (raw-text raw-response))))
          submission (when (some? raw-submission) (strict-json (raw-text raw-submission)))
          ctx (decode-length-context expected-context)
          c (if (or (string? raw-custody) (instance? js/Uint8Array raw-custody))
              (strict-json (raw-text raw-custody)) (js->clj raw-custody :keywordize-keys true))
          v2? (= "open-hax.review-input/v2" (get-in ctx [:input-source :schema]))
          snapshot (fn [key property]
                     (if (and v2? (not (or (string? raw-custody) (instance? js/Uint8Array raw-custody))))
                       (aget raw-custody property)
                       (clj->js (get c key))))
          custody {:invocation-state (:invocationState c) :exit-code (:exitCode c)
                   :submission-present? (some? raw-submission)
                   :submission-state (:submissionState c) :response-sha256 (raw-sha256 raw-response)
                   :response-sha256-before (:responseSha256Before c)
                   :response-sha256-after (:responseSha256After c)
                   :context-before (decode-length-context (snapshot :contextBefore "contextBefore"))
                   :context-after (decode-length-context (snapshot :contextAfter "contextAfter"))}
          result (law/classify-length-ended-review events submission ctx custody)]
      (clj->js {:eligible (:eligible? result) :classification (name (:classification result))
                :code (name (:code result)) :violations (:violations result)
                :requiredAction (some-> (:required-action result) name)
                :sessionID (:session-id result) :pageCount (:page-count result)
                :recordedStages (:recorded-stages result) :terminalPosition (:terminal-position result)
                :terminalReason (:terminal-reason result) :responseSha256 (:response-sha256 result)
                :fullInputSha256 (:full-input-sha256 result)}))
    (catch :default _
      #js {:eligible false :classification "unestablished-length-ended-review"
           :code "structured-input-unestablished" :violations #js []})))
