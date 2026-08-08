<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Katamorph and Partial Interoperability — Pass 5

Date: 2026-08-07
Status: orientation / operator-corroboration / non-decision
Research tier: orientation with formalization hypotheses
Predecessor: `docs/research/2026-08-07-host-neutral-declaration-language-pass-4.md`

## Question

How should the Katamorph / host-translation architecture be understood after the operator clarified that Katamorph was intentionally named from category theory, that it defines valid concepts and valid data shapes rather than host transformations, and that target integrations need interoperability over supported fragments rather than universal feature parity?

## Operator clarification — 2026-08-07

The operator states that the name `Katamorph` came from category theory.

The intended responsibility was:

- describe which concepts are valid;
- describe which shapes of data are valid within each concept;
- do **not** make Katamorph responsible for translating those concepts into OpenCode, Claude, Codex, MCP, GitHub Actions, or other target runtimes;
- use the project currently named `muse` to implement those translations around the target systems;
- use implementation pressure from a Presence/Actor model and many target spaces to discover missing concepts and reshape Katamorph gradually;
- let Katamorph's grammar crystallize only after the shared semantic forms have survived many different spaces.

The operator further states that universal feature parity is not required. A target may understand only a subset of the shared semantic language. What matters is that:

1. values entering a target through a concept it implements are valid instances of the shared shape;
2. values leaving that target through an implemented concept can be represented as valid outputs consumable by any other system implementing a valid consumer for that shape;
3. unsupported concepts may remain untranslated rather than being given false target meanings.

This is recorded as operator testimony and current intended direction, not silently represented as already-landed contract law.

## Corroboration from current Katamorph

Katamorph's README currently describes the repository as a `portable contract language and interpreter toolkit for data-described runtimes` and says that identities, capabilities, policies, triggers, actions, sources, stores, providers, and agent configuration are described in EDN, while applications provide deployment-specific effects.

Its stated narrow execution boundary is:

```text
EDN namespace file
  -> manifest projections with qualified identities
  -> kind-specific schema validation
  -> pure interpreter or law
  -> consumer-injected effects
```

The README also states that Muse is the first intended host interpreter and that a translation must preserve meaning or report incompatibility rather than silently drop unsupported semantics.

This strongly corroborates the separation between a shared data/contract language and host translation.

## Corroboration from the schema registry

The current `katamorph.schema` namespace calls itself a unified resource-boundary schema registry and explicitly says it merges inherited dialects from Proxx, Knoxx, and eta-mu. Many maps remain `{:closed false}` so dialect-specific fields can coexist during migration.

This is important evidence that the grammar is still being discovered and reconciled rather than already crystallized.

The current registry includes shapes for agents, sub-agents, actors, roles, capabilities, policies, actions, stores, triggers, schedules, generators, providers, models, MCP servers, runtime sources, and other surfaces. Several shapes still carry obvious historical-host or product vocabulary.

Disposition: Katamorph is a living semantic grammar under convergence, not a completed universal ontology.

## Revised architecture

The previous three-layer sketch is retained but corrected:

```text
Lisp authoring surface
  ergonomic construction / composition / experimentation
        |
        | produces or elaborates
        v
Katamorph
  shared grammar of valid concepts, shapes, identities, and relations
        |
        | target-specific partial interpretation
        v
host adapters / translators
  OpenCode, Claude, Codex, MCP, GitHub Actions, Sol, etc.
```

The host translation responsibility may historically be called Keryx and is currently implemented inside the repository named Muse, but this pass makes no package/name decision.

## The critical correction: interoperability, not parity

The desired property is not:

```text
Every target implements all Katamorph concepts.
```

It is:

```text
Every target declares the Katamorph fragment it understands.
Every accepted input is valid in that fragment.
Every exported semantic output is valid in a shared shape.
Unsupported meaning is rejected, preserved externally, or reported as loss.
```

A target may therefore implement a **supported fragment** rather than the whole language.

Examples:

- MCP may implement callable capability exposure but not a rich session lifecycle.
- GitHub Actions may implement trigger/action/workflow relations but not interactive Presence semantics.
- OpenCode may implement primary/sub-agent surfaces, tools, hooks, permissions, and sessions.
- Claude or Codex may expose a different subset and different lifecycle hooks.
- Sol may eventually realize a much larger Presence/Actor fragment natively.

None of these targets becomes the ontology.

## Category-theory hypothesis

The operator confirms category theory was the naming source. This pass therefore tests a category-theoretic reading but does not claim the current code already forms a rigorously specified category.

Let `K` denote the intended Katamorph semantic world.

At minimum, we can think provisionally of:

- objects: lawful semantic resource shapes / typed concepts or structured instances;
- morphisms: lawful declared relations, compositions, or transformations between semantic forms;
- laws: identity/composition and schema/relationship constraints that preserve validity.

The current implementation has strong evidence for the object/shape and relation side, but the morphism and composition laws are not yet formalized enough to claim a complete categorical semantics.

### Supported target fragment

For target `T`, let `K_T` be the fragment of Katamorph that target `T` actually understands.

Provisional model:

```text
K_T ⊆ K
```

where `K_T` should become a genuine subcategory/sub-language only when the supported concepts and relations are closed under the composition laws Katamorph eventually defines.

Then a target interpretation resembles:

```text
F_T : K_T -> T
```

Rather than pretending there is a total `K -> T` mapping.

This is stronger and more accurate than treating every unsupported construct as a compiler bug.

## Boundary and round-trip laws

The operator's interoperability requirement suggests the important laws live at boundaries.

For a supported Katamorph value `x`:

```text
decode_T(encode_T(x)) ≈ x
```

where `≈` means semantic equivalence, not byte equality or preservation of host presentation details.

For a target-originating value `y` that the adapter claims corresponds to a shared concept:

```text
normalize_T(y) ∈ K
```

and ideally more specifically:

```text
normalize_T(y) ∈ K_T
```

This means host-specific output becomes a valid shared semantic value before another system is asked to consume it.

The crucial architectural invariant becomes:

```text
target artifact -> adapter -> shared valid shape -> another adapter -> target artifact
```

not:

```text
target A artifact -> bespoke target B translation
```

The latter produces pairwise integration explosion and makes targets authorities over each other's vocabulary.

## Pairwise interoperability through shared shapes

Suppose targets `A` and `B` implement fragments `K_A` and `K_B`.

They need not support each other's complete feature sets.

Their direct semantic interoperability is bounded by the shared region:

```text
K_A ∩ K_B
```

or, once formal categorical structure is defined, by an appropriate common subcategory / shared signature.

A value produced by A can be consumed by B when its normalized Katamorph meaning belongs to a shape B accepts.

This is a much better success criterion than host parity:

```text
compatibility(A, B, x)
  := normalize_A(x) satisfies a Katamorph contract consumed by B
```

No A->B special ontology is required.

## Information loss is not always failure

A target may lawfully forget context that it does not need.

The important distinction is between:

1. **irrelevant context** — information outside the target's declared consumer contract;
2. **presentation loss** — target-specific names/layout/configuration that do not affect semantic identity;
3. **recoverable external context** — meaning retained in shared ledgers/resources but not materialized in the target;
4. **semantic loss** — information required by the declared contract which the target cannot preserve.

Only the fourth necessarily invalidates the claimed translation.

This suggests target adapters should declare something like:

```text
accepts     = concepts/contracts the target consumes
produces    = concepts/contracts it can emit back into the shared world
preserves   = semantic properties guaranteed across the boundary
forgets     = context intentionally omitted because it is outside the target contract
rejects     = required semantics with no lawful target representation
```

The exact vocabulary is not accepted here.

## Presence/Actor pressure as grammar discovery

The operator's description provides a deliberate development method:

```text
attempt Presence/Actor behavior in multiple spaces
      |
      v
find what cannot be expressed cleanly
      |
      v
identify whether the missing thing is
  - a new concept
  - a missing relation
  - an implementation concern
  - a target-only projection
      |
      v
reshape Katamorph only when evidence says the shared grammar is deficient
```

This is different from designing a universal schema in advance.

A concept earns a place in Katamorph by surviving pressure from multiple realizations and remaining meaningful outside any one host.

## Consequence for Muse and Keryx

The repository currently called Muse is better understood, for the translation portion of its history, as a laboratory and implementation site for a **family of target interpretations**, not as the authority defining Katamorph's meanings.

Keryx remains useful historical vocabulary for the act of carrying lawful meaning across a boundary.

A future design may separate:

```text
Katamorph
  defines valid shared meaning

[target interpretation package / Keryx responsibility]
  knows how a target consumes and emits selected Katamorph shapes

Presence runtime
  uses those shapes and adapters to realize Muse/Phase/etc. behavior
```

No rename or package split is accepted in this pass.

## Formal caution

Terms such as `subcategory`, `functor`, `natural transformation`, `catamorphism`, or `institution` should not be promoted merely because the metaphor fits.

To earn stronger category-theory terminology, the next research must identify:

- what the objects actually are;
- what the morphisms actually are;
- what composition means;
- what identities are;
- which laws Katamorph enforces or intends to enforce;
- what a target interpretation preserves;
- whether unsupported concepts are best modeled through subcategories, partial functors, forgetful functors, profunctors, institutions/signature morphisms, or a simpler typed-language account.

## Session Mycology lesson

Interoperability does not require universal vocabulary at every endpoint. A stable shared grammar can support heterogeneous systems when each system declares the fragment it consumes and produces, adapters normalize boundary values back into shared lawful shapes, and semantic loss is distinguished from context that a target never promised to preserve.

## Disposition

Orientation / operator-corroborated boundary clarification.

This pass strengthens:

- Katamorph as shared grammar / validity authority, not host translator;
- target implementations as partial consumers/producers of that grammar;
- interoperability through common valid shapes rather than feature parity;
- Presence/Actor implementation pressure as a method for evolving the shared grammar;
- target translation as bidirectional boundary interpretation, not merely artifact generation.

It does not establish a formal category-theoretic model or accept a new name for the translator project.

## Exact next collection step

Trace one concrete concept that exists in Katamorph and at least two target integrations — preferably capability/tool invocation or agent/model configuration — and record its Katamorph input contract, each target's accepted fragment, target-specific projection, normalized output contract, and round-trip/loss behavior to determine what the actual morphisms and preservation laws are before applying stronger category-theory terminology.
