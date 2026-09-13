# 16: Measure and optimize the qualification harness

**What to build:** Reduce qualification turnaround without weakening evidence or assuming a language rewrite is automatically beneficial.

Blocked by: [14: Remove Maven and verify the atomic cutover](14-remove-maven-atomic-cutover.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: User-requested post-migration follow-up; intentionally unprioritized and blocked until migration-only Maven work can be removed.

## Acceptance criteria

- [ ] Profile wall time by Gradle/Maven build, conformance task, PowerShell process, Python inspector, JDK tool launch, and duplicated gate execution before selecting changes.
- [ ] Add resumable, digest-bound phase checkpoints so harness-only failures do not force completed policy or full-suite work to rerun.
- [ ] Batch `javap` inspection and reuse compiled modular test fixtures where measurements show process startup dominates.
- [ ] Move pure comparison/policy logic to Kotlin or Java only when measured maintenance or runtime gains justify the port; keep operator orchestration thin.
- [ ] Preserve exact-JDK, clean-worktree, strict offline, reproducibility, negative-policy, configuration-cache, gate, CLI, and timing evidence semantics.
- [ ] Demonstrate the optimized harness on representative non-release evidence without rerunning expensive suites unnecessarily.

## Context

Ticket 10 showed that repeated full builds and conformance/policy gates dominate runtime, while the
current PowerShell and Python layers also lack resumable checkpoints. Measure first; do not combine
this work with JDK provisioning or weaken qualification to improve elapsed time.
