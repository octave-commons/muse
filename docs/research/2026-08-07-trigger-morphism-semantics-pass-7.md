<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Trigger / Lifecycle Morphism Semantics — Pass 7

Date: 2026-08-07
Status: orientation / formalization target / non-decision
Research tier: decision-support preparation
Predecessor: `docs/research/2026-08-07-categorical-interop-trace-pass-6.md`

## Question

What would Katamorph's `TriggerContract` and Muse's canonical
`:tool/requested` lifecycle relation have to mean for us to legitimately talk
about objects, morphisms, identities, composition, effects, and target functors?

The operator wants category theory to be an intentional architectural grammar
that humans and agents can reason in, even where today's implementation has not
yet earned the stronger terms. This pass therefore separates observed behavior
from target semantics and treats missing laws as design information.

## Sources inspected

Observed repository seams:

- `open-hax/katamorph/src/cljs/katamorph/schema.cljs`
- `open-hax/katamorph/src/cljs/katamorph/manifest.cljs`
- `open-hax/katamorph/examples/hello-world.edn`
- `open-hax/katamorph/examples/cljs/katamorph/examples/runner.cljs`
- `open-hax/katamorph/README.md`
- `octave-commons/muse/src/cljs/eta_mu/dsl.cljc`
- `octave-commons/muse/src/cljs/eta_mu/dsl/schema.cljc`
- `octave-commons/muse/src/cljs/eta_mu/dsl/events.cljc`
- `octave-commons/muse/src/cljs/eta_mu/dsl/compile.cljc`
- `octave-commons/muse/src/cljs/eta_mu/boundaries/opencode.cljs`
- `octave-commons/muse/src/cljs/eta_mu/boundaries/claude.cljs`
- `octave-commons/muse/src/cljs/eta_mu/boundaries/mcp.cljs`

Formal comparison leads used only as orientation:

- functorial data modeling: schema as category, instance as Set-valued functor;
- restriction categories / partial maps for partially defined behavior;
- Kleisli categories as a standard categorical account of effectful composition.

No external formalism is accepted as Katamorph law by this pass.

---

# Observed implementation

## 1. What a Katamorph trigger currently is

Current `TriggerContract` is a data shape approximately of the form:

```clojure
{:contract/kind     :trigger
 :contract/id       ...
 :trigger/kind      :event
 :trigger/events    [...]
 :trigger/action    ...
 :trigger/agent     ...
 :trigger/actor     ...
 :trigger/emitter   ...
 :trigger/listener  ...
 :trigger/condition ...
 :trigger/with      ...}
```

The manifest grammar gives a trigger a stable qualified identity when
`:trigger/id` is present. References such as `:trigger/action` live under the
owning trigger namespace.

This is a **declaration of a reactive relation**, not an event occurrence and not
currently an executable state transition by itself.

## 2. The checked-in hello-world trigger proves that distinction

Katamorph's example declares:

```clojure
{:action/id :greet
 :action/kind :demo/greet
 :action/with {:greeting "Hello"}}

{:trigger/id :greeting-requested
 :trigger/events [:hello/requested]
 :trigger/action :hello/greet
 :trigger/with {:name "Katamorph"}}
```

The manifest expands the action and trigger to stable qualified identities and
validates their shapes.

However the example runner does not implement a general event-dispatch loop.
It locates the trigger resource, extracts `:trigger/action` and `:trigger/with`,
and directly asks the action interpreter to execute the referenced action.

Observed consequence:

```text
Trigger grammar             implemented
Trigger identity/reference  implemented
Trigger validation          implemented
Generic event occurrence -> trigger matching -> action execution
                            not established by this example
```

Therefore current Katamorph supports the static declaration more strongly than
the dynamic event semantics.

## 3. Muse has a second lifecycle vocabulary

Muse's canonical event registry currently defines target-neutral names such as:

```text
:session/open
:session/closed
:tool/requested
:tool/succeeded
:permission/requested
:permission/resolved
:context/compacting
:context/compacted
:file/changed
```

and maps them to host names.

For the event studied here:

```text
:tool/requested
  OpenCode -> :tool.execute.before
  Claude   -> "PreToolUse"
```

The event registry calls these canonical target-agnostic events.

Current `eta-mu.dsl.schema/hook`, however, still says that `:event` vocabulary is
target-specific. This is documentation/schema drift: runtime code is already
trying to establish a canonical vocabulary that the schema documentation has
not caught up with.

## 4. A Muse hook is an interceptor descriptor

Current `defhook` emits plain data with:

```clojure
{:ημ/kind :hook
 :id       ...
 :event    ...
 :priority ...
 :handler  fn}
```

The handler returns the current effect algebra:

```text
nil
{:effect :reject :message ...}
{:effect :patch :output {...}}
```

A hook therefore currently combines:

- registration identity;
- event-point selection;
- priority;
- executable implementation;
- decision/transformation effect.

It is not yet represented as a Katamorph Trigger resource.

## 5. Current hook composition is not categorical function composition

`compile-hooks-by-event` groups hooks by event and sorts by descending priority.

`compose-event-handler` then invokes them in order. Each hook receives the same
`input` and `ctx`. The first non-`nil` result wins. Both `:reject` and `:patch`
terminate the chain.

The effective result operator is approximately:

```text
a ⊕ b = if a is non-nil then a else b
```

with:

```text
identity element = nil
```

This left-biased choice operator is associative over result values and gives the
handler set a useful ordered-choice/fold interpretation, but it is **not** the
same thing as composition of typed transformations.

In particular:

- a patch produced by hook A is not threaded into hook B;
- B does not see a transformed request from A;
- the codomain of A is not the domain consumed by B;
- priority determines which verdict wins rather than defining a dataflow path.

Calling this current mechanism `morphism composition` would therefore be
misleading.

## 6. OpenCode realizes the full current effect algebra

For OpenCode, the boundary:

1. maps the canonical event to its OpenCode event name;
2. decodes the host input/output objects into CLJS data;
3. calls the composed canonical hook handler;
4. realizes the result as:
   - `nil`: allow/no change;
   - `:reject`: reject the host promise with an error;
   - `:patch`: mutate the OpenCode output object with the returned patch.

For `:tool/requested`, this gives the canonical hook enough target power to
prevent the call or alter the host-side request/output object before execution.

## 7. Claude realizes only a strict subset

Claude's `PreToolUse` event arrives as one flat JSON payload. The boundary
normalizes it to a canonical pair approximately like:

```clojure
[{:tool       tool-name
  :session/id session-id
  :directory  cwd
  :worktree   cwd}
 {:args tool-input}]
```

and runs the same compiled hook chain used by OpenCode.

Claude realization then maps:

```text
:reject -> continue=false / deny
nil     -> continue=true
:patch  -> continue=true
```

The source code explicitly says Claude's `PreToolUse` hook cannot rewrite the
call's arguments in the same way, so patch currently degrades to allow.

This is a concrete semantic-loss example, not merely a presentation difference.

If a target interpretation claims to preserve a patching interceptor, Claude's
current implementation does not satisfy that claim.

## 8. MCP does not implement this lifecycle fragment

The generic MCP boundary explicitly has no host lifecycle hook analogue and
ignores hooks.

This is not a defect if MCP's declared supported semantic fragment excludes this
lifecycle relation.

It is strong evidence for the operator's interoperability rule:

```text
not every target needs every concept
```

---

# What is the morphism?

## 9. First rejected model: `:tool/requested` itself is the hook morphism

A naive reading would say:

```text
:tool/requested = morphism
```

but this collapses several different things:

- the lifecycle stage/event type;
- one occurrence of that event;
- the proposed tool invocation;
- a hook observing/intercepting it;
- the disposition returned by the hook;
- the eventual host execution transition.

These do not have the same source or target types and should not share one
formal role.

## 10. Better target model: lifecycle transition plus interceptor

A cleaner initial model distinguishes the proposed request from the interceptor.

Let `R` be the semantic object representing a pending tool call, with a portable
core such as:

```text
R = ToolRequest
    semantic capability identity
    validated arguments
    invoking participant/actor identity when relevant
    portable causal/evidence identity when relevant
    optional context facets
```

Target-only session/worktree/message metadata can remain in host-specific or
optional context facets rather than defining the identity of `R`.

Then a tool-request policy/interceptor is not merely an event label. It is an
effectful endomorphism over pending requests:

```text
h : R -> M R
```

where `M` represents the effects needed to evaluate and disposition the request.

This is the first formal shape in this pass that gives all three current hook
outcomes coherent jobs.

## 11. Reinterpret the current effect algebra

Instead of making `nil`, `patch`, and `reject` three unrelated effect variants,
the target model can treat them as:

```text
nil
  = return the same request
  = η_R(r)
  = identity/allow

patch
  = return a transformed request r'
  = lawful endomorphism when r' still satisfies R

reject
  = terminate in the rejection/error branch of M
```

Then `patch` is no longer a magic host mutation effect. It is ordinary semantic
transformation of a request value.

`reject` is the effectful branch that prevents a valid downstream request from
being produced.

Async evaluation, evidence emission, audit receipts, or other agent-system
obligations can also live in `M` if Katamorph later gives them explicit laws.

No exact monad stack is selected here.

## 12. Desired composition

If hooks are interpreted as Kleisli-style arrows:

```text
h1 : R -> M R
h2 : R -> M R
```

then their desired sequential composition is:

```text
h2 ⋆ h1 : R -> M R
```

with the intuitive behavior:

```text
allow unchanged -> next hook sees original request
patch            -> next hook sees patched request
reject           -> short-circuit; next hook does not run
```

This is categorically and operationally different from today's first-verdict
fold.

The identity interceptor becomes:

```text
id_R^M = pure / return unchanged request
```

and associativity is inherited from the effect composition law rather than from
priority selection.

### Important non-decision

This pass does **not** say the runtime must change to this model.

It says that if the project wants `hook/interceptor` to be discussed as a
composable morphism, state-threading effectful composition is a much better fit
than the current first-non-nil choice semantics.

The existing priority fold may remain useful as a separate algebra.

## 13. The current fold still has a formal job

Today's hook-selection operator is approximately a left-biased choice monoid:

```text
nil ⊕ x = x
x   ⊕ y = x, when x is decisive/non-nil
```

At the handler level this can be lifted pointwise over the shared request input.

That means current priority behavior can be named precisely without calling it
categorical composition.

Possible role:

```text
ordered policy alternative / first-decisive choice
```

This may be an intentional policy-combination algebra orthogonal to process
composition.

The architecture can therefore have both:

```text
composition  = thread semantic transformations/effects
choice       = select among alternative policy verdicts
```

Conflating the two is the current conceptual hazard.

---

# Two categorical layers

## 14. Katamorph probably should not force static resources and runtime processes into one category

The trace exposes two different structures.

### Static semantic schema

Call it provisionally `K_schema`.

Its job is close to current Katamorph's strongest implementation:

```text
objects
  resource/concept shapes or semantic sorts

morphisms
  typed references/relations between those concepts

identities
  stable qualified semantic identities / identity paths

composition
  lawful typed reference paths

instances
  concrete namespace resource populations
```

This is the layer most directly analogous to functorial data modeling where a
schema is category-like and an instance interprets it into data.

### Dynamic process semantics

Call it provisionally `K_process`.

Its job is behavior:

```text
objects
  semantic lifecycle states/value types

morphisms
  actions, transitions, message deliveries, request transformations

composition
  execution/dataflow/effectful sequencing

identity
  no-op/return transitions

effects
  rejection, asynchronous work, evidence, observation, etc.
```

Katamorph declarations can then receive semantics in this process layer rather
than pretending that a manifest reference and an executable transition are the
same kind of arrow.

## 15. Interpretation between those layers

A Trigger resource belongs primarily to the static declaration layer.

For example:

```text
trigger resource
  listens to EventType E
  references Action A
  carries condition/config C
```

A portable semantic interpreter can map that declaration into dynamic behavior:

```text
⟦ trigger ⟧ : EventOccurrence(E) -> M ActionResult(A)
```

or, for an interceptor-style trigger:

```text
⟦ hook ⟧ : ToolRequest -> M ToolRequest
```

The exact dynamic arrow depends on the trigger kind.

This means `TriggerContract` does not itself have to be the runtime morphism. It
can be the **data that denotes a morphism** under a semantic interpretation.

That is a strong fit with the operator's original Katamorph intent:

```text
Katamorph describes what shapes are valid.
Interpreters assign executable meaning.
Target adapters realize that meaning in host spaces.
```

## 16. A three-stage categorical picture

Provisional design target:

```text
K_schema
  shared declaration grammar
      |
      | semantic interpretation ⟦-⟧
      v
K_process
  portable behavioral semantics
      |
      | target functor J_T over supported fragment
      v
H_T
  host-native realization category
```

This is stronger than making Muse translate arbitrary EDN directly to target
syntax because it creates an intermediate behavioral meaning that target
projections can be judged against.

It also gives agents three different debugging questions:

```text
1. Is the declaration valid in K_schema?
2. Does its semantic interpretation denote the intended process in K_process?
3. Does target T preserve that process under J_T?
```

Those are distinct failure modes.

---

# `:tool/requested` as a process relation

## 17. Proposed source and target objects

If the event name is retained as the name of the interception point, the most
useful dynamic object is:

```text
ToolRequest<A,C>
```

where:

- `A` is the capability argument contract;
- `C` is the portable context contract/facet.

A policy/interceptor arrow is:

```text
h : ToolRequest<A,C> -> M ToolRequest<A,C>
```

The capability execution itself is a different arrow:

```text
exec : ToolRequest<A,C> -> M ToolResult<B>
```

where `B` is the capability output contract.

This separates:

```text
request formation
request interception
request execution
result observation
```

instead of assigning all of those meanings to `:tool/requested`.

## 18. If event occurrences are themselves morphisms

An alternative process-category presentation is:

```text
request : ToolIntent -> ToolRequest
execute : ToolRequest -> M ToolResult
succeed : ToolResult -> CompletedToolCall
```

Then the string/keyword `:tool/requested` names or observes the `request`
transition boundary.

Hooks that rewrite or veto the pending `execute` arrow may eventually be better
modeled as higher cells/rewrite structure rather than ordinary 1-morphisms.

That suggests a future 2-categorical or double-category research direction:

```text
1-cells  = proposed/executed processes
2-cells  = policy transformations, approvals, refinements, evidence comparisons
```

This pass does not promote that model. The simpler Kleisli endomorphism model is
sufficient to expose today's laws and losses.

## 19. Exact identity semantics

The word `identity` has two separate current candidates which must not be
confused.

### Current choice identity

In today's hook fold:

```text
nil
```

means "no verdict; ask the next hook" and is the identity element for the
left-biased choice operator.

### Desired process identity

In effectful request composition:

```text
η_R : R -> M R
```

means "preserve this request unchanged."

These happen to have similar operational intuition today, but they are identities
for different algebraic structures.

A formal Katamorph vocabulary should distinguish them.

## 20. Exact composition semantics

Target composition for request interceptors should answer:

```text
what request does the next interceptor see?
what evidence/effects are accumulated?
what happens on rejection?
what equivalence counts as preserving meaning?
```

One initial law set:

```text
identity:
  id ⋆ h = h = h ⋆ id

associativity:
  h3 ⋆ (h2 ⋆ h1) = (h3 ⋆ h2) ⋆ h1

validation closure:
  every successful patch still inhabits ToolRequest<A,C>

rejection absorption:
  once the effect context yields rejection, no downstream executable request is produced
```

If evidence is emitted, an additional law must say whether evidence is preserved
across rejection and how it composes.

## 21. OpenCode support under the target model

OpenCode appears capable of realizing at least this fragment:

```text
identity/allow
request transformation/patch
rejection
async hook evaluation
```

Therefore OpenCode could plausibly implement a richer request-interceptor
subcategory once semantic request values and effects are explicit.

Its current adapter does not yet prove functoriality because:

- hooks do not thread transformed requests;
- semantic ids are not preserved through all compiled descriptors;
- domain/codomain schemas are not explicit at the hook level;
- composition laws are not tested.

## 22. Claude support under the target model

Claude's current `PreToolUse` realization supports:

```text
identity/allow
rejection
async hook evaluation through the one-shot command bridge
```

but not semantic request transformation.

Therefore its honest supported fragment is smaller.

If `S_Claude` excludes patching request morphisms, the adapter may still be a
lawful interpretation over the fragment it claims.

If the compiler feeds a patching interceptor into Claude and silently maps patch
to allow, the target interpretation is not preserving the morphism's semantics.

Desired outcomes are therefore one of:

```text
compile/assembly rejects unsupported patch semantics
profile selection excludes the patching relation
adapter declares explicit semantic loss and caller elects a weaker contract
host gains a genuine way to realize the transformation
```

Silently allowing is not compatible with the desired functorial contract.

## 23. Common OpenCode/Claude fragment

For this relation, the common semantic fragment is approximately:

```text
allow unchanged
reject with reason
```

OpenCode additionally supports patch/transform.

That gives a concrete example of a common subcategory/support pullback once the
process laws are formalized:

```text
S_common -> S_OpenCode
    |
    v
S_Claude
```

The shared fragment is defined by preserved semantics, not by matching host API
names.

## 24. MCP support

MCP has no lifecycle-hook realization.

Therefore this process relation is outside its supported lifecycle fragment.

MCP can still interoperate with systems that use `:tool/requested` internally
because the **resulting capability values** can cross the common callable-data
fragment without MCP participating in the interception process.

This exactly demonstrates the operator's requirement that a consumer need only
understand the shared concept it consumes, not every process that produced it.

---

# Consequences for TriggerContract

## 25. TriggerContract currently mixes several future semantic roles

Current fields potentially refer to distinct structures:

```text
:trigger/events
  event type selection / observation

:trigger/action
  executable semantic relation reference

:trigger/condition
  predicate / guard

:trigger/with
  parameterization

:trigger/agent / :trigger/actor
  participant relation

:trigger/emitter / :trigger/listener
  communication topology
```

This is useful exploratory grammar, but the categorical model asks whether these
are:

- object references;
- morphism references;
- predicates/subobjects;
- parameters;
- source/target participant annotations;
- wiring/topology declarations.

The answer may not be the same for every field.

## 26. Trigger as denotation rather than arrow

A promising target distinction is:

```text
TriggerDefinition
  static declaration in K_schema

TriggerSemantics
  portable interpretation into K_process

TargetTriggerRealization
  J_T(TriggerSemantics) in H_T
```

That avoids making Katamorph's raw EDN maps carry more formal meaning than they
currently earn.

It also preserves the original architectural intent: the data grammar can stay
portable while interpreters become progressively more formal.

## 27. Event schemas are currently missing

Muse's canonical event registry gives names but not explicit typed payload
contracts.

For `:tool/requested`, the Claude bridge implies a provisional shape, but the
canonical domain is not declared as a Katamorph schema with required/optional
fields and semantic identities.

Without that, we cannot yet state:

```text
h : ToolRequest<A,C> -> M ToolRequest<A,C>
```

as a checked Katamorph morphism.

A first-class event/request shape is therefore a prerequisite for formal
composition.

## 28. Effect semantics are also missing from Katamorph

Muse's current hook algebra is runtime code/documentation, not a Katamorph
contract.

The shared grammar does not yet say:

- what rejection means;
- whether patch is a total transformation or partial one;
- whether evidence must be preserved;
- whether async completion is observable semantic behavior;
- whether policy evaluation is pure or effectful;
- what constitutes semantic equivalence of two request transformations.

Those are exactly the laws needed before an agent should be told that this is a
Kleisli category rather than merely "Kleisli-like."

---

# Agent-facing formal grammar

## 29. Vocabulary that is safe now

For this subsystem an agent can safely reason with:

```text
static declaration
stable identity
typed reference
event type
request value
interceptor
ordered choice
host realization
semantic loss
supported fragment
```

## 30. Vocabulary used as explicit convergence target

The following terms are useful if every agent is told the associated proof
obligation:

```text
K_schema / schema category
  obligation: typed references, identity paths, associative composition

instance functor
  obligation: declarations preserve the schema's typed relations in data

K_process / process category
  obligation: explicit state/value objects and composable transitions

Kleisli morphism
  obligation: a defined effect context M with lawful identity/composition

subcategory / supported fragment
  obligation: closure under identities and compositions being claimed

target functor
  obligation: preserve identity and composition

semantic retraction
  obligation: normalize(project(x)) is equivalent to x on supported meaning
```

This is the desired human/agent contract: category-theory nouns are shorthand
for concrete laws, not decoration.

## 31. A compact target picture

```text
        static meaning

      K_schema
          |
          | ⟦-⟧ portable semantics
          v
      K_process
       /      \
      /        \
 S_OpenCode   S_Claude
     |           |
 J_OC|           |J_CC
     v           v
 OpenCode      Claude
```

For `:tool/requested`:

```text
R = ToolRequest<A,C>

OpenCode supported interceptor:
  R -> M R
  allow / transform / reject

Claude supported interceptor:
  R -> M' R
  allow / reject

M' is semantically weaker with respect to request transformation.
```

MCP has no arrow for this lifecycle relation and need not pretend otherwise.

---

# Findings

## Finding A — the current event name is not enough to be a morphism

`:tool/requested` currently identifies a lifecycle interception point. It does
not specify a checked source object, target object, composition law, or effect
law.

## Finding B — the current hook fold is choice, not composition

Priority + first non-`nil` is a left-biased choice algebra. It should not be
mistaken for categorical composition.

## Finding C — request interceptors have a plausible Kleisli shape

A useful target is:

```text
h : ToolRequest -> M ToolRequest
```

where allow is identity, patch is semantic request transformation, and reject is
an effect branch.

This naturally supports sequential composition if the effect context is made
lawful.

## Finding D — OpenCode and Claude expose a genuine supported-fragment split

OpenCode can realize transformation + rejection.

Claude currently realizes rejection but not transformation.

Therefore the shared lifecycle category must be smaller than the OpenCode one,
or Claude must explicitly reject unsupported patch semantics.

## Finding E — static and dynamic categories should probably be distinct

Katamorph's resource grammar is currently much closer to a static categorical
schema than to a complete process category.

A separate portable process semantics gives declarations executable meaning
without conflating reference paths and runtime transitions.

## Finding F — TriggerContract is a denotation candidate, not necessarily a morphism itself

The static Trigger resource can denote a dynamic reactive/process morphism under
an interpreter.

That preserves Katamorph's data-oriented purpose and lets target adapters be
judged against an intermediate portable meaning.

## Finding G — the categorical vocabulary already produces actionable defects

The trace identifies missing requirements without changing runtime code:

- canonical event payload/domain schemas;
- semantic request identity;
- explicit effect algebra/laws;
- distinction between policy choice and transition composition;
- transformed-request threading if hooks are to compose as endomorphisms;
- explicit target support for patch/reject/etc.;
- incompatibility rather than silent Claude patch degradation.

---

# Session Mycology lesson

A shared event name does not make a shared morphism. To earn categorical
composition, the system must say what value/state the relation starts from, what
it returns, how effects compose, and what identity means. The existing hook
priority chain is a useful left-biased choice algebra, while a future
category-theoretic interceptor model likely needs state-threading effectful
composition. Keeping static declaration categories distinct from dynamic process
categories makes both the current Katamorph grammar and the desired host
interpretations easier for humans and agents to reason about.

## Disposition

Orientation / formal convergence target only.

No runtime behavior, schema, package, or naming decision is accepted by this
pass.

The strongest target hypothesis is now:

```text
K_schema
  portable semantic declarations and typed references

⟦-⟧
  portable semantic interpretation

K_process
  composable semantic behavior, potentially effectful

J_T
  target functor over the fragment T lawfully supports
```

`:tool/requested` should currently be treated as the name of a lifecycle
interception point, not yet as a proven Katamorph morphism.

## Exact next collection step

Inspect Katamorph's policy, policy-gate, fulfillment, and condition interpreters
together with one real Muse `:tool/requested` policy hook, then determine whether
the existing first-decisive hook choice is already a reusable policy algebra that
should remain separate from Kleisli/process composition, recording its carrier,
identity, associative operator, precedence, rejection/patch interaction, and any
counterexample to closure or associativity.
