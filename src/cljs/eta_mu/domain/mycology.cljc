(ns eta-mu.domain.mycology
  "Pure session-mycology logic: p-score classification, spore shaping, and
   skill-promotion eligibility and rendering. No I/O."
  (:require [clojure.string :as str]))

(def spore-threshold 0.72)
(def friction-threshold 0.68)
(def default-promotion-min-recurrence 2)
(def default-promotion-hint-p 0.84)

(defn clamp-01 [v fallback]
  (if (number? v) (max 0 (min 1 v)) fallback))

(defn slugify [value]
  (let [s (-> (str (or value ""))
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) "skill-spore" s)))

(defn normalize-reuse-scope [value]
  (let [v (str/lower-case (str/trim (str (or value "session"))))]
    (if (contains? #{"turn" "session" "multi-session"} v) v "session")))

(defn reflection-kind
  [{:keys [skill-candidate-p friction-p efficiency-p]}]
  (cond
    (>= skill-candidate-p spore-threshold) "sporeworthy"
    (>= friction-p friction-threshold) "gnarly"
    (and (>= efficiency-p 0.75) (<= friction-p 0.35)) "smooth"
    :else "mixed"))

(defn incubate?
  "Should this reflection incubate a spore (given a named candidate)?"
  [{:keys [skill-candidate-p friction-p]}]
  (or (>= skill-candidate-p spore-threshold)
      (>= friction-p friction-threshold)))

;; ---------------------------------------------------------------------------
;; Promotion
;; ---------------------------------------------------------------------------

(defn promotion-eligible?
  "A spore earns promotion into a live eta-mu skill when it recurs enough,
   or a single occurrence scores an exceptional skill-candidate p.
   Turn-scoped spores need one extra recurrence — a pattern that only ever
   mattered within single turns must prove itself harder."
  ([spore] (promotion-eligible? spore {}))
  ([{:keys [reuseScope recurrence skillCandidateP]}
    {:keys [min-recurrence hint-p]
     :or {min-recurrence default-promotion-min-recurrence
          hint-p default-promotion-hint-p}}]
   (let [recurrence (or recurrence 0)
         skill-p (clamp-01 skillCandidateP 0)]
     (cond
       (and (= (str (or reuseScope "session")) "turn")
            (< recurrence (inc min-recurrence))) false
       (>= recurrence min-recurrence) true
       (>= skill-p hint-p) true
       :else false))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- quoted [value]
  (pr-str (str (or value ""))))

(defn spore-draft
  "Markdown draft for an incubated (not yet promoted) spore."
  [reflection spore]
  (str "# Skill Spore: " (:name spore) "\n\n"
       "- Generated: " (:ts spore) "\n"
       "- Recurrence: " (:recurrence spore) "\n"
       "- CWD: " (:cwd spore) "\n"
       "- Reuse scope: " (:reuseScope spore) "\n"
       "- Reflection kind: " (:reflectionKind spore) "\n"
       "- p-efficiency: " (:efficiencyP spore) "\n"
       "- p-friction: " (:frictionP spore) "\n"
       "- p-skill-candidate: " (:skillCandidateP spore) "\n\n"
       "## Lesson\n" (or (not-empty (:lesson reflection)) "_none captured_") "\n\n"
       "## Better path next time\n" (or (not-empty (:betterPath reflection)) "_none captured_") "\n\n"
       "## Candidate description\n" (:description spore) "\n\n"
       "## Promotion gate\n"
       "Promote this spore into a live skill after either:\n"
       "- recurrence >= " default-promotion-min-recurrence "\n"
       "- explicit user request\n"
       "- or strong evidence that the pattern generalizes beyond the current task\n"))

(defn live-skill
  "SKILL.md content for a promoted spore."
  [spore]
  (str "---\n"
       "name: " (:slug spore) "\n"
       "description: " (quoted (:description spore)) "\n"
       "license: GPL-3.0\n"
       "metadata:\n"
       "  origin: session-mycology-promotion\n"
       "  promoted-from-spore: " (:slug spore) "\n"
       "  recurrence: " (:recurrence spore) "\n"
       "---\n\n"
       "# Skill: " (:name spore) "\n\n"
       "## Goal\n"
       (:description spore) "\n\n"
       "## Use This Skill When\n"
       "- The same pattern or failure mode has recurred enough to deserve a named protocol.\n"
       "- The current task clearly matches the lesson captured by this promoted spore.\n\n"
       "## Do Not Use This Skill When\n"
       "- The situation is obviously unrelated to " (:name spore) ".\n"
       "- You only have a one-off glitch with no evidence that the recurring pattern applies.\n\n"
       "## Inputs\n"
       "- The current task context.\n"
       "- The relevant files, logs, or artifacts that exhibit the pattern.\n\n"
       "## Steps\n"
       "1. Verify the current task really matches the recurring pattern.\n"
       "2. Apply the core lesson from the originating spore: " (:description spore) "\n"
       "3. Prefer concrete evidence over narrative momentum.\n"
       "4. If the pattern no longer fits reality, update or retire this skill instead of forcing it.\n\n"
       "## Output\n"
       "- A truthful, concrete application of the pattern to the current task.\n"))

(defn live-contract
  "CONTRACT.edn content for a promoted spore."
  [spore]
  (str "(skill-contract\n"
       "  (name " (quoted (:slug spore)) ")\n"
       "  (v \"ημ.skill/" (:slug spore) "@0.1.0\")\n\n"
       "  (intent " (quoted (:description spore)) ")\n\n"
       "  (activation\n"
       "    (priority 41)\n"
       "    (explicit [\"skill:" (:slug spore) "\"])\n"
       "    (triggers [" (quoted (str/lower-case (str (:name spore))))
       " " (quoted (str/replace (str (:slug spore)) #"-" " ")) "]))\n\n"
       "  (governance\n"
       "    (touch-layer :mutable)\n"
       "    (non-override [:mission :directives :safety :license :output-shape])\n"
       "    (requires-user-approval false))\n)\n"))
