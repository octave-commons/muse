# Kanban process (muse)

This repo's board lives in `./kanban/` (this directory's child). This file
is deliberately outside the task tree, so process guidance is not projected
as a card.

## Tool

Canonical source Rheos discovers `openhax.kanban.edn`; the published
`eta-mu` CLI installed here discovers the synchronized JSON compatibility
mirror. Both select:

```json
{
  "tasksDir": "./docs/agile/kanban",
  "fsm": "promethean"
}
```

Run everything from the repo root.

## Card anatomy

One markdown file per card, named `<uuid>.md`, YAML frontmatter:

```yaml
---
uuid: "<uuid v4>"
title: "Imperative title"
status: incoming        # Promethean initial state
priority: P1            # P0..P3
labels: ["area", "3sp"]
created_at: "<ISO-8601>"
points: 3
category: audit         # free-form grouping
---
```

Body is markdown. `---` on its own line after the frontmatter toggles
comment sections (muted in the web UI) — `eta-mu kanban comment` appends
these for provenance.

## Statuses

`icebox → incoming → accepted → breakdown → ready → todo → in_progress → testing → review → document → done`
(`blocked` for scoped impediments, `rejected` for dead ends, and `archived`
for terminal storage).

The CLI's `frontmatter <uuid> status <new>` delegates to Rheos and enforces
the configured FSM, WIP limits, and transition gates. Walk lawful edges;
don't edit status frontmatter directly.

## Loop

1. `eta-mu kanban count` / `list` — board state.
2. `eta-mu kanban search "topic"` — don't duplicate cards.
3. Walk a ready card through `todo` to `in_progress`, then
   `comment <uuid> "plan/progress"` so the ledger of intent travels with
   the card.
4. Finish through `review` → `document` → `done`.

## State

- Rheos writes through `docs/agile/kanban/.events`, a symlink whose physical
  target is `.ημ/kanban-events`. Never replace it with a ledger under docs.
- Muse's historical Receipt River remains the existing repo-root
  `receipts.edn`; do not relocate or rewrite its prior entries.
- Not Trello. `sync trello`/`sync github` exist upstream but are not part
  of this repo's process.

## Web UI

```
eta-mu kanban serve --tasks-dir docs/agile/kanban --port 8791
```
