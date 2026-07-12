(ns plugins.session-mycology
  "Per-turn retrospection with p-scores, skill-spore incubation, and
   promotion of recurring spores into live eta-mu skills
   (~/.eta-mu/agent/skills/<slug>/{SKILL.md,CONTRACT.edn}).
   Pure logic in eta-mu.domain.mycology; fs via the node boundary."
  (:require [clojure.string :as str]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.mycology :as myco]
            [eta-mu.dsl :refer [deftool defplugin]]))

(defn- state-dir [] (bfs/state-dir "session-mycology"))
(defn- reflections-file [] (bfs/join (state-dir) "turn-reflections.jsonl"))
(defn- spores-file [] (bfs/join (state-dir) "skill-spores.jsonl"))
(defn- promotions-file [] (bfs/join (state-dir) "skill-promotions.jsonl"))
(defn- drafts-dir [] (bfs/join (state-dir) "spores"))
(defn- skills-dir [] (bfs/join (bfs/home-dir) ".eta-mu" "agent" "skills"))

(defn- promotion-opts []
  {:min-recurrence (or (some-> (bfs/env "ETA_MU_MYCOLOGY_PROMOTION_MIN_RECURRENCE")
                               js/parseInt)
                       myco/default-promotion-min-recurrence)
   :hint-p (or (some-> (bfs/env "ETA_MU_MYCOLOGY_PROMOTION_HINT_P")
                       js/parseFloat)
               myco/default-promotion-hint-p)})

;; ---------------------------------------------------------------------------
;; Spore ledger queries
;; ---------------------------------------------------------------------------

(defn- recent-spores [directory limit]
  (->> (bfs/read-jsonl (spores-file) 400)
       (filter #(or (nil? directory) (= (:cwd %) directory)))
       (take-last limit)
       reverse
       vec))

(defn- latest-spore [slug directory]
  (->> (bfs/read-jsonl (spores-file) 400)
       (filter #(and (= (:slug %) slug)
                     (or (nil? directory) (= (:cwd %) directory))))
       last))

(defn- latest-spores-by-slug [directory]
  (->> (bfs/read-jsonl (spores-file) 1200)
       (filter #(and (:slug %)
                     (or (nil? directory) (= (:cwd %) directory))))
       (reduce (fn [acc spore] (assoc acc (:slug spore) spore)) {})
       vals
       vec))

;; ---------------------------------------------------------------------------
;; Promotion
;; ---------------------------------------------------------------------------

(defn- promote-spore!
  "Write a live eta-mu skill from an eligible spore. Idempotent: existing
   SKILL.md/CONTRACT.edn are never overwritten."
  [spore]
  (if-not (myco/promotion-eligible? spore (promotion-opts))
    {:slug (:slug spore) :promoted false :eligible false}
    (let [dir           (bfs/join (skills-dir) (:slug spore))
          skill-path    (bfs/join dir "SKILL.md")
          contract-path (bfs/join dir "CONTRACT.edn")
          created-skill (when-not (bfs/exists? skill-path)
                          (bfs/write-text! skill-path (myco/live-skill spore))
                          true)
          created-contract (when-not (bfs/exists? contract-path)
                             (bfs/write-text! contract-path (myco/live-contract spore))
                             true)]
      (when (or created-skill created-contract)
        (bfs/append-jsonl! (promotions-file)
                           {:ts (bfs/now-iso)
                            :slug (:slug spore)
                            :name (:name spore)
                            :recurrence (:recurrence spore)
                            :skillCandidateP (:skillCandidateP spore)
                            :cwd (:cwd spore)
                            :skillPath skill-path
                            :contractPath contract-path}))
      {:slug (:slug spore)
       :promoted (boolean (or created-skill created-contract))
       :eligible true
       :skillPath skill-path
       :contractPath contract-path})))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- list-recent [ctx]
  {:action "list_recent"
   :spores (recent-spores (:directory ctx) 5)})

(defn- promote [{:keys [slug]} ctx]
  (let [target     (str/trim (str (or slug "all")))
        all?       (= (str/lower-case target) "all")
        directory  (when-not all? (:directory ctx))
        candidates (cond->> (latest-spores-by-slug directory)
                     (not all?) (filter #(= (:slug %) (myco/slugify target))))
        results    (mapv promote-spore! candidates)]
    {:action "promote"
     :target target
     :considered (count results)
     :promoted (vec (filter :promoted results))
     :skipped (vec (remove :promoted results))}))

(defn- reflect [params ctx]
  (let [now        (bfs/now-iso)
        directory  (:directory ctx)
        reflection {:ts now
                    :cwd directory
                    :session (:session/id ctx)
                    :agent (:agent ctx)
                    :efficiency-p (myco/clamp-01 (:efficiencyP params) 0.5)
                    :friction-p (myco/clamp-01 (:frictionP params) 0.5)
                    :skill-candidate-p (myco/clamp-01 (:skillCandidateP params) 0.5)
                    :lesson (str/trim (str (or (:lesson params) "")))
                    :betterPath (str/trim (str (or (:betterPath params) "")))}
        _          (bfs/append-jsonl! (reflections-file)
                                      (assoc reflection
                                             :efficiencyP (:efficiency-p reflection)
                                             :frictionP (:friction-p reflection)
                                             :skillCandidateP (:skill-candidate-p reflection)))
        cand-name  (str/trim (str (or (:candidateName params) "")))
        cand-desc  (str/trim (str (or (:candidateDescription params) "")))
        incubate?  (and (seq cand-name) (seq cand-desc)
                        (myco/incubate? reflection))]
    (if-not incubate?
      {:action "reflect"
       :recorded true
       :incubated false
       :reflection (select-keys reflection
                                [:ts :efficiency-p :friction-p :skill-candidate-p])}
      (let [slug  (myco/slugify cand-name)
            prior (latest-spore slug directory)
            spore {:ts now
                   :name cand-name
                   :slug slug
                   :description cand-desc
                   :reuseScope (myco/normalize-reuse-scope (:reuseScope params))
                   :cwd directory
                   :reflectionTs now
                   :reflectionKind (myco/reflection-kind reflection)
                   :recurrence (max 1 (inc (long (or (:recurrence prior) 0))))
                   :efficiencyP (:efficiency-p reflection)
                   :frictionP (:friction-p reflection)
                   :skillCandidateP (:skill-candidate-p reflection)}
            draft-path (or (:draftPath prior)
                           (bfs/join (drafts-dir) (str slug ".md")))
            spore (assoc spore :draftPath draft-path)]
        (bfs/write-text! draft-path (myco/spore-draft reflection spore))
        (bfs/append-jsonl! (spores-file) spore)
        (let [promotion (promote-spore! spore)]
          {:action "reflect"
           :recorded true
           :incubated true
           :spore (select-keys spore [:name :slug :recurrence :draftPath :reuseScope])
           :promotion promotion})))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(deftool session-mycology
  {:id          :session/mycology
   :description "Record a per-turn retrospective with p-scores (efficiency, friction, skill-candidate) and incubate reusable skill spores when work felt harder than it should have. action=reflect near the end of a substantive turn; action=list_recent to inspect recent spores; action=promote to promote recurring spores into live eta-mu skills (optional slug, default all eligible)."
   :args        [:map
                 [:action [:enum "reflect" "list_recent" "promote"]]
                 [:efficiencyP {:optional true} :number]
                 [:frictionP {:optional true} :number]
                 [:skillCandidateP {:optional true} :number]
                 [:lesson {:optional true} :string]
                 [:betterPath {:optional true} :string]
                 [:candidateName {:optional true} :string]
                 [:candidateDescription {:optional true} :string]
                 [:reuseScope {:optional true} [:enum "turn" "session" "multi-session"]]
                 [:slug {:optional true} :string]]
   :tags        #{:mycology :retrospective :skills}}
  [{:keys [action] :as params} ctx]
  (case action
    "list_recent" (list-recent ctx)
    "promote"     (promote params ctx)
    "reflect"     (reflect params ctx)))

(defplugin plugin {:id :eta-mu/session-mycology}
  session-mycology)
