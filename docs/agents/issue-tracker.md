# Issue tracker: GitHub Issues

Issues and specs for this repo live in GitHub Issues on [`evildarkarchon/jbsa`](https://github.com/evildarkarchon/jbsa/issues). Use the `gh` CLI for every tracker operation.

## Conventions

- A feature with more than one ticket gets a **parent issue** that holds the spec. Its implementation tickets are GitHub **sub-issues** of that parent, one ticket per issue. Never combine several tickets in one issue.
- Title child tickets with a short feature prefix and a sequence number so their order is visible in lists, for example `Pack Pipeline 01: …`.
- Triage state is a GitHub label (see `triage-labels.md` for the role strings). Lifecycle is the issue's open/closed state; close completed work with `--reason completed` and a comment recording the outcome.
- Comments and conversation history are issue comments.
- Record prerequisites as a `Blocked by: #N, #N` line near the top of the issue body, and mirror them with GitHub's native issue dependencies when available. A ticket is unblocked when every listed issue is closed.

## When a skill says "publish to the issue tracker"

Create a GitHub issue with `gh issue create`. For a child ticket, create it and then attach it to its parent as a sub-issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`. The user will normally pass the issue number or URL directly.

## Historical local tracker

Before this switch, tickets lived as Markdown under `.scratch/`. That directory is now **read-only history**: the 2026-09-10 migration of GitHub #23–#62 and the local efforts that followed. `.scratch/README.md` maps legacy GitHub numbers to local paths.

- Do not create new tickets under `.scratch/` or update the files there.
- A local ticket that is still `State: open` is frozen. To resume that work, open a GitHub issue that links the local file and carries the current scope. The GitHub issue is authoritative from then on.
- Original GitHub states for migrated issues were never changed by the migration and still do not imply completion.

## Implementation triage and dependencies

- Apply `ready-for-agent` or `ready-for-human` only when the ticket is specified and every prerequisite is closed. While a ticket is blocked, use `needs-triage` and explain why in a `Triage rationale:` line in the body.
- Parent spec issues and closed history carry no triage label.
- After closing a ticket, reassess the sub-issues and dependents it unblocks. Confirm their acceptance criteria are still current before applying a ready label. Select only open, ready, unassigned, unblocked tickets; parent issues are navigation records.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a parent issue with one **child** sub-issue per ticket.

- **Map**: a parent issue labelled `wayfinder:map` whose body holds the Notes / Decisions-so-far / Fog sections.
- **Child ticket**: a sub-issue of the map with the question in the body, labelled with its type: `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling` or `wayfinder:task`.
- **Blocking**: a `Blocked by: #N, #N` line near the top. A ticket is unblocked when every issue it lists is closed.
- **Frontier**: open, unblocked, unassigned sub-issues of the map; the lowest issue number wins.
- **Claim**: assign the issue to yourself before any work.
- **Resolve**: post the answer as a comment under an `## Answer` heading, close the issue as completed, then append a context pointer (gist + link) to the map's Decisions-so-far section.
