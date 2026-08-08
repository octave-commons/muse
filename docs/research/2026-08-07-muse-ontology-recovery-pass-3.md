<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Muse Ontology Archaeology — Pass 3: Operator Recollection and Concept Recovery

Date: 2026-08-07
Status: orientation / operator-corroboration / non-decision
Research tier: orientation
Predecessors:
- `docs/research/2026-08-07-muse-ontology-archaeology.md`
- `docs/research/2026-08-07-muse-ontology-lineage-pass-2.md`

## Question

Given the operator's recovered recollection of the July 2026 Epiphany/Muse bootstrap, which concepts were actually being discovered, which were merely implementation machinery, and which names became attached to the wrong concern?

This pass specifically tests the recollection that:

- `Muse` and `Phase` were intended as participant/Presence concepts;
- a Muse was the human-facing participant that accumulated evidence through subordinate phases and ledgers;
- the `muse` repository was a temporary container for overlapping thoughts during Epiphany bootstrap;
- the host-neutral, data-oriented DSL/compiler was a separate concern and was never intended to *be* the Muse concept;
- Keryx notes were documenting that compiler/carrier concern;
- eta-mu's legacy ClojureScript extensions package was the implementation pressure that motivated a cleaner multi-harness form.

## Epistemic handling

The operator's present recollection is recorded as **operator testimony**. It is authoritative about present intended meaning and autobiographical design context, but it is not silently rewritten into historical repository observation.

Repository artifacts are used to classify each part of the recollection as:

- corroborated;
- partially corroborated;
- not yet recovered;
- contradicted by a particular historical implementation.

An accepted repository architecture decision is evidence of what was accepted at that time. It does not outrank a later explicit operator correction about what a Proper Noun was intended to denote; instead, the conflict becomes a new decision/reconciliation problem.

## Operator testimony — 2026-08-07

The operator recalls that the `muse` repository emerged during an unusual, highly iterative night while bootstrapping Epiphany. The project became a place for thoughts whose shape was recognizable but whose concern boundaries and names were still unstable.

The operator now distinguishes the concepts as follows:

### Presence

`Presence` may be the better semantic term for the participant concept that has often been called an Agent. `Actor` remains useful and comparatively precise as an execution/addressability model, while `Agent` is overloaded between generic acting entities and products/harnesses such as ChatGPT, Claude, Codex, OpenCode agents, etc.

The operator proposes that **Muse** and **Phase** are types of Presence.

### Muse

A Muse is intended to be the Presence a human actually speaks to.

The recovered mythic/semantic constraints are:

- a Muse creates circumstances in which others can create or discover;
- the Muse inspires but does not itself create;
- the Muse can interact with other Presences;
- other Presences do not directly interact with the Muse;
- in the recent implementation, the Muse's world was deliberately restricted to evidence surfaces such as files/logs/ledgers and communication with ledger-backed actors rather than arbitrary direct action;
- the Muse accumulates evidence from Phases and presents/synthesizes it for the human.

The operator sees this as a more grounded descendant of the original Fork Tales Muse.

### Phase

A Phase is another type of Presence. It performs bounded work intended to increase understanding and records enough evidence/artifacts that later work can proceed from its ledger.

### Epiphany relation

The operator remembers a narrative formulation that resisted direct technical description:

- a Muse creates the circumstances for an Epiphany;
- only through an Epiphany can one become aware of something like a Muse.

The exact committed wording has **not yet been recovered** in this pass. This recollection is preserved as operator testimony, not promoted to observed repository fact.

### The unnamed DSL/compiler concern

The operator states that the host-neutral, data-oriented DSL allowing one set of configurations/plugins/capabilities to compile to several harnesses was **never intended to be called Muse**.

That concern arose from trying to build what `eta-mu/packages/extensions` wished it was:

- one semantic/data description;
- multiple harness projections;
- OpenCode and Claude as richer, established targets;
- eta-mu/Pi as another target rather than the architecture everything else must imitate;
- no repeated framework-specific rewrites that work for one target and break another.

Keryx notes were documenting this concern. The `muse` repository acquired the name because the creative process itself needed a Muse and the repository had become the container for the concurrent thought streams.

## Corroboration from repository evidence

### C1 — Epiphany itself contained the Muse/Phase concept before the repository boundary hardened

`octave-commons/epiphany/docs/designs/epiphany-agent-ledgers.md` is dated 2026-07-11 and describes itself as:

> Muses as plugin-defined primary agents and Phases as sub-agents, each with isolated append-only event ledgers.

Its body says:

- Muses are custom primary agents in OpenCode;
- the Muse can interact with the system only through sub-agents and ledgers;
- it cannot inspect truth directly;
- it is intentionally skeptical of sub-agent claims and requires ledger evidence;
- its responsibility is to accumulate evidence of Phase completion;
- Phases of Understanding are background sub-agents;
- each Muse and Phase has an append-only event ledger;
- a Muse may read ledgers of its Phases, while Phase agents may observe only their own Phase events.

**Disposition:** strongly corroborated.

This source is especially important because it lives in Epiphany and is explicitly exploratory. The Muse/Phase shape was therefore part of the Epiphany process-design thought stream, not merely an accidental type introduced later by the `muse` compiler repository.

### C2 — The same Muse/Phase text was committed into the initial `muse` repository

The initial `muse` commit contains `docs/inbox/2026.07.11.17.19.19.md`, whose first section is `## Muses` and repeats the same core rules:

```text
Muse = custom primary agent
Muse -> subagents + global/session ledgers only
Muse cannot inspect truth directly
Muse requires evidence from Phase ledgers
Muse accumulates evidence of Phase completion
Phase = background sub-agent
Muse reads Phase ledgers
Phase reads only its own Phase events
```

**Disposition:** strongly corroborated.

The duplicated text links the Epiphany exploratory model directly to the new Muse repository's initial actor implementation.

### C3 — Epiphany records the moment of resisting eta-mu conceptual import

`docs/designs/epiphany-meta-workflow.md`, also dated 2026-07-11, says the author looked at eta-mu-sol and stopped themself from bringing its actor-model material over wholesale:

```text
No, I shouldn't bring that over here. It's a seperate thing, that's what I always do.
that's how I create the problem that inspired this project.
```

The same exploratory file's metadata describes it as a workflow including Muses, Phase agents, ledgers, and grounding phases of understanding.

**Disposition:** corroborates the remembered concern-separation pressure and the fact that the Muse/Phase thought stream was active during Epiphany bootstrap.

It does not itself contain the remembered poetic Muse/Epiphany sentence.

### C4 — The Muse implementation quickly acquired the 'inspire but do not create' law

On 2026-07-12 the Muse primary-agent definition says the Muse:

- bestows creativity, inspiration, and motivation on spawned Phases;
- cannot be creative herself;
- influences and observes;
- never directly touches the world;
- judges Phase completion through recorded evidence;
- reports what the ledgers establish to the user.

The actor implementation simultaneously replaces command semantics with `influence!` and explicitly says the Muse is not an orchestrator: an influence is appended to a Phase ledger and the Phase chooses whether/when to read it.

**Disposition:** strongly corroborated for inspiration/non-creation/influence/evidence-synthesis.

**Version difference:** this historical implementation says the Muse cannot directly read files/search/run/edit, whereas the operator recalls a later/recent form with restricted file/log inspection tools. Treat these as potentially different revisions, not a contradiction to normalize away.

### C5 — The host-neutral DSL was being designed as a separate data/interpreter concern

The initial Muse commit also contains the 2026-07-11 DSL discussion. It explicitly proposes:

- a data-first registry as the stable intermediate representation;
- `deftool`, `defhook`, and `defplugin` as ergonomic constructors, not ontology;
- plain data as their output;
- a bootstrap/compiler that renders the registry into OpenCode;
- OpenCode as **one interpreter** of the registry;
- domain logic that remains ignorant of OpenCode, Bun, Node, and raw JS objects;
- the same semantic policy/capability being renderable toward OpenCode, Pi, MCP, or other targets.

A companion note says:

```text
ημ data is the only language your application code speaks;
JavaScript is an external transport;
the interpreter owns every crossing.
```

**Disposition:** strongly corroborated. This is the unnamed compiler/DSL concern in technical form, and the source does not require it to be a Muse.

### C6 — Keryx named the compiler/carrier concern before the Muse repository existed

The 2026-07-10 eta-mu Keryx notes define Keryx as the herald/interpreter that:

- reads lawful ημ declarations;
- assembles capabilities/policies/exposures;
- crosses a host boundary;
- emits target-native adapters or explicit incompatibility;
- treats OpenCode as the first receiving realm rather than the source ontology.

This precedes the `octave-commons/muse` initial commit by one day.

**Disposition:** strongly corroborated that Keryx was a name attached to the host-translation concern before the Muse repository existed.

This does **not** decide whether Keryx remains the best final system name.

### C7 — Current code has now separated the unnamed DSL's internal concerns more cleanly

Muse PR #4 (merged 2026-07-27) separates:

```text
capability
  semantic identity, description, input/output, effects, errors

implementation
  runtime, handler, dependencies, version

exposure
  target-facing identity, selected implementation, name, args/tags/presentation
```

The linker projects those descriptors into legacy flat tool forms for current target adapters. One capability can have multiple implementations/exposures.

**Disposition:** corroborates the current technical shape of the unnamed host-neutral DSL/compiler concern.

It also reinforces why this concern should not be defined by one runtime's concept of a tool/plugin/agent.

### C8 — The 2026-07-26 'Muse = compiler' finding must be reopened

Epiphany's cross-repository synthesis on 2026-07-26 records a provisional finding that “Keryx and Muse occupy the same compiler boundary.” The current Muse compatibility-boundary decision then assigns the compiler responsibility to Muse and treats Keryx as lineage.

The operator's 2026-08-07 clarification changes the interpretation of that evidence:

- Keryx and the host-neutral DSL/compiler may indeed describe substantially the same **technical concern**;
- but the Proper Noun `Muse` was attached to that concern through repository/container history, not because the compiler concern itself was intended to be a Muse;
- therefore the accepted repository ownership decision must not be reused as a type definition.

**Disposition:** reopen as an ontology/naming decision. Do not silently rewrite or delete the accepted record.

## What has not yet been recovered

### U1 — Exact Muse/Epiphany narrative

No inspected source yet contains the remembered formulation that a Muse creates circumstances for an Epiphany and an Epiphany makes the Muse perceptible.

The surrounding concepts are clearly committed, but the exact thought remains **operator testimony / unrecovered source**.

### U2 — Interaction asymmetry

The operator remembers:

```text
Muse can interact with other Presences
other Presences cannot interact directly with Muse
```

The July 12 implementation partially resembles this through influence-only behavior and ledger isolation, but the inspected sources do not yet establish this exact law. Preserve it as a candidate law pending lineage/code search.

### U3 — Later Muse file/log inspection surface

The July 11–12 version is deliberately ledger-only and cannot directly inspect files. The operator recalls a later implementation with bounded file/log reading. The exact revision has not yet been identified.

## Corrected provisional ontology v0.2

This model is intentionally split by ontological layer.

### Execution substrate

#### Actor

A runtime-addressable participant/process with identity, mailbox/ledger, lifecycle, and delivery semantics.

Existing eta-mu contract evidence supports Actor as broader than Agent (`human | agent | service | cron` in an earlier contract vocabulary).

`Actor` answers primarily:

> What can receive events / execute / persist runtime identity?

### Semantic participant layer

#### Presence

**Provisional definition:** a meaningful participant as experienced or reasoned about inside a world/relationship, independent of which concrete harness actor currently realizes it.

A Presence may be realized by one Actor, multiple Actors over time, or a composition/projection over actors. This remains a hypothesis until current runtime and identity laws are inspected.

`Presence` answers primarily:

> Who/what is participating here, from the system/user's semantic point of view?

This is deliberately distinct from a host's overloaded word `agent`.

#### Muse : Presence

Candidate defining relations:

```text
human -> converses-with -> Muse
Muse -> spawns/elicits -> Phase(s)
Muse -> observes -> evidence/Phase ledgers
Muse -> influences -> Phase
Muse -> synthesizes/accounts-for -> evidence
Muse -/-> arbitrary direct world mutation
```

Candidate law from operator testimony:

```text
Muse may address other Presences
other Presences may not directly address Muse
```

Candidate epistemic role:

```text
Muse does not establish truth by fiat.
Muse assembles conditions/evidence in which understanding can emerge.
```

#### Phase : Presence

Candidate defining relations:

```text
Phase -> pursues -> bounded objective / mode of understanding
Phase -> observes/acts -> permitted world surfaces
Phase -> appends -> isolated ledger
Phase -> produces -> evidence/artifacts/conclusion
Muse -> reads -> Phase ledger
```

The important type claim is not that a Phase is always an OpenCode sub-agent. `OpenCode subagent` is one host projection capable of realizing a Phase Presence.

### Host realization layer

#### Harness agent / session / subagent

OpenCode primary agent, OpenCode subagent, Claude session, Codex agent, eta-mu/Pi process, etc. are concrete host execution forms.

They should be treated as realizations/projections of semantic participant and capability descriptions rather than as the ontology itself.

### Declaration/compilation layer — deliberately unnamed

For now call this only the **host-neutral declaration compiler** in research prose.

Its concern is:

```text
semantic declarations
  + capability contracts
  + implementation bindings
  + exposures/profiles/policies
        |
        v
link + validate + target-loss analysis
        |
        +-> OpenCode artifacts
        +-> Claude artifacts
        +-> MCP artifacts
        +-> eta-mu/Pi/native artifacts
        +-> future hosts
```

Properties:

- data first;
- host-neutral semantic vocabulary;
- target-specific codecs/adapters are projections;
- one semantic capability may have many implementations and exposures;
- host limitations produce explicit compatibility/loss diagnostics;
- generated target artifacts are rebuildable projections, not canonical meaning.

Historical candidate names: Keryx and later the repository name Muse.

**No final Proper Noun is selected in this pass.**

## Separation-of-concerns consequence

The historical `muse` repository currently contains at least three different concern families:

```text
1. Muse/Phase Presence prototypes
   actor, mailbox, watch, evidence-oriented interaction

2. host-neutral declaration/compiler mechanism
   DSL, descriptors, linking, profiles, target generation

3. compatibility/conformance fixtures
   host adapters, generated agents/plugins, receipt/event integration examples
```

Their co-location is useful archaeological evidence but should not be treated as proof that they belong to one named ontology node.

## Relation to eta-mu/packages/extensions

The operator's framing makes `packages/extensions` a legacy predecessor rather than the target abstraction:

```text
legacy extensions
  = behavior accreted around eta-mu/Pi and later CLJS adaptation

host-neutral declaration compiler
  = semantic source independent of a host, with eta-mu/Pi/OpenCode/Claude/etc.
    as target realizations
```

The next research pass should compare these directly rather than infer the desired compiler solely from the current Muse repository.

## Research lesson

A repository can be a **chrysalis or convergence workspace** rather than an ontology boundary. When multiple thought streams meet during discovery, the repository name can become attached to the mechanism that survived there even when that mechanism is not what the name originally denoted.

Therefore:

```text
repository name != entity type
prototype location != semantic ownership
creative catalyst != artifact discovered through it
```

## Disposition

Orientation / corrected lineage.

This pass reopens the *name* of the host-neutral declaration compiler while strengthening the Muse/Phase-as-Presence hypothesis. It does not rename the `muse` repository, revoke the compatibility boundary on `main`, or promote the Presence hierarchy into current contract law.

## Exact next collection step

Trace the unnamed host-neutral declaration compiler end-to-end from legacy `eta-mu/packages/extensions` through the July 10 Keryx notes, the July 11 data-first DSL notes, and Muse PR #4's capability/implementation/exposure descriptors, then produce a responsibility-and-loss matrix showing what must remain semantic, what belongs to implementations, and what belongs only to target projections — without assigning the compiler a Proper Noun.
