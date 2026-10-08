(ns eta-mu.domain.review-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [eta-mu.domain.review :as review]))

(deftest deterministic-requires-complete-input-without-advancing
  (let [diff (str "diff --git a/tail b/tail\n--- a/tail\n+++ b/tail\n@@ -0,0 +1,130 @@\n"
                  (apply str (repeat 130 "+line\n")))
        initial (review/begin diff)
        delivered (review/read-diff-chunk initial 1)
        assessed (review/assess-diff-chunk (:session delivered) 1 "Prefix only.")]
    (is (= 2 (count (:diff-chunks initial))))
    (doseq [session [initial (:session delivered) (:session assessed)]]
      (let [result (review/record-evidence session :deterministic "Premature stage evidence.")]
        (is (false? (:ok? result)))
        (is (nil? (:session result)) "Refusal supplies no advanced state.")
        (is (= :deterministic (:stage session)))
        (is (= [] (:evidence session)))
        (is (re-find #"Unassessed full-input chunks" (or (:error result) "")))))))

(deftest complete-input-and-empty-input-admit-deterministic
  (doseq [diff ["" "diff"]]
    (let [initial (review/begin diff)
          assessed (reduce (fn [session {:keys [id]}]
                             (let [read (review/read-diff-chunk session id)]
                               (:session (review/assess-diff-chunk (:session read) id "Complete input."))))
                           initial (:diff-chunks initial))
          result (review/record-evidence assessed :deterministic "All pages assessed first.")]
      (is (:ok? result))
      (is (= :map-change (get-in result [:session :stage])))
      (is (= [{:stage :deterministic :note "All pages assessed first."}]
             (get-in result [:session :evidence]))))))

(deftest deterministic-preserves-truncation-and-restart-refusals
  (let [truncated (review/begin "diff\n[eta-mu review] diff truncated at 4 bytes (was 8).\n")
        assessed (reduce (fn [session {:keys [id]}]
                           (:session (review/assess-diff-chunk
                                      (:session (review/read-diff-chunk session id)) id "Preview assessed.")))
                         truncated (:diff-chunks truncated))
        refused (review/record-evidence assessed :deterministic "Preview is insufficient.")
        restarted (:session (review/reject-restart (review/begin "diff")))
        restart (review/record-evidence restarted :deterministic "Restart is insufficient.")]
    (is (false? (:ok? refused)))
    (is (re-find #"truncated preview" (or (:error refused) "")))
    (is (false? (:ok? restart)))
    (is (re-find #"fresh invocation" (or (:error restart) "")))))

(def sample-diff
  (str/join
   "\n"
   ["diff --git a/src/example.js b/src/example.js"
    "--- a/src/example.js"
    "+++ b/src/example.js"
    "@@ -10,4 +10,5 @@ function example() {"
    " context"
    "-old"
    "+new"
    "+another"
    " tail"
    "diff --git a/src/other.js b/src/other.js"
    "--- a/src/other.js"
    "+++ b/src/other.js"
    "@@ -1 +1,2 @@"
    "+added"
    " kept"]))

(defn- begun []
  (review/begin sample-diff))

(defn- assess-all [session]
  (reduce (fn [s id]
            (let [delivered (review/read-diff-chunk s id)]
              (:session (review/assess-diff-chunk (:session delivered) id "Fixture assessed the full changed hunk."))))
          session (:missing (review/input-coverage session))))

(defn- through-stage
  "Advance session to the given stage by recording evidence."
  [session stage]
  (loop [s (assess-all session)]
    (if (= (:stage s) stage)
      s
      (let [result (review/record-evidence s (:stage s) (str "note for " (name (:stage s))))]
        (assert (:ok? result) (:error result))
        (recur (:session result))))))

(def quoted-receipt-diff
  ;; Git-emitted header from the isolated red fixture, with quotePath=true.
  ;; Native Foresight125/126 evidence uses this same C-octal UTF-8 spelling.
  (str/join "\n"
            ["diff --git \"a/.\\316\\267\\316\\274/receipts.edn\" \"b/.\\316\\267\\316\\274/receipts.edn\""
             "--- \"a/.\\316\\267\\316\\274/receipts.edn\""
             "+++ \"b/.\\316\\267\\316\\274/receipts.edn\""
             "@@ -1,2 +1,3 @@"
             " historical receipt one"
             " historical receipt two"
             "+new receipt"]))

(defn- location-candidate [path line]
  {:id "receipt" :severity "medium" :category "contract"
   :claim "Synthetic location probe" :path path :line line
   :body "Location-law fixture, not an adjudicated receipt defect"
   :confidence 0.9 :blocking false})

(deftest git-quoted-utf8-receipt-index-and-admission
  (let [session (through-stage (review/begin quoted-receipt-diff) :generate-candidates)
        canonical ".ημ/receipts.edn"
        alias "\"b/.\\316\\267\\316\\274/receipts.edn\""
        admitted (review/propose-finding session (location-candidate canonical 3))]
    (is (= {canonical #{3}} (:changed-lines session)))
    (is (:ok? admitted))
    (is (false? (:ok? (review/propose-finding session (location-candidate alias 3)))))
    (is (false? (:ok? (review/propose-finding session (location-candidate canonical 2)))))
    (is (false? (:ok? (review/propose-finding session (location-candidate "absent.edn" 3)))))
    (when (:ok? admitted)
      (let [session (through-stage (:session admitted) :adversarial-validate)
            session (:session (review/classify-finding session "receipt" "confirmed" "Synthetic fixture trace"))
            result (review/submission (through-stage session :publish) "Synthetic publication boundary")]
        (is (:ok? result))
        (is (= [canonical] (mapv :path (get-in result [:envelope :comments]))))))))

(defn- header-diff [header]
  (str "diff --git a/old b/new\n--- a/old\n+++ " header "\n@@ -1 +1,2 @@\n kept\n+added"))

(deftest diff-paths-preserve-filename-identity
  (doseq [[header filename]
          [["b/.ημ/receipts.edn" ".ημ/receipts.edn"]
           ["b/café.edn" "café.edn"]
           ["b/café.edn" "café.edn"]
           ["b/ trailing space \t" " trailing space "]
           ["\"b/\\303\\251.edn\"" "é.edn"]
           ["\"b/\\360\\237\\230\\200.edn\"" "😀.edn"]
           ["\"b/tab\\tline\\nquote\\\"slash\\\\.edn\"" "tab\tline\nquote\"slash\\.edn"]
           ["\"b/bell\\aback\\bvertical\\vform\\freturn\\r.edn\""
            (str "bell" (char 7) "back\bvertical" (char 11) "form\freturn\r.edn")]
           ["\"b/\\141\\163\\143\\151\\151.edn\"" "ascii.edn"]
           ["\"b/raw-η\\t.edn\"" "raw-η\t.edn"]]]
    (is (= {filename #{2}} (review/parse-diff-added-lines (header-diff header))) header))
  (let [diff (str (header-diff "b/café.edn") "\n" (header-diff "b/café.edn"))]
    (is (= #{"café.edn" "café.edn"} (set (keys (review/parse-diff-added-lines diff)))))))

(deftest malformed-diff-paths-fail-before-session-admission
  (doseq [header ["\"b/unterminated" "\"b/trailing\"junk" "\"b/unknown\\q\""
                  "\"b/short\\12\"" "\"b/invalid\\400\"" "\"b/null\\000\""
                  "\"b/overlong\\300\\257\"" "\"b/shortutf8\\316\""
                  "\"b/continuation\\200\"" "\"b/surrogate\\355\\240\\200\""
                  "\"b/outofrange\\364\\220\\200\\200\"" "b/" "\"\""
                  "b/raw\\escape" "b/raw\"quote" "b/raw\tcontrol"]]
    (is (thrown-with-msg? js/Error #"Invalid Git diff path" (review/begin (header-diff header))) header)))

(deftest renamed-deleted-and-header-looking-added-lines
  (let [renamed (str "diff --git a/old.edn \"b/.\\316\\267\\316\\274/new.edn\"\n"
                     "similarity index 50%\nrename from old.edn\nrename to .ημ/new.edn\n"
                     "--- a/old.edn\n+++ \"b/.\\316\\267\\316\\274/new.edn\"\n"
                     "@@ -1 +1,3 @@\n kept\n+++ \"not a header\n+tail\n")
        deleted "diff --git a/gone b/gone\n--- a/gone\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\n"]
    (is (= {".ημ/new.edn" #{2 3}} (review/parse-diff-added-lines (str renamed deleted))))))

(deftest parse-diff-added-lines-indexes-only-added-head-lines
  (let [indexed (review/parse-diff-added-lines sample-diff)]
    (is (= #{11 12} (get indexed "src/example.js")))
    (is (= #{1} (get indexed "src/other.js")))
    (is (= 2 (count indexed)))))

(deftest parse-diff-added-lines-handles-empty-input
  (is (= {} (review/parse-diff-added-lines "")))
  (is (= {} (review/parse-diff-added-lines nil))))

(deftest begin-starts-at-deterministic-stage
  (let [session (begun)]
    (is (= :deterministic (:stage session)))
    (is (= 2 (get-in session [:diff-stats :files])))
    (is (false? (get-in session [:diff-stats :truncated?])))))

(deftest record-evidence-enforces-stage-order
  (let [session (begun)
        out-of-order (review/record-evidence session :map-change "nope")]
    (is (false? (:ok? out-of-order)))
    (is (re-find #"deterministic" (:error out-of-order))))
  (let [session (assess-all (begun))
        ok (review/record-evidence session :deterministic "gates read")]
    (is (:ok? ok))
    (is (= :map-change (get-in ok [:session :stage])))))

(deftest propose-finding-validates-against-changed-lines
  (let [session (through-stage (begun) :generate-candidates)
        base {:id "f1" :severity "high" :category "semantic-regression"
              :claim "drops caller result" :path "src/example.js" :line 11
              :body "impact and fix" :confidence 0.9 :blocking true}
        {:keys [session] :as first-result} (review/propose-finding session base)]
    (is (:ok? first-result))
    (is (false? (:ok? (review/propose-finding session (assoc base :id "f2" :line 10)))))
    (is (false? (:ok? (review/propose-finding session (assoc base :id "f3" :path "src/absent.js")))))
    (is (false? (:ok? (review/propose-finding session base))))))

(deftest propose-finding-rejects-blocking-on-low-severity
  (let [session (through-stage (begun) :generate-candidates)
        result (review/propose-finding session {:id "f1" :severity "low" :category "test-gap"
                                                :claim "x" :path "src/example.js" :line 11
                                                :body "b" :confidence 0.9 :blocking true})]
    (is (false? (:ok? result)))
    (is (re-find #"critical or high" (:error result)))))

(deftest classify-finding-only-at-adversarial-validate
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.5 :blocking false})]
    (is (false? (:ok? (review/classify-finding session "f1" "confirmed" "too early"))))
    (let [session (through-stage session :adversarial-validate)
          ok (review/classify-finding session "f1" "rejected" "disproved by guard")]
      (is (:ok? ok))
      (is (= :rejected (get-in ok [:session :candidates "f1" :status]))))))

(deftest classify-finding-remains-legal-at-publish-stage
  ;; Regression: recording the :adversarial-validate evidence before classifying
  ;; advances the stage to :publish; classification must still be legal there or
  ;; the machine deadlocks with unclassified candidates that submit rejects.
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "high" :category "security"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.9 :blocking true})
        session (through-stage session :publish)
        {:keys [session] :as classified} (review/classify-finding session "f1" "confirmed" "trace verified")]
    (is (:ok? classified))
    (let [result (review/submission session "summary")]
      (is (:ok? result))
      (is (= "REQUEST_CHANGES" (get-in result [:envelope :event]))))))

(deftest submission-requires-publish-stage-and-classified-candidates
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "high" :category "security"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.95 :blocking true})]
    (is (false? (:ok? (review/submission session "summary"))))
    (let [session (through-stage session :publish)]
      (is (false? (:ok? (review/submission session "summary")))))))

(deftest submission-derives-request-changes-from-blocking-confirmed
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "high" :category "security"
                                                           :claim "fails open" :path "src/example.js" :line 11
                                                           :body "fix it" :confidence 0.95 :blocking true})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "confirmed" "trace verified")
        session (through-stage session :publish)
        result (review/submission session "One blocking defect.")]
    (is (:ok? result))
    (is (= "REQUEST_CHANGES" (get-in result [:envelope :event])))
    (is (= [{:path "src/example.js" :line 11 :side "RIGHT"
             :severity "high" :blocking true :body "fix it"}]
           (get-in result [:envelope :comments])))))

(deftest submission-derives-approve-with-no-confirmed-findings
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 12
                                                           :body "b" :confidence 0.4 :blocking false})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "rejected" "not a defect")
        session (through-stage session :publish)
        result (review/submission session "Clean.")]
    (is (:ok? result))
    (is (= "APPROVE" (get-in result [:envelope :event])))
    (is (= [] (get-in result [:envelope :comments])))))

(deftest submission-rejects-underconfident-confirmations
  (let [session (through-stage (begun) :generate-candidates)
        {:keys [session]} (review/propose-finding session {:id "f1" :severity "medium" :category "contract"
                                                           :claim "c" :path "src/example.js" :line 11
                                                           :body "b" :confidence 0.5 :blocking false})
        session (through-stage session :adversarial-validate)
        {:keys [session]} (review/classify-finding session "f1" "confirmed" "plausible")
        session (through-stage session :publish)
        result (review/submission session "summary")]
    (is (false? (:ok? result)))
    (is (re-find #"confidence threshold" (:error result)))))

(deftest submission-rejects-duplicate-confirmed-locations
  ;; Observed live: two confirmed findings on one line passed submission and
  ;; were then rejected by the publisher's defensive validation. The law
  ;; belongs at submit time so the reviewer gets actionable feedback.
  (let [session (through-stage (begun) :generate-candidates)
        propose (fn [s id]
                  (:session (review/propose-finding s {:id id :severity "medium" :category "contract"
                                                       :claim "c" :path "src/example.js" :line 11
                                                       :body "b" :confidence 0.9 :blocking false})))
        session (-> session (propose "f1") (propose "f2"))
        session (through-stage session :publish)
        classify (fn [s id] (:session (review/classify-finding s id "confirmed" "verified")))
        session (-> session (classify "f1") (classify "f2"))
        result (review/submission session "summary")]
    (is (false? (:ok? result)))
    (is (re-find #"share a location" (:error result)))))

(deftest missing-full-input-cannot-approve
  (let [diff (str sample-diff "\n[eta-mu review] diff truncated at 300000 bytes (was 400000).\n")
        ;; Native failure shape: stage notes exist, but the omitted tail was
        ;; never supplied or assessed. No findings is not full-input review.
        ;; Forged stage state still cannot bypass submission's defensive guard.
        session (assoc (assess-all (review/begin diff)) :stage :publish)
        result (review/submission session "Only the preview/risk zones were assessed.")]
    (is (false? (:ok? result)))
    (is (not= "APPROVE" (get-in result [:envelope :event])))))

(deftest missing-tail-delivery-and-assessment-are-separate
  (let [tail (str sample-diff "\n" (apply str (repeat 200 "diff --git a/tail b/tail\n--- a/tail\n+++ b/tail\n@@ -1 +1 @@\n-old\n+tail-risk\n")))
        begun (review/begin tail)
        ;; Stage notes and a delivery receipt do not attest tail assessment.
        partial (:session (review/read-diff-chunk begun 1))
        publish (assoc partial :stage :publish)]
    (is (> (count (:diff-chunks begun)) 1))
    (is (false? (:ok? (review/submission publish "The delivered prefix had no findings."))))
    (is (false? (:ok? (review/assess-diff-chunk begun 1 "Not actually delivered."))))
    (is (:ok? (review/submission (assess-all publish) "All changed hunks assessed; full input recovered.")))
    (is (= "APPROVE" (get-in (review/submission (assess-all publish) "Complete review.") [:envelope :event])))))

(deftest invalid-page-or-empty-assessment-cannot-supply-coverage
  (let [begun (review/begin sample-diff)
        delivered (:session (review/read-diff-chunk begun 1))]
    (is (false? (:ok? (review/read-diff-chunk begun (inc (count (:diff-chunks begun)))))))
    (is (false? (:ok? (review/assess-diff-chunk begun 99 "Unknown page."))))
    (doseq [note ["" " \n\t"]]
      (is (false? (:ok? (review/assess-diff-chunk delivered 1 note)))))
    (is (= 0 (:assessed (review/input-coverage delivered))))))

(deftest unassessed-tail-keeps-findings-open-until-input-recovery
  (let [diff (str "diff --git a/large b/large\n--- a/large\n+++ b/large\n@@ -0,0 +1,300 @@\n"
                  (apply str (repeat 299 "+prefix\n")) "+tail-risk\n")
        begun (review/begin diff)
        prefix (:session (review/read-diff-chunk begun 1))
        prefix (:session (review/assess-diff-chunk prefix 1 "Assessed the prefix, not the missing tail."))
        ;; Explicit fixture for the existing adversarial guard, not admitted
        ;; stage history: the new deterministic guard refuses this prefix.
        adversarial (assoc prefix :stage :adversarial-validate
                          :evidence (mapv (fn [stage] {:stage stage :note "Stage evidence without tail coverage."})
                                          [:deterministic :map-change :generate-candidates]))
        refused (review/record-evidence adversarial :adversarial-validate "Ready to publish the prefix.")]
    (is (> (count (:diff-chunks begun)) 1))
    (is (false? (:ok? refused)))
    (is (re-find #"Unassessed full-input chunks" (or (:error refused) "")))
    (is (= :adversarial-validate (:stage adversarial)))
    (is (= 3 (count (:evidence adversarial))))
    ;; Recovery retains this session so the omitted tail can still supply a
    ;; finding; no restart or relaxation of the :publish restriction is needed.
    (let [recovered (assess-all adversarial)
          proposed (review/propose-finding recovered
                                          {:id "tail" :severity "high" :category "semantic-regression"
                                           :claim "Synthetic tail finding" :path "large" :line 300
                                           :body "The recovered tail contains this synthetic blocking fixture."
                                           :confidence 0.95 :blocking true})
          classified (review/classify-finding (:session proposed) "tail" "confirmed" "Recovered tail verified.")
          advanced (review/record-evidence (:session classified) :adversarial-validate "All pages assessed.")
          submitted (review/submission (:session advanced) "Recovered tail finding retained.")]
      (is (:ok? proposed))
      (is (:ok? classified))
      (is (:ok? advanced))
      (is (= :publish (get-in advanced [:session :stage])))
      (is (= "REQUEST_CHANGES" (get-in submitted [:envelope :event])))
      (is (= [300] (mapv :line (get-in submitted [:envelope :comments]))))
      (is (false? (:ok? (review/propose-finding (:session advanced) (location-candidate "large" 300))))))))

(deftest truncated-input-cannot-enter-publish
  (let [diff (str sample-diff "\n[eta-mu review] diff truncated at 300000 bytes (was 400000).\n")
        session (assoc (assess-all (review/begin diff)) :stage :adversarial-validate)
        result (review/record-evidence session :adversarial-validate "Every supplied preview page assessed.")]
    (is (false? (:ok? result)))
    (is (re-find #"truncated preview" (or (:error result) "")))))

(deftest reader-pages-preserve-unicode-and-long-lines
  (doseq [padding [8190 8191]
          astral ["😀" (js/String.fromCodePoint 0x10FFFF)]]
    ;; Low half at8191 must remain in the first page; high half at8191
    ;; must move with its low half to the next page. Concatenation alone
    ;; conceals a split pair, so also encode every page independently.
    (let [text (str (apply str (repeat padding "x")) astral "ημ" (apply str (repeat 400 "\n")))
          chunks (review/diff-chunks text)
          encoder (js/TextEncoder.)]
      (is (= text (apply str (map :text chunks))))
      (is (= (if (= padding 8190) 8192 8191) (:end (first chunks))))
      (is (every? #(<= (count (:text %)) 8192) chunks))
      (is (every? #(<= (count (re-seq #"\n" (:text %))) 128) chunks))
      (is (= (vec (.encode encoder text))
             (vec (mapcat #(vec (.encode encoder (:text %))) chunks)))
          (str "Independent page UTF-8 encoding changed input at alignment " padding)))))

(deftest fresh-session-exposes-healthy-chronology
  (let [session (begun)
        status (review/status session)]
    (is (= #{} (:invalidated-chunks session)))
    (is (false? (:restart-required? status)))
    (is (= [] (:invalidated-chunks status)))))

(deftest repeated-reads-before-assessment-remain-healthy
  (let [begun (begun)
        read-once (review/read-diff-chunk begun 1)
        read-twice (review/read-diff-chunk (:session read-once) 1)
        read-thrice (review/read-diff-chunk (:session read-twice) 1)
        assessed (review/assess-diff-chunk (:session read-thrice) 1 "Assessed after the final read.")]
    (doseq [result [read-once read-twice read-thrice]]
      (is (:ok? result))
      (is (= (first (:diff-chunks begun)) (:chunk result)))
      (is (false? (:restart-required? (review/status (:session result))))))
    (is (:ok? assessed))
    (is (= {:chunks 1 :delivered 1 :assessed 1 :missing []} (:coverage assessed)))
    (is (:ok? (review/submission (through-stage (:session assessed) :publish) "Healthy final-read chronology.")))))

(deftest repeated-assessments-without-reread-remain-healthy
  (let [read-result (review/read-diff-chunk (begun) 1)
        assessed (review/assess-diff-chunk (:session read-result) 1 "Initial substantive assessment.")
        revised (review/assess-diff-chunk (:session assessed) 1 "Revised assessment without another read.")
        session (through-stage (:session revised) :publish)
        submitted (review/submission session "Healthy revision.")]
    (is (:ok? revised))
    (is (= "Revised assessment without another read." (get-in revised [:session :assessed-chunks 1])))
    (is (false? (:restart-required? (review/status session))))
    (is (:ok? submitted))
    (is (= "Revised assessment without another read." (get-in submitted [:envelope :input-assessments 0 :note])))))

(deftest reread-invalidates-assessment-at-every-stage
  (doseq [stage review/stages]
    (let [session (assoc (through-stage (begun) stage) :input-source {:head-sha "immutable-fixture-head"})
          reread (review/read-diff-chunk session 1)
          invalidated (:session reread)
          status (review/status invalidated)]
      (is (:ok? reread) (name stage))
      (is (= (first (:diff-chunks session)) (:chunk reread)))
      (is (true? (:restart-required? reread)))
      (is (= #{1} (:invalidated-chunks invalidated)))
      (is (= {} (:assessed-chunks invalidated)))
      (is (= {:chunks 1 :delivered 1 :assessed 0 :missing [1]} (:input-coverage status)))
      (is (true? (:restart-required? status)))
      (is (= [1] (:invalidated-chunks status)))
      (is (= (select-keys session [:stage :evidence :candidates :candidate-order :changed-lines :diff-chunks :input-source])
             (select-keys invalidated [:stage :evidence :candidates :candidate-order :changed-lines :diff-chunks :input-source])))
      (is (false? (:ok? (review/assess-diff-chunk invalidated 1 "Later assessment cannot erase the earlier violation.")))))))

(deftest invalidation-is-permanent-and-keeps-unaffected-coverage
  (let [diff (str "diff --git a/large b/large\n--- a/large\n+++ b/large\n@@ -0,0 +1,300 @@\n"
                  (apply str (map #(str "+line-" % "\n") (range 1 301))))
        assessed (assess-all (review/begin diff))
        read-third (review/read-diff-chunk assessed 3)
        read-first (review/read-diff-chunk (:session read-third) 1)
        invalidated (:session read-first)
        reread (review/read-diff-chunk invalidated 3)
        still-invalid (:session reread)]
    (is (= 3 (count (:diff-chunks assessed))))
    (is (:ok? read-third))
    (is (:ok? read-first))
    (is (= #{1 3} (:invalidated-chunks still-invalid)))
    (is (= [1 3] (:invalidated-chunks (review/status still-invalid))))
    (is (= {2 (get (:assessed-chunks assessed) 2)} (:assessed-chunks still-invalid)))
    (is (= {:chunks 3 :delivered 3 :assessed 1 :missing [1 3]} (review/input-coverage still-invalid)))
    (is (:ok? reread))
    (is (= (nth (:diff-chunks assessed) 2) (:chunk reread)))
    (is (true? (:restart-required? reread)))
    (doseq [id [1 2 3]]
      (let [result (review/assess-diff-chunk still-invalid id "Attempted in-session repair.")]
        (is (false? (:ok? result)))
        (is (re-find #"fresh invocation" (or (:error result) "")))))
    (is (false? (:ok? (review/submission (assoc still-invalid :stage :publish) "Cannot publish this failed session."))))))

(deftest invalidated-session-can-deliver-new-pages-without-restoring-assessment
  (let [diff (apply str (repeat 200 (str sample-diff "\n")))
        begun (review/begin diff)
        read-first (review/read-diff-chunk begun 1)
        assessed (review/assess-diff-chunk (:session read-first) 1 "Prefix assessed.")
        invalidated (:session (review/read-diff-chunk (:session assessed) 1))
        new-page (review/read-diff-chunk invalidated 2)]
    (is (> (count (:diff-chunks begun)) 1))
    (is (:ok? new-page))
    (is (= (second (:diff-chunks begun)) (:chunk new-page)))
    (is (true? (:restart-required? new-page)))
    (is (= #{1} (get-in new-page [:session :invalidated-chunks])))
    (is (contains? (get-in new-page [:session :delivered-chunks]) 2))
    (is (false? (:ok? (review/assess-diff-chunk (:session new-page) 2 "New page cannot repair the failed chronology."))))))

(deftest reassessment-cannot-erase-strict-last-read-failure
  (let [session (through-stage (begun) :adversarial-validate)
        invalidated (:session (review/read-diff-chunk session 1))
        reassessed (review/assess-diff-chunk invalidated 1 "Reassessment after reread.")
        retained (or (:session reassessed) invalidated)
        transition (review/record-evidence retained :adversarial-validate "All chunks appear assessed again.")
        submit (review/submission (assoc retained :stage :publish) "Attempted stale publication.")]
    (is (false? (:ok? reassessed)))
    (is (false? (:ok? transition)))
    (is (re-find #"fresh invocation" (or (:error transition) "")))
    (is (= :adversarial-validate (:stage retained)))
    (is (= 3 (count (:evidence retained))))
    (is (false? (:ok? submit)))
    (is (re-find #"fresh invocation" (or (:error submit) "")))))

(deftest chronology-error-precedes-truncation-and-missing-coverage
  (let [diff (str sample-diff "\n[eta-mu review] diff truncated at 300000 bytes (was 400000).\n")
        assessed (assoc (assess-all (review/begin diff)) :stage :adversarial-validate)
        invalidated (:session (review/read-diff-chunk assessed 1))
        transition (review/record-evidence invalidated :adversarial-validate "Cannot recover by filling the preview.")
        submit (review/submission (assoc invalidated :stage :publish) "Latched chronology has priority.")]
    (is (get-in invalidated [:diff-stats :truncated?]))
    (is (= [1] (:missing (review/input-coverage invalidated))))
    (doseq [result [transition submit]]
      (is (false? (:ok? result)))
      (is (re-find #"fresh invocation" (or (:error result) "")))
      (is (not (re-find #"truncated preview|Unassessed full-input" (or (:error result) "")))))))

(deftest reread-after-successful-submission-revokes-session-publication
  (let [session (through-stage (begun) :publish)
        initial (review/submission session "Initially healthy.")
        reread (review/read-diff-chunk session 1)
        submitted (review/submission (:session reread) "Same bytes read after assessment.")]
    (is (:ok? initial))
    (is (:ok? reread))
    (is (= sample-diff (get-in reread [:chunk :text])))
    (is (false? (:ok? submitted)))
    (is (nil? (:envelope submitted)))
    (is (re-find #"fresh invocation" (or (:error submitted) "")))))

(deftest invalid-read-does-not-invalidate-a-healthy-assessment
  (let [session (through-stage (begun) :publish)]
    (doseq [id [0 -1 2 1.5 "1" nil]]
      (let [result (review/read-diff-chunk session id)]
        (is (false? (:ok? result)))
        (is (nil? (:session result)))))
    (is (false? (:restart-required? (review/status session))))
    (is (:ok? (review/submission session "Invalid reader requests did not deliver a page.")))))

(def deleted-only-diff
  "A Git deletion-only patch with no head-side added lines."
  (str "diff --git a/.coderabbit.yaml b/.coderabbit.yaml\n"
       "deleted file mode 100644\nindex 1234567..0000000\n"
       "--- a/.coderabbit.yaml\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-old\n-config\n"))

(deftest diff-file-count-includes-non-added-line-git-patches
  (let [rename-only (str "diff --git a/old.edn b/new.edn\n"
                         "similarity index 100%\nrename from old.edn\nrename to new.edn\n")
        binary (str "diff --git a/icon.png b/icon.png\nindex 1234567..7654321 100644\n"
                    "Binary files a/icon.png and b/icon.png differ\n")
        binary-patch (str "diff --git a/data.bin b/data.bin\nindex 1234567..7654321 100644\n"
                          "GIT binary patch\nliteral 1\nIc${Nk000310RR91\n\nliteral 0\nHcmV?d00001\n")
        mode-only "diff --git a/run.sh b/run.sh\nold mode 100644\nnew mode 100755\n"
        mixed (str sample-diff "\n" deleted-only-diff rename-only binary binary-patch mode-only quoted-receipt-diff)]
    (doseq [[diff expected] [[nil 0] ["" 0] [" \n\t" 0]
                           [deleted-only-diff 1] [rename-only 1] [binary 1] [binary-patch 1]
                           [mode-only 1] [quoted-receipt-diff 1] [sample-diff 2] [mixed 8]]]
      (is (= expected (get-in (review/begin diff) [:diff-stats :files]))))
    (is (= {} (review/parse-diff-added-lines (str deleted-only-diff rename-only binary binary-patch mode-only))))
    (is (= (merge (review/parse-diff-added-lines sample-diff)
                  (review/parse-diff-added-lines quoted-receipt-diff))
           (:changed-lines (review/begin mixed))))))

(deftest content-looking-like-git-headers-does-not-inflate-file-count
  (let [diff (str "diff --git \"a/file with space.edn\" \"b/file with space.edn\"\n"
                  "--- a/file with space.edn\t\n+++ b/file with space.edn\t\n"
                  "@@ -1,2 +1,2 @@\n-diff --git a/deleted b/deleted\n"
                  "+diff --git a/added b/added\n diff --git a/context b/context\n")
        session (review/begin diff)]
    (is (= 1 (get-in session [:diff-stats :files])))
    (is (= {"file with space.edn" #{1}} (:changed-lines session)))))

(deftest deletion-only-input-still-requires-delivery-and-assessment
  (let [session (review/begin deleted-only-diff)
        missing (review/submission (assoc session :stage :publish) "No added lines does not imply reviewed.")
        read-result (review/read-diff-chunk session 1)
        assessed (review/assess-diff-chunk (:session read-result) 1 "Assessed the removed configuration.")
        completed (through-stage (:session assessed) :publish)
        submitted (review/submission completed "Full deletion patch assessed.")]
    (is (= 1 (get-in session [:diff-stats :files])))
    (is (= {} (:changed-lines session)))
    (is (false? (:ok? missing)))
    (is (= deleted-only-diff (get-in read-result [:chunk :text])))
    (is (:ok? assessed))
    (is (:ok? submitted))
    (is (= {:chunks 1 :delivered 1 :assessed 1 :missing []} (get-in submitted [:envelope :input-coverage])))
    (is (= (mapv :stage (:evidence completed)) [:deterministic :map-change :generate-candidates :adversarial-validate]))
    (is (:ok? (review/record-evidence completed :publish "All five stages retained.")))
    (is (false? (:ok? (review/propose-finding (assoc completed :stage :generate-candidates)
                                            (location-candidate ".coderabbit.yaml" 1)))))))

(def restart-refusal-message
  "The fixed refusal for a second admission in one bounded invocation."
  "A review session has already been admitted in this invocation. Start a fresh invocation; this session cannot be restarted.")

(deftest restart-refusal-transition-retains-admitted-history
  ;; Resolve the newly added API as data so the same tests report its absence
  ;; on the preserved first-phase source without aborting the whole suite.
  (let [reject-restart (resolve 'eta-mu.domain.review/reject-restart)]
    (is (some? reject-restart))
    (when reject-restart
      (doseq [session [(begun)
                       (assess-all (begun))
                       (through-stage (begun) :publish)]]
        (let [rejected (reject-restart session)
              retained (:session rejected)]
          (is (false? (:ok? rejected)))
          (is (true? (:restart-required? rejected)))
          (is (= restart-refusal-message (:error rejected)))
          (is (= restart-refusal-message (:restart-error retained)))
          (is (= session (dissoc retained :restart-error)))
          (is (= #{} (:invalidated-chunks retained)))
          (is (= (review/input-coverage session) (review/input-coverage retained)))
          (is (= rejected (reject-restart retained))))))))

(deftest public-restart-predicate-recognizes-both-latches
  (let [restart-required? (resolve 'eta-mu.domain.review/restart-required?)]
    (is (some? restart-required?))
    (when restart-required?
      (doseq [session [nil {} (begun) (assess-all (begun))]]
        (is (false? (restart-required? session))))
      (doseq [session [(assoc (begun) :restart-error restart-refusal-message)
                       (assoc (begun) :invalidated-chunks #{1})
                       (assoc (begun) :restart-error restart-refusal-message :invalidated-chunks #{1})]]
        (is (true? (restart-required? session)))
        (is (= (restart-required? session) (:restart-required? (review/status session))))))))

(deftest rejected-restart-status-does-not-invent-rereads
  (doseq [session [(begun) (assess-all (begun)) (through-stage (begun) :publish)]]
    (let [latched (assoc session :restart-error restart-refusal-message)
          status (review/status latched)]
      (is (true? (:restart-required? status)))
      (is (= [] (:invalidated-chunks status)))
      (is (= #{} (:invalidated-chunks latched)))
      (is (= (review/input-coverage session) (:input-coverage status)))
      (is (= (:stage session) (:stage status))))))

(deftest valid-first-reads-remain-truthful-after-rejected-restart
  (let [session (assoc (begun) :restart-error restart-refusal-message)
        read-once (review/read-diff-chunk session 1)
        read-twice (review/read-diff-chunk (:session read-once) 1)]
    (doseq [result [read-once read-twice]]
      (is (:ok? result))
      (is (= (first (:diff-chunks session)) (:chunk result)))
      (is (true? (:restart-required? result)))
      (is (= restart-refusal-message (get-in result [:session :restart-error])))
      (is (= #{} (get-in result [:session :invalidated-chunks])))
      (is (= {} (get-in result [:session :assessed-chunks])))
      (is (= {:chunks 1 :delivered 1 :assessed 0 :missing [1]}
             (review/input-coverage (:session result)))))))

(deftest rejected-restart-prevents-first-assessment-and-reassessment
  (doseq [session [(:session (review/read-diff-chunk (begun) 1))
                   (assess-all (begun))]]
    (let [latched (assoc session :restart-error restart-refusal-message)
          result (review/assess-diff-chunk latched 1 "Cannot heal the rejected restart with another note.")]
      (is (false? (:ok? result)))
      (is (= restart-refusal-message (:error result)))
      (is (nil? (:session result)))
      (is (= (review/input-coverage session) (review/input-coverage latched)))
      (is (= #{} (:invalidated-chunks latched))))))

(deftest rejected-restart-blocks-complete-adversarial-and-publish-sessions
  (doseq [stage [:adversarial-validate :publish]]
    (let [session (assoc (through-stage (begun) stage) :restart-error restart-refusal-message)
          submitted (review/submission (assoc session :stage :publish) "Coverage cannot erase rejected admission.")]
      (is (= [] (:missing (review/input-coverage session))))
      (is (false? (:ok? submitted)))
      (is (= restart-refusal-message (:error submitted)))
      (is (nil? (:envelope submitted)))
      (when (= stage :adversarial-validate)
        (let [advanced (review/record-evidence session stage "All pages assessed before the rejected restart.")]
          (is (false? (:ok? advanced)))
          (is (= restart-refusal-message (:error advanced)))
          (is (= 3 (count (:evidence session)))))))))

(deftest rejected-restart-error-precedes-reread-truncation-and-missing-input
  (let [diff (str sample-diff "\n[eta-mu review] diff truncated at 300000 bytes (was 400000).\n")
        assessed (assoc (assess-all (review/begin diff)) :stage :adversarial-validate)
        invalidated (:session (review/read-diff-chunk assessed 1))
        latched (assoc invalidated :restart-error restart-refusal-message)
        assessment (review/assess-diff-chunk latched 1 "Attempted repair.")
        transition (review/record-evidence latched :adversarial-validate "Attempted advancement.")
        submitted (review/submission (assoc latched :stage :publish) "Attempted publication.")]
    (is (= #{1} (:invalidated-chunks latched)))
    (is (get-in latched [:diff-stats :truncated?]))
    (is (= [1] (:missing (review/input-coverage latched))))
    (doseq [result [assessment transition submitted]]
      (is (false? (:ok? result)))
      (is (= restart-refusal-message (:error result))))))

(deftest rejected-restart-latch-survives-other-pure-transitions
  (let [latched (assoc (begun) :restart-error restart-refusal-message)
        ;; Isolate preservation across the other pure transitions. A latched
        ;; invocation no longer advances through deterministic admission.
        advanced (assoc latched :stage :adversarial-validate)
        proposed (review/propose-finding advanced
                                        {:id "preserved" :severity "low" :category "contract"
                                         :claim "Synthetic history fixture" :path "src/example.js" :line 11
                                         :body "Pure state preservation only." :confidence 0.5 :blocking false})
        classified (review/classify-finding (:session proposed) "preserved" "rejected" "Synthetic fixture rejected.")]
    (is (:ok? proposed))
    (is (:ok? classified))
    (doseq [session [advanced (:session proposed) (:session classified)]]
      (is (= restart-refusal-message (:restart-error session)))
      (is (true? (:restart-required? (review/status session))))
      (is (= [] (:invalidated-chunks (review/status session)))))
    (is (false? (:ok? (review/record-evidence (:session classified) :adversarial-validate "Cannot publish retained failed history."))))))

(deftest reject-restart-retains-real-reread-invalidation
  (let [reject-restart (resolve 'eta-mu.domain.review/reject-restart)]
    (is (some? reject-restart))
    (when reject-restart
      (let [invalidated (:session (review/read-diff-chunk (assess-all (begun)) 1))
            rejected (reject-restart invalidated)
            reread (review/read-diff-chunk (:session rejected) 1)]
        (is (false? (:ok? rejected)))
        (is (= invalidated (dissoc (:session rejected) :restart-error)))
        (is (= #{1} (get-in reread [:session :invalidated-chunks])))
        (is (true? (:restart-required? reread)))
        (is (:ok? reread))
        (is (= sample-diff (get-in reread [:chunk :text])))
        (is (= restart-refusal-message (get-in reread [:session :restart-error])))
        (is (false? (:ok? (review/assess-diff-chunk (:session reread) 1 "Both latches remain permanent."))))))))
