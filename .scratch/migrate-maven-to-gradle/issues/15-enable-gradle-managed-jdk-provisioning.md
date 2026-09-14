# 15: Evaluate Gradle-managed JDK provisioning after cutover

**What to build:** Decide whether developer builds should provision the pinned Java toolchain through Gradle after the migration is complete.

Blocked by: [14: Remove Maven and verify the atomic cutover](14-remove-maven-atomic-cutover.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and closed; managed developer provisioning is enabled while exact Windows and Linux/WSL qualification archives remain independently pinned and verified.

## Acceptance criteria

- [x] Evaluate a pinned Gradle toolchain-resolver/provider design without weakening vendor, version, provenance, checksum, or offline qualification requirements.
- [x] Keep qualification capable of binding an exact reviewed JDK distribution independently of mutable remote resolution.
- [x] Isolate resolver/plugin additions and verify their versions, artifacts, repositories, locks, and checksums.
- [x] Measure developer experience and cache behavior, then rerun reproducibility, build-policy, offline, and configuration-cache gates affected by the change.
- [x] Update the post-cutover specification and ADR only if the evidence supports enabling provisioning; retain an explicit installed-JDK path otherwise.

## Context

Ticket 10 intentionally retained `org.gradle.java.installations.auto-download=false` while proving
same-JDK Maven/Gradle parity and offline operation. This follow-up must not reinterpret that evidence
or become a hidden network dependency for release qualification.

## Comments

### 2026-09-13 — Managed developer provisioning enabled

The [provisioning evaluation](../jdk-provisioning-evaluation.md) tested the pinned Foojay `1.0.0`
settings plugin in an isolated cache, verified its marker, implementation JAR, and module metadata,
and measured cold, warm, offline, and configuration-cache behavior. Both the product and included
builds now request an Adoptium Java 25 toolchain and can run Gradle on Java 17 or newer.

The accepted toolchain is Temurin `25.0.4.1+1`. Qualification does not trust the resolver's mutable
selection: Windows and Linux/WSL use separate exact platform archives whose official SHA-256 values
are recorded in the evaluation and must be checked before extraction. Historical parity and
acceptance records remain unchanged because they describe the prior JDK that produced those results.

The resolver adds only the Gradle Plugin Portal already allowed by repository policy. Plugin
artifacts are covered by strict verification metadata; settings plugin version pinning remains
explicit because Gradle emits no dependency lock entry for it. A primed cache works offline, while a
fresh Windows or WSL cache still requires network access. Focused policy, cross-platform,
offline, configuration-cache, reproducibility, and complete verification results are recorded in
the evaluation.
