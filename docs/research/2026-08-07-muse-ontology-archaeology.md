<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Muse Ontology Archaeology — Orientation Record

Date: 2026-08-07
Status: orientation / non-decision
Research tier: orientation, intended to graduate to decision-support only after lineage and contrary evidence are collected
Actor/harness: ChatGPT via GitHub connector; repository read/write access, no local checkout, shell, worktree, eta-mu CLI, or direct Session Mycology/Receipt River runtime

## Research question

What stable ontology can separate the concerns currently named or associated with **Muse**, **Presence**, **Agent**, **Actor**, **Keryx**, eta-mu, Katamorph, Sol, Rheos, Epiphany, Receipt River, Session Mycology, and adjacent systems, while preserving the historical concept that a Muse is the agent/presence a user actually speaks to?

The immediate uncertainty is whether `Muse` currently names one coherent kind of thing or whether two historically related but materially different concerns have been collapsed under the same Proper Noun:

1. a user-facing interlocutor/composite presence that mediates interaction with a system of models, tools, facts, and internal agents; and
2. a harness-compatibility linker/compiler that projects portable declarations into OpenCode, Claude, MCP, Codex, and other host-native artifacts.

## Intended use

This record may inform later ontology, naming, repository-boundary, and architecture proposals. It does **not** rename a repository, supersede an accepted architecture note, or accept any proposed type relation.

## Scope

Included in this orientation pass:

- direct source inspection in `octave-commons/fork_tales`, `octave-commons/muse`, `open-hax/eta-mu`, `octave-commons/epiphany`, `octave-commons/Truth`, and `octave-commons/fork_tales_v2`;
- the historical/runtime meaning of Muse and Presence;
- current cross-repository system-role records;
- process, evidence, receipt, and reflection distinctions needed to conduct the archaeology responsibly.

Excluded for this pass:

- external literature on agent architectures, actor systems, HCI assistants, personas, orchestration, or multi-agent systems;
- a final naming recommendation;
- code changes or migrations;
- claims about uninspected historical commits or unavailable referenced files.

## Method

1. Prefer directly fetched repository files over directory trees, search-result snippets, or prior model summaries.
2. Treat repository documents according to their recorded status; an accepted note is evidence of a current project decision, not evidence that the terminology is optimal or immutable.
3. Keep direct observations separate from derived findings and proposals.
4. Search for contrary evidence: usages where the same term names a different role, especially `Muse`, `Presence`, `Actor`, `Agent`, and `Keryx`.
5. Preserve historical lineage rather than normalizing old terms into current vocabulary.
6. Use Epiphany's research discipline: question, scope, method, evidence set, findings, limitations, and disposition.
7. Borrow Truth's research quality loop as an operational pattern: producer -> peer review -> gap analysis -> coordination/promotion, without treating its missing referenced skill file as available authority.

## Harness limitation / process exception record

The current harness has direct GitHub repository reads and writes but no local checkout, shell, worktree, eta-mu CLI, or ability to execute repository-local tests. Therefore local-worktree and CLI-specific turn-close mechanics cannot be executed here. Remote writes are isolated on `research/muse-ontology-archaeology-20260807`; no completion or verification claim is made. This is a capability limitation, not evidence that those local obligations are unnecessary.

## Evidence set inspected in pass 1

### `octave-commons/fork_tales`

- `.opencode/agent/README.md` — classifies general Presence agents, Muse persona cores, Muse advisory fields, and Muse council members.
- `.opencode/agent/presence.muse.archon.md` — Archon is a `presence.v1` strategic persona with council access and delegation.
- `.opencode/agent/presence.muse.sophia.md` — Sophia is a `presence.v1` wisdom/coherence persona with council access.
- `docs/notes/implementation/2026-02-26-agent-prompt-daimoi-crawler-muse.md` — states the LLM Muse epistemic constraint and user-chat workflow.
- `docs/notes/implementation/2026-02-27-daimoi-crawler-muse-architecture-note.md` — records Muse grounding through facts, named queries, and receipts.
- `part64/code/world_web/muse_runtime.py` — defines multiple runtime Muse IDs, labels, roles, system prompts, events, context manifests, and runtime state.
- `world_building/misc/Receipt_River_Protocol.md` — distinguishes persistence/order from judgment and frames receipts as anti-amnesia.
- commit `bf4d04d39247d1db36a7483af2ab771b12550a36` — commit message explicitly calls the work “Muse grounding infrastructure.”

### `octave-commons/muse`

- `README.md` — current repository self-definition as a ClojureScript compatibility and compilation workspace; explicitly not a new agent harness.
- `docs/architecture/compatibility-boundary.md` — accepted operator boundary: Muse links resources/implementations/exposures and compiles host-native artifacts; it does not own canonical actor/session/policy/capability/event/workflow semantics.
- issue #1, “Correct Muse compatibility boundary and actor watch semantics” — records the operator clarification behind that boundary.
- `receipts.edn` — demonstrates the repository's Receipt River fixture and historical implementation decisions.

### `open-hax/eta-mu`

- `docs/architecture/contract-dialect-and-data-authority.md` — working cross-repository role map: Katamorph resource/contract language; Muse compiler; eta-mu integration/runtime monorepo; Sol native agent runtime; Rheos work coordination; Epiphany evidence-governed workspace/research platform; event-ledger operational event spine; plus Axxium/Proxx/Uxx/Knoxx boundaries.
- `docs/notes/design/muse-keryx-runtime-compiler-reconciliation.md` — accepted reconciliation classifying Keryx as lineage/source material rather than a second top-level compiler authority.
- `packages/session-mycology/README.md` and `law/reflection.cljs` — Session Mycology owns reflection/lesson/retrospective/spore semantics and explicitly separates those events from Receipt River receipts.

### `octave-commons/epiphany`

- `PROCESS.md` — constitutional separation of observation, inference/finding, decision, verification, and acceptance; responsible-work modes Explore/Orient/Commit/Act/Verify/Reflect.
- `docs/process/research.md` — research must record question, scope, method, evidence set, findings, and disposition; search snippets/model summaries are discovery aids, not standalone support.
- `docs/process/glossary.md` — operational definitions for artifact, source, projection, observation, evidence, finding, hypothesis, inference, decision, acceptance, and related terms.
- `receipts.edn` — examples of research/decomposition/decision-review receipts with artifacts and observations.

### `octave-commons/Truth`

- `AGENTS.md` — requires recent receipts and Session Mycology lessons, ground work in specs, and documents the deep-research actor family.
- `docs/research/ACTORS.md` — domain research actors feed a peer reviewer and gap analyst, then a coordinator.
- `docs/research/INDEX.md` — research promotion states `draft -> validated -> spec-derivation -> promoted` and links findings to sources/specs.
- `docs/research/physics/multi-timescale-integration-jacobi-ecs.md` — example of a validated notebook with an explicit research question, primary sources, literature comparison, equations, limitations, and implementation mapping.
- referenced `.agents/skills/deep-research/SKILL.md` — **unavailable at `main` through direct fetch during this pass**; its contents are not assumed.

### `octave-commons/fork_tales_v2`

- `AGENTS.md` and `PROCESS.md` — current corpus/provenance boundaries and explicit relation to Muse/eta-mu/Epiphany; no direct `muse` code-search result was observed in this pass.

## Direct observations

### O1 — Muse originally existed as a user-facing LLM Presence

**Tier:** observed.

The 2026-02-26 Fork Tales implementation prompt states: “Muse is a Presence connected to a small LLM” and requires the Muse to answer from fact extraction + named logic queries + receipts. Its workflow starts when a **user sends a message in the chat panel**, queues a Muse job, waits for compute budget, calls named logic queries, and composes an answer with receipts and explicit uncertainty.

This directly supports the historical/user-facing meaning of Muse. It does not by itself establish whether `Presence` should remain the canonical supertype.

### O2 — Presence was broader than Muse in Fork Tales

**Tier:** observed.

The Presence agent index contains health, UX, development, project-management, governance, and other `presence.v1` agents in addition to Muse-associated presences. Within the Muse namespace it separately lists:

- persona cores (`Archon`, `Sophia`, `Trickster`),
- advisory fields (`Chaos`, `Symmetry`, `Memory`, `Aesthetic`, `Futures`),
- council members (`Efficiency`, `Stability`, `Emergence`, `Compression`, `Alignment`).

Thus `Presence` was a broader operational/addressable category, while `muse` marked a family/composition of personas/advisors/council members.

### O3 — Fork Tales runtime also instantiated multiple named Muses

**Tier:** observed.

`muse_runtime.py` defines runtime Muse records, events, context manifests, per-Muse system prompts, and bootstrap Muse IDs such as `witness_thread`, `chaos`, and `github_security_review`. This indicates that historical implementation did not consistently reserve `Muse` for exactly one singleton façade.

### O4 — Current `octave-commons/muse` assigns Muse a different system boundary

**Tier:** observed / accepted project decision.

The current README and accepted compatibility boundary define Muse as the portable linker/compiler from eta-mu/Katamorph resources into host-native artifacts. They explicitly reject Muse as the canonical owner of agent, actor, session, policy, event, or workflow semantics.

This is a real accepted repository boundary. It is also semantically different from O1's user-facing LLM Presence.

### O5 — Keryx was explicitly prevented from becoming a duplicate compiler

**Tier:** observed / accepted project decision.

The accepted eta-mu reconciliation says Keryx notes supplied portable compiler requirements, OpenCode target requirements, eta-mu-native runtime requirements, and historical naming material. It says Keryx is not a second top-level system and that assigning it a new active boundary requires a new explicit cross-repository decision.

Therefore “rename the compiler Keryx” is not a conclusion available from existing authority; it would be a new proposal requiring fresh evidence and acceptance.

### O6 — Current cross-repo architecture already separates many concerns cleanly

**Tier:** observed, record status itself is working architecture rather than immutable canon.

`contract-dialect-and-data-authority.md` distinguishes at least:

- shared semantic resource/contract language (Katamorph),
- target compilation/host compatibility (Muse, current meaning),
- native agent runtime (Sol),
- workflow/work coordination (Rheos),
- operational event semantics (event-ledger),
- evidence/workspace cognition/research (Epiphany),
- provider/routing policy (Proxx),
- identity/authorization (Axxium),
- presentation library (Uxx),
- deployable product composition (Knoxx).

The unresolved issue is not absence of separation; it is whether the Proper Nouns attached to those concerns preserve the intended ontology and historical human-facing concepts.

### O7 — Receipt River and Session Mycology are deliberately different concerns

**Tier:** observed.

Receipt River is execution/provenance persistence: append-only evidence/receipts that should outlive a storyteller. Session Mycology explicitly owns reflection, lessons, retrospectives, spore candidates, incubation, and promotion, and its law says a reflection is **not** a Receipt River kind.

This distinction should survive any ontology cleanup: what happened and what was learned from what happened are different fact classes.

### O8 — Truth's strongest reusable research pattern is a quality loop, not merely citation density

**Tier:** derived from inspected Truth guidance and artifacts.

Truth separates domain research production, peer review, gap analysis, coordination/indexing, and later promotion into specs/code. Its index records promotion state rather than treating a notebook's existence as implementation authority. This is compatible with Epiphany's explicit epistemic tiers.

## Preliminary findings

### F1 — `Muse` currently has a load-bearing naming collision

**Tier:** provisional finding.
**Confidence:** high for the existence of collision; low for the remedy.

Two directly supported meanings coexist in repository history:

```text
Muse-A: user-facing interlocutor / LLM Presence / composite cognitive interface
Muse-B: harness compatibility linker/compiler / target projection system
```

They are related by a general theme of mediation, but they do not have the same inputs, authority, lifecycle, or user relationship. Treating them as one architectural type risks hiding a separation-of-concerns problem behind a poetic name.

### F2 — “Muse is the agent a user actually speaks to” is consistent with the older implementation, but the exact supertype is unresolved

**Tier:** provisional finding.
**Confidence:** high that the human-facing role existed; medium on type hierarchy.

The historical prompt is strong evidence for the user-facing role. The broader Presence catalog and multiple runtime Muse IDs make at least three type models plausible:

1. `Muse <: Presence` — a Muse is a special user-facing Presence;
2. `Muse = composite of Presences/actors` — the Muse is the conversational identity while internal Presences form its council/facets;
3. `Muse` is a role/projection over an underlying Actor/Agent/Presence rather than a runtime entity type.

No selection is made in this pass.

### F3 — “Agent”, “Actor”, “Presence”, and “Muse” should not be allowed to collapse into synonyms

**Tier:** provisional finding.

The inspected corpus already gives them different pressures:

- **Agent** tends toward model/tool/session execution semantics.
- **Actor** tends toward asynchronous addressable runtime/process semantics.
- **Presence** historically classified addressable project-local roles, including non-LLM operational roles.
- **Muse** carried human-facing identity/composition and LLM-grounded interaction semantics.

The next pass must inspect their actual schemas and runtime laws before promoting these tendencies into definitions.

### F4 — Keryx is still useful as a candidate *relation/role* term, but current evidence does not authorize it as the compiler's new system name

**Tier:** provisional finding.

The existing Keryx decision only closes one question: do not build a duplicate top-level compiler under that name. It does not establish that the messenger/herald metaphor is unusable for a narrower delivery/translation relation. Any reuse must be explicit and must not recreate the rejected compiler duplication.

### F5 — Ontology should separate entity types from responsibilities and projections

**Tier:** provisional finding.

The current collision appears partly caused by using Proper Nouns at multiple ontological levels. A stable model likely needs distinct categories such as:

```text
entity / identity       — what persists and can be addressed
role                    — what responsibility it bears in a context
capability              — what semantic operation exists
implementation          — how an operation is executed
exposure/projection     — how a host presents it
relationship            — how entities communicate/delegate/ground each other
artifact/record         — what durable evidence or state is produced
product/composition     — which concrete system assembles the above for users
```

This categorization is a research hypothesis, not an accepted taxonomy.

## Concern inventory v0

| Concern | Current/legacy names touching it | Collision to investigate |
|---|---|---|
| Human-facing conversational identity | Muse, Presence, agent, persona | Is Muse an entity, role, composite, or projection? |
| Internal cognitive plurality | Muse council, persona cores, advisory fields, actors | Which parts are addressable actors versus facets/configuration? |
| Async execution | Actor, Sol, eta-mu runtime | Actor vs agent vs session/turn lifetime |
| Communication/delivery | event-ledger, mailbox, Keryx notes | Event vs message vs signal vs delivery relation |
| Semantic contracts/resources | Katamorph, capability vocabulary | Resource identity vs runtime behavior |
| Harness compatibility | current Muse repo, exposures, target adapters | Does this concern need a Proper Noun distinct from user-facing Muse? |
| Provider/model routing | Proxx, Sol | Model selection is not conversational identity |
| Work coordination | Rheos | Task/workflow state is not agent runtime state |
| Evidence/knowledge/research | Epiphany | Understanding is not execution |
| Operational provenance | Receipt River, event-ledger | Receipt semantics vs generic event semantics |
| Reflection/learning | Session Mycology | Lessons are not receipts |
| Identity/authorization | Axxium | Principal identity is not persona identity |
| Presentation/product surface | Uxx, Knoxx | UI surface is not runtime/harness semantics |
| Domain application/corpus | Fork Tales | Product meaning should not become universal runtime ontology |

## Contrary evidence / cautions found

1. Historical Fork Tales uses multiple Muse IDs, which weakens any claim that Muse has always meant one singleton user façade.
2. The current Muse compatibility boundary is accepted project authority, so historical meaning alone cannot silently overwrite it.
3. Keryx has already been considered and explicitly rejected as a second top-level compiler authority.
4. `Presence` includes operational/non-Muse agents, so equating Presence with Muse would erase a historical distinction.
5. Truth references `.agents/skills/deep-research/SKILL.md`, but that file was unavailable at `main` in this harness; this pass uses only inspected Truth artifacts.

## Research gaps opened

### G1 — Historical Muse lineage before and after the February 2026 Fork Tales implementation

Need introducing commits, preceding design notes, later refactors, and evidence of how the user-facing Muse related to `Presence`, Council, Daimoi, Nooi, Nexus, facts, and runtime state.

### G2 — Runtime type law for Agent / Actor / Presence / Session / Turn

Need direct schemas and authoritative code/docs in Katamorph, Sol, eta-mu, event-ledger, and any surviving Presence implementation.

### G3 — Muse repository naming lineage

Need the earliest commits/notes that caused the compatibility/compiler workspace to inherit the name `muse`, including the concurrent Keryx stream, to distinguish intentional semantic extension from historical accident.

### G4 — Communication ontology

Need direct evidence for event, message, signal, intent, fulfillment, mailbox, delivery, causal identity, and Keryx/herald concepts before choosing names for inter-agent communication.

### G5 — External terminology

After internal lineage is mapped, compare against actor-model, multi-agent, HCI assistant, persona, mediator/broker, orchestration, capability, and projection terminology to identify false friends and useful established distinctions.

## Disposition

This is an **orientation result only**. It establishes that a naming/ontology collision exists and identifies the evidence needed to resolve it. It does not establish a final ontology and does not supersede the current Muse compatibility boundary.

## Exact next collection step

Trace the dated internal lineage of `Muse`, `Presence`, `Actor`, and `Keryx` by reading the introducing and immediately surrounding commits/files in `octave-commons/fork_tales`, `octave-commons/muse`, and `open-hax/eta-mu`, then record a chronological term-to-responsibility matrix before inspecting external terminology.
