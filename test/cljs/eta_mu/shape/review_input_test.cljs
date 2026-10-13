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
