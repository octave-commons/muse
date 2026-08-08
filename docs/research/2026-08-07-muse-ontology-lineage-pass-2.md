<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Muse Ontology Archaeology — Pass 2: Internal Term Lineage

Date: 2026-08-07
Status: orientation / lineage reconstruction / non-decision
Research tier: orientation
Predecessor: `docs/research/2026-08-07-muse-ontology-archaeology.md`
Actor/harness: ChatGPT via GitHub connector; repository read/write access, no local checkout, shell, worktree, eta-mu CLI, or canonical Session Mycology runtime

## Question

How did the responsibilities associated with **Presence**, **Muse**, **Actor**, and **Keryx** change over time inside `octave-commons/fork_tales`, `octave-commons/muse`, and `open-hax/eta-mu`, and which distinctions appear stable enough to carry into the next ontology pass?

## Intended use

This pass establishes chronological lineage. It does not choose a final type hierarchy or rename any system.

## Method

- Read introducing commits where available, not only present-day files.
- Prefer files added in the dated commit over later summaries of those files.
- Treat historical transcripts as evidence of design intent, not proof of implementation.
- Distinguish repository identity from an internal entity named by the same word.
- Record semantic changes even when they happen within a day.
- Search for evidence that contradicts a simple linear evolution.

## Chronological lineage

### 2026-02-16 — Presence is already a user-facing interaction surface

Repository: `octave-commons/fork_tales`
Commit: `052df949b71f842ca24ecf54da80873dfa56e451`

The commit adds/refactors a `PresenceCallDeck` UI. Its model contains:

```text
CallStatus     = idle | connecting | connected | error
TranscriptRole = user | presence | system
CallSession    = ... presenceId
```

It selects `WorldPresence` values, presents a call UI, sends user text to `/api/presence/say`, and appends Presence replies to a transcript.

The fallback Presence set includes `witness_thread`, `receipt_river`, and `gates_of_truth`.

**Observation:** at this point, Presence is not merely a backend process term. It is directly addressable from a human-facing communication surface.

**Caution:** the commit is a UI refactor; it proves the surface existed by this date, not that this is the first historical use of the term.

### 2026-02-18 — `presence.v1` becomes a broad role contract; Muse becomes a family within it

Repository: `octave-commons/fork_tales`
Commit: `ef449cc1c6148c4830e582e1375786d8edc08f94`

The commit message says collaboration is expanding across “many presences” and introduces explicit contracts/routing language.

It creates a Presence-agent index with broad classes:

- health presences (`CPU`, `RAM`, `Disk`, `GPU`, `NPU`),
- development and project-management presences,
- governance/verification presences,
- lore-specific steward presences,
- Muse-associated presences.

Within the Muse-associated set it explicitly distinguishes:

```text
Muse persona cores
  Archon
  Sophia
  Trickster

Muse advisory fields
  Chaos
  Symmetry
  Memory
  Aesthetic
  Futures

Muse council members
  Efficiency
  Stability
  Emergence
  Compression
  Alignment
```

The individual Muse files are themselves `protocol: presence.v1` records. For example Archon is a Presence with persona=true, council access, advisory-only behavior, and delegation rights.

The same commit contains a persona-switch example:

```text
presence.muse.archon -> presence.muse.trickster
```

**Observation:** `Presence` is the broad addressable/role protocol; `muse` is a more specific namespace/family over some Presences. Not all Presences are Muses.

**Observation:** `Muse` here does not denote one singleton object. It describes a structured plurality of persona cores, fields, and council members.

### 2026-02-26 — Muse is explicitly a Presence connected to an LLM and the user chat path

Repository: `octave-commons/fork_tales`
File: `docs/notes/implementation/2026-02-26-agent-prompt-daimoi-crawler-muse.md`

The recorded constraint says:

```text
Muse is a Presence connected to a small LLM
```

and requires answers to be grounded in state/fact extraction, named logic queries, and receipts.

The Muse workflow begins:

```text
1. User sends message in chat panel.
2. Message is queued as a muse job.
3. Muse waits for compute budget.
4. Muse calls named logic queries.
5. Muse composes answer + receipts + labeled uncertainty.
```

**Observation:** this is the strongest explicit internal source for the current operator statement “the Muse is the agent a user actually speaks to.”

**Limitation:** the file is an implementation prompt/design record. It establishes intended semantics, not that every part was implemented exactly as stated.

### 2026-02-27 — Muse grounding becomes an implemented/runtime concern

Repository: `octave-commons/fork_tales`
Commit: `bf4d04d39247d1db36a7483af2ab771b12550a36`
Files include the Daimoi/Crawler/Muse architecture note and grounding infrastructure.

The commit explicitly names “Muse grounding infrastructure.” The resulting architecture records:

- explicit Muse tool requests and intent phrases map to named queries;
- grounded replies include receipts (`snapshot_hash`, `queries_used`);
- the demo asks Muse grounded questions about crawler learning and Daimoi outcomes.

**Observation:** Muse is now a consumer/interpreter of system facts for a human-facing answer path, not merely a poetic persona label.

### 2026-04-18 — eta-mu separates Actor from Agent

Repository: `open-hax/eta-mu`
Commit: `04d788aa4ec733ee221aa1682a2557327814bb29`

The contracts-v1 schema introduces:

```text
actor-kind = human | agent | service | cron
```

An Actor has identity, status, roles, capabilities, and optional model/system fields. Separately, an Agent Contract contains an `actor-id` and agent-specific execution/configuration fields.

The concrete `mindfuck` record states:

```text
actor/id   = "mindfuck"
actor/kind = :agent
```

**Observation:** in this contract lineage, **Actor is the broader entity class; Agent is one Actor kind and also has a separate agent-contract concern.**

This is stronger than an ordinary-language reading where actor/agent are synonyms.

### 2026-07-10 — Keryx is proposed for host translation before the `muse` repository exists

Repository: `open-hax/eta-mu`
Source transcript: `docs/notes/design/keryx-naming-anti-runtime.md`
Created: `2026-07-10`

The design record rejects `runtime` as a junk-drawer name and proposes **Keryx** for a role with two responsibilities:

```text
1. interpret declarations: contracts, capabilities, policies, exposures
2. carry them faithfully into a host: OpenCode first, later Pi/MCP/etc.
```

Its proposed public promise is essentially:

```text
lawful ημ declarations + target
  -> validated target artifact OR explicit incompatibility
```

A sibling July 10 record, `keryx-role-extern-boundary.md`, sharpens the intended split:

```text
ημ     = constitution / laws / declarations / ledgers / meaning
Keryx  = herald / assembly / translation / invocation / delivery
extern = gate to raw host world
```

**Observation:** the compiler/host-translation concern that current architecture assigns to the `muse` repository already had a distinct Proper Noun, Keryx, before `octave-commons/muse` was created.

**Caution:** these are design transcripts, not evidence that a `packages/keryx` implementation landed.

### 2026-07-11 — `octave-commons/muse` is born as a mixed actor-system + OpenCode DSL workspace

Repository: `octave-commons/muse`
Initial commit: `fcbde974ff3260046b20f2989cca1151724875a4`
Commit time: 2026-07-11

The initial commit message says:

```text
muse workspace with ClojureScript actor system
- actor system with memory, muse, and store modules
- OpenCode DSL for configuration and plugin integration
```

So repository birth already fuses two concerns:

```text
A. actor semantics / Muse entity behavior
B. host DSL / plugin compilation
```

The initial `eta-mu.actor.muse` namespace is especially explicit:

```text
The Muse: primary actor that orchestrates sub-actors (phases).
A Muse cannot inspect truth directly — she reads phase mailboxes
and requires evidence in ledgers to verify claims.
```

The initial type behavior is:

```text
spawn Muse -> actor kind "muse"
spawn Phase -> actor kind "phase", parented by muse-id
Muse -> command phase
Muse -> inspect phase observations/conclusions/evidence
```

**Observation:** on repository creation, `Muse` is unquestionably an **actor/entity concept**, not merely the repository's compiler-system name.

**Observation:** the repository itself is not cleanly one thing at birth. It contains both the Muse actor system and the host-DSL concern that Keryx had named the prior day.

### 2026-07-12 — Muse actor semantics reject command/orchestrator authority

Repository: `octave-commons/muse`
Commit: `ad6a7ceccb3c407fe638311fbcbca630316b6a04`

One day after the initial commit, `eta-mu.actor.muse` changes its semantic statement:

```text
She is not an orchestrator.
```

The `command!` operation becomes:

```text
influence! — not command
```

with the rule that influence is only appended to the phase ledger; the phase may read it later and nothing is interrupted or forced.

The same commit emits a primary OpenCode agent definition named **The Muse**:

- `mode: primary`;
- can spawn Phases of Understanding;
- cannot directly inspect files/search/run/edit;
- observes the world through phase ledgers;
- judges completion only from ledger evidence;
- reports to the user what the ledgers establish.

**Observation:** the internal Muse concept becomes *more* aligned with a human-facing epistemic/integrative agent while becoming *less* aligned with centralized orchestration.

**Observation:** “orchestration” is itself unstable terminology in the Muse lineage. The stable concern is closer to **spawn/influence/observe/synthesize/account for evidence** than command-and-control orchestration.

### 2026-07-26 — the repository name `Muse` is redefined as compatibility/compiler boundary

Repository: `octave-commons/muse`
Commit: `e350e627d4b85afba29d8c84c4e5b5a0571a94d1`

This commit deliberately rewrites the repository's README from:

```text
ClojureScript workspace that authors OpenCode plugins as pure data
and runs a filesystem daemon
```

into:

```text
ClojureScript compatibility and compilation workspace
```

and creates the accepted compatibility boundary:

```text
Muse = consume eta-mu/Katamorph resources
       link implementations/exposures
       emit host-native artifacts
```

It explicitly classifies `eta-mu.actor.muse` as **historical actor-role vocabulary, not the repository's system boundary**.

**Observation:** this is a conscious architectural reclassification. It did not discover that the repository had always meant compiler; it selected compiler/compatibility as the repository's stable future responsibility out of a mixed historical workspace.

### 2026-07-26 — eta-mu reconciles Keryx into current-Muse

Repository: `open-hax/eta-mu`
Commits: `d904daecc99775a8d9fa9e40eccb0c35a121926d`, later accepted in `62ae1cd6d36206a5ab844a33a8dcecc0558e6b3b`

The reconciliation classifies the Keryx corpus into:

1. portable compiler requirements -> current Muse;
2. OpenCode target requirements -> current Muse adapter;
3. eta-mu-native execution -> Sol/Rheos/etc.;
4. historical naming material -> lineage.

It prohibits building a second universal compiler called Keryx without a new decision.

**Observation:** this settles *repository ownership* under current architecture, but it does not erase the historical fact that Keryx originally named the host-translation concern and Muse originally named an actor/human-facing concern.

## Term-to-responsibility matrix

| Date | Term | Evidence-backed responsibility at that point | Ontological level suggested by evidence | Notes |
|---|---|---|---|---|
| 2026-02-16 | Presence | selectable/callable user-facing system participant | addressable interaction identity / role | Human communicates with selected Presence |
| 2026-02-18 | Presence | broad family of health/dev/governance/lore/Muse agents under `presence.v1` | role/entity protocol | Wider than Muse |
| 2026-02-18 | Muse | persona cores, advisory fields, council members, all expressed as Presences | specialization/family/composition within Presence | Not singleton |
| 2026-02-26 | Muse | LLM-connected Presence receiving user chat, grounding answers in facts/queries/receipts | human-facing conversational agent/presence | Strongest direct support for operator definition |
| 2026-02-27 | Muse | grounded fact/query/receipt interpreter for user questions | epistemic conversational interface | Runtime implementation begins matching design |
| 2026-04-18 | Actor | broad identity-bearing entity: human/agent/service/cron | base runtime/resource entity | Agent is one actor kind |
| 2026-04-18 | Agent | actor kind + separate execution/config contract referring to actor-id | specialization/configuration over Actor | Not equivalent to Actor |
| 2026-07-10 | Keryx | declaration interpreter + faithful host carrier/compiler | system/service responsible for translation/projection | Design intent, not landed compiler |
| 2026-07-11 | Muse | primary actor that spawns phases, observes ledgers, requires evidence | Actor specialization / epistemic parent | Coexists with OpenCode DSL in same repo |
| 2026-07-12 | Muse | primary user-facing agent; influences rather than commands phases; synthesizes ledger evidence | Actor specialization + user-facing role | Explicitly “not an orchestrator” |
| 2026-07-26 | Muse (repo) | harness compatibility/linker/compiler | compilation/projection system | Explicit reclassification of repository boundary |
| 2026-07-26 | Keryx | historical requirements/lineage subsumed into current Muse compiler boundary | inactive historical system name | New active use requires explicit decision |

## Findings

### F2.1 — The collision is historical, not hypothetical

**Tier:** provisional finding.
**Confidence:** very high.

The same Proper Noun `Muse` is directly evidenced at two distinct levels:

```text
Muse/entity-role
  a primary/user-facing actor or Presence
  grounded in evidence
  interacting with subordinate/internal actors

Muse/repository-system
  a compiler/linker
  translating portable declarations into host projections
```

The second meaning was selected later from a repository that initially contained both concerns.

### F2.2 — Keryx is not merely a rename suggestion invented after the collision

**Tier:** provisional finding.
**Confidence:** high about lineage; no recommendation yet.

Keryx's host-translation metaphor appears in dated July 10 design material before the July 11 Muse repository initial commit. Thus it is evidence of a separate concurrent conceptual stream, not merely a retrospective replacement name.

This does **not** itself justify renaming the current repository to Keryx; current accepted architecture deliberately assigned Keryx's compiler requirements to Muse.

### F2.3 — Presence is historically useful, but it is probably not identical to runtime Actor

**Tier:** provisional finding.
**Confidence:** medium-high.

Presence carried human/UI addressability and responsibility/persona contracts, including telemetry and governance roles. Actor later carries a more mechanical identity/lifetime/messaging/resource meaning and explicitly includes humans, agents, services, and cron actors.

A plausible distinction to test is:

```text
Actor     = addressable participant with runtime/resource identity and message/event relationships
Presence  = an identity/role as presented into a world/context, especially a human-facing or continuously represented participant
```

This remains a hypothesis until current authoritative schemas are inspected.

### F2.4 — Agent appears subordinate to Actor in the eta-mu contract lineage

**Tier:** provisional finding.
**Confidence:** high for the April 18 schema; current authority still needs inspection.

The observed contract explicitly encodes `actor/kind :agent` and makes agent contracts refer to `actor-id`. Unless superseded, ontology work should not flatten Agent and Actor into synonyms.

### F2.5 — “Orchestrator” should be quarantined until its semantics are specified

**Tier:** provisional finding.
**Confidence:** high that usage drifted.

Within one day the Muse implementation changes from “primary actor that orchestrates sub-actors” to “She is not an orchestrator,” while retaining phase creation, influence, observation, and synthesis.

So `orchestrator` is currently an adjective soup risk: it can mean command authority, task creation, routing, synthesis, lifecycle supervision, or merely being the main user-facing agent. Future ontology should name those operations separately.

## Candidate axes exposed by lineage

The chronological evidence suggests the ontology may need at least these independent axes:

```text
identity       — what persistent/addressable thing is this?
kind           — human, agent, service, cron, etc.
role           — what responsibility does it bear here?
presence       — how/where is that participant represented or made available?
persona        — what conversational/interpretive character or stance is active?
composition    — which other actors/faculties does it contain, consult, or represent?
addressability — who/what can message it and by which channel?
authority      — command, influence, advise, observe, decide, execute, accept?
capability     — what semantic operations may be requested?
lifetime       — persistent identity, session, turn, task, process, ephemeral invocation?
projection     — how is semantic intent represented in a particular host?
```

This list is a hypothesis generated by lineage, not a final schema.

## Contrary evidence / unresolved tensions

1. Fork Tales had multiple `presence.muse.*` entities, so “Muse = one user façade” is not historically universal.
2. `muse_runtime.py` later had multiple Muse IDs, again showing plural Muses.
3. The July 11 Muse actor initially used command semantics, then July 12 explicitly rejected command/orchestrator semantics. The exact authority model was actively evolving.
4. `Presence` was implemented through OpenCode “agents,” which means host implementation terminology and ontology were already mixed.
5. The April Actor/Agent schema may have been superseded by Katamorph/Sol work. It is strong lineage evidence, not yet asserted as current canonical law.
6. Current accepted architecture makes Muse the compiler repository boundary. Historical semantics do not automatically supersede that decision.
7. Keryx was a design proposal/transcript; no separately landed universal Keryx compiler was observed in the existing reconciliation.

## Disposition

The dated lineage supports keeping four terms conceptually distinct during further investigation:

```text
Actor    — broad participant identity/lifecycle/messaging class
Agent    — an Actor kind or execution specialization
Presence — contextual/user-facing representation or role family (hypothesis)
Muse     — historically a human-facing epistemic Agent/Presence or composition thereof
Keryx    — historically the host-translation/compiler concept
```

Only the first two have strong direct schema evidence in eta-mu lineage. Presence/Muse/Keryx require current-law reconciliation before any type relation is accepted.

No rename is proposed or accepted in this pass.

## Exact next collection step

Read the **current authoritative schemas and runtime laws** for Actor, Agent, Session, Turn, principal/identity, messaging, and capability bindings in Katamorph, Sol/eta-mu, Axxium, and event-ledger, and search for any surviving authoritative `Presence` or `Muse` type; then produce a current-state identity/lifetime/addressability/authority matrix to compare against this historical lineage.
