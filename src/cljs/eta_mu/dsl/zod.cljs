(ns eta-mu.dsl.zod
  "Malli schema-expr → zod validator. Shared by every host boundary that
   renders DSL tool :args into a zod raw shape (OpenCode, MCP, ...)."
  (:require ["zod" :refer [z]]))

(defn- entry-properties? [x]
  (and (map? x) (not (vector? x))))

(declare schema->zod)

(defn- map-entries->shape
  "[[:k props? schema] ...] → JS object of zod validators (a ZodRawShape)."
  [entries]
  (let [shape #js {}]
    (doseq [[k & more] entries]
      (let [[props schema] (if (entry-properties? (first more))
                             [(first more) (second more)]
                             [nil (first more)])
            validator (cond-> (schema->zod schema)
                        (:optional props) (.optional))]
        (unchecked-set shape (name k) validator)))
    shape))

(defn schema->zod
  "Convert the supported subset of Malli schema expressions to a zod validator."
  [schema]
  (cond
    (keyword? schema)
    (case schema
      :string            (.string z)
      (:int :double :number) (.number z)
      :boolean           (.boolean z)
      :keyword           (.string z)
      (:any :map)        (.any z)
      (.any z))

    (vector? schema)
    (let [[tag & args] schema
          ;; drop schema-level properties map, e.g. [:int {:min 1}]
          args (if (entry-properties? (first args)) (rest args) args)]
      (case tag
        :map    (.object z (map-entries->shape args))
        :vector (.array (schema->zod (first args)))
        :maybe  (.nullable (schema->zod (first args)))
        :enum   (.enum z (to-array (map #(if (keyword? %) (name %) (str %)) args)))
        :or     (.union z (to-array (map schema->zod args)))
        (:int :double :number) (.number z)
        :string (.string z)
        (.any z)))

    :else (.any z)))

(defn args->zod-shape
  "Tool :args is a [:map ...] schema expression; hosts want the raw shape."
  [args]
  (if (and (vector? args) (= :map (first args)))
    (map-entries->shape (rest args))
    #js {}))
