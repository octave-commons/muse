# Muse Compatibility Boundary

Status: accepted operator boundary; implementation migration open  
Recorded: 2026-07-26  
Tracks: [issue #1](https://github.com/octave-commons/muse/issues/1)  
Cross-repository authority: `open-hax/eta-mu/docs/architecture/contract-dialect-and-data-authority.md`

## Decision

Muse is a compatibility and compilation system for agent harnesses.

It consumes shared eta-mu/Katamorph resources, links implementations and
exposures, reports compatibility loss, and emits host-native artifacts for
OpenCode, Claude, Codex, MCP, eta-mu-native, and later targets.

Muse is not a new agent harness and is not the canonical owner of actor,
session, policy, capability, event, workflow, or evidence semantics.

```text
Katamorph / eta-mu resources and laws
              |
              v
           Muse linker
              |
       +------+------+------+
       |             |      |
   OpenCode       Claude   MCP ...
   projection    projection projection
```

The operating model being projected is asynchronous and actor/ledger based.
A target adapter may translate that model into the constraints of a particular
host, but it must not silently replace asynchronous interaction with a single
synchronous orchestration turn.

## Repository inventory

The current repository combines several different roles. Their presence does
not establish permanent ownership.

| Current area | Disposition | Authority |
|---|---|---|
| `src/cljs/eta_mu/dsl*` | Muse compiler kernel; retain and evolve | Muse owns linking and target compilation, not canonical semantic vocabulary |
| `src/clj/eta_mu/{opencode,claude,mcp}/*` | Target build adapters; retain | Muse |
| `src/cljs/eta_mu/boundaries/{opencode,claude,mcp}*` | Host codecs and projections; retain | Muse |
| `src/cljs/eta_mu/daemon/*` | Compatibility build/publish service; retain with bounded scope | Muse, as artifact convergence infrastructure |
| `.ημ/config/**` | Target profiles and composition examples | Source resources remain governed by eta-mu/Katamorph; generated host configuration belongs to Muse |
| `.ημ/plugins/**` | Conformance fixtures and reference integrations | Semantic behavior belongs to the implementing domain/runtime package |
| `src/cljs/eta_mu/actor/**` | Prototype and compatibility fixture; migrate or consume from eta-mu runtime modules | Sol/eta-mu runtime plus event-ledger contracts |
| `src/cljs/eta_mu/domain/{task,mailbox,agent}*` | Prototype runtime behavior; not Muse-owned semantics | Sol/Rheos/eta-mu modules as classified by the constellation architecture |
| `src/cljs/eta_mu/domain/receipts*` and ledger boundaries | Reference projection and compatibility adapter | `event-ledger` owns event envelope, append, causality, ordering, and replay laws |
| `eta-mu.actor.muse` | Historical actor-role vocabulary, not the repository's system boundary | Actor semantics belong to the eta-mu runtime model |

## Keryx disposition

The inspected eta-mu repository contains Keryx design notes but no separately
landed Keryx compiler implementation. Those notes are requirements and lineage
for Muse rather than authority to create another universal compiler.

Keryx material divides into:

1. portable capability/linking/compiler requirements that belong in Muse;
2. OpenCode-specific target requirements that belong in Muse's OpenCode adapter;
3. eta-mu-native runtime behavior that belongs in eta-mu/Sol/Rheos modules;
4. historical naming and package sketches that remain source material.

## Rejected current behavior

### Blocking mailbox monitor

The current OpenCode `actor_monitor` tool holds one host tool invocation open
while polling an actor mailbox. That is a synchronous compatibility projection
of an asynchronous system and is therefore not the default model Muse should
teach a host.

The replacement surface must:

- register a watch/subscription and return immediately;
- preserve a durable cursor and correlation identity;
- evaluate fulfillment independently of the initiating host turn;
- append fulfillment, cancellation, and failure records to a ledger;
- notify a subscriber actor or expose resumable status without pretending the
  initiating tool call remained the actor interaction;
- preserve causal root/parent, delivery, session, and turn identities where
  supplied.

A bounded blocking wait may remain only as an explicitly named legacy adapter,
not as the canonical actor-monitor operation.

### Fused tool descriptor

The current `deftool` record combines:

- semantic capability identity;
- executable handler implementation;
- host-facing tool exposure.

Muse must add separate descriptors for capability, implementation, and
exposure, then link them for each target. `deftool` may remain temporarily as a
compatibility macro that expands into the separated model.

```text
capability      = meaning, input/output, effects, errors
implementation  = runtime + handler + dependencies + version
exposure        = target + host name/event/route + presentation metadata
profile         = selected implementations, exposures, and policies
```

## Invariants

1. Muse-generated artifacts are projections and may be rebuilt.
2. Host codecs do not define canonical domain semantics.
3. Runtime implementations may be supplied by eta-mu modules without moving
   their ownership into Muse.
4. One capability may have multiple runtime implementations and host
   exposures.
5. Lossy target compilation emits explicit diagnostics.
6. Actor communication remains ledger-addressable and resumable across host
   turns.
7. No prototype's repository location silently establishes authority.

## Migration order

1. Replace blocking `actor_monitor` with a non-blocking watch projection and
   conformance tests.
2. Add additive capability, implementation, and exposure descriptors while
   retaining compatibility macros.
3. Move or consume actor/task/event semantics from their authoritative runtime
   packages; keep only target adapters and fixtures here.
4. Add cross-target fixtures proving equivalent declared behavior for
   OpenCode, Claude, and MCP.
5. Remove compatibility shims only after generated-artifact parity is recorded.
