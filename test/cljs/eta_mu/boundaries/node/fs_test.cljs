(ns eta-mu.boundaries.node.fs-test
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer-macros [deftest is testing]]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.law.review-invocation-test :as fixture]))

(defn- read-native-input [dir]
  (try (bfs/read-review-input dir) (catch :default _ nil)))

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
