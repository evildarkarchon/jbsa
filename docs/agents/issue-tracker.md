# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01` for new features, never a single combined tickets file. Migrated JBSA 1.0 children retain GitHub numbers `24`–`60` in their filename prefixes
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading
- Track lifecycle separately with `State: open` or `State: closed`; keep `Status:` for the triage role. On completion, set `State: closed` and append the outcome.
- Keep local tickets and their index in version control. List open work by scanning `.scratch/*/issues/*.md` for `State: open`, then filter by `Status:` as needed.

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

Local ticket numbers are scoped to their feature directory; use a full path when ambiguous. For legacy GitHub numbers, consult `.scratch/README.md` first. Migrated tickets are authoritative locally; use GitHub only to retrieve historical references or tickets that have not been migrated.

## GitHub migration

The 2026-09-10 migration copied #62 and the delivery map #23 with all 37 direct children (#24–#60), including closed history. The index at `.scratch/README.md` maps GitHub numbers to local paths. Issue #23 lives at `.scratch/jbsa-1-0/map.md`; its children live in that effort's `issues/` directory. Original bodies, comments, metadata, child order, and native blockers are preserved in local Markdown and adjacent JSON snapshots.

Original GitHub states remain unchanged; migration does not mean implementation is complete. New issues and updates to migrated tickets belong in the local tracker. Historical planning issues #1 and #17 remain linked on GitHub.

## Implementation triage and dependencies

- Assign `ready-for-agent` or `ready-for-human` only when the ticket is specified and every prerequisite is closed. Record the intended owner separately while blocked; use `needs-triage` until readiness can be reassessed and explain why in `Triage rationale:`.
- Use `Status: none` and `Labels: none` for closed history and parent maps with no actionable triage role. This is absence of a label, not a sixth canonical role.
- Implementation `Blocked by:` links include completed prerequisites to preserve history. Read the linked local files and use their current `State:` values; all must be `closed` before starting. For unmigrated references, fetch the source state.
- After closing a ticket, update the index and reassess children that depend on it. Confirm acceptance criteria remain current before applying a ready label. Select only open, ready, unassigned, unblocked child tickets; parent maps are navigation records.
- Source snapshots and original issue bodies are historical evidence. Current local metadata and dependency links govern execution when they differ from original GitHub workflow wording.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
