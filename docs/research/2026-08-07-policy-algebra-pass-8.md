<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Policy Algebra vs Process Composition — Pass 8

Date: 2026-08-07
Status: orientation / formalization target / non-decision
Research tier: decision-support preparation
Predecessor: `docs/research/2026-08-07-trigger-morphism-semantics-pass-7.md`

## Question

Does the existing first-decisive Muse hook behavior already correspond to a
reusable policy algebra which should remain distinct from the Kleisli/process
composition proposed in Pass 7?

The exact collection step was to inspect Katamorph policy, policy-gate,
fulfillment, and condition interpretation together with one real Muse
`:tool/requested` policy hook, then identify the carrier, identity, associative
operator, precedence behavior, and rejection/patch interaction of the existing
policy mechanisms.

The operator's standing direction is that category theory should become an
intentional architectural grammar for humans and agents. This pass therefore
uses formal names only where the current behavior earns them, and records where
an aspirational formalization would require the grammar to change.

No runtime or schema change is authorized by this research.

## Sources inspected

Katamorph:

- `src/cljs/katamorph/schema.cljs`
- `src/cljs/katamorph/policy/eval.cljs`
- `src/cljs/katamorph/policy/gate.cljs`
- `src/cljs/katamorph/policy/fulfillment.cljs`
- `src/cljs/katamorph/condition/registry.cljs`
- `test/cljs/katamorph/policy/eval_test.cljs`
- `test/cljs/katamorph/policy/gate_test.cljs`

Muse:

- `src/cljs/eta_mu/dsl.cljc`
- `src/cljs/eta_mu/dsl/normalize.cljc`
- `src/cljs/eta_mu/dsl/compile.cljc`
- `.ημ/plugins/kanban_gate.cljs`

The concrete Muse policy hook used is `:policy/kanban-done-gate`, with
`:policy/kanban-direct-edit-guard` as a second same-plugin comparison.

---

# Observed mechanisms

## 1. Katamorph currently contains several different things called policy-like

The current schema contains at least four distinct policy-related concerns:

1. tree-shaped `:policy` contracts inherited from Proxx;
2. flat `:policy-gate` contracts inherited from eta-mu contract-runtime-v2;
3. `:fulfillment` contracts for post-call notification/audit;
4. trigger `:condition` expressions evaluated through a safe predicate registry.

These do not share one combination law today.

That is a useful discovery rather than a defect: the word `policy` has been
covering several algebraically different operations.

## 2. The old PolicyOutcome enum already mixes two dimensions

Current Katamorph declares:

```clojure
[:enum :apply :try :next :reduce :block :warn :note :allow]
```

This is a strong indication of historical semantic fusion.

The latter four values naturally look like **decision values**:

```text
allow
note
warn
block
```

The former four naturally look more like **evaluation/control strategies**:

```text
apply
try
next
reduce
```

A category-oriented grammar should probably not keep traversal/composition
strategy and decision result in one carrier unless a later formal model proves
that fusion intentional.

Current `StrategyContract` reinforces the historical overlap by fixing
`:policy/outcome :try`.

Disposition: preserve as observed heritage; do not promote the mixed enum as the
future policy algebra.

## 3. Policy expression evaluation is a predicate/value language

`katamorph.policy.eval` evaluates quoted Clojure forms against a context.

It supports:

- equality and ordering;
- logical `not`, `and`, and `or`;
- collection access;
- simple coercions and predicates;
- selected string operations;
- injected functions through `contract/apply`.

Evaluation errors become `nil` in the public `eval-form` API.

`eval-forms` then combines forms using:

```text
:all
:some
:none
:not
:assert
```

This layer is best read as **applicability / proposition evaluation**, not as
policy decision composition.

It also uses Clojure truthiness rather than a strict Boolean carrier in several
places. For example `(and 1 2 3)` returns `3`, and `:some` may return the first
truthy value rather than literal `true`.

If Katamorph later wants a formal Boolean algebra of policy propositions, the
boundary should either normalize these values into `Bool` or explicitly define
truthiness as part of the semantics.

## 4. Trigger conditions are pure predicates, but error law has drift

`katamorph.condition.registry` documents condition functions as pure predicates:

```text
(condition-fn event actor trigger config) -> boolean
```

and provides safe expressions with `and`, `or`, `not`, comparisons, whitelisted
functions, and registered condition functions.

No condition means `true`.

The `evaluate` docstring says exceptions become false / fail closed. The current
function body does not itself catch exceptions thrown by `safe-eval`.

Disposition: this is documentation/implementation drift which matters if
failure behavior is later elevated into a formal law. This pass does not choose
the intended side.

---

# The cleanest existing algebra: policy-gate severity

## 5. Flat policy gates use strongest-wins, not first-wins

A `policy-gate` matches a tool call by tool name and selected params.

Matching gates contribute one action:

```text
block
warn
note
```

The evaluator also synthesizes `allow` when no gate matches.

It defines a numeric severity:

```text
allow = 0
note  = 1
warn  = 2
block = 3
```

and chooses the maximum severity among all matches.

The tests explicitly exercise strongest-wins behavior.

## 6. Decision carrier and order

Define:

```text
D = {allow, note, warn, block}
```

with order:

```text
allow <= note <= warn <= block
```

and operation:

```text
d1 ⊔ d2 = max(d1, d2)
```

Then from the implementation:

```text
associative:
  (a ⊔ b) ⊔ c = a ⊔ (b ⊔ c)

commutative:
  a ⊔ b = b ⊔ a

idempotent:
  a ⊔ a = a

identity / bottom:
  a ⊔ allow = a

top:
  a ⊔ block = block
```

Therefore the **decision projection** is already a finite bounded join-semilattice.

This term is earned by the current implementation; it is not merely aspirational.

## 7. Thin-category reading

Every poset can be regarded as a thin category: there is at most one morphism
between two objects, with a morphism `a -> b` exactly when `a <= b`.

Under that reading, the gate decision order gives a tiny category:

```text
allow -> note -> warn -> block
```

and `⊔` is the least upper bound. In the corresponding thin category, the join
acts as the binary coproduct when it exists.

This gives agents a legitimate category-theory sentence:

> Independent gate decisions combine by join in the policy-severity
> semilattice; adding a policy may only move the aggregate decision upward in
> severity, never downward.

That monotonicity law is a useful architecture constraint.

## 8. Explicit `allow` is not currently a PolicyGate contract value

There is a small schema/implementation distinction:

- `strongest-action` understands `:allow`;
- `evaluate-gates` returns `:allow` as the no-match/default result;
- current `PolicyGateContract` uses `Severity`, whose explicit schema enum is
  `:block | :warn | :note` and excludes `:allow`.

So `allow` is currently the algebraic bottom synthesized by evaluation rather
than a legal explicit gate declaration.

That is coherent, but it should be documented if this algebra becomes law.

---

# Decision and provenance are not the same algebra

## 9. The action result is commutative; the returned witness is not

`evaluate-gates` does more than return the strongest action. It also returns:

```clojure
{:action  ...
 :reason  ...
 :policy  winning-policy
 :matches [...all matching policies...]}
```

When several matching gates have the same strongest action, the evaluator uses
the **first** matching gate of that severity as `:policy` and the source of
`:reason`.

Therefore:

```text
projectDecision(evaluate([P,Q]))
  = projectDecision(evaluate([Q,P]))
```

for equal-severity P and Q,

but generally:

```text
evaluate([P,Q]) != evaluate([Q,P])
```

because witness/reason/match ordering changes.

This is a major formal distinction.

### Derived architecture rule

Separate:

```text
Decision
```

from:

```text
Evidence / Witness / Provenance of Decision
```

The first currently has a commutative semilattice law.
The enriched result currently carries ordered provenance and therefore does not.

This aligns naturally with the broader eta-mu emphasis on ledgers and receipts:
the semantic decision can have clean algebraic laws without erasing the causal
record explaining how it was reached.

---

# Fulfillment uses accumulation, not decision join

## 10. All matching fulfillments fire

`katamorph.policy.fulfillment/evaluate-fulfillments` explicitly says there is no
strongest-wins reduction.

Every matching fulfillment becomes an action containing:

```text
mode
message
level
fulfillment contract
```

and all such actions are returned as a vector.

The source declaration order is preserved by the ordinary filter/map/vector
pipeline.

## 11. Natural algebraic reading

At the result level, fulfillment actions naturally accumulate in a sequence.

Let `F*` be finite sequences of fulfillment actions and `++` be concatenation:

```text
identity = []
operation = ++
```

Then:

```text
(xs ++ ys) ++ zs = xs ++ (ys ++ zs)
[] ++ xs = xs = xs ++ []
```

So fulfillment accumulation naturally has a list-monoid structure.

It is generally **not commutative** because order is retained and may be
observable to downstream notification/audit consumers.

This is a different algebra from gate severity join.

A useful agent-facing sentence is therefore:

> Gates join; fulfillments accumulate.

---

# Muse hooks use ordered first-decisive choice

## 12. Current hook carrier

Muse hook handlers currently return:

```text
nil
{:effect :reject :message ...}
{:effect :patch :output {...}}
```

Abstractly, define:

```text
H = None | Some(Verdict)
```

where `Verdict` is currently `Reject` or `Patch`.

The compiler sorts hooks by descending priority and evaluates them in sequence.
The first non-`nil` result terminates the chain.

## 13. Current operator

The effective result-level operator is:

```text
x <|> y =
  if x is Some(_)
    then x
    else y
```

with:

```text
identity = None / nil
```

This is the familiar left-biased `First` operation over optional values.

It is:

```text
associative
idempotent
non-commutative
```

with `nil` as a two-sided identity.

Thus the **result-level first-decisive choice already forms a monoid**.

Because priority sorting happens before the fold, the full hook evaluator is
better described as:

```text
sortByPriority
  then fold First
```

rather than as plain unordered monoidal combination.

## 14. Priority is external to the `First` monoid

Priority is not itself encoded in the `First` operator. It determines the input
sequence to that operator.

This distinction matters because:

```text
First(RejectA, PatchB) = RejectA
First(PatchB, RejectA) = PatchB
```

The operation is intentionally non-commutative.

Current hook descriptors have an integer priority, but the compiler has no
explicit semantic secondary tie-break field. Hooks at equal priority therefore
do not have a separately modeled precedence law at the descriptor level.

The current kanban plugin contains two `:tool/requested` hooks both at priority
100. Their match domains are intended to be different, reducing practical
conflict, but this does not constitute a general tie law.

If equal-priority conflicts become meaningful, ordering must be declared rather
than inferred from incidental registry/source order.

---

# Concrete Muse policy: kanban gate

## 15. `:policy/kanban-done-gate`

The real hook:

```text
id       :policy/kanban-done-gate
event    :tool/requested
priority 100
```

matches calls resembling `kanban_update_status`.

For a transition to `done`, it obtains current card facts from Rheos and checks
three mechanical evidence floors:

- test/assertion/failure evidence;
- explicit review/audit approval disposition;
- referenced CLI commands exist in the configured dispatch file.

If Rheos cannot be reached, it blocks rather than guesses.
If required evidence is missing, it rejects.
Otherwise it returns `nil` and the next hook may be consulted.

Its observed result subset is therefore:

```text
None | Reject(reason)
```

not Patch.

It is also effectful/asynchronous internally because it may perform HTTP and file
reads before deciding.

## 16. `:policy/kanban-direct-edit-guard`

The sibling hook has the same event and priority.

It rejects direct edit/write calls which attempt to set a kanban card's
frontmatter status to done, forcing callers through the status-update path where
the evidence floor can be checked.

Again its result subset is:

```text
None | Reject(reason)
```

Together these are a good example of **ordered alternatives over guards**, not
request transformations.

---

# Rejection and patch should not be forced into one algebra

## 17. Current hook runtime treats Reject and Patch identically for choice

At the `First` fold level:

```text
Reject(...) is decisive
Patch(...)  is decisive
```

Either terminates evaluation of lower-priority hooks.

That is current behavior.

But Pass 7 showed that a future process interpretation may want:

```text
patch : Request -> M Request
reject : Request -> M Request
```

where patch transforms a request which later policies may inspect, while reject
short-circuits.

Those semantics do **not** match today's first-decisive hook fold.

Therefore the formal grammar should preserve two separate operations:

```text
CHOICE / PRECEDENCE
  choose which policy alternative gets authority

PROCESS COMPOSITION
  thread a lawful request/result through effectful transformations
```

A patch may participate in the latter.
A precedence rule may participate in the former.
Neither should silently inherit the other's laws.

---

# A five-part policy decomposition

## 18. Proposed conceptual decomposition — orientation only

The inspected code suggests that `policy` currently spans at least five
algebraically distinct layers:

```text
1. Applicability / proposition
   Does this rule apply?

2. Decision
   What severity / permission verdict is contributed?

3. Choice / precedence
   Which competing rule has authority when alternatives are ordered?

4. Transformation / process
   How is a valid request/state changed before continuing?

5. Evidence / fulfillment
   What observations, notifications, receipts, or explanations accumulate?
```

A future Katamorph grammar could make these separate concepts or explicit facets
of one policy construct.

No naming/schema decision is made here.

## 19. The corresponding algebras already differ

Observed/proposed law table:

| Concern | Current/provisional carrier | Operation | Identity | Commutative? | Status |
| --- | --- | --- | --- | --- | --- |
| applicability | truthy/predicate values | and/or depending expression | true/false by op | yes for pure Bool ops | implemented, but truthiness/error semantics need cleanup |
| gate decision | allow <= note <= warn <= block | join/max | allow | yes | implemented and semilattice-shaped |
| gate provenance | winning policy + ordered matches | first witness + collection | none/empty | no | implemented |
| fulfillment | list of actions | concatenation | [] | no | implemented |
| hook precedence | optional decisive verdict | First / left-biased choice | nil | no | implemented |
| request transformation | Request -> M Request | Kleisli-like composition | pure/id | generally no | design target from Pass 7 |

This table is the central finding of Pass 8.

---

# Category-theory consequences

## 20. A policy system does not need one universal composition operator

Category theory does not require us to pretend all policy combination is
ordinary arrow composition.

Instead the architecture can intentionally contain several compatible algebraic
structures.

For example:

```text
Decision
  a join-semilattice / thin category

Policy alternatives
  an ordered-choice monoid

Fulfillment evidence
  an accumulation monoid

Process transitions
  a category, plausibly Kleisli-like when effects are explicit
```

The important design work is then to define **how these structures act on or
annotate one another**.

That question is substantially sharper than asking for one generic policy
interpreter.

## 21. Decision may annotate process rather than *be* process

A useful provisional picture is:

```text
              Policy decision D
                    |
                    | guards / annotates
                    v
Request ----------------------------> Result
           process morphism
```

or for a request transformation:

```text
Request --h--> M Request --invoke--> M Result
```

with a separate decision/evidence structure attached to the transition.

This prevents `block`, `warn`, and `note` from being confused with host events or
business-state transitions.

## 22. Monotonicity becomes an explicit law for severity policies

Because independent gate decisions combine by join, adding an additional gate
cannot lower the resulting severity:

```text
aggregate(P) <= aggregate(P ∪ {q})
```

with respect to the severity order.

That is a valuable law for agents reviewing policy changes:

> Adding an independent gate may preserve or strengthen the decision, but may
> never weaken it unless the policy set itself is being replaced or scoped
> differently.

If a future implementation violates that, it is not merely a surprising edge
case; it has broken the declared algebra.

## 23. Provenance should survive algebraic reduction

The gate implementation already keeps `:matches` after reducing them to one
action.

That points toward a broader principle:

```text
reduce meaning without erasing evidence
```

The decision can collapse through a clean algebra while the receipt/ledger layer
retains the causal witnesses.

This may eventually be modeled with writer-like annotation, an enriched result,
a product construction, or another categorical mechanism, but the current
repository evidence is not sufficient to choose among those models.

The important requirement is simpler:

> semantic reduction must not imply evidentiary erasure.

## 24. `warn` and `note` raise a target-realization question

The generic gate algebra contains intermediate decisions `note` and `warn`, but
the current Muse hook effect algebra exposes only:

```text
nil
reject
patch
```

There is no shared target-neutral `warn` or `note` hook effect in the inspected
path.

Therefore a direct translation from Katamorph gate decisions into Muse hook
verdicts is not currently total.

Possible future interpretations include:

- warnings/notes become evidence/ledger emissions while the request proceeds;
- hosts with native warning surfaces receive a presentation;
- unsupported presentation is omitted while semantic evidence is retained.

This pass does not choose one.

It does establish that `Decision` and `HostVerdict` should not be assumed to be
the same type.

---

# Formal vocabulary agents can use now

## 25. Earned terms

The following statements are supported strongly enough by current code:

- The flat gate **decision carrier forms a finite join-semilattice** under
  strongest-severity choice.
- The severity order can be read as a **thin category**.
- Fulfillment results **accumulate as a list monoid** under concatenation.
- Current Muse hook verdicts use a **left-biased First monoid** after priority
  ordering.
- The hook First operation is not request/process composition.
- Decision reduction and evidence/provenance have different algebraic laws.

## 26. Aspirational terms which still need laws

Do not yet claim:

- policies are endofunctors;
- warnings form a natural transformation;
- hook chains are Kleisli composition;
- policy evidence is a Writer monad;
- the combined policy system is a semiring;
- gate-to-host realization is a functor preserving all decision structure.

Each may become useful, but current evidence does not yet establish the required
laws.

---

# Implications for Katamorph crystallization

## 27. Split strategy from outcome

The historical `PolicyOutcome` enum currently places:

```text
apply / try / next / reduce
```

next to:

```text
block / warn / note / allow
```

The formal analysis gives a concrete reason to revisit that shape eventually:

- one set describes **how evaluation combines/proceeds**;
- the other describes **what decision was reached**.

This is exactly the kind of inherited shape that implementation pressure across
several semantic spaces should be allowed to crystallize into cleaner concepts.

## 28. Preserve independent structures rather than inventing one giant Policy

A future grammar may be clearer if policies declare separate facets such as:

```text
condition / applicability
contribution / decision
precedence / strategy
transform / effect
observation / evidence
```

or other names discovered through implementation.

The names are deliberately not accepted here.

What is accepted as a research conclusion is the **separation of laws**.

## 29. Category-oriented agent questions

When an agent encounters policy code, useful questions now include:

1. Is this predicate deciding applicability, or is it itself a decision?
2. Are multiple decisions joined, ordered, or accumulated?
3. Is this operator commutative? Should declaration order matter?
4. What is the identity element?
5. Does adding a rule monotonically strengthen the result?
6. Are we reducing a semantic value while separately retaining provenance?
7. Is this a policy choice, or a transformation that should compose with the
   next process morphism?
8. If two rules have equal precedence, where is the tie law declared?
9. Does the target realize the decision itself, or only a projection of it?
10. What evidence must survive even when the target cannot present the full
    semantic decision?

These questions give the desired formal grammar practical leverage during agent
work.

---

# Session Mycology lesson

A system can contain several lawful notions of combination without one of them
being the universal meaning of `compose`. In the current policy neighborhood,
gate severities join, fulfillments accumulate, hook alternatives choose the
first decisive result, and process transformations may eventually compose
Kleisli-style. Separating those algebras turns ambiguous words like `policy`,
`next`, and `reduce` into testable design questions. Semantic reduction should
also remain distinct from evidentiary reduction: a clean decision algebra must
not erase the witnesses that caused the decision.

## Disposition

Orientation / formal convergence target.

This pass strengthens the following direction:

- preserve a distinct predicate/applicability layer;
- recognize flat gate decisions as an existing join-semilattice;
- keep gate decision algebra separate from ordered provenance;
- recognize fulfillment as accumulation rather than strongest/first wins;
- recognize current Muse hook short-circuiting as ordered `First` choice;
- keep that choice operator separate from effectful request transformation;
- eventually separate policy evaluation/control strategies from terminal
  decision outcomes in Katamorph's inherited grammar;
- retain evidence even when semantic decisions reduce to one value.

No schema, runtime, package, or naming decision is accepted.

## Exact next collection step

Trace one concrete blocking decision from a current Muse `:tool/requested` hook
through every evidence/provenance surface that exists today — hook result,
OpenCode/Claude boundary realization, ledger/receipt emission if any, and the
Rheos-backed evidence consulted by the kanban gate — then determine which parts
are semantic decision, causal witness, host presentation, and durable evidence,
so we can test whether provenance should be modeled as a writer-like annotation,
an enriched/lax interpretation, or a simpler product alongside the policy
semilattice without choosing the formalism in advance.
