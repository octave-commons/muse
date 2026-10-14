(ns eta-mu.boundaries.node.fs-test
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.law.review-invocation-test :as fixture]))

(defn- read-native-input [dir]
  (try (bfs/read-review-input dir) (catch :default _ nil)))

(deftest ordinary-v2-filesystem-reads-the-three-originals-without-a-composite-file
  (let [{:keys [source texts view trusted]} (fixture/ordinary-v2-fixture)
        dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-ordinary-v2-"))
        manifest-path (.join path dir "input-manifest.json")]
    (try
      (doseq [[component text] (map vector (:components source) texts)]
        (.writeFileSync fs (.join path dir (:path component)) (js/Buffer.from text "utf8")))
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js source)))
      (is (false? (.existsSync fs (.join path dir "combined-input.txt"))))
      ;; JS .call reaches the same existing function with an additional pure
      ;; caller context. The predecessor ignores that extra argument and reaches
      ;; its real unsupported-v2 refusal, rather than a CLJS arity error.
      (let [actual (try (.call bfs/read-review-input nil dir trusted) (catch :default _ nil))]
        (is (some? actual) "existing filesystem boundary must admit complete v2 originals")
        (is (= source (:manifest actual)))
        (is (= view (:text actual)))
        (is (= (.toString (js/Buffer.from view "utf8") "hex")
               (when actual (.toString (js/Buffer.from (:text actual) "utf8") "hex")))))
      (is (thrown? js/Error (bfs/read-review-input dir))
          "complete v2 originals without separately supplied context must refuse")
      (doseq [changed [(assoc-in trusted [:producer :head_sha] (apply str (repeat 40 "f")))
                       (assoc-in trusted [:content :authorization] "unauthorized")
                       (assoc-in trusted [:content :authorization] "unknown")
                       (assoc-in trusted [:content :classification] "sensitive")
                       (assoc-in trusted [:content :credential_canary] true)]]
        (is (thrown? js/Error (.call bfs/read-review-input nil dir changed))))
      ;; These specialized refusal obligations need the admitted positive above
      ;; to turn green; predecessor blanket-v2 refusal supplies no enforcement credit.
      (doseq [[component text] (map vector (:components source) texts)]
        (let [full (.join path dir (:path component)) bytes (js/Buffer.from text "utf8")]
          (.writeFileSync fs full (.subarray bytes 0 (max 0 (dec (.-length bytes)))))
          (is (thrown? js/Error (.call bfs/read-review-input nil dir trusted)))
          (.writeFileSync fs full bytes)))
      (.unlinkSync fs (.join path dir "summary.json"))
      (is (thrown? js/Error (.call bfs/read-review-input nil dir trusted)))
      (finally (.rmSync fs dir #js {:recursive true :force true})))))

(defn- with-native-input [bytes f]
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-native-input-"))
        full (.join path dir "actionability-input.txt")
        manifest-path (.join path dir "input-manifest.json")
        manifest (assoc fixture/native-manifest :full_input
                        {:path "actionability-input.txt" :bytes (.-length bytes)
                         :sha256 (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))})]
    (try
      (.writeFileSync fs full bytes)
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (f dir full manifest-path manifest)
      (finally (.rmSync fs dir #js {:recursive true :force true})))))

(deftest ordinary-v2-bounded-reader-refuses-before-allocation-and-file-races
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-bounded-read-"))
        file (.join path dir "basehead.diff")
        alloc (.-alloc js/Buffer) read-sync (.-readSync fs) allocations (atom [])]
    (try
      (.writeFileSync fs file "short original")
      (is (= "short original" (.toString (bfs/read-bounded-file file 4194304 nil) "utf8")))
      (.truncateSync fs file 4194305)
      (set! (.-alloc js/Buffer) (fn [size] (swap! allocations conj size) (.call alloc js/Buffer size)))
      (is (thrown? js/Error (bfs/read-bounded-file file 4194304 nil)))
      (is (empty? @allocations) "oversized actual stat refuses before byte-buffer allocation")
      (set! (.-alloc js/Buffer) alloc)
      (.writeFileSync fs file "short original")
      (let [grew? (atom false)]
        (set! (.-readSync fs)
              (fn [fd buffer offset length position]
                (let [n (.call read-sync fs fd buffer offset length position)]
                  (when (and (pos? n) (compare-and-set! grew? false true)) (.appendFileSync fs file "!"))
                  n)))
        (is (thrown-with-msg? js/Error #"changed during" (bfs/read-bounded-file file 4194304 nil))))
      (set! (.-readSync fs) (fn [& _] 0))
      (is (thrown-with-msg? js/Error #"short read" (bfs/read-bounded-file file 4194304 nil)))
      (finally
        (set! (.-alloc js/Buffer) alloc)
        (set! (.-readSync fs) read-sync)
        (.rmSync fs dir #js {:recursive true :force true})))))

(deftest ordinary-v2-metadata-profile-is-bounded-before-allocation
  (let [{:keys [source texts view trusted]} (fixture/ordinary-v2-fixture)
        dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-v2-metadata-"))
        file (.join path dir "input-manifest.json") cap 9437184
        manifest (js/JSON.stringify (clj->js source)) alloc (.-alloc js/Buffer) allocations (atom [])]
    (try
      (doseq [[component text] (map vector (:components source) texts)]
        (.writeFileSync fs (.join path dir (:path component)) text "utf8"))
      (bfs/write-text! file (str manifest (.repeat " " (- cap (count manifest)))))
      (is (= view (:text (.call bfs/read-review-input nil dir trusted))))
      (.truncateSync fs file (inc cap))
      (set! (.-alloc js/Buffer) (fn [size] (swap! allocations conj size) (.call alloc js/Buffer size)))
      (is (thrown? js/Error (.call bfs/read-review-input nil dir trusted)))
      (is (empty? @allocations) "v2-only oversized metadata stat refuses before allocation")
      (set! (.-alloc js/Buffer) alloc)
      (bfs/write-text! file (js/JSON.stringify (clj->js fixture/manifest)))
      (is (thrown? js/Error (.call bfs/read-review-input nil dir trusted))
          "explicit caller profile cannot downgrade to legacy schema")
      (let [old (fixture/ordinary-v2-fixture ["older diff" "{}" "older log"])
            head (apply str (repeat 40 "f"))
            old-source (-> (:source old) (assoc :head_sha head) (assoc-in [:producer :head_sha] head))]
        (doseq [[component text] (map vector (:components old-source) (:texts old))]
          (.writeFileSync fs (.join path dir (:path component)) text "utf8"))
        (bfs/write-text! file (js/JSON.stringify (clj->js old-source)))
        (is (thrown? js/Error (.call bfs/read-review-input nil dir trusted))))
      (finally (set! (.-alloc js/Buffer) alloc) (.rmSync fs dir #js {:recursive true :force true})))))

(deftest native-input-retains-complete-unicode-bytes-and-cannot-read-diff-fallback
  (let [text (str (apply str (repeat 300001 "x")) "\noriginal body ημ 😀\n")
        bytes (js/Buffer.from text "utf8")]
    (with-native-input bytes
      (fn [dir full _ manifest]
        (is (true? (= {:manifest manifest :text text} (read-native-input dir))))
        (.writeFileSync fs (.join path dir "basehead.diff") bytes)
        (.writeFileSync fs full (.subarray bytes 0 300000))
        (is (thrown? js/Error (bfs/read-review-input dir)))
        (.writeFileSync fs full bytes)
        (is (true? (= text (:text (read-native-input dir)))))
        (let [changed (js/Buffer.from bytes)]
          (aset changed (dec (.-length changed)) 32)
          (.writeFileSync fs full changed)
          (is (thrown? js/Error (bfs/read-review-input dir))))
        (.unlinkSync fs full)
        (is (thrown? js/Error (bfs/read-review-input dir))))))
  (with-native-input (js/Buffer.from #js [255])
    (fn [dir _ _ _] (is (thrown? js/Error (bfs/read-review-input dir)))))
  (with-native-input (js/Buffer.from "" "utf8")
    (fn [dir _ _ _] (is (thrown? js/Error (bfs/read-review-input dir))))))

(deftest native-filesystem-reuses-the-fixed-transport-shape
  (with-native-input (js/Buffer.from "context" "utf8")
    (fn [dir _ manifest-path manifest]
      (doseq [changed [(assoc manifest :purpose "diff")
                       (assoc manifest :full_diff {:path "basehead.diff" :bytes 7 :sha256 (get-in manifest [:full_input :sha256])})
                       (assoc-in manifest [:full_input :path] "/etc/passwd")
                       (assoc manifest :proposal_id 0)
                       (assoc-in manifest [:target :root_comment_id] "7")
                       (assoc manifest :context_sha256 "bad")]]
        (bfs/write-text! manifest-path (js/JSON.stringify (clj->js changed)))
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (is (= manifest (:manifest (read-native-input dir)))))))

(defn- with-input [bytes f]
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-full-input-"))
        full (.join path dir "basehead.diff")
        manifest-path (.join path dir "input-manifest.json")
        manifest {:schema "open-hax.review-input/v1"
                  :base_sha (apply str (repeat 40 "a"))
                  :diff_base_sha (apply str (repeat 40 "a"))
                  :head_sha (apply str (repeat 40 "b"))
                  :full_diff {:path "basehead.diff" :bytes (.-length bytes)
                              :sha256 (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))}}]
    (try
      (.writeFileSync fs full bytes)
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (f dir full manifest-path manifest)
      (finally (.rmSync fs dir #js {:recursive true :force true})))))

(deftest manifest-protects-tail-bytes-and-recovers-full-input
  (let [text (str (apply str (repeat 300001 "x")) "\n+tail ημ 😀\n")
        bytes (js/Buffer.from text "utf8")]
    (with-input bytes
      (fn [dir full _ manifest]
        (is (= text (:text (bfs/read-review-input dir))))
        (is (= manifest (:manifest (bfs/read-review-input dir))))
        (testing "a prefix with the original full manifest cannot masquerade as complete input"
          (.writeFileSync fs full (.subarray bytes 0 300000))
          (is (thrown-with-msg? js/Error #"missing bytes" (bfs/read-review-input dir))))
        (testing "restoring the exact full bytes restores lawful input"
          (.writeFileSync fs full bytes)
          (is (= text (:text (bfs/read-review-input dir)))))
        (testing "same-length corruption is also rejected"
          (let [mutated (js/Buffer.from bytes)]
            (aset mutated (dec (.-length mutated)) 32)
            (.writeFileSync fs full mutated)
            (is (thrown-with-msg? js/Error #"immutable manifest" (bfs/read-review-input dir)))))))))

(deftest manifest-cannot-redirect-or-omit-full-input
  (with-input (js/Buffer.from "diff --git a/ημ b/ημ\n" "utf8")
    (fn [dir full manifest-path manifest]
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js (assoc-in manifest [:full_diff :path] "../other.diff"))))
      (is (thrown-with-msg? js/Error #"Invalid full-review input manifest" (bfs/read-review-input dir)))
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (.unlinkSync fs full)
      (is (thrown? js/Error (bfs/read-review-input dir)))
      (.unlinkSync fs manifest-path)
      (is (thrown? js/Error (bfs/read-review-input dir))))))

(deftest verified-bytes-still-require-lossless-utf8
  (with-input (js/Buffer.from #js [255])
    (fn [dir _ _ _]
      (is (thrown? js/Error (bfs/read-review-input dir))))))

(deftest manifest-supports-complete-commit-identities
  (with-input (js/Buffer.from "+complete input\n" "utf8")
    (fn [dir _ manifest-path manifest]
      (let [sha256-id (apply str (repeat 64 "a"))
            complete (assoc manifest :base_sha sha256-id :head_sha sha256-id :diff_base_sha sha256-id)]
        (bfs/write-text! manifest-path (js/JSON.stringify (clj->js complete)))
        (is (= complete (:manifest (bfs/read-review-input dir))))
        (doseq [invalid [(apply str (repeat 41 "a"))
                         (apply str (repeat 63 "a"))
                         (apply str (repeat 65 "a"))
                         (str sha256-id "z")]]
          (bfs/write-text! manifest-path (js/JSON.stringify (clj->js (assoc complete :head_sha invalid))))
          (is (thrown-with-msg? js/Error #"Invalid full-review input manifest" (bfs/read-review-input dir))))))))

(defn- ambiguous-manifests [manifest]
  (let [text (js/JSON.stringify (clj->js manifest))]
    [(str "{\"schema\":\"invalid\"," (subs text 1))
     (str "{\"sch\\u0065ma\":\"invalid\"," (subs text 1))
     (str "{\"schema\":" (js/JSON.stringify (:schema manifest)) "," (subs text 1))
     (str/replace text "\"sha256\":" "\"sha256\":\"invalid\",\"sha256\":")
     (str "{\"ignored\":{\"x\":1,\"x\":2}," (subs text 1))
     (str "{\"ignored\":1e309," (subs text 1))]))

(deftest filesystem-manifests-refuse-duplicate-escaped-nested-and-nonfinite-members
  (doseq [with-fixture [with-native-input with-input]]
    (with-fixture (js/Buffer.from "context" "utf8")
      (fn [dir _ manifest-path manifest]
        (doseq [text (ambiguous-manifests manifest)]
          (bfs/write-text! manifest-path text)
          (is (thrown? js/Error (bfs/read-review-input dir))))
        (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
        (is (= manifest (:manifest (bfs/read-review-input dir)))))))
  (with-native-input (js/Buffer.from "context" "utf8")
    (fn [dir _ manifest-path manifest]
      (bfs/write-text! manifest-path (str "{\"purpose\":\"diff\"," (subs (js/JSON.stringify (clj->js manifest)) 1)))
      (is (thrown? js/Error (bfs/read-review-input dir)))
      (bfs/write-text! manifest-path
                       (str/replace (js/JSON.stringify (clj->js manifest)) "\"context_sha256\":"
                                    "\"context_sha256\":\"invalid\",\"context_sha256\":"))
      (is (thrown? js/Error (bfs/read-review-input dir))))))

(deftest filesystem-input-preserves-leading-bom-covered-by-byte-digest
  (doseq [text ["\uFEFF" "\uFEFForiginal context ημ 😀\n" "\uFEFF\uFEFFcontext"]
          with-fixture [with-native-input with-input]]
    (let [bytes (js/Buffer.from text "utf8")]
      (with-fixture bytes
        (fn [dir _ _ _]
          (let [actual (:text (bfs/read-review-input dir))]
            (is (= text actual))
            (is (= (count text) (count actual)))
            (is (= (.toString bytes "hex") (.toString (js/Buffer.from actual "utf8") "hex")))))))))

(deftest filesystem-manifest-requires-fatal-utf8-and-json-bom-refusal
  (with-native-input (js/Buffer.from "context" "utf8")
    (fn [dir _ manifest-path manifest]
      (let [json (js/JSON.stringify (clj->js manifest))]
        (bfs/write-text! manifest-path (str "\uFEFF" json))
        (is (thrown? js/Error (bfs/read-review-input dir)))
        (.writeFileSync fs manifest-path
                        (js/Buffer.concat #js [(js/Buffer.from "{\"ignored\":\"")
                                               (js/Buffer.from #js [255])
                                               (js/Buffer.from (str "\"," (subs json 1)))]))
        (is (thrown? js/Error (bfs/read-review-input dir)))))))

(deftest derived-large-legacy-metadata-and-symlinks-keep-existing-policy
  (with-input (js/Buffer.from "diff" "utf8")
    (fn [dir full manifest-path manifest]
      (bfs/write-text! manifest-path (str (js/JSON.stringify (clj->js manifest)) (.repeat " " 9437185)))
      (is (= "diff" (:text (bfs/read-review-input dir))))
      (let [manifest-target (.join path dir "owned-manifest.json") diff-target (.join path dir "owned.diff")]
        (.renameSync fs manifest-path manifest-target)
        (.renameSync fs full diff-target)
        (.symlinkSync fs manifest-target manifest-path)
        (.symlinkSync fs diff-target full)
        (is (= {:manifest manifest :text "diff"} (bfs/read-review-input dir)))))))


(defn- with-rejection-input [bytes f]
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-rejection-input-"))
        full (.join path dir "rejection-assessment-input.txt")
        manifest-path (.join path dir "input-manifest.json")
        manifest (assoc fixture/rejection-manifest :full_input
                        {:path "rejection-assessment-input.txt" :bytes (.-length bytes)
                         :sha256 (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))})]
    (try
      (.writeFileSync fs full bytes)
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (f dir full manifest-path manifest)
      (finally (.rmSync fs dir #js {:recursive true :force true})))))

(deftest rejection-filesystem-keeps-original-bytes-without-other-purpose-fallback
  (let [text (str (apply str (repeat 300001 "x")) "\noriginal proposal ημ 😀\n")
        bytes (js/Buffer.from text "utf8")]
    (with-rejection-input bytes
      (fn [dir full _ manifest]
        (is (= {:manifest manifest :text text} (bfs/read-review-input dir)))
        (.writeFileSync fs (.join path dir "basehead.diff") bytes)
        (.writeFileSync fs (.join path dir "actionability-input.txt") bytes)
        (.writeFileSync fs full (.subarray bytes 0 300000))
        (is (thrown? js/Error (bfs/read-review-input dir)))
        (.writeFileSync fs full bytes)
        (is (= text (:text (bfs/read-review-input dir))))
        (let [changed (js/Buffer.from bytes)]
          (aset changed (dec (.-length changed)) 32)
          (.writeFileSync fs full changed)
          (is (thrown? js/Error (bfs/read-review-input dir))))
        (.unlinkSync fs full)
        (is (thrown? js/Error (bfs/read-review-input dir))))))
  (doseq [bytes [(js/Buffer.from #js [255]) (js/Buffer.from "" "utf8")]]
    (with-rejection-input bytes
      (fn [dir _ _ _] (is (thrown? js/Error (bfs/read-review-input dir)))))))

(deftest rejection-filesystem-reuses-strict-json-fatal-utf8-and-original-bom
  (doseq [text ["\uFEFF" "\uFEFForiginal proposal ημ 😀\n" "\uFEFF\uFEFFcontext"]]
    (with-rejection-input (js/Buffer.from text "utf8")
      (fn [dir _ _ _]
        (is (= text (:text (bfs/read-review-input dir)))))))
  (with-rejection-input (js/Buffer.from "context" "utf8")
    (fn [dir _ manifest-path manifest]
      (doseq [text (ambiguous-manifests manifest)]
        (bfs/write-text! manifest-path text)
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (doseq [source [(assoc manifest :purpose "actionability")
                      (assoc manifest :full_diff (:full_diff fixture/manifest))
                      (assoc-in manifest [:full_input :path] "../rejection-assessment-input.txt")
                      (assoc manifest :proposal_id 0)]]
        (bfs/write-text! manifest-path (js/JSON.stringify (clj->js source)))
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (let [text (js/JSON.stringify (clj->js manifest))]
        (bfs/write-text! manifest-path (str "\uFEFF" text))
        (is (thrown? js/Error (bfs/read-review-input dir)))
        (.writeFileSync fs manifest-path
                        (js/Buffer.concat #js [(js/Buffer.from "{\"ignored\":\"")
                                               (js/Buffer.from #js [255])
                                               (js/Buffer.from (str "\"," (subs text 1)))]))
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (is (= manifest (:manifest (bfs/read-review-input dir)))))))


(defn- with-cohort-input [bytes f]
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "muse-cohort-input-"))
        full (.join path dir "positive-cohort-assessment-input.txt")
        manifest-path (.join path dir "input-manifest.json")
        manifest (assoc fixture/cohort-manifest :full_input
                        {:path "positive-cohort-assessment-input.txt" :bytes (.-length bytes)
                         :sha256 (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))})]
    (try
      (.writeFileSync fs full bytes)
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (f dir full manifest-path manifest)
      (finally (.rmSync fs dir #js {:recursive true :force true})))))

(deftest cohort-filesystem-retains-full-tail-and-has-no-other-purpose-fallback
  (let [text (str (apply str (repeat 300001 "x")) "\nall original cohort members ημ 😀\n")
        bytes (js/Buffer.from text "utf8")]
    (with-cohort-input bytes
      (fn [dir full _ manifest]
        (is (= {:manifest manifest :text text} (bfs/read-review-input dir)))
        (doseq [name ["basehead.diff" "actionability-input.txt" "rejection-assessment-input.txt"]]
          (.writeFileSync fs (.join path dir name) bytes))
        (.writeFileSync fs full (.subarray bytes 0 300000))
        (is (thrown? js/Error (bfs/read-review-input dir)))
        (.writeFileSync fs full bytes)
        (is (= text (:text (bfs/read-review-input dir))))
        (let [changed (js/Buffer.from bytes)]
          (aset changed (dec (.-length changed)) 32)
          (.writeFileSync fs full changed)
          (is (thrown? js/Error (bfs/read-review-input dir))))
        (.unlinkSync fs full)
        (is (thrown? js/Error (bfs/read-review-input dir))))))
  (doseq [bytes [(js/Buffer.from #js [255]) (js/Buffer.from "" "utf8")]]
    (with-cohort-input bytes
      (fn [dir _ _ _] (is (thrown? js/Error (bfs/read-review-input dir)))))))

(deftest cohort-filesystem-reuses-strict-json-target-shape-and-original-bom
  (doseq [text ["\uFEFF" "\uFEFForiginal cohort ημ 😀\n" "\uFEFF\uFEFFcontext"]]
    (with-cohort-input (js/Buffer.from text "utf8")
      (fn [dir _ _ _] (is (= text (:text (bfs/read-review-input dir)))))))
  (with-cohort-input (js/Buffer.from "context" "utf8")
    (fn [dir _ manifest-path manifest]
      (doseq [text (ambiguous-manifests manifest)]
        (bfs/write-text! manifest-path text)
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (doseq [source [(assoc manifest :purpose "rejection-assessment")
                      (assoc-in manifest [:target :cohort_targets] [42])
                      (assoc-in manifest [:target :cohort_targets] [["PRRT_fixture_B" 8] ["PRRT_fixture_A" 7]])
                      (assoc-in manifest [:target :thread_id] "PRRT_fixture_A")
                      (assoc-in manifest [:full_input :path] "rejection-assessment-input.txt")
                      (assoc-in manifest [:full_input :bytes] 6)]]
        (bfs/write-text! manifest-path (js/JSON.stringify (clj->js source)))
        (is (thrown? js/Error (bfs/read-review-input dir))))
      (bfs/write-text! manifest-path (js/JSON.stringify (clj->js manifest)))
      (is (= manifest (:manifest (bfs/read-review-input dir)))))))
