(ns eta-mu.actor.task
  "Task actors: a background OS process embodied as an actor — the
   spawn_process_actor cell of the epiphany meta-workflow phase-0
   toolset. All decisions live in eta-mu.domain.task; this namespace
   owns the effects.

   Lifecycle (task.started / task.exited) is appended to the task's
   own ledger, threaded under the started event's causal root. Every
   stdout/stderr line is fanned out to subscriber mailboxes. Control
   messages sent TO the task (task.subscribe / task.unsubscribe /
   task.kill) are picked up by a mailbox poll — steering a task is a
   ledger append it reads when it gets around to it, like any actor."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.envelope :as envelope]
            [eta-mu.boundaries.node.proc :as proc]
            [eta-mu.domain.task :as task]
            [promesa.core :as p]))

(def poll-interval-ms 250)

;; task-id → {:state atom :kill fn :pid int :cmd [..] :exit promise}
;; The store holds the durable ledgers; this registry holds what a
;; ledger cannot: the live kill capability and in-flight counters.
(defonce ^:private running (atom {}))

;; ---------------------------------------------------------------------------
;; Ledger effects
;; ---------------------------------------------------------------------------

(defn- send-threaded!
  "Append an envelope threaded under the task's causal root. Resolves
   to the event-id, or nil after counting a delivery failure."
  [state task-id to-id root event-type payload]
  (-> (actor/send! task-id to-id
                   {:event/type  event-type
                    :causal/root root
                    :payload     payload})
      (p/catch (fn [_]
                 (swap! state update :delivery-failures inc)
                 nil))))

(defn- fan-out-line!
  "Count the line and deliver it to every current subscriber."
  [state task-id root stream line]
  (swap! state task/count-line stream)
  (let [event-type (task/line-event-type stream)]
    (doseq [sub (:subscribers @state)]
      (send-threaded! state task-id sub root event-type
                      {:stream (name stream) :line line}))))

;; ---------------------------------------------------------------------------
;; Control mailbox poll
;; ---------------------------------------------------------------------------

(defn- apply-control!
  "Fold one control envelope into the task state; a kill request also
   fires the live kill capability."
  [state kill-fn envelope]
  (when-let [effect (task/control-effect envelope)]
    (swap! state task/apply-control effect)
    (when (:kill effect)
      (kill-fn))))

(defn- start-control-poll!
  "Poll the task's own mailbox for control messages until stopped.
   Returns a stop fn."
  [state task-id kill-fn]
  (let [last-seen (atom nil)
        interval  (js/setInterval
                   (fn []
                     (-> (p/let [events (actor/recv task-id
                                                    (if @last-seen
                                                      {:since-id @last-seen}
                                                      {}))]
                           (when-let [latest (last events)]
                             (reset! last-seen (:event/id latest)))
                           (doseq [e (filter task/control-type? events)]
                             (apply-control! state kill-fn e)))
                         (p/catch (fn [_] nil))))
                   poll-interval-ms)]
    (fn [] (js/clearInterval interval))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn spawn-task!
  "Spawn [cmd & args] as a task actor. Registers the actor, appends
   task.started to its ledger, fans stdout/stderr lines to subscriber
   mailboxes, and appends task.exited when the process ends.

   opts: {:cwd string :subscribers [actor-ids] :kind string :name-hint
   string :meta map} — kind/name-hint/meta let other actor species
   (agents) ride the same machinery under their own identity.
   Resolves to {:task-id kw :pid int :exit promise-of-exit-envelope}."
  ([cmd] (spawn-task! cmd {}))
  ([cmd {:keys [cwd subscribers kind name-hint meta]}]
   (let [kind       (or kind "task")
         task-id    (task/task-id envelope/new-id kind
                                  (or name-hint (first cmd)))
         state      (atom (task/initial-state subscribers))
         started-id (envelope/new-id)]
     (p/let [_ (actor/spawn! task-id (merge {:kind kind
                                             :cmd  (vec cmd)
                                             :cwd  cwd}
                                            meta))]
       (let [handle (proc/spawn-lines!
                     cmd {:cwd     cwd
                          :on-line #(fan-out-line! state task-id started-id %1 %2)})
             kill!  (fn []
                      (swap! state assoc :kill-requested true)
                      ((:kill handle)))
             stop!  (start-control-poll! state task-id kill!)
             exit   (p/let [res  (:exit handle)
                            _    (do (stop!)
                                     (swap! state assoc :status :exited)
                                     true)
                            _    (actor/send! task-id task-id
                                              {:event/type  task/exited-type
                                               :causal/root started-id
                                               :payload     (task/exited-payload @state res)})]
                      (swap! running update task-id dissoc :kill)
                      res)]
         (swap! running assoc task-id {:state state
                                       :kill  kill!
                                       :pid   (:pid handle)
                                       :cmd   (vec cmd)
                                       :kind  kind
                                       :exit  exit})
         (p/let [_ (actor/send! task-id task-id
                                {:event/id    started-id
                                 :event/type  task/started-type
                                 :causal/root started-id
                                 :payload     (task/started-payload cmd cwd (:pid handle))})]
           {:task-id task-id
            :pid     (:pid handle)
            :exit    exit}))))))

(defn kill!
  "Directly kill a running task (the mailbox route is task.kill).
   Returns true when a live kill capability existed."
  [task-id]
  (when-let [k (get-in @running [task-id :kill])]
    (k)
    true))

(defn await-exit
  "The task's exit promise, or nil for unknown tasks."
  [task-id]
  (get-in @running [task-id :exit]))

(defn status
  "Live status snapshot for one task, or nil when this process never
   spawned it. Ledger history remains queryable via the actor store."
  [task-id]
  (when-let [{:keys [state pid cmd kind]} (get @running task-id)]
    (let [s @state]
      {:task-id           task-id
       :kind              kind
       :pid               pid
       :cmd               cmd
       :status            (:status s)
       :subscribers       (vec (:subscribers s))
       :stdout-lines      (:stdout-lines s)
       :stderr-lines      (:stderr-lines s)
       :kill-requested    (:kill-requested s)
       :delivery-failures (:delivery-failures s)})))

(defn list-tasks
  "Status snapshots for every task this process has spawned."
  []
  (into [] (keep status) (keys @running)))
