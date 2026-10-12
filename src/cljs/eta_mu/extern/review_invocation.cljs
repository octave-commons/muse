(ns eta-mu.extern.review-invocation
  "Decode the actual OpenCode --format json host shape, never model prose.
   Parent exports verify-review-invocation as verifyReviewInvocation in CJS.
   Caller owns terminal EOF, raw hashes/source custody/retry authorization.
   GPL-3.0-or-later."
  (:require [clojure.string :as str]
            [eta-mu.extern.json :as json]
            [eta-mu.law.review-invocation :as law]
            [eta-mu.shape.review-input :as input-shape]
            [eta-mu.domain.review :as review]
            ["node:crypto" :as crypto]))

(defn- invalid! [] (throw (ex-info "Unestablished structured input" {})))

(defn- raw-text [raw]
  (json/decode-text raw))

(defn- raw-sha256 [raw]
  (let [text (raw-text raw)
        bytes (if (string? raw) (.encode (js/TextEncoder.) text) raw)]
    (-> (js-invoke crypto "createHash" "sha256")
        (js-invoke "update" bytes)
        (js-invoke "digest" "hex"))))

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

(defn prepare-review-invocation-context
  "CJS prepareReviewInvocationContext(rawFullInput, rawManifest, JS tools[],
   submissionFile): fatal UTF8/strict JSON/exact SHA256 byte count; canonical
   review/diff-chunks geometry. Throws only a safe typed message on refusal.
   Caller independently binds actual Git/native source before and after child;
   hold this returned context in trusted supervisor memory, not artifact JSON."
  [raw-full-diff raw-manifest raw-review-tools submission-file]
  (try
    (when-not (js/Array.isArray raw-review-tools) (invalid!))
    (let [ctx (context-from-input raw-full-diff (strict-json (raw-text raw-manifest))
                                  (js->clj raw-review-tools) submission-file)]
      (clj->js {:inputSource (:input-source ctx) :pages (:pages ctx) :pageCount (:page-count ctx)
                :fullInputSha256 (:full-input-sha256 ctx) :reviewTools (:review-tools ctx)
                :submissionFile (:submission-file ctx)}))
    (catch :default _
      (throw (js/Error. "unestablished-review-trace: invalid full-input context")))))

(defn- decode-context [raw]
  (let [ctx (if (or (string? raw) (instance? js/Uint8Array raw))
              (strict-json (raw-text raw)) (js->clj raw :keywordize-keys true))
        prepared {:input-source (:inputSource ctx) :pages (:pages ctx)
                  :page-count (:pageCount ctx) :full-input-sha256 (:fullInputSha256 ctx)
                  :review-tools (:reviewTools ctx) :submission-file (:submissionFile ctx)}]
    ;; Optional full bytes are verified again. Without them this must be the
    ;; actual prepare export's result retained by the trusted caller; data
    ;; alone cannot authenticate its source or prove its preparation.
    (when (contains? ctx :fullDiff)
      (when-not (= prepared (context-from-input (:fullDiff ctx) (:inputSource ctx)
                                               (:reviewTools ctx) (:submissionFile ctx)))
        (invalid!)))
    {:input-source (:inputSource ctx) :pages (:pages ctx)
     :page-count (:pageCount ctx) :full-input-sha256 (:fullInputSha256 ctx)
     :review-tools (:reviewTools ctx) :submission-file (:submissionFile ctx)
     :session-id (:sessionID ctx)}))

(defn verify-review-invocation
  "CJS API: rawResponseText/rawSubmission accept strings or UTF8 byte arrays;
   rawSubmission may be null (missing). expectedFullInputContext must be caller
   trusted: actual prepare-review-invocation-context result in supervisor
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
                                      {:sessionID (:session-id accepted)
                                       :submissionCallID (:submission-call-id accepted)
                                       :submissionPosition (:submission-position accepted)
                                       :submissionFile (:submission-file accepted)
                                       :responseSha256 (raw-sha256 raw-response)
                                       :submissionSha256 (raw-sha256 raw-submission)
                                       :fullInputSha256 (:full-input-sha256 accepted)
                                       :pageCount (:page-count accepted)})}))
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
    (assoc (decode-context raw) :full-input-text (raw-text (:fullDiff ctx)))))

(defn classify-length-ended-review
  "UNPUBLISHED candidate boundary. Raw response/submission/context have the
   verify API's syntax, but context MUST include fullDiff for exact revalidation.
   rawCustody is caller-trusted JSON or JS data with invocationState, exitCode,
   submissionState, responseSha256Before/After and contextBefore/After (each with
   fullDiff). Actual response digest is derived here; all admission policy is law.
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
          custody {:invocation-state (:invocationState c) :exit-code (:exitCode c)
                   :submission-present? (some? raw-submission)
                   :submission-state (:submissionState c) :response-sha256 (raw-sha256 raw-response)
                   :response-sha256-before (:responseSha256Before c)
                   :response-sha256-after (:responseSha256After c)
                   :context-before (decode-length-context (clj->js (:contextBefore c)))
                   :context-after (decode-length-context (clj->js (:contextAfter c)))}
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
