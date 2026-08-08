<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Host-Neutral Declaration Language — Pass 4: Responsibility, Loss, and the λ Neighborhood

Date: 2026-08-07
Status: orientation / non-decision
Research tier: orientation with decision-support preparation
Predecessors:
- `docs/research/2026-08-07-muse-ontology-archaeology.md`
- `docs/research/2026-08-07-muse-ontology-lineage-pass-2.md`
- `docs/research/2026-08-07-muse-ontology-recovery-pass-3.md`

## Question

What is the unnamed data-oriented language/compiler concern that emerged from
`eta-mu/packages/extensions`, Keryx, the July 11 data-first DSL work, Katamorph,
and the current capability/implementation/exposure split?

This pass deliberately does **not** assign it a Proper Noun. It instead asks:

1. what belongs to the source language;
2. what belongs to canonical data/contract representation;
3. what belongs to implementation binding;
4. what belongs to host projection;
5. what kinds of semantic loss must be reported rather than silently erased;
6. whether the operator's new `λ-*` intuition corresponds to an existing formal
   programming-language concept.

## New operator-supplied source

The operator supplied a Muse inbox passage during this pass. The passage is
important because it distinguishes the *Muse that enabled the discovery* from
the technical abstraction discovered through that process.

Selected phrases from the supplied text:

```text
The muse is the newest project.
...
I followed the river of receipts to a Mushroom Forest..
...
What does not have to be true right now, to have been true 5 minutes from now,
for the purposes of being false afterwards?

Why, a data as interpreter.
```

The remainder describes logical expressions whose useful meaning exists at a
particular interpretive moment, whose compiled/communicated consequence can
remain intelligible after that interpretation is no longer the active one.

**Epistemic status:** direct operator-supplied historical text. The repository
path/commit for this excerpt was not independently recovered through code search
in this pass, so the text is preserved as supplied rather than represented as a
Git-addressed observation.

## Internal lineage trace

### L0 — legacy `packages/extensions`: cross-platform intent with host-shaped primitives

`open-hax/eta-mu/packages/extensions/README.md` calls the package the canonical
source for eta-mu contract runtimes used by Pi, OpenCode, and other frameworks.
Its architecture already contains:

```text
manifest.edn
extension specs
Pi target generator
OpenCode target generator
macros for state / events / tools
```

The old `eta-mu.core/defextension` captures an extension as data, but its source
vocabulary is still:

```text
command
 tool
 event
 handler
```

and `tool` embeds a Pi-shaped five-argument execute contract. The Pi target and
OpenCode target then generate host modules from these forms.

The OpenCode generator still contains an explicit TODO for wiring tools and
commands into the plugin export.

**Finding:** the legacy package already wanted to be a multi-target compiler,
but the supposed source language inherited the ontology and calling conventions
of its first hosts. Portability was implemented *after* host concepts had
already entered the source representation.

### L1 — eta-mu platform μ0–μ4: data-first separation begins

The later `eta-mu.platform` layer improves the boundary substantially:

- authoring macros emit plain data;
- capability handlers receive no JS host objects;
- a registry contains no host-target knowledge;
- effect plans are interpreted against injected capabilities;
- JS conversion is isolated behind explicit boundary namespaces.

However, this layer still contains transitional fusion:

- a capability includes its executable handler;
- `tool` remains a source-level exposure kind;
- `hook` carries an event vocabulary and handler directly;
- the runtime constructors still know the names `tool`, `hook`, and `plugin`.

**Finding:** μ0–μ4 established the correct direction — declarative source,
injected effects, explicit boundary — but did not finish separating semantics,
implementation, and exposure.

### L2 — Keryx, 2026-07-10: the translation responsibility becomes explicit

The Keryx notes define a separate responsibility:

```text
ημ declarations
      |
      v
Keryx
  - link approved handlers
  - validate contracts
  - interpret capabilities/policies
  - render host adapters
      |
      +-> OpenCode
      +-> Pi
      +-> MCP
```

The strongest promise in those notes is effectively:

```text
Given lawful declarations and a target,
produce a validated target artifact
or report why the declaration cannot be carried there.
```

The Keryx role notes also recover `extern` as the gate at which foreign host
values are decoded into lawful internal shapes.

They explicitly reclassify the old extension bundle by semantic responsibility:
receipt persistence as observation/ledger behavior, contract gates as law or
interception, provider configuration as extern/provider concern, etc.

**Finding:** Keryx did not merely propose another compiler package. It proposed
a law for *semantic carriage across a boundary*, including explicit refusal to
silently degrade unsupported semantics.

### L3 — July 11 data-first DSL: source forms are constructors over an IR

The July 11 Muse inbox/design material states the core distinction directly:

```text
deftool / defhook / defplugin = ergonomic constructors
resulting values              = plain data
OpenCode                      = one interpreter of the registry
```

A companion note makes the stronger statement:

```text
ημ data is the only language application code speaks.
JavaScript is an external transport.
The interpreter owns every crossing.
```

**Finding:** the core discovery was not a portable set of OpenCode plugins. It
was a language boundary in which host-specific syntax is a projection of a
stable data representation.

### L4 — Katamorph: the canonical data-language concern has already separated

Current `open-hax/katamorph` describes itself as:

```text
a portable contract language and interpreter toolkit
for data-described runtimes
```

Its declared path is:

```text
EDN namespace file
  -> manifest projections with qualified identities
  -> kind-specific schema validation
  -> pure interpreter or law
  -> consumer-injected effects
```

Katamorph explicitly owns stable identities, portable resource kinds,
relationships, schemas, validation, and small pure interpreters. It explicitly
does not own OpenCode/Claude/Codex/Sol host behavior.

Current architecture records therefore already identify Katamorph as the shared
resource/contract language, even though those records still assign host
compilation to the repository named Muse — a naming conclusion reopened by the
operator in Pass 3.

**Finding:** the previously “unnamed DSL” has partially differentiated into at
least two layers already:

```text
Katamorph
  canonical portable data language / contract IR

[unnamed authoring + projection concerns]
  ergonomic Lisp forms and host compilation machinery around that IR
```

### L5 — Muse PR #4: semantics, implementation, and exposure finally split

Current `octave-commons/muse/src/cljs/eta_mu/dsl.cljc` now declares:

```text
capability
  semantic meaning and contracts
  no executable handler
  no host-facing name

implementation
  runtime executable binding for a capability

exposure
  target-facing presentation selecting an implementation
```

`link-tool` then projects those records into the old flat tool shape for
compatibility with existing adapters.

The schema file makes the same boundary explicit and labels the old fused
capability shape as migration-only compatibility.

**Finding:** this is the strongest implementation evidence yet that semantic
meaning, executable realization, and host presentation are distinct axes.

It also exposes remaining transitional leakage:

- `exposure :target :tool` is not yet a complete host-neutral exposure ontology;
- hooks still use a target-specific event vocabulary;
- `plugin` remains a loadable/registration unit inherited from host packaging;
- linking currently collapses separated descriptors back into a flat tool for
  legacy targets.

## Responsibility matrix

| Concern | Canonical responsibility | Must remain host-neutral? | Current fossil / implementation | Notes |
|---|---|---:|---|---|
| Resource identity | stable qualified identity and kind | yes | Katamorph manifest grammar | identity must survive every projection |
| Semantic capability | input/output/effects/errors/docs/meaning | yes | Katamorph target model + Muse semantic-capability | no handler or host-facing name |
| Policy / law | admissibility and semantic constraints | yes | Katamorph policy/law, eta-mu law | host permission UI is only one enforcement surface |
| Presence declaration | Muse/Phase/etc. semantic participant description | yes | still under ontology research | must not equal a host agent/session type |
| Implementation | one executable realization of a semantic capability | mostly | Muse implementation descriptor | runtime/language/deps/version belong here |
| Grant | who may invoke/use a capability | yes | Katamorph roles/capabilities, architecture proposal | distinct from implementation and exposure |
| Exposure intent | how a capability may be presented in a class of surfaces | ideally | Muse exposure descriptor | needs vocabulary above `tool`, `route`, MCP method, UI action |
| Assembly/linking | resolve identities, implementations, grants, profiles, references | yes until target selection | Muse normalization/linking; Keryx notes | produces inspectable linked graph, not host objects |
| Effect plan | declarative requested effects | yes | eta-mu effect interpreter | execution environment supplies handlers |
| Effect interpretation | perform effects and return normalized results | boundary-sensitive | eta-mu interpreter / consumer injected effects | can be replaced for test/runtime/host |
| Extern | decode/encode foreign values and failures | no, explicitly target-specific | Keryx extern concept; eta-mu JS boundary | foreign values stop here |
| Target interpretation | map lawful assembly to one host's vocabulary | no | current Muse boundaries/adapters | may fail with structured loss |
| Artifact emission | host plugin/config/tool/MCP/CLI/etc. | no | generated OpenCode/Claude/MCP artifacts | rebuildable projection, never source authority |
| Compatibility diagnostics | explain unsupported or lossy translation | target-indexed but semantically required | Keryx law / current architecture intent | silence is a bug |
| Evidence/receipt | durable account of what interpretation/action occurred | semantic record, storage implementation varies | event-ledger/Receipt River | not merely host logging |

## Semantic-loss matrix

A target compiler should be modeled as a partial interpretation, not a printer.
The following losses require explicit handling.

| Source meaning | Possible target loss | Required disposition |
|---|---|---|
| Capability is callable | host has no equivalent callable surface | choose a supported exposure, declare target optional, or reject |
| Input/output law | host schema language cannot express constraint | preserve stronger internal validation and mark presentation loss; reject if unsafe |
| Effect set | host has no permission primitive | enforce internally; never treat missing host permission UI as a grant |
| Grant/authorization | host identity model cannot express principal/role | supply an adapter binding or reject |
| Lifecycle/interception relation | host lacks equivalent before/after/session event | explicit incompatibility or declared fallback; never silently omit |
| Observer semantics | host has no post-action hook | run observer in internal invocation path or reject if evidence law requires it |
| Async actor/message semantics | host exposes only blocking request/response | project through ledger/watch/background mechanism or reject; do not turn async law into polling-by-accident |
| Presence identity | host gives only session/subagent IDs | maintain separate semantic identity binding; do not collapse Presence into host process identity |
| Implementation runtime | selected implementation cannot execute in target environment | select alternate implementation or reject |
| Model/provider relation | host uses incompatible provider/model naming | resolve portable references before projection; target name is derived |
| Presentation | target requires different names/descriptions/schema decoration | vary freely while preserving semantic identity |
| Evidence requirement | host has only transient logs | retain event-ledger/receipt sink outside host or reject evidence-critical operation |
| Unsupported source construct | target has no lawful representation | produce structured incompatibility with source identity and lost property |

## A more precise mathematical sketch

The system can be described without claiming a new calculus yet.

Let:

```text
D = canonical declarations / Katamorph resources
I = available implementation bindings
G = grants/policies/profiles
A = an assembled, validated graph produced from D + I + G
T = a selected target
```

Then target projection is approximately:

```text
interpret_T : A -> Artifact_T + Loss_T
```

where `Loss_T` is first-class and structured rather than an exception string or
silent omission.

For execution, a capability may return a declarative effect plan:

```text
plan : Input -> Result + EffectPlan
```

and an environment supplies interpretation:

```text
handle_E : EffectPlan -> Result
```

Different hosts/tests/deployments can provide different `handle_E` functions
without changing the semantic capability description.

This is already enough structure to explain most of the architecture without
using `runtime` as a generic noun.

## What is this relative to lambda calculus?

### Not yet a lambda calculus

Lambda calculus has a formal syntax and reduction/evaluation semantics centered
on abstraction and application. This project currently has no comparable small
formal calculus, reduction relation, type-safety theorem, or equivalence law for
agent declarations.

Therefore calling the current DSL a `λ-calculus` would overclaim its formal
status.

### Closest PL-theory relative: initial/data encoding with multiple interpreters

The current system represents source language constructs as inspectable data and
then supplies interpreters/projections over that data. In programming-language
literature this is closer to an **initial encoding** of an embedded language than
to the tagless-final style.

That distinction is useful:

```text
initial/data style
  program = syntax/data tree
  interpreter = fold/interpretation over that syntax

final/tagless style
  program = already parameterized by the semantic algebra/interface
  adding an interpreter supplies a new meaning directly
```

The present Clojure/EDN approach intentionally wants the first property because
source values must be serializable, inspectable, diffable, validated, linked,
committed to Git, and consumed outside one running host language.

### Strong relative: algebraic effects and handlers

The eta-mu `:plan` + injected effect interpreter pattern is structurally close to
algebraic-effects systems:

```text
semantic code names an effectful operation
interpreter/handler decides what that operation means here
```

This is a stronger correspondence than “it uses lambdas.” It explains why
capabilities can remain pure descriptions/plans while test, OpenCode, Sol, or a
service environment supplies different effect handlers.

The current code is not an implementation of a formal algebraic-effects calculus,
but the family resemblance is useful design evidence.

### Relevant but different: Agent-Oriented Programming

Shoham's 1993 Agent-Oriented Programming formalized agents around mental-state
categories including beliefs, decisions, capabilities, obligations, and typed
communication acts, with agent interpreters controlling programs.

That makes **agent-oriented language** a real existing programming-language term,
not merely a contemporary LLM phrase.

The present system differs materially:

- Presence/Actor semantics are ledger- and evidence-oriented rather than defined
  primarily through mental attitudes;
- the declaration language must compile into multiple existing harnesses;
- capability, implementation, exposure, grant, provenance, and target-loss are
  explicit separate concerns;
- durable data/contract representation is load-bearing.

So “Agent-Oriented Lisp” is descriptively reasonable, but it should not imply
that this is simply a Clojure implementation of Shoham's AOP model.

## The λ naming hypothesis

The operator proposed that `λ-*` may belong in the eventual language name,
partly because the system is Lisp/Clojure and partly because the language is
“deliberately unnamed like a λ.”

This is semantically evocative but currently a **naming hypothesis**, not a
formal classification.

Useful distinctions:

```text
λ as metaphor
  anonymous / parameterized / function-producing / Lisp lineage

λ as formal claim
  a specified calculus with syntax, judgments, and reduction/equivalence laws
```

The first is available now. The second has not been earned yet.

### Collision cautions

Several obvious names are already occupied:

- `λμ` is Parigot's established calculus for classical natural deduction and
  continuation/control semantics.
- `λ_A` was used in a 2026 preprint for a typed lambda calculus for LLM agent
  composition.
- `LLMbda Calculus` was used in a 2026 preprint for LLM conversations, tool use,
  and information-flow control.

Therefore `lambda-mu`, `λ_A`, and generic “LLMbda” naming would create false
formal associations.

## A candidate three-layer stack — no names accepted

The evidence now supports testing this separation:

```text
[λ-* ?]
  Clojure/Lisp authoring surface
  ergonomic constructors/macros/combinators
  agent-oriented semantic composition
       |
       | lowers to / constructs
       v
Katamorph
  canonical portable data language / contract IR
  stable identities + schemas + relations + pure laws
       |
       | assembled + interpreted for target
       v
[Keryx responsibility]
  link implementations/exposures/grants
  select target
  prove compatibility or report semantic loss
  emit host artifact
       |
       +-> OpenCode
       +-> Claude
       +-> MCP
       +-> Sol / eta-mu-native
       +-> future harness
```

This model would let the Muse return to the Presence ontology without requiring
the repository history to be erased.

It also lets Katamorph keep its already-declared role as portable contract
language while leaving room for a Lisp-native **authoring metalanguage** above
it and a host **projection responsibility** below it.

## Important open questions

1. Is the Lisp authoring surface merely syntax sugar for Katamorph resources, or
   can it express higher-order composition that cannot be faithfully serialized
   into Katamorph data?
2. Should implementations be Katamorph resources, source-language values, or a
   linked external registry whose metadata is described by Katamorph?
3. Is an exposure itself semantic (`callable`, `intercept`, `observe`, `stream`)
   with host-specific forms below it, or should exposures remain entirely
   target-owned?
4. What is the minimal semantic lifecycle vocabulary that can project to
   OpenCode/Claude/MCP/Sol without adopting one host's hooks?
5. Can the target interpreter be specified as a lawful fold/catamorphism over a
   canonical declaration algebra, and if so is that relationship already part
   of why `Katamorph` is the correct IR name?
6. Which agent/Presence constructs belong in the source language versus the
   contract IR? The answer affects whether the eventual `λ-*` language is an
   authoring EDSL or a fuller agent-oriented programming language.

## Disposition

Orientation / boundary clarification.

This pass strengthens these conclusions:

- `packages/extensions` is a behavioral fossil, not the desired source-language
  boundary;
- Keryx captured the host-translation responsibility and loss law;
- Katamorph already occupies much of the canonical portable data-language role;
- Muse PR #4 supplies strong implementation evidence for separating capability,
  implementation, and exposure;
- the remaining unnamed concern may be a Lisp-native authoring metalanguage plus
  a target projection responsibility, not one monolithic “Muse compiler”;
- `λ-*` is currently a promising metaphorical naming family, but the system is
  not yet a new lambda calculus in the formal sense.

No rename, package split, or ontology promotion is accepted by this artifact.

## Exact next collection step

Inspect Katamorph's actual resource schemas/interpreters together with the
current Muse target adapters and one real cross-target capability fixture, then
trace a single declaration from Lisp authoring form -> Katamorph data -> linked
assembly -> OpenCode/Claude/MCP artifacts, recording every transformation and
whether it is semantic-preserving, implementation-selecting, presentation-only,
or lossy.