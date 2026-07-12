(ns eta-mu.domain.websearch-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eta-mu.domain.websearch :as ws]))

(deftest endpoint-normalizes-trailing-slashes
  (is (= "http://x:1/api/tools/websearch" (ws/endpoint "http://x:1")))
  (is (= "http://x:1/api/tools/websearch" (ws/endpoint "http://x:1///"))))

(deftest request-body-includes-only-provided-options
  (let [body (ws/request-body {:query "q"} "m")]
    (is (= {:query "q" :model "m"} body)))
  (let [body (ws/request-body {:query "q" :numResults 5 :model "custom"} "m")]
    (is (= 5 (:numResults body)))
    (is (= "custom" (:model body)))))

(deftest format-sources-markdown
  (is (nil? (ws/format-sources [])))
  (let [text (ws/format-sources [{:url "http://a" :title "A"}
                                 {:url "http://b"}
                                 {:title "no url"}])]
    (is (.includes text "- [A](http://a)"))
    (is (.includes text "- http://b"))
    (is (not (.includes text "no url")))))

(deftest truncate-tail-passthrough
  (let [r (ws/truncate-tail "a\nb" 100 10)]
    (is (not (:truncated r)))
    (is (= "a\nb" (:content r)))))

(deftest truncate-tail-keeps-trailing-lines
  (let [text (str/join "\n" (map str (range 100)))
        r    (ws/truncate-tail text 10000 10)]
    (is (:truncated r))
    (is (= 10 (:output-lines r)))
    (is (.endsWith (:content r) "99"))))

(deftest shape-response-includes-sources-and-truncation-flags
  (let [r (ws/shape-response {:output "hello"
                              :sources [{:url "http://a" :title "A"}]
                              :responseId "r1"}
                             "http://x/api/tools/websearch" "m")]
    (is (.includes (:output r) "hello"))
    (is (.includes (:output r) "[A](http://a)"))
    (is (= 1 (get-in r [:metadata :sources-count])))
    (is (= "r1" (get-in r [:metadata :response-id])))
    (is (false? (get-in r [:metadata :truncated])))))
