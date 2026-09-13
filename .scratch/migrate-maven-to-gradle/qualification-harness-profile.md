# Qualification harness profile

Measured on 2026-09-13 before selecting ticket 16 changes. This is non-release engineering
evidence: it explains harness optimization decisions but does not replace parity, conformance,
reproducibility, or Release Qualification evidence.

## Retained same-revision evidence

The retained ticket-10 attempt under target/qualification-evidence/236202b used revision
236202b8203d35340fe364c669cf18be07a054e6 and the pinned Temurin 25.0.4+7 archive. Gradle and
Maven durations below come from their retained command logs; repeated Gradle ranges cover the five
pending attempts retained after orchestration failures.

| Surface | Observed wall time | Evidence |
| --- | ---: | --- |
| Maven clean verify | 4m25s | maven/logs/clean-verify.log |
| Gradle cache-prime clean verify spotlessCheck | 4m44s–4m54s | gradle.pending-*/logs/prime-verified-cache.log |
| Gradle strict-offline clean verify spotlessCheck | 3m42s–3m49s | gradle.pending-*/logs/verified-cache-offline-build.log |
| Gradle build-logic prime | 55s–59s | gradle.pending-*/logs/prime-build-logic-cache.log |
| Gradle build-logic offline | 50s–51s | gradle.pending-*/logs/verified-cache-offline-build-logic.log |
| Separate policy gate | 2m29s–2m32s | gradle.pending-*/logs/gate-policy.log |
| Separate conformance-harness gate | 1m49s–1m55s | gradle.pending-*/logs/gate-conformance.log |
| Two clean reproducibility builds | 14s + 12s | gradle.pending-*/logs/gradle-reproducibility.log |
| Explicit CLI restaging after clean builds | 12s | gradle.pending-*/logs/restage-cli-inputs.log |

The prime and offline builds already execute the complete verify closure. Reproducibility then
cleaned those outputs before seven named gate commands were launched. This made policy and
conformance execute again and caused CLI restaging. Five retained attempts repeat the two full
builds, showing that a late harness failure could discard more than nine minutes of completed work
even before repeated gates.

## Process and tool startup profile

Ten-launch samples on the same Windows workstation used no shell reparsing and discarded output.
The figures are raw observations, not portable performance thresholds.

| Process | Median | Range | Ten-launch total |
| --- | ---: | ---: | ---: |
| pwsh -NoProfile no-op | 152.155ms | 149.591–181.063ms | 1,547.925ms |
| Python no-op | 48.763ms | 25.015–69.891ms | 420.092ms |
| javap -version | 134.053ms | 84.079–215.950ms | 1,401.116ms |
| jar --version | 106.099ms | 60.560–124.759ms | 994.345ms |

The real artifact snapshot contains eight library and eight CLI classes. The prior inspector
therefore launched two jar processes and sixteen javap processes, and adjacent retained log
timestamps put inspection at roughly 31–35 seconds. By contrast, the pure Python normalized
comparison completed between adjacent retained timestamps in under one second. The inspector test
suite took 26.656 seconds before fixture reuse on this workstation.

## Selected changes

1. Retain a deterministic pending attempt and its exact JDK extraction, detached worktree, and
   initially empty Gradle home after failure. -Resume revalidates all ordinary preflight checks,
   the global source/protocol/Maven-evidence/JDK/harness/inspector digest, checkpoint-file hashes,
   and log hashes before reuse.
2. Add the architecture and policy selections to the strict-offline full-suite command. Derive the
   seven named gate outcomes from that successful exact task closure instead of launching the same
   test selections again. Observe the staged CLI before the separate two-clean-build reproducibility
   proof removes its outputs.
3. Batch up to 64 classes per javap process. The representative 16-class snapshot drops from
   sixteen javap launches to two while retaining ordered per-class signatures.
4. Compile two modular inspector fixtures and copy the byte-identical control instead of compiling
   it again. The complete nine-test inspector suite subsequently measured 17.889 seconds.
5. Keep PowerShell orchestration and Python comparison policy in their existing languages. The
   measured pure-comparison/process cost is immaterial beside build and JDK startup, so a Kotlin or
   Java port would add maintenance risk without a demonstrated runtime gain.

Timing clean/cold/warm remains an atomic non-resumable sequence. Exact-JDK checks, caller and
isolated-worktree cleanliness, strict offline operation, the five-artifact two-clean-build proof,
negative checksum policy, configuration-cache stored/reused/rejected semantics, gate and CLI
observations, and raw timing records are unchanged.

## Representative demonstration

build/test-gradle-qualification.ps1 uses an isolated committed fixture and injects a one-time
late restaging failure. It verifies that a modified Maven evidence input and a modified checkpoint
log both fail closed, restores those bytes, resumes the valid digest-bound attempt, and proves the
offline full-suite checkpoint was reused. The final fixture report retains all prior policy,
offline, reproducibility, configuration-cache, gate, CLI, JDK-profile, and timing assertions.
