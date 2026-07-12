(ns plugins.websearch
  "Web search via the Open Hax proxy (OAuth-backed; no OPENAI_API_KEY).
   Ported from pseudo/websearch_open_hax.cljs. Request/response shaping is
   pure (eta-mu.domain.websearch); HTTP goes through the fetch boundary.
   Async composes with promesa — never inline ^:async in a macro body
   (syntax-quote strips the metadata)."
  (:require [eta-mu.boundaries.fetch :as http]
            [eta-mu.domain.websearch :as ws]
            [eta-mu.dsl :refer [deftool defplugin]]
            [promesa.core :as p]))

(def default-proxy-url "http://127.0.0.1:8789")

(defn- proxy-url []
  (or (http/env "OPEN_HAX_OPENAI_PROXY_URL" "OPEN_HAX_PROXY_URL")
      default-proxy-url))

(defn- proxy-token []
  (http/env "OPEN_HAX_OPENAI_PROXY_AUTH_TOKEN"
            "OPEN_HAX_PROXY_AUTH_TOKEN"
            "PROXY_AUTH_TOKEN"))

(defn- run-websearch! [params]
  (let [token (proxy-token)]
    (when-not token
      (throw (ex-info "Missing auth token for Open Hax proxy. Set OPEN_HAX_OPENAI_PROXY_AUTH_TOKEN (or PROXY_AUTH_TOKEN)." {})))
    (let [endpoint (ws/endpoint (proxy-url))
          model    (or (:model params)
                       (http/env "OPEN_HAX_WEBSEARCH_MODEL")
                       ws/default-model)]
      (p/let [{:keys [ok status status-text body]}
              (http/post-json! endpoint
                               {:headers {"Authorization" (str "Bearer " token)}
                                :body (ws/request-body params model)})]
        (cond
          (:raw body)
          (throw (ex-info (str "Open Hax websearch returned non-JSON: "
                               (subs (:raw body) 0 (min 2000 (count (:raw body)))))
                          {:status status}))

          (not ok)
          (throw (ex-info (str "Open Hax websearch error (" status " " status-text ")")
                          {:status status :body body}))

          :else
          (ws/shape-response body endpoint model))))))

(deftool websearch
  {:id          :web/search
   :name        "websearch"
   :description "Search the web via the Open Hax proxy using stored OpenAI OAuth logins (no OPENAI_API_KEY needed)."
   :args        [:map
                 [:query :string]
                 [:numResults {:optional true} :int]
                 [:searchContextSize {:optional true} [:enum "low" "medium" "high"]]
                 [:allowedDomains {:optional true} [:vector :string]]
                 [:model {:optional true} :string]]
   :tags        #{:web :search :network}
   :effects     #{:network/search}}
  [params _ctx]
  (run-websearch! params))

(defplugin plugin {:id :eta-mu/websearch}
  websearch)
