(ns eta-mu.domain.mycology-test
  (:require [cljs.test :refer [deftest is testing]]
            [eta-mu.domain.mycology :as myco]))

(deftest slugify-normalizes
  (is (= "fix-the-build" (myco/slugify "Fix the Build!")))
  (is (= "skill-spore" (myco/slugify "")))
  (is (= "skill-spore" (myco/slugify nil))))

(deftest reflection-kind-classifies
  (is (= "sporeworthy" (myco/reflection-kind {:skill-candidate-p 0.8 :friction-p 0.1 :efficiency-p 0.5})))
  (is (= "gnarly" (myco/reflection-kind {:skill-candidate-p 0.1 :friction-p 0.7 :efficiency-p 0.5})))
  (is (= "smooth" (myco/reflection-kind {:skill-candidate-p 0.1 :friction-p 0.2 :efficiency-p 0.9})))
  (is (= "mixed" (myco/reflection-kind {:skill-candidate-p 0.1 :friction-p 0.5 :efficiency-p 0.5}))))

(deftest incubate-thresholds
  (is (true? (myco/incubate? {:skill-candidate-p 0.72 :friction-p 0})))
  (is (true? (myco/incubate? {:skill-candidate-p 0 :friction-p 0.68})))
  (is (not (myco/incubate? {:skill-candidate-p 0.5 :friction-p 0.5}))))

(deftest promotion-eligibility
  (testing "recurrence gate"
    (is (false? (myco/promotion-eligible? {:recurrence 1 :skillCandidateP 0.5})))
    (is (true?  (myco/promotion-eligible? {:recurrence 2 :skillCandidateP 0.5}))))
  (testing "exceptional single occurrence"
    (is (true?  (myco/promotion-eligible? {:recurrence 1 :skillCandidateP 0.9}))))
  (testing "turn-scoped spores need one extra recurrence"
    (is (false? (myco/promotion-eligible? {:reuseScope "turn" :recurrence 2 :skillCandidateP 0.9})))
    (is (true?  (myco/promotion-eligible? {:reuseScope "turn" :recurrence 3 :skillCandidateP 0.5}))))
  (testing "custom options"
    (is (true? (myco/promotion-eligible? {:recurrence 5 :skillCandidateP 0}
                                         {:min-recurrence 5})))
    (is (false? (myco/promotion-eligible? {:recurrence 4 :skillCandidateP 0}
                                          {:min-recurrence 5})))))

(def spore
  {:ts "2026-07-12T00:00:00.000Z"
   :name "Upstream failure first triage"
   :slug "upstream-failure-first-triage"
   :description "Check upstream service health before debugging local code."
   :reuseScope "multi-session"
   :cwd "/repo"
   :reflectionKind "sporeworthy"
   :recurrence 2
   :efficiencyP 0.4
   :frictionP 0.8
   :skillCandidateP 0.85})

(deftest live-skill-renders-frontmatter-and-lesson
  (let [text (myco/live-skill spore)]
    (is (.startsWith text "---\n"))
    (is (.includes text "name: upstream-failure-first-triage"))
    (is (.includes text "origin: session-mycology-promotion"))
    (is (.includes text "# Skill: Upstream failure first triage"))
    (is (.includes text (:description spore)))))

(deftest live-contract-renders-edn
  (let [text (myco/live-contract spore)]
    (is (.includes text "(skill-contract"))
    (is (.includes text "\"upstream-failure-first-triage\""))
    (is (.includes text "ημ.skill/upstream-failure-first-triage@0.1.0"))))

(deftest spore-draft-includes-promotion-gate
  (let [text (myco/spore-draft {:lesson "check upstream" :betterPath "curl first"}
                               spore)]
    (is (.includes text "# Skill Spore:"))
    (is (.includes text "check upstream"))
    (is (.includes text "curl first"))
    (is (.includes text "Promotion gate"))))
