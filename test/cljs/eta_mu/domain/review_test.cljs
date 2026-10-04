(ns eta-mu.domain.review-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [eta-mu.domain.review :as review]))

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
          session (map :id (:diff-chunks session))))

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
  (let [session (begun)
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
        session (assoc (through-stage (review/begin diff) :adversarial-validate) :stage :publish)
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
        adversarial (reduce (fn [session stage]
                              (:session (review/record-evidence session stage "Stage evidence without tail coverage.")))
                            prefix [:deterministic :map-change :generate-candidates])
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
        session (through-stage (review/begin diff) :adversarial-validate)
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
