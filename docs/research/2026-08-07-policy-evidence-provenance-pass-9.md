<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Policy Decision / Evidence Provenance — Pass 9

Date: 2026-08-07
Status: orientation / formalization target / non-decision
Research tier: decision-support preparation
Predecessor: `docs/research/2026-08-07-policy-algebra-pass-8.md`

## Question

Trace one concrete blocking decision from the current Muse `:tool/requested`
kanban hook through every evidence/provenance surface that exists today — Rheos
evidence consulted, hook result, OpenCode/Claude realization, and any ledger or
receipt emission — then classify each value as semantic decision, causal witness,
host presentation, or durable evidence.

The purpose is to determine what categorical relationship should connect the
policy decision semilattice discovered in Pass 8 with the event-ledger / receipt
systems, while keeping observed implementation separate from desired formal law.

No runtime or schema change is authorized by this pass.

## Sources inspected

Muse:

- `.ημ/plugins/kanban_gate.cljs`
- `src/cljs/eta_mu/dsl.cljc`
- `src/cljs/eta_mu/dsl/compile.cljc`
- `src/cljs/eta_mu/boundaries/opencode.cljs`
- `src/cljs/eta_mu/boundaries/claude.cljs`
- `.ημ/plugins/receipt_river.cljs`

Rheos / eta-mu:

- `packages/rheos/src/rheos/backend/infra/http_server.cljs`
- `packages/rheos/src/rheos/backend/infra/task_edit.cljs`
- `packages/rheos/src/rheos/backend/infra/transition.cljs`
- `packages/rheos/src/rheos/backend/domain/events.cljs`

Predecessor research:

- Pass 7: lifecycle relation / process composition
- Pass 8: policy algebras and gate-decision join-semilattice

---

# Concrete trace

## 1. Request

The concrete request is a tool invocation attempting to move a Rheos-backed
kanban card to `done` through a tool whose name matches the kanban status-update
surface.

Conceptually:

```text
ToolRequest
  tool   = kanban_update_status-ish
  args   = {uuid, project, status=done, ...}
  ctx    = host/session/worktree context
```

Muse's `:policy/kanban-done-gate` is registered on the canonical lifecycle point:

```text
:tool/requested
```

with priority `100`.

If the tool is not the status-update tool, or the requested state is not `done`,
the hook returns no verdict.

If it is a `done` transition with a UUID, the hook obtains the card content from
Rheos before deciding.

## 2. Evidence source consulted

The hook performs:

```text
GET /api/task/:uuid/content?project=...
```

Rheos's handler:

1. resolves the project;
2. loads the current task list;
3. finds the task by UUID;
4. reads the task's markdown file;
5. parses that file using `rheos.backend.shape.content-parser`;
6. returns:
   - `uuid`
   - parsed `frontmatter`
   - parsed `sections`
   - `sourcePath`.

The kanban gate therefore consults a **current parsed content snapshot** rather
than querying the Rheos event ledger directly.

This distinction is important:

```text
ledger history -> contributes to current card/file state
current card/file state -> what policy actually observes
```

The gate's decision is made from the second arrow, not by folding or querying the
first.

## 3. Evidence predicates

For a move to `done`, the hook computes blocking reasons from the parsed card.

It currently requires evidence including:

1. a greppable test line in comments matching approximately:

```text
<N> tests, <N> assertions, 0 failures
```

2. an explicit review/audit disposition in comments containing an
   approve/accepted-style verdict;

3. when the repo config declares a dispatch file, every `ep <command>` named in
   the card body must occur in that dispatch file.

The third check consults the current worktree directly rather than Rheos.

Therefore one policy judgement may depend on multiple evidence authorities:

```text
Rheos parsed card snapshot
local worktree dispatch source
Rheos availability itself
```

If Rheos cannot be reached, the gate blocks rather than guessing.

## 4. Hook semantic result

If all checks pass, the hook returns `nil` and current hook semantics treat that as
no decisive verdict.

If one or more checks fail, it returns:

```clojure
{:effect :reject
 :message "kanban-done-gate blocked this transition:\n- ..."}
```

If Rheos is unavailable, it returns a similar reject result whose reason records
that evidence could not be verified.

The semantic payload surviving the hook boundary is therefore currently only:

```text
Reject(reason-text)
```

It does not carry structured fields identifying:

- policy id;
- task UUID as a typed reference;
- request/session/message id;
- evidence snapshot hash;
- individual evidence predicates and their results;
- Rheos ledger event ids which produced the comments/frontmatter;
- worktree commit/blob identity used for dispatch checking;
- timestamp of the evidence snapshot;
- target-independent judgement id.

Those facts may have influenced the decision, but they are not part of the
returned semantic value.

---

# What is durable today

## 5. Card evidence is durable when produced through Rheos

Rheos's non-status edit chokepoint says every successful frontmatter/comment
mutation is recorded in the project's event ledger.

For comments specifically:

```text
append comment to markdown
  -> emit comment event
  -> append event to ledger
```

The event vocabulary records task id, text, source, agent, timestamp and
`write-id` in a kanban event envelope.

The ledger is therefore durable evidence that particular card mutations occurred.

This gives the gate a valuable upstream evidence substrate even though the gate
does not query that substrate directly.

## 6. The content response does not retain those event references

`GET /api/task/:uuid/content` returns the parsed current file state.

It does not return:

```text
event ids
write ids for each section
causal parent ids
ledger positions
content hash
snapshot version
```

So after parsing:

```text
"review approved"
```

there is no direct machine-readable link in the gate input saying *which ledger
event caused this text to exist*.

The evidence remains durable in the underlying systems, but the causal witness is
not carried through the policy boundary.

## 7. Successful status transitions are ledger-backed

Rheos status mutations use one FSM-enforced write path.

On a successful move:

```text
validate FSM
run command gate
write markdown status
append status-change event
```

The event ledger therefore records successful state transitions.

## 8. Rejected status transitions are deliberately non-mutations

Rheos's own transition path says an FSM or command-gate rejection changes
nothing. No status-change event is emitted for a rejected transition.

The Muse kanban hook sits even earlier. When Muse rejects the `done` tool call,
the status-update tool itself is prevented from running, so Rheos never receives
the transition request through its normal mutation path.

Observed consequence:

```text
source evidence mutations       durable
successful transition           durable
blocked Muse attempt             not recorded by Rheos status ledger
```

This is the central provenance gap found by this pass.

---

# Target realizations

## 9. OpenCode

For a canonical hook result:

```text
{:effect :reject :message m}
```

OpenCode realizes the decision by rejecting the host promise with an error whose
message is `m`.

Classification:

```text
Reject                 semantic decision
message text           human-facing reason / weak witness summary
JS Error / rejection   host presentation
```

The inspected boundary performs no ledger or receipt append for the rejection.

The host may itself retain session logs, but that is not currently a declared
Katamorph/Muse evidence contract and therefore cannot be treated as the durable
semantic record of the decision.

## 10. Claude Code

Claude maps the same semantic rejection into host-native decision JSON roughly:

```text
continue = false
permissionDecision = deny
permissionDecisionReason = reason
```

with a generic stop reason.

Classification:

```text
Reject                         semantic decision
permissionDecisionReason       reason presentation
Claude hook JSON                host presentation
```

Again the inspected boundary does not append a Katamorph/Muse/Rheos ledger event
for the rejected attempt.

## 11. MCP

Generic MCP does not implement this lifecycle-interception fragment, so there is
no corresponding policy decision realization there.

This remains lawful partial target support rather than an interoperability bug.

---

# Receipt River is separate

## 12. No automatic Receipt River emission occurs on the gate path

Muse has a separate `receipt_river` tool which can append structured receipt
records to `receipts.edn` when explicitly invoked.

The kanban gate plugin does not import or call that tool, and the OpenCode/Claude
hook boundaries do not invoke it on rejection.

Therefore:

```text
policy rejected a request
```

does **not** currently imply:

```text
receipt appended
```

Receipt River remains a distinct work/audit mechanism rather than the automatic
provenance sink for policy judgements.

This is important because `receipt`, `Rheos event`, and `policy judgement` are
currently three different durable/semantic concepts even when they concern the
same work.

---

# Classification

## 13. Current values by role

| Value | Current role | Durable? | Stable semantic identity? |
| --- | --- | --- | --- |
| card markdown/comment | source evidence state | yes, file-backed | task identity yes; individual proposition no |
| Rheos comment/frontmatter event | causal mutation witness | yes, ledger-backed | event id + task id |
| Rheos parsed content response | evidence snapshot | transient | task uuid, no snapshot id |
| dispatch-file source text | external evidence snapshot | durable in Git/worktree when committed | not carried into verdict |
| evidence regex/check result | evaluation fact | transient | no |
| missing-reason string | witness summary | transient unless host logs it | no |
| `Reject` | semantic decision | transient in current hook path | no judgement id |
| OpenCode error | host presentation | host-dependent | no shared id |
| Claude deny JSON | host presentation | host-dependent | no shared id |
| successful Rheos status event | causal transition witness | yes | yes |
| blocked-attempt event | absent today | no | n/a |
| Receipt River record | explicit work/audit receipt | yes when separately invoked | receipt fields, but unrelated automatically |

## 14. The most important semantic loss is not the reject value

Both OpenCode and Claude preserve the important binary fact that the request is
rejected.

The larger loss occurs earlier:

```text
rich evidence context
      |
      v
plain reason string
      |
      v
Reject
```

A human can often reconstruct what happened from the string. A machine cannot
reliably reconstruct the exact evidence snapshot, provenance chain, or evaluation
trace.

This makes the current policy boundary **decision-preserving but
provenance-erasing**.

---

# Formalization target

The following is a design target, not accepted Katamorph law.

## 15. Preserve the decision semilattice

From Pass 8:

```text
D = {allow <= note <= warn <= block}
```

with:

```text
d1 ⊔ d2 = least upper bound / stronger decision
```

The decision algebra should remain small and reducible.

Do not solve provenance by stuffing arbitrary evidence data into the decision
carrier itself.

## 16. Introduce a proof-relevant judgement layer

A policy evaluation should conceptually produce a **judgement**, not merely a
decision:

```text
Judgement = Decision + Witness
```

One provisional product form is:

```text
J = D × W
```

where:

```text
D = semantic decision
W = evidence/provenance record
```

A witness may include stable references to:

```text
policy identity
request identity
subject/resource identity
evidence snapshot identity
evidence refs
checks evaluated
check outcomes
reason
observation time
runtime/target context where materially relevant
```

The exact schema is not chosen here.

## 17. Evidence should accumulate under a different law

Decision reduction and evidence preservation should obey different operations.

A desirable law is:

```text
π_D(j1 ⊗ j2) = π_D(j1) ⊔ π_D(j2)
```

where:

```text
π_D : Judgement -> Decision
```

projects away provenance.

Meanwhile evidence should accumulate rather than collapse to only the winner:

```text
π_W(j1 ⊗ j2) = π_W(j1) ⋄ π_W(j2)
```

where `⋄` is an evidence-combination operation such as ordered append or a causal
DAG merge.

This means the semantic reduction can be commutative/idempotent while the
provenance structure remains ordered or causally rich.

The current gate evaluator from Pass 8 already hints at this by returning all
matching policies while separately choosing one winning reason.

## 18. Writer-like semantics are a useful programming-language analogy

A Writer-style computation returns both a value and an accumulated monoidal log.

Policy judgement has a related shape:

```text
Request × EvidenceSnapshot
    -> Decision × EvaluationTrace
```

or more suggestively:

```text
Request
    -> Reader EvidenceSnapshot (Writer Trace Decision)
```

This is only an analogy/formalization lead. The evidence snapshot is an *input*
used to reach the decision, while the evaluation trace is an *output* explaining
what was actually consulted and concluded.

Conflating those two would again erase useful distinctions.

## 19. Negative evidence requires an evaluation witness

The kanban gate frequently blocks because something is absent:

```text
no test line found
no accepted review disposition found
named command absent from dispatch source
```

A ledger reference alone cannot prove this negative observation.

To reproduce the judgement, the system needs some representation of the snapshot
and evaluator:

```text
snapshot/ref or content hash
policy/version
check identity
check result
```

Thus durable policy evidence cannot be only "links to prior events". It also needs
an attestation of the evaluation performed over a particular observed state.

## 20. A denied attempt should be representable independently of mutation

The current Rheos invariant is good:

```text
rejected mutation changes nothing
```

But "changes nothing" should not have to mean "leaves no evidence that a decision
occurred".

A future shared semantic event could distinguish:

```text
state mutation event
policy judgement event
```

so rejection produces the second without falsely producing the first.

That lets ledgers preserve:

```text
request attempted
policy consulted evidence
policy decided block
state remained unchanged
```

without weakening the mutation ledger's truthfulness.

## 21. Decision-to-evidence relation

The strongest current formal target is not yet a new category name. It is the
following preservation structure:

```text
                 Judgement
                /         \
               /           \
        π_D   v             v  π_W
          Decision        Witness
             |               |
             | join          | accumulate / causalize
             v               v
       small algebra      durable evidence
```

The decision projection should be a homomorphism with respect to judgement
combination and decision join.

The witness projection does not need to be commutative or idempotent.

This is already enough formal grammar for agents to reason usefully:

> reduce decisions; retain witnesses.

> target adapters may project the decision, but the shared boundary must not
> destroy the evidence required to reproduce or audit that decision.

## 22. Possible future categorical lift

Once evidence events have explicit source/target states and causal relationships,
a ledger may admit a category-of-histories interpretation:

```text
objects    = semantic states / observations
morphisms  = evidence-producing transitions
composition = causal/path composition
```

Current Rheos events have event ids, timestamps, task ids, sources and write ids,
but this pass did not find a general explicit causal-parent/source-state/target-
state law sufficient to promote the whole ledger to such a category.

Do not use that stronger terminology yet.

A less ambitious statement is already justified:

```text
ledger histories have an append/accumulation structure
```

and policy decisions have a join structure.

The architecture needs an explicit bridge preserving both.

---

# Implications for target functors

## 23. Target realization should not own judgement persistence

If OpenCode can reject and Claude can deny, both preserve the core decision.

Neither host should be required to become the canonical evidence store.

A cleaner target architecture is:

```text
shared policy evaluation
  -> durable shared judgement/witness
  -> target decision realization
```

rather than:

```text
shared policy evaluation
  -> host error/JSON
  -> hope host logs are enough
```

This preserves the operator's broader interoperability rule: target-local context
is optional unless it belongs to the shared semantic contract.

## 24. Host presentation can remain lossy after shared evidence is secured

Once a durable shared judgement exists, host projections may legitimately use
simpler presentations:

```text
OpenCode Error(message)
Claude deny JSON
future UI toast
```

provided they preserve the decision meaning they claim to implement.

The rich witness does not need to fit inside every host's rejection primitive.
It needs a stable shared identity/reference that systems capable of consuming
provenance can follow.

---

# Relation to Presence / Actor work

## 25. Policy judgements are candidate causal facts for actor systems

For Presence/Actor semantics, a rejected influence/action is still something that
happened in the system's causal history even when the requested state change did
not happen.

This fits the emerging distinction:

```text
attempt
observation
decision
mutation
```

as separate semantic facts.

An actor ledger which records only successful mutations cannot by itself explain
why an attempted action never occurred.

Conversely, a policy receipt which records only `block` without evidence refs
cannot explain why the decision was lawful.

This suggests the eventual Presence Actor Model will need causal linkage across
those facts without collapsing them into one event kind.

---

# Session Mycology lesson

Semantic reduction and causal evidence obey different laws. A policy decision may
safely collapse many inputs to one severity join, while the reasons and evidence
that justified that result must remain proof-relevant and often ordered. The
architecture should therefore make `Decision` small and compositional while
making `Judgement` evidence-bearing and durable. A rejected mutation may leave
state unchanged without being absent from history.

# Disposition

Orientation / formal convergence target.

Observed:

- Rheos card mutations are ledger-backed and therefore provide durable upstream
  evidence;
- the kanban gate reads a parsed current-state snapshot rather than ledger event
  references;
- the hook reduces its evidence context to `Reject(reason)`;
- OpenCode and Claude preserve rejection but only as host presentations;
- a Muse-blocked attempt does not create a Rheos status-change event;
- the inspected gate path does not automatically emit Receipt River records;
- current policy semantics therefore preserve the decision while erasing much of
  the causal/evidentiary structure of the judgement.

Target direction strengthened by this pass:

```text
Decision     = reducible semantic value
Witness      = proof/provenance structure
Judgement    = decision plus witness
Ledger       = durable evidence accumulation
Host result  = target presentation of the decision
```

No schema, package, or runtime change is accepted by this document.

## Exact next collection step

Trace one concrete Presence/Actor message or influence through eta-mu's current
envelope, mailbox, task/session ledger, and causal/correlation identifiers, then
compare that shape with one Rheos ledger event to identify the smallest existing
cross-system causal-evidence vocabulary (event identity, subject, source,
correlation/parent, time, payload/result) that could carry a durable policy
Judgement reference without inventing a new ontology first.
