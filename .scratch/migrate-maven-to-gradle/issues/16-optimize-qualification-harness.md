# 16: Measure and optimize the qualification harness

**What to build:** Reduce qualification turnaround without weakening evidence or assuming a language rewrite is automatically beneficial.

Blocked by: None
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and measured; no further work remains in this ticket.

## Acceptance criteria

- [x] Profile wall time by Gradle/Maven build, conformance task, PowerShell process, Python inspector, JDK tool launch, and duplicated gate execution before selecting changes.
- [x] Add resumable, digest-bound phase checkpoints so harness-only failures do not force completed policy or full-suite work to rerun.
- [x] Batch `javap` inspection and reuse compiled modular test fixtures where measurements show process startup dominates.
- [x] Move pure comparison/policy logic to Kotlin or Java only when measured maintenance or runtime gains justify the port; keep operator orchestration thin.
- [x] Preserve exact-JDK, clean-worktree, strict offline, reproducibility, negative-policy, configuration-cache, gate, CLI, and timing evidence semantics.
- [x] Demonstrate the optimized harness on representative non-release evidence without rerunning expensive suites unnecessarily.

## Context

Ticket 10 showed that repeated full builds and conformance/policy gates dominate runtime, while the
current PowerShell and Python layers also lack resumable checkpoints. Measure first; do not combine
this work with JDK provisioning or weaken qualification to improve elapsed time.

## Comments

### 2026-09-13 — Implemented

Recorded the measure-first profile in ../qualification-harness-profile.md. The optimized harness
retains a deterministic pending attempt and digest-bound command, log, and output checkpoints;
-Resume reuses the exact JDK extraction, detached worktree, and verified Gradle cache after
revalidating all source and evidence identities. Named gate evidence is derived from the successful
strict-offline full-suite closure, with architecture and policy included explicitly, so the same
test selections are not launched again.

The artifact inspector batches javap by modular JAR and profiles nested JDK launches separately.
Its byte-identical control fixture is reused instead of compiled again; the nine-test inspector
suite improved from 26.656 seconds to 17.889 seconds on the profiling workstation. The isolated
qualification regression demonstrated a late timing-setup failure, rejected changed
input/JDK/log/output bytes, then resumed without rerunning the completed full suite. One final
Gradle clean verify passed in 3m10s with all 62 tasks successful. The CONTRIBUTING-required Maven
clean verify was also run once; it reproduced the migration baseline's expected policy failure
because Maven clean removes the Gradle-owned build-layout and resolved-dependency evidence before
the shared policy tests execute.
