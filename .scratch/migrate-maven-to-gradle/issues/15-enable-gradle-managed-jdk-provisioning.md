# 15: Evaluate Gradle-managed JDK provisioning after cutover

**What to build:** Decide whether developer builds should provision the pinned Java toolchain through Gradle after the migration is complete.

Blocked by: [14: Remove Maven and verify the atomic cutover](14-remove-maven-atomic-cutover.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: User-requested post-migration follow-up; intentionally unprioritized and blocked until the authoritative cutover is complete.

## Acceptance criteria

- [ ] Evaluate a pinned Gradle toolchain-resolver/provider design without weakening vendor, version, provenance, checksum, or offline qualification requirements.
- [ ] Keep qualification capable of binding an exact reviewed JDK distribution independently of mutable remote resolution.
- [ ] Isolate resolver/plugin additions and verify their versions, artifacts, repositories, locks, and checksums.
- [ ] Measure developer experience and cache behavior, then rerun reproducibility, build-policy, offline, and configuration-cache gates affected by the change.
- [ ] Update the post-cutover specification and ADR only if the evidence supports enabling provisioning; retain an explicit installed-JDK path otherwise.

## Context

Ticket 10 intentionally retained `org.gradle.java.installations.auto-download=false` while proving
same-JDK Maven/Gradle parity and offline operation. This follow-up must not reinterpret that evidence
or become a hidden network dependency for release qualification.
