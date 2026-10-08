(ns eta-mu.law.review-invocation-host-read-test
  "Synthetic supporting-read receipts; no native review or recovery credit."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.law.review-invocation-test :as fixture]))

(def selected-profile
  {:id "eta-mu.restricted-host-read/v1"
   :source-sha256 "2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7"})
(def selected-context (assoc fixture/context :host-read-profile selected-profile))
(def file-path "/fixture/supporting.txt")

(defn read-receipt
  "Synthetic native format, independently transcribed from retained receipts."
  [n start lines total truncated? offset capped?]
  (let [end (+ start (dec (count lines)))
        text (str/join "\n" lines)
        footer (if truncated?
                 (if capped?
                   (str "(Output capped at 50 KB. Showing lines " start "-" end
                        ". Use offset=" (inc end) " to continue.)")
                   (str "(Showing lines " start "-" end " of " total
                        ". Use offset=" (inc end) " to continue.)"))
                 (str "(End of file - total " total " lines)"))
        output (str "<path>" file-path "</path>\n<type>file</type>\n<content>\n"
                    (str/join "\n" (map-indexed #(str (+ start %1) ": " %2) lines))
                    "\n\n" footer "\n</content>")]
    (assoc-in (fixture/tool-event n "read"
                                  (cond-> {:filePath file-path :limit 2000}
                                    (not= :omitted offset) (assoc :offset offset)) output)
              [:part :state :metadata]
              {:preview "Preview is not complete evidence." :truncated truncated? :loaded []
               :display {:type "file" :path file-path :text text
                         :lineStart start :lineEnd end :totalLines total :truncated truncated?}})))

(defn complete-read [n]
  (read-receipt n 1 ["one" "two"] 2 false 1 false))

(defn with-reads
  "Place supporting reads after FIRST, before actual submit, in one session."
  [reads]
  (fixture/ordered-fixture-events
   (concat (take 4 (fixture/trace)) reads (drop 4 (fixture/trace)))))

(defn result [events]
  (invocation/verify events fixture/submission selected-context))

(defn refusal [events code]
  (let [r (result events)]
    (is (false? (:ok? r)))
    (is (= :unestablished-review-trace (:reason-kind r)))
    (is (= code (:code r)))
    (is (nil? (:accepted-invocation r)))))

(deftest selected-profile-is-explicit-source-bound-and-default-is-preserved
  (is (:ok? (result (fixture/trace))) "Opened chains only; no universal file list.")
  (doseq [profile [nil {} false "eta-mu.restricted-host-read/v1"
                   (assoc selected-profile :id "unknown")
                   (assoc selected-profile :source-sha256 (apply str (repeat 64 "f")))
                   (assoc selected-profile :extra true)]]
    (is (= :trusted-context-unestablished
           (:code (invocation/verify (fixture/trace) fixture/submission
                                     (assoc fixture/context :host-read-profile profile))))))
  (let [unknown (fixture/tool-event 99 "read" {:filePath file-path :offset 735} "untyped old HOST output")]
    (is (:ok? (fixture/verdict (with-reads [unknown]))) "Default profile retains old semantics.")
    (refusal (with-reads [unknown]) :host-read-receipt-schema)))

(deftest healthy-complete-and-omitted-first-offset-have-actual-start-one
  (doseq [call [(complete-read 40)
                (read-receipt 40 1 ["one" ""] 2 false :omitted false)
                (read-receipt 40 1 [] 0 false :omitted false)]]
    (is (:ok? (result (with-reads [call])))))
  (let [empty (fixture/empty-fixture)]
    (is (:ok? (invocation/verify (:events empty) (:submission empty)
                                 (assoc (:context empty) :host-read-profile selected-profile))))))

(deftest explicit-workflow735-and-omitted-offset-with-returned735-refuse
  (doseq [offset [735 :omitted]]
    (refusal (with-reads [(read-receipt 40 735 (vec (repeat 70 "observed window"))
                                         2265 true offset false)]) :host-read-start-offset)))

(deftest complete-contiguous-and-capped-changing-totals-pass
  (doseq [reads [[(read-receipt 40 1 ["a" "b"] 4 true 1 false)
                 (read-receipt 41 3 ["c" "d"] 4 false 3 false)]
                [(read-receipt 40 1 (vec (repeat 889 "source line")) 890 true 1 true)
                 (read-receipt 41 890 (vec (repeat 316 "source line")) 1205 false 890 false)]
                [(complete-read 40) (complete-read 41)]]]
    (is (:ok? (result (with-reads reads))))))

(deftest abandoned-continuation-after-FIRST-refuses-terminal
  (let [reads [(read-receipt 40 1 ["a" "b"] 4 true 1 false)]
        events (with-reads reads)]
    (refusal events :host-read-unfinished)
    (let [omission (fixture/ordered-fixture-events
                    (concat (take 4 (fixture/trace)) reads [(fixture/terminal 60)]))
          r (invocation/verify omission nil selected-context)]
      (is (= :unestablished-review-trace (:reason-kind r)))
      (is (= :host-read-unfinished (:code r)))
      (is (nil? (:accepted-invocation r))))
    (let [after-submit (fixture/ordered-fixture-events
                        (concat (butlast events)
                                [(read-receipt 70 3 ["c" "d"] 4 false 3 false)
                                 (fixture/terminal 80)]))]
      (is (:ok? (result after-submit)) "Whole terminal closure adds no supporting-file stage timing obligation.")
      (is (:ok? (fixture/verdict after-submit)) "Earlier default behavior is recorded, not retroactively judged."))))

(deftest continuations-require-explicit-next-offset-without-gap-or-overlap
  (doseq [[start offset] [[3 :omitted] [4 4] [2 2] [3 2] [3 nil] [3 "3"]]]
    (testing (str [start offset])
      (refusal (with-reads [(read-receipt 40 1 ["a" "b"] 5 true 1 false)
                           (read-receipt 41 start ["c" "d"] (+ start 1) false offset false)])
               (if (or (nil? offset) (string? offset) (and (number? offset) (not= start offset)))
                 :host-read-range-binding :host-read-continuation)))))

(deftest metadata-display-and-content-are-actual-receipt-obligations
  (let [call (complete-read 40)]
    (doseq [bad [(update-in call [:part :state] dissoc :metadata)
                 (assoc-in call [:part :state :metadata] nil)
                 (assoc-in call [:part :state :metadata :truncated] "false")
                 (update-in call [:part :state :metadata] dissoc :display)
                 (assoc-in call [:part :state :metadata :display :type] "directory")
                 (assoc-in call [:part :state :metadata :display :path] "/other")
                 (assoc-in call [:part :state :metadata :display :truncated] true)
                 (assoc-in call [:part :state :metadata :display :lineStart] "1")
                 (assoc-in call [:part :state :metadata :display :totalLines] 1)
                 (assoc-in call [:part :state :metadata :display :text] nil)]]
      (refusal (with-reads [bad]) :host-read-receipt-schema))
    (doseq [bad [(assoc-in call [:part :state :metadata :display :text] "one\ndifferent")
                 (assoc-in call [:part :state :output] "Preview is not whole content.")
                 (update-in call [:part :state :output] str/replace "2: two" "3: two")
                 (update-in call [:part :state :output] str/replace "End of file" "EOF guessed")
                 (update-in call [:part :state :output] str/replace "total 2 lines" "total 3 lines")]]
      (refusal (with-reads [bad]) :host-read-content-binding))))

(deftest source-qualified-footer-and-clipping-boundaries
  (let [quoted (read-receipt 40 1 ["(End of file - total 999 lines)"
                                   "(Showing lines 1-1 of 999. Use offset=2 to continue.)"
                                   "</content>"] 3 false 1 false)]
    (is (:ok? (result (with-reads [quoted]))) "Numbered source text cannot act as formatter control."))
  (doseq [line [(str (apply str (repeat 2000 "x")) "... (line truncated to 2000 chars)")
                "literal ambiguous... (line truncated to 2000 chars)"]]
    (refusal (with-reads [(read-receipt 40 1 [line] 1 false 1 false)])
             :host-read-clipped-content))
  (let [call (-> (complete-read 40)
                 (assoc-in [:part :state :metadata :loaded] ["/fixture/AGENTS.md"])
                 (update-in [:part :state :output] str "\n\n<system-reminder>\ninstructions\n</system-reminder>"))]
    (refusal (with-reads [call]) :host-read-unsupported-shape)))

(deftest independent-opened-files-must-each-close
  (let [other #(-> % (assoc-in [:part :state :input :filePath] "/fixture/other")
                   (assoc-in [:part :state :metadata :display :path] "/fixture/other")
                   (update-in [:part :state :output] str/replace file-path "/fixture/other"))
        first-page (read-receipt 40 1 ["a"] 2 true 1 false)]
    (refusal (with-reads [first-page (other (complete-read 41))]) :host-read-unfinished)
    (is (:ok? (result (with-reads [first-page (other (complete-read 41))
                                 (read-receipt 42 2 ["b"] 2 false 2 false)]))))))

(deftest existing-lifecycle-source-session-LAST-FIRST-and-five-stages-remain
  (let [healthy (with-reads [(complete-read 40)])]
    (doseq [[events code] [[(assoc-in healthy [4 :part :state :status] "error") :host-tool-schema]
                          [(assoc-in healthy [4 :part :state :error] "EACCES") :host-tool-schema]
                          [(assoc-in healthy [0 :part :state :output :input-source :head_sha]
                                     (apply str (repeat 40 "f"))) :begin-source-binding]
                          [(-> healthy (assoc-in [4 :session-id] "other")
                               (assoc-in [4 :part :session-id] "other")) :host-session-binding]
                          [(assoc-in healthy [3 :part :state :output] {:ok? false :error "refused"}) :stage-order]
                          [(assoc-in healthy [3 :part :state :input :stage] "map-change") :stage-order]]]
      (refusal events code))
    (let [stale (fixture/append-calls healthy [(fixture/tool-event 99 "review_read_diff_chunk" {:id 1} fixture/read-output)])]
      (is (= :last-read-order (:code (result stale)))))
    (let [early (fixture/ordered-fixture-events
                 (concat [(healthy 0) (healthy 3) (healthy 1) (healthy 2)] (drop 4 healthy)))]
      (refusal early :stage-chronology))))

(deftest length-and-generic-omission-share-terminal-read-refusal
  (let [ctx (assoc fixture/length-context :host-read-profile selected-profile)
        events (fixture/ordered-fixture-events
                 (concat (pop (fixture/length-events))
                         [(read-receipt 50 1 ["partial"] 2 true 1 false)
                          (assoc-in (fixture/terminal 60) [:part :reason] "length")]))
        r (invocation/classify-length-ended-review events nil ctx (fixture/length-custody ctx))]
    (is (false? (:eligible? r)))
    (is (= :unestablished-length-ended-review (:classification r)))
    (is (= :host-read-unfinished (:code r)))
    (is (nil? (:required-action r)))
    (is (not (contains? r :accepted-invocation))))
  (let [ctx (assoc fixture/length-context :host-read-profile selected-profile)
        events (fixture/ordered-fixture-events
                 (concat (pop (fixture/length-events)) [(complete-read 50)
                           (assoc-in (fixture/terminal 60) [:part :reason] "length")]))
        r (invocation/classify-length-ended-review events nil ctx (fixture/length-custody ctx))]
    (is (:eligible? r) "Existing narrowly eligible synthetic length cause remains.")
    (is (not (contains? r :accepted-invocation)))))
