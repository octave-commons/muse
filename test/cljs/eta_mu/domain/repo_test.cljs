(ns eta-mu.domain.repo-test
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.repo :as repo]))

(defn make-fs [existing] (fn [p] (contains? existing p)))
(defn make-join [] (fn [a b] (str a "/" b)))
(defn make-dirname []
  (fn [p]
    (let [idx (.lastIndexOf p "/")]
      (if (pos? idx) (subs p 0 idx) nil))))

(deftest find-git-root-direct
  (let [result (repo/find-git-root (make-join) (make-dirname)
                                   (make-fs #{"/repo/.git"})
                                   "/repo/src/core")]
    (is (= "/repo" result))))

(deftest find-git-root-walks-up
  (let [result (repo/find-git-root (make-join) (make-dirname)
                                   (make-fs #{"/mono/.git"})
                                   "/mono/packages/eta-mu/src")]
    (is (= "/mono" result))))

(deftest find-git-root-submodule
  (testing "stops at the nearest .git (submodule wins over monorepo root)"
    (let [result (repo/find-git-root (make-join) (make-dirname)
                                     (make-fs #{"/mono/.git" "/mono/packages/eta-mu/.git"})
                                     "/mono/packages/eta-mu/src")]
      (is (= "/mono/packages/eta-mu" result)))))

(deftest find-git-root-returns-nil-when-not-found
  (is (nil? (repo/find-git-root (make-join) (make-dirname) (make-fs #{})
                                "/tmp/no-git"))))

(deftest find-git-root-nil-path
  (is (nil? (repo/find-git-root (make-join) (make-dirname) (make-fs #{}) nil))))

(def fake-find-git-root
  (fn [path]
    (cond
      (and path (.startsWith path "/mono/packages/alpha")) "/mono/packages/alpha"
      (and path (.startsWith path "/mono/packages/beta"))  "/mono/packages/beta"
      (and path (.startsWith path "/mono/shared"))         "/mono"
      :else nil)))

(def tool-calls-mixed
  [{:tool/name "read_file"  :tool/params {:path "/mono/packages/alpha/src/core.cljs"}}
   {:tool/name "write_file" :tool/params {:path "/mono/packages/alpha/src/core.cljs"}}
   {:tool/name "read_file"  :tool/params {:path "/mono/packages/beta/README.md"}}
   {:tool/name "read_file"  :tool/params {:path "/mono/packages/beta/src/main.cljs"}}
   {:tool/name "bash"       :tool/params {:command "echo hi"}}])

(deftest touched-repos-counts-calls-per-repo
  (let [result (repo/touched-repos fake-find-git-root tool-calls-mixed)]
    (is (= 2 (get result "/mono/packages/alpha")))
    (is (= 2 (get result "/mono/packages/beta")))))

(deftest touched-repos-ignores-pathless-tools
  (let [result (repo/touched-repos fake-find-git-root tool-calls-mixed)]
    (is (not (contains? result nil)))))

(deftest touched-repos-empty
  (is (= {} (repo/touched-repos fake-find-git-root []))))

(deftest touched-repos-all-outside-git
  (let [calls [{:tool/name "read_file" :tool/params {:path "/tmp/scratch.txt"}}]]
    (is (= {} (repo/touched-repos fake-find-git-root calls)))))

(deftest should-activate-thresholds
  (is (false? (repo/should-activate? {:call-count 2 :threshold 3 :active? false})))
  (is (true?  (repo/should-activate? {:call-count 3 :threshold 3 :active? false})))
  (is (true?  (repo/should-activate? {:call-count 0 :threshold 3 :active? true})))
  (is (false? (repo/should-activate? {:call-count 2 :active? false})))
  (is (true?  (repo/should-activate? {:call-count 3 :active? false}))))

(deftest contract-violations-all-covered
  (is (empty? (repo/contract-violations
               {"/mono/packages/alpha" 3 "/mono/packages/beta" 1}
               #{"/mono/packages/alpha" "/mono/packages/beta"}))))

(deftest contract-violations-missing-receipt
  (let [result (repo/contract-violations
                {"/mono/packages/alpha" 3 "/mono/packages/beta" 2}
                #{"/mono/packages/alpha"})]
    (is (= ["/mono/packages/beta"] (vec result)))))

(deftest contract-violations-nothing-touched
  (is (empty? (repo/contract-violations {} #{}))))

(deftest contract-violations-read-only-repo-exempt
  (let [result (repo/contract-violations
                {"/mono/packages/alpha" 1 "/mono/packages/beta" 4}
                #{})]
    (is (= ["/mono/packages/beta"] (vec result)))))

(deftest contract-violations-returns-sorted
  (let [result (repo/contract-violations {"/b" 5 "/a" 5 "/c" 5} #{})]
    (is (= ["/a" "/b" "/c"] (vec result)))))
