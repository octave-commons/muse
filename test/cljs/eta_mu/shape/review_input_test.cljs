;; SPDX-License-Identifier: GPL-3.0-or-later
(ns eta-mu.shape.review-input-test
  "Characterize the unchanged fixed manifest projection and law boundary."
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.shape.review-input :as shape]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.law.review-invocation-test :as fixture]))

(deftest law-validates-and-shape-projects-original-descriptor
  (doseq [[source key] [[fixture/manifest :full_diff] [fixture/native-manifest :full_input]]]
    (let [descriptor (assoc (get source key) :retained-extra "original descriptor data")
          source (assoc source key descriptor)]
      (is (true? (invocation/input-source-valid? source)))
      (is (identical? descriptor (shape/input-descriptor source)))
      (is (= descriptor (shape/input-descriptor source)))))
  (let [source (assoc fixture/manifest :full_input (:full_input fixture/native-manifest))]
    (testing "ordinary descriptor precedence retains its existing accepted shape"
      (is (= (:full_diff source) (shape/input-descriptor source)))))
  (testing "ordinary source validation does not invent native provenance"
    (let [source (dissoc fixture/manifest :provenance)]
      (is (true? (invocation/input-source-valid? source)))
      (is (= (:full_diff source) (shape/input-descriptor source)))
      (is (false? (invocation/context-valid? (assoc fixture/context :input-source source)))))))

(deftest descriptor-preserves-complete-oids-and-ordinary-empty-input
  (let [oid64 (apply str (repeat 64 "a"))
        empty-digest "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        source (assoc fixture/manifest :base_sha oid64 :head_sha oid64 :diff_base_sha oid64
                      :full_diff {:path "basehead.diff" :bytes 0 :sha256 empty-digest})
        context (assoc fixture/context :input-source source :pages [] :page-count 0
                       :full-input-sha256 empty-digest)]
    (is (true? (invocation/input-source-valid? source)))
    (is (= (:full_diff source) (shape/input-descriptor source)))
    (is (true? (invocation/context-valid? context))))
  (let [source (-> fixture/native-manifest
                   (assoc :head_sha (apply str (repeat 64 "b")))
                   (assoc-in [:provenance :workflow_sha] (apply str (repeat 64 "d"))))]
    (is (= (:full_input source) (shape/input-descriptor source))))
  (testing "ordinary descriptor validation and context numeric bound remain distinct"
    (let [source (assoc-in fixture/manifest [:full_diff :bytes] 9007199254740992)]
      (is (= (:full_diff source) (shape/input-descriptor source)))
      (is (false? (invocation/context-valid? (assoc fixture/context :input-source source)))))))

(deftest descriptor-refuses-invalid-or-native-mixed-transport
  (doseq [source [nil [] {} (assoc fixture/manifest :schema "unknown")
                  (assoc-in fixture/manifest [:full_diff :path] "../other.diff")
                  (assoc-in fixture/manifest [:full_diff :bytes] -1)
                  (assoc-in fixture/manifest [:full_diff :sha256] "short")
                  (assoc fixture/manifest :head_sha (apply str (repeat 41 "a")))
                  (assoc fixture/native-manifest :purpose "diff")
                  (assoc fixture/native-manifest :full_diff (:full_diff fixture/manifest))
                  (assoc-in fixture/native-manifest [:full_input :path] "basehead.diff")
                  (assoc-in fixture/native-manifest [:full_input :bytes] 0)
                  (assoc-in fixture/native-manifest [:full_input :bytes] 9007199254740992)
                  (assoc fixture/native-manifest :proposal_id 0)
                  (assoc fixture/native-manifest :context_sha256 "short")
                  (assoc-in fixture/native-manifest [:target :thread_id] " ")
                  (assoc-in fixture/native-manifest [:target :root_comment_id] 0)
                  (dissoc fixture/native-manifest :provenance)]]
    (is (false? (invocation/input-source-valid? source)))
    (is (nil? (shape/input-descriptor source)))))

(deftest context-validation-preserves-selected-digest-and-native-page-refusals
  (doseq [source [fixture/manifest fixture/native-manifest]]
    (let [context (assoc fixture/context :input-source source)]
      (is (true? (invocation/context-valid? context)))
      (is (false? (invocation/context-valid? (assoc context :full-input-sha256 (apply str (repeat 64 "f"))))))))
  (is (false? (invocation/context-valid? (assoc fixture/context :input-source fixture/native-manifest
                                              :pages [] :page-count 0)))))


(deftest ordinary-v2-descriptor-selects-the-view-without-changing-raw-diff
  (let [{:keys [source texts]} (fixture/ordinary-v2-fixture)]
    (is (= (:view source) (shape/input-descriptor source)))
    (is (= (fixture/byte-descriptor "basehead.diff" (first texts)) (:full_diff source)))
    (is (not= (get-in source [:full_diff :sha256]) (get-in source [:view :sha256])))))

(deftest rejection-descriptor-is-the-original-validated-full-input-map
  (let [descriptor (assoc (:full_input fixture/rejection-manifest) :retained-extra "original carrier data")
        source (assoc fixture/rejection-manifest :full_input descriptor)]
    (is (true? (invocation/input-source-valid? source)))
    (is (identical? descriptor (shape/input-descriptor source)))
    (is (= descriptor (shape/input-descriptor source))))
  (doseq [source [(assoc fixture/rejection-manifest :purpose "actionability")
                  (assoc fixture/rejection-manifest :schema "open-hax.actionability-input/v1")
                  (assoc fixture/rejection-manifest :full_diff (:full_diff fixture/manifest))
                  (assoc-in fixture/rejection-manifest [:full_input :path] "actionability-input.txt")
                  (dissoc fixture/rejection-manifest :provenance)]]
    (is (nil? (shape/input-descriptor source)))))


(deftest cohort-descriptor-preserves-the-original-validated-full-input-map
  (let [descriptor (assoc (:full_input fixture/cohort-manifest) :retained-extra "original cohort bytes")
        source (assoc fixture/cohort-manifest :full_input descriptor)]
    (is (true? (invocation/input-source-valid? source)))
    (is (identical? descriptor (shape/input-descriptor source)))
    (is (= descriptor (shape/input-descriptor source))))
  (doseq [source (fixture/malformed-cohort-sources)]
    (is (nil? (shape/input-descriptor source))))
  (let [source (-> fixture/cohort-manifest
                   (assoc :head_sha (apply str (repeat 64 "b")))
                   (assoc-in [:provenance :workflow_sha] (apply str (repeat 64 "d"))))]
    (is (= (:full_input source) (shape/input-descriptor source))))
  (is (false? (invocation/context-valid?
               (assoc fixture/context :input-source fixture/cohort-manifest
                      :full-input-sha256 (apply str (repeat 64 "f")))))))


(deftest ordinary-v2-combined-component-page-ceiling-precedes-materialization
  ;; The fixed ceiling bounds delivered component pages, independently of the
  ;; transport view geometry. Each witness remains under every byte ceiling.
  (doseq [[log-lines expected] [[130816 1024] [130817 1025] [131070 1026]]]
    (let [texts ["\n" "\n" (.repeat "\n" log-lines)]
          {:keys [source view]} (fixture/ordinary-v2-fixture texts)
          materialize shape/diff-chunks calls* (atom [])]
      (is (invocation/ordinary-v2-contract-valid? source))
      (is (<= (get-in source [:view :page_count]) 1024)
          "the aggregate view is admitted, even when role boundaries add pages")
      (is (= expected (reduce + (map shape/bounded-page-count texts))))
      (with-redefs [shape/diff-chunks (fn [text]
                                      (swap! calls* conj (count text))
                                      (materialize text))]
        (if (= expected 1024)
          (let [pages (shape/component-chunks source view)]
            (is (= 1024 (count pages)))
            (is (= view (apply str (map :text pages))))
            (is (= 3 (count @calls*))))
          (do
            (is (thrown? js/Error (shape/component-chunks source view))
                "combined component cap must refuse the +1 and native +2 cases")
            (is (= [] @calls*)
                "all component counts must be admitted before collecting any pages")))))))


(deftest raw-hunk-spans-stop-at-counts-and-preserve-exact-page-text
  (let [raw "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\r\n+😀new\r\n\\ No newline at end of file\noutside\n"
        {:keys [source view]} (fixture/ordinary-v2-fixture [raw "{}" "fixture log"])
        pages (shape/component-chunks source view)
        page (first pages)
        spans (:hunk-spans page)
        changed (:changed-spans page)]
    (is (= raw (:text page)))
    (is (= [(.indexOf raw "@@") (.indexOf raw "outside")] (first spans)))
    (is (= (.indexOf raw "-old") (first (first changed))))
    (is (= (.indexOf raw "\\ No newline") (second (first changed))))
    (is (invocation/component-pages-valid? source (:components source) (:view source) pages))
    (doseq [changed-pages [(assoc-in pages [0 :hunk-spans] [[0 (inc (count raw))]])
                           (assoc-in pages [0 :changed-spans] [[0 1]])
                           (assoc-in pages [0 :hunk-spans] [[1 0]])]]
      (is (false? (invocation/component-pages-valid? source (:components source) (:view source) changed-pages))))))
