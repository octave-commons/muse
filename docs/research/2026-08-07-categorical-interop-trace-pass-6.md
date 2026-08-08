<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Categorical Interoperability Trace — Pass 6

Date: 2026-08-07
Status: orientation / operator-corroboration / formalization target / non-decision
Research tier: decision-support preparation
Predecessor: `docs/research/2026-08-07-katamorph-partial-interoperability-pass-5.md`

## Question

Can one real cross-target path be described in category-theoretic terms strongly
enough that those terms become useful design constraints for humans and agents,
not merely metaphors?

The operator explicitly wants category theory to become a **design language the
system converges toward**, even where the current implementation is not yet the
right model. The purpose is to give humans and agents a shared formal grammar for
reasoning about context, composition, translation, and semantic preservation.

This pass therefore keeps two columns distinct:

1. **Observed implementation today** — what Katamorph and Muse actually do.
2. **Target formal model** — laws the architecture should satisfy if we want to
   legitimately reason in categorical terms.

No claim is made that the current repositories already implement the full formal
model.

## Operator direction

The operator clarified:

- Katamorph was intentionally named from category theory.
- Katamorph should describe valid concepts, valid data shapes, and relations.
- The repository currently named Muse was intended to implement translations of
  those shapes into and around OpenCode, Claude, Codex, MCP, GitHub Actions, and
  other runtimes.
- Presence/Actor implementation pressure across many host worlds should reshape
  Katamorph until its grammar crystallizes.
- Targets do not need universal feature parity.
- A target needs only to consume the valid shared shapes it claims to implement
  and emit outputs which become valid shared shapes consumable by other lawful
  consumers.
- Category theory is desired as an explicit architectural grammar because it
  provides a shared formal language for operator/agent understanding.

Epistemic status: direct operator testimony and current intended direction.

## Trace chosen

The concrete trace is the callable-tool + lifecycle-event fragment because it is
implemented in multiple host worlds today:

```text
source declaration
  -> Muse DSL normalization/linking
  -> host-neutral compiled adapter
  -> OpenCode boundary
  -> MCP boundary
  -> Claude Code boundary
```

The `receipt_river` tool supplies a real source declaration. Canonical lifecycle
events supply a second relation demonstrating that two hosts may share a semantic
concept while exposing structurally different host APIs.

This trace also exposes where Katamorph does **not yet** contain the canonical
semantic object we need.

---

## 1. Source declaration: `receipt_river`

Current Muse source:

```clojure
(deftool receipt-river
  {:id          :receipt/river
   :name        "receipt_river"
   :description "..."
   :args        [:map ...]
   :tags        #{:receipt :ledger :audit}}
  [params ctx]
  ...)
```

Observed semantic material:

- stable id: `:receipt/river`
- argument shape: Malli data
- implementation: CLJS handler
- presentation name: `receipt_river`
- tags: receipt/ledger/audit
- result: plain CLJS data

The compatibility `deftool` macro internally constructs a capability,
implementation, and exposure before linking them back into the legacy flat tool
shape.

This is already evidence that one source expression contains several categories
of concern which the architecture is trying to separate.

## 2. Compile step

`eta-mu.dsl.compile/compile-tool` turns a linked tool into:

```clojure
{::schema/kind :tool
 :name          ...
 :description   ...
 :args          ...
 :handler       ...
 :permissions   ...
 :source        ...
 :effects       ...}
```

### Observed preservation

Preserved:

- target-facing name
- description
- input schema
- executable handler
- tags as `:permissions`
- source location
- effects

### Observed loss

The stable semantic id `:receipt/river` is **not present in the compiled tool**.

This is the most important concrete defect found by this pass.

If `:receipt/river` is the shared identity of a semantic capability, then a
host-neutral compilation step that removes it cannot later prove that two host
artifacts are interpretations of the same semantic thing except by relying on a
presentation name or out-of-band knowledge.

Target formal law:

```text
semantic identity must survive interpretation
```

Host names may vary. Semantic identity may not silently become a host name.

## 3. OpenCode interpretation

The OpenCode boundary receives the compiled adapter as pure CLJC data.

For a tool it:

1. converts the Malli-ish argument shape to a Zod raw shape;
2. decodes JS arguments to CLJS data;
3. decodes a rich OpenCode context containing:
   - session id
   - message id
   - agent
   - directory
   - worktree
4. invokes the shared handler;
5. converts the result to either:
   - a string;
   - an OpenCode structured `{output,title?,metadata?}` result;
   - JSON text in the output slot.

OpenCode also realizes canonical lifecycle hooks directly through its plugin hook
object.

Observed OpenCode-only context:

```text
session identity
message identity
agent identity
worktree/directory
rich lifecycle interception surface
```

This context is useful to the realization but is not automatically part of the
callable capability's portable input contract.

## 4. MCP interpretation

The MCP boundary consumes the **same compiled tool descriptor**.

It:

1. converts the same argument shape into Zod;
2. registers the tool with `McpServer/registerTool`;
3. decodes arguments to CLJS data;
4. synthesizes only `directory` and `worktree` from process cwd;
5. invokes the same handler;
6. encodes every result as MCP text content, JSON-stringifying non-string data.

The boundary explicitly states that hooks have no MCP analogue and are ignored.

This is not evidence that MCP is an incomplete OpenCode. It is evidence that the
MCP adapter realizes a smaller supported semantic fragment.

## 5. Claude Code interpretation

Claude Code currently has two planes:

1. MCP tools, realized like the generic MCP target;
2. lifecycle hooks, realized by one-shot commands whose stdin/stdout data is
   translated into the same canonical hook handler shape used by OpenCode.

For example the canonical event:

```text
:tool/requested
```

maps to:

```text
OpenCode -> :tool.execute.before
Claude   -> "PreToolUse"
```

The handler receives a canonical `[input output]` pair even though Claude emits
one flat JSON payload and OpenCode calls a plugin hook with two host objects.

This is strong direct evidence for an interpretation layer:

```text
one semantic event relation
  -> two structurally different host realizations
```

### Observed semantic asymmetry

The shared effect algebra includes:

```text
nil
reject
patch
```

OpenCode can realize `patch` by mutating the host output object.

Claude's `PreToolUse` hook cannot rewrite arguments in the same way, so the
current boundary treats `patch` as allow.

That is a real example of semantic loss.

Under the desired architecture it should not be silent. The adapter should
declare that it preserves `reject` but not `patch` for this host relation.

---

## 6. Katamorph gap exposed by the trace

Current Katamorph `CapabilityContract` is inherited from Knoxx and currently
contains approximately:

```clojure
{:cap/id ...
 :cap/tools ...
 :cap/user-surfaces ...}
```

It does not yet define the callable semantic operation seen in Muse PR #4:

```text
identity
input
output
effects
errors
```

Current Katamorph `ActionContract` contains executable/action metadata such as:

```text
:action/id
:action/kind
:action/handler
:action/fn
:action/with
:action/scope
```

but it also does not provide an explicit domain/codomain contract for callable
composition.

Current Katamorph `TriggerContract` provides event/action/agent/actor relations,
and the manifest grammar supplies qualified identities and typed facets, but the
callable morphism needed by this trace remains split across inherited concepts.

**Finding:** the desired categorical model cannot be made honest merely by
renaming current records. The grammar needs a first-class semantic operation or
capability relation with explicit domain/codomain/effects if callable operations
are intended to compose lawfully.

---

# Formalization target

The following is a design target, not a claim about current implementation.

## 7. Separate schema/category from instances

Applied category theory gives a useful distinction already used in functorial
data models:

```text
schema = category
instance = functor from schema into a category of values
```

For Katamorph, provisionally call the semantic schema category `K`.

A first target model is:

```text
K
  objects    = semantic sorts / contract shapes
  morphisms  = lawful typed relations or transformations
  identities = do-nothing semantic relations
  composition = lawful chaining of relations/operations
```

A project/configuration/world is then not `K` itself. It is an **instance/model
of K**:

```text
I : K -> Data
```

where `Data` might initially be Set-like collections of EDN values and references,
with richer categories introduced only when needed.

This separation prevents concrete resources from being confused with the grammar
that validates them.

## 8. Katamorph manifest as an instance presentation

Current manifest rules already point toward this distinction:

```text
:K/id          registers a resource of kind K
namespace + id gives stable qualified identity
references live under the owning kind namespace
composite entries expose multiple facets
interpreters read only their own facets
```

Today those rules are implemented as parsing conventions and schema validation.

Target direction:

- every reference has a declared source object and target object;
- composition of references is typed;
- invalid paths are rejected;
- identity and composition laws are testable;
- resource files become presentations of instances/models over the shared schema.

## 9. Callable capability as a morphism

A pure callable operation with input contract `A` and output contract `B` can be
read as:

```text
f : A -> B
```

Real agent operations have effects, rejection, failure, evidence, asynchronous
work, and possibly streaming. Therefore the more realistic target is not a plain
arrow arrow.

One candidate:

```text
f : A -> M B
```

where `M` captures the effect/result context, making capabilities Kleisli-like
arrows.

Another candidate is an algebraic-effects presentation where the semantic arrow
produces an effect plan interpreted by the environment.

Do not choose between these yet. The important requirement is that domain,
codomain, and effect obligations become explicit enough that composition can be
checked.

## 10. Supported target fragment as a subcategory

For target `T`, define a supported semantic fragment `S_T` with an inclusion:

```text
i_T : S_T ↪ K
```

Only call `S_T` a subcategory when it is actually closed under the identities and
compositions Katamorph defines.

The target interpretation is then:

```text
J_T : S_T -> H_T
```

where `H_T` is an appropriate category of host-native semantic realizations or
artifacts.

This avoids pretending that every target defines a total functor `K -> H_T`.

### Partial-functor alternative

Category theory has explicit machinery for partial maps/functors. If target
support cannot be represented cleanly by a closed supported subcategory, a
partial functor or restriction-category formulation may be more honest.

A particularly explicit form is a span:

```text
K <- S_T -> H_T
```

The left leg says which Katamorph fragment is supported; the right leg says how
that fragment is realized by the host.

This is presently a better conceptual default than pretending unsupported
concepts are failed total translations.

## 11. Functor laws become architecture tests

If `J_T` is a functor, two equations stop being vocabulary and become tests.

### Identity preservation

```text
J_T(id_A) = id_{J_T(A)}
```

A semantic no-op / identity relation must not acquire host-visible behavior.

### Composition preservation

```text
J_T(g ∘ f) = J_T(g) ∘ J_T(f)
```

If two semantic operations compose lawfully in Katamorph and the target claims
to implement both and their composition, compiling the composed form should be
semantically equivalent to composing the compiled forms.

This is an extremely useful agent-facing design rule:

> A target adapter is wrong when it changes the meaning of composition.

## 12. Identity preservation in the concrete trace

The current `compile-tool` path fails an important precursor to functoriality:
semantic identity is dropped.

Desired compiled adapter shape should retain something like:

```clojure
{:semantic/id :receipt/river
 :exposure/id ...
 :target/name "receipt_river"
 ...}
```

Then:

```text
OpenCode tool name
MCP tool name
Claude MCP tool name
```

are presentations of the same semantic identity rather than the identity itself.

## 13. Encode/decode as a retraction law

For values belonging to the supported Katamorph fragment, let:

```text
E_T = encode/project into host representation
N_T = normalize/decode host representation back to shared semantic value
```

A strong interoperability law is:

```text
N_T ∘ E_T ≅ Id
```

on the supported semantic domain.

The `≅` may eventually be a natural isomorphism, or a weaker observational
semantic equivalence if host presentation details make strict equality
unreasonable.

The reverse need not hold:

```text
E_T ∘ N_T ≠ Id
```

because a host object may contain extra host-only context. Re-encoding may produce
a normalized host representation rather than the identical object.

This asymmetry matches the operator's interoperability requirement precisely.

## 14. Concrete round-trip status today

### Arguments

OpenCode, MCP, and Claude MCP all decode host arguments into CLJS data before
calling the shared handler.

The source Malli argument schema is translated to host Zod schema.

This provides the beginnings of a round-trip boundary law, but no persisted
semantic identity accompanies the value today.

### Results

The shared handler returns CLJS data.

OpenCode may retain structured output.

MCP/Claude MCP serialize non-string values into JSON **inside text content**.

There is currently no canonical target-independent result envelope proving that a
host output has been normalized back into a Katamorph-valid output contract.

Therefore the desired law:

```text
host result -> normalize -> Katamorph-valid B
```

is not yet generally implemented.

### Permissions/effects

`compile-tool` retains permissions/effects metadata, but current OpenCode and MCP
tool rendering paths do not use those fields when constructing the tool surface.

Whether that is harmless context omission or semantic loss depends on the laws
assigned to those fields. The formal model forces that question to be answered.

## 15. Shared target overlap

For targets A and B with supported inclusions:

```text
S_A -> K <- S_B
```

the meaningful common fragment is better modeled categorically by an appropriate
pullback/common subcategory than by a loose set intersection.

Provisional notation:

```text
S_AB = S_A ×_K S_B
```

An A-produced semantic value is directly consumable by B when its normalized
meaning lies in an instance of `S_AB` accepted by B's consumer contract.

This expresses the operator's rule:

```text
producer does not need consumer's whole context
consumer does not need producer's whole context
both need the shared law governing the exchanged value
```

## 16. Functorial data migration is a relevant precedent

Spivak/Wisnesky's functorial data model treats database schemas as categories and
instances as set-valued functors. A functor between schemas induces canonical
data-migration operations.

Katamorph is not a relational database schema language, but this distinction is
highly relevant because it separates:

```text
shared schema/category
concrete instance/model
mapping between schemas
migration/restriction of instance data
```

In particular, when `i_T : S_T -> K` is an inclusion, restricting a full semantic
instance to the fragment understood by T resembles precomposition/data
restriction rather than lossy ad-hoc field deletion.

This deserves a dedicated follow-up before importing the full Sigma/Pi/Delta
data-migration vocabulary.

## 17. Institutions may be relevant later

Institution theory abstracts a logical/specification system into:

```text
signatures
sentences
models
satisfaction
```

with a law ensuring satisfaction is invariant under signature translation.

That is potentially relevant to Katamorph because the operator wants one formal
grammar in which different systems can describe and compare their understanding
without sharing one implementation language.

However Katamorph does not yet expose enough explicit sentence/model/satisfaction
structure to call it an institution. Preserve this as a research lead only.

## 18. Natural transformations: likely useful, but not where first assumed

A natural transformation should not be used as a fancy synonym for arbitrary
host conversion.

A stronger likely use is comparison/evolution of two functorial interpretations
of the **same supported semantic fragment**.

For example:

```text
J_T_v1, J_T_v2 : S_T -> H_T
```

A natural transformation:

```text
η : J_T_v1 => J_T_v2
```

would express a coherent migration/change of host realization across every object
and morphism in the supported fragment.

Likewise, two implementations of a shared semantic interface might admit a
natural comparison only if their component mappings commute with all semantic
relations.

This gives `natural transformation` a precise future job rather than a metaphorical
one.

## 19. Category-theory vocabulary ladder for agents

The architecture can intentionally earn terminology in stages.

### Level 0 — typed graph / grammar

Use now:

```text
kind
shape
identity
reference
source/target
path
validation
```

### Level 1 — category presentation

Earn when:

```text
references/morphisms are explicitly typed
identity morphisms are defined
composition is defined
associativity and unit laws are tested
```

Then use:

```text
category
object
morphism
identity
composition
```

### Level 2 — target functor

Earn when a target declares a composition-closed supported fragment and tests:

```text
identity preservation
composition preservation
```

Then use:

```text
subcategory / supported category
functor
```

### Level 3 — interoperability laws

Earn when target boundaries normalize outputs back into semantic instances and
round-trip laws are tested.

Then use:

```text
functorial interpretation
retraction / section
semantic equivalence
pullback/common subcategory
```

### Level 4 — higher comparison

Earn when adapter versions/interpretations are coherently comparable.

Then use:

```text
natural transformation
adjunction (only if universal-property laws exist)
Kan extension/data migration (only if actually implemented)
institution (only if signatures/models/satisfaction are explicit)
```

This ladder lets agents use category theory aspirationally **without lying about
which laws currently exist**.

## 20. Proposed architecture contract — not accepted

A concise target contract for Katamorph + target interpreters:

```text
KATAMORPH
  defines semantic objects, morphisms, identities, composition, and laws

INSTANCE
  is a lawful population/model of Katamorph concepts and relations

TARGET T
  declares a supported semantic category S_T and inclusion S_T -> K

INTERPRETER T
  provides a functor J_T from S_T into host realizations

BOUNDARY T
  provides encode/normalize operations whose semantic round trip is identity
  on the supported domain, modulo explicitly declared equivalence

LOSS
  is any required semantic property that cannot be preserved by J_T
  and must be reported rather than silently discarded

INTEROPERABILITY
  occurs through the common semantic fragment of two target supports,
  never by making one host's vocabulary canonical
```

## 21. Immediate implications for current code

The trace suggests concrete future constraints, without authorizing implementation
in this research pass:

1. Preserve semantic ids through compile/adapter layers.
2. Separate semantic capability identity from host exposure name.
3. Give callable semantics explicit input/output/effect contracts in Katamorph.
4. Define normalized result envelopes rather than relying on host text encoding.
5. Make adapter support explicit by semantic concept/relation, not target-wide
   boolean compatibility.
6. Record which semantic laws an adapter preserves, forgets, or cannot implement.
7. Treat canonical lifecycle events as Katamorph-level relations only after their
   source/target and composition semantics are made explicit.
8. Test composition preservation, not only fixture rendering.

## 22. Relation to Presence / Actor work

The categorical grammar should not be designed in isolation from the Presence
Actor Model.

The development loop remains:

```text
attempt Presence/Actor behavior across host categories
      -> observe required semantic invariants
      -> determine whether they are objects, morphisms, laws, effects, or projections
      -> promote only host-independent invariants into Katamorph
      -> implement target functors over supported fragments
      -> test interoperability through normalized shared values
```

This gives agents a useful question whenever a new concept appears:

> Is this a new object, a morphism, a law on composition, an instance-level value,
> an implementation of a morphism, or merely one target's presentation?

That question is likely more valuable than prematurely deciding package names.

## Session Mycology lesson

Category theory becomes useful architectural language only when each noun earns a
law. `Functor` should mean identity and composition preservation; `subcategory`
should mean closure; `natural transformation` should mean coherent commuting
components. Using that ladder aspirationally is still valuable because every
missing law becomes a precise research or implementation question rather than a
vague disagreement between human and agent mental models.

## Disposition

Orientation / formal convergence target.

This pass establishes no accepted category-theory ontology, but it strengthens a
specific direction:

- Katamorph should evolve from a typed resource grammar toward an explicit
  categorical semantic presentation;
- host support is best modeled through declared supported fragments rather than
  universal parity;
- target translators should aim to become functorial interpretations;
- output normalization and semantic identity preservation are prerequisites for
  interoperability;
- current Muse adapters provide enough cross-target evidence to begin writing
  concrete categorical laws;
- current Katamorph callable capability/action shapes are not yet sufficient to
  state those laws cleanly.

## Exact next collection step

Trace Katamorph's `TriggerContract` plus Muse's canonical lifecycle-event registry
through the concrete `:tool/requested` OpenCode and Claude realizations, then
enumerate the exact source object, target object, identity, composition, and
effect semantics required for that event relation to become a real Katamorph
morphism rather than only a shared event-name mapping.
