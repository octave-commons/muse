(ns eta-mu.domain.task
  "Pure decisions for task actors: a background OS process embodied as an
   actor (the spawn_process_actor cell of the epiphany meta-workflow
   phase-0 toolset).

   Mailbox semantics:
   - The task's own ledger records lifecycle: task.started, task.exited.
   - Every stdout/stderr line is fanned out to subscriber mailboxes as
     task.stdout / task.stderr messages.
   - Control messages sent TO the task steer it: task.subscribe,
     task.unsubscribe, task.kill.

   No I/O here — all effects live in eta-mu.actor.task."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Event types
;; ---------------------------------------------------------------------------

(def started-type "task.started")
(def stdout-type "task.stdout")
(def stderr-type "task.stderr")
(def exited-type "task.exited")

(def subscribe-type "task.subscribe")
(def unsubscribe-type "task.unsubscribe")
(def kill-type "task.kill")

(def control-types #{subscribe-type unsubscribe-type kill-type})

(defn control-type?
  "True when the envelope is a control message for a task actor."
  [envelope]
  (contains? control-types (:event/type envelope)))

;; ---------------------------------------------------------------------------
;; Actor id naming
;; ---------------------------------------------------------------------------

(defn- slug [s]
  (let [base (-> (str s) (str/replace #"^.*/" "") str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? base) "proc" base)))

(defn task-id
  "Deterministic-shape task actor id: :<kind>.<slug>.<short-uuid>.
   id-fn is injected (defaults handled by callers) for purity. The
   3-arity names other task-shaped actors, e.g. agents: kind \"agent\"
   with the agent's own slug."
  ([id-fn cmd] (task-id id-fn "task" (first cmd)))
  ([id-fn kind name-hint]
   (let [short (-> (id-fn) (str/replace #"-" "") (subs 0 8))]
     (keyword (str kind "." (slug name-hint) "." short)))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn initial-state
  "Fresh task state. Subscribers may be strings or keywords."
  [subscribers]
  {:status            :running
   :subscribers       (set (map keyword subscribers))
   :stdout-lines      0
   :stderr-lines      0
   :kill-requested    false
   :delivery-failures 0})

(defn terminal? [state]
  (not= :running (:status state)))

(defn count-line
  "Account one emitted line for its stream (:stdout or :stderr)."
  [state stream]
  (update state (if (= :stdout stream) :stdout-lines :stderr-lines) inc))

;; ---------------------------------------------------------------------------
;; Control messages
;; ---------------------------------------------------------------------------

(defn control-effect
  "Interpret a control envelope. Returns one of
   {:subscribe actor-id} {:unsubscribe actor-id} {:kill true} — or nil
   when the envelope is not a control message or is malformed."
  [envelope]
  (case (:event/type envelope)
    "task.subscribe"   (when-let [sub (:subscriber (:payload envelope))]
                         {:subscribe (keyword sub)})
    "task.unsubscribe" (when-let [sub (:subscriber (:payload envelope))]
                         {:unsubscribe (keyword sub)})
    "task.kill"        {:kill true}
    nil))

(defn apply-control
  "Fold a control effect into the state."
  [state effect]
  (cond
    (:subscribe effect)   (update state :subscribers conj (:subscribe effect))
    (:unsubscribe effect) (update state :subscribers disj (:unsubscribe effect))
    (:kill effect)        (assoc state :kill-requested true)
    :else                 state))

;; ---------------------------------------------------------------------------
;; Outbound payloads
;; ---------------------------------------------------------------------------

(defn started-payload [cmd cwd pid]
  {:cmd (vec cmd) :cwd cwd :pid pid})

(defn line-event-type
  "The subscriber-facing event type for a line on the given stream."
  [stream]
  (if (= :stdout stream) stdout-type stderr-type))

(defn exited-payload
  "Terminal event payload. ok means a clean exit the task owner asked for:
   zero exit code and no kill requested."
  [state {:keys [code signal]}]
  {:code              code
   :signal            signal
   :killed            (boolean (:kill-requested state))
   :ok                (and (zero? code) (not (:kill-requested state)))
   :stdout-lines      (:stdout-lines state)
   :stderr-lines      (:stderr-lines state)
   :delivery-failures (:delivery-failures state)})
