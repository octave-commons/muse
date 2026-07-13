(ns eta-mu.boundaries.opencode
  "The OpenCode host boundary. This is the ONLY namespace allowed to touch
   JS values, #js literals, promises-as-transport, or the zod library.
   Everything upstream (schema, normalize, profile, compile, plugin) is pure
   CLJC data; this namespace renders a compiled adapter into the Hooks shape
   OpenCode 1.x expects:
     tool args    → zod raw shape (z.ZodRawShape)
     tool result  → string | {output, title?, metadata?}
     plugin       → (input) => Promise<Hooks>"
  (:require [eta-mu.dsl.zod :as zod]))

;; ---------------------------------------------------------------------------
;; Ingress / egress
;; ---------------------------------------------------------------------------

(defn- decode-args [js-args]
  (js->clj js-args :keywordize-keys true))

(defn- decode-tool-ctx [ctx]
  (when ctx
    {:session/id (.-sessionID ctx)
     :message/id (.-messageID ctx)
     :agent      (.-agent ctx)
     :directory  (.-directory ctx)
     :worktree   (.-worktree ctx)}))

(defn- encode-result
  "Handlers return plain CLJS data. Strings pass through; a map with an
   :output key is treated as a structured ToolResult (:output/:title/
   :metadata); anything else is serialized to JSON in the output slot."
  [result]
  (cond
    (string? result) result
    (and (map? result) (contains? result :output))
    (let [o   (:output result)
          out #js {:output (if (string? o) o (js/JSON.stringify (clj->js o)))}]
      (when-let [title (:title result)]
        (unchecked-set out "title" title))
      (when-let [metadata (:metadata result)]
        (unchecked-set out "metadata" (clj->js metadata)))
      out)
    :else (js/JSON.stringify (clj->js result))))

(defn- wrap-execute [handler]
  (fn [js-args js-ctx]
    (-> (js/Promise.resolve (handler (decode-args js-args) (decode-tool-ctx js-ctx)))
        (.then encode-result))))

;; ---------------------------------------------------------------------------
;; Rendering the compiled adapter
;; ---------------------------------------------------------------------------

(defn- render-tool [{:keys [description args handler]}]
  #js {:description description
       :args        (zod/args->zod-shape args)
       :execute     (wrap-execute handler)})

(defn- render-tools [tools]
  (let [out #js {}]
    (doseq [{:keys [name] :as tool} tools]
      (unchecked-set out name (render-tool tool)))
    out))

(defn- wrap-hook
  "Compiled hooks return an effect algebra:
     nil | {:effect :reject :message ...} | {:effect :patch :output {...}}
   The boundary decides what those mean to the host."
  [composed]
  (fn [js-input js-output]
    (let [result (composed (js->clj js-input :keywordize-keys true)
                           (js->clj js-output :keywordize-keys true))]
      (case (:effect result)
        :reject (js/Promise.reject (js/Error. (or (:message result) "rejected")))
        :patch  (do (js/Object.assign js-output (clj->js (:output result)))
                    (js/Promise.resolve nil))
        (js/Promise.resolve nil)))))

(defn render-hooks-into! [js-hooks hooks]
  (doseq [[event composed] hooks]
    (unchecked-set js-hooks (name event) (wrap-hook composed)))
  js-hooks)

(defn render-plugin
  "Compiled adapter {:tools [...] :hooks {event fn}} → OpenCode Hooks object."
  [{:keys [tools hooks]}]
  (render-hooks-into!
   #js {:tool (render-tools tools)}
   hooks))

(defn activate!
  "Run the adapter's init fns and return what OpenCode expects from a
   plugin function: Promise<Hooks>."
  [{:keys [inits] :as adapter}]
  (doseq [init inits] (init))
  (js/Promise.resolve (render-plugin adapter)))
