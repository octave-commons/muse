(ns eta-mu.domain.agent
  "Pure decisions for agent actors: a background opencode session
   embodied as an actor — the spawn_agent_actor cell of the epiphany
   meta-workflow phase-0 toolset. An agent actor is one configuration
   of a task actor (the muse and her phases were another): the process
   is `opencode run`, its output streams to subscriber mailboxes, its
   lifecycle lands on its own ledger.")

(defn run-cmd
  "argv for a background opencode session. Never a shell string.
   opts: {:agent name :model provider/model :session id :continue bool}"
  [prompt {:keys [agent model session continue]}]
  (-> ["opencode" "run"]
      (into (when agent ["--agent" agent]))
      (into (when model ["--model" model]))
      (into (when session ["--session" session]))
      (into (when continue ["--continue"]))
      (conj prompt)))

(defn agent-slug
  "Actor-id slug for an agent: the agent name when given, else the
   generic agent kind."
  [{:keys [agent]}]
  (or agent "agent"))
