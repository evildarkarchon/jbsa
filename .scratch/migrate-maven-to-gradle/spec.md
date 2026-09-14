# Migrate the Maven build to Gradle with Kotlin DSL

Status: ready-for-agent
State: open
Assignees: none
Blocked by: none
Triage rationale: The build inventory, migration boundaries, compatibility contracts, testing seams, cutover policy, and evidence requirements were resolved through a completed grilling session.

## Problem Statement

JBSA's build policy is currently encoded as a Maven reactor. Maven-specific structure is normative in the build specification, enforced by integration tests, invoked by CI and PowerShell automation, and embedded in publication, compliance, reproducibility, and staging assumptions. This makes the desired move to Kotlin-based Gradle build logic much broader than translating dependency declarations: an incomplete migration could compile successfully while changing consumer metadata, JPMS behavior, release inputs, dependency integrity, or qualification evidence.

The project needs one authoritative Gradle build that preserves the behavior and outputs already relied upon by library consumers, CLI operators, maintainers, compliance reviewers, and Release Qualification procedures. It must not combine build migration with production-language conversion, module redesign, dependency upgrades, unfinished distribution work, or other unrelated changes that would make parity failures ambiguous.

## Solution

Replace the Maven reactor atomically with a pinned Gradle 9.7.1 multi-project build expressed in Kotlin DSL. Preserve the six existing project roles, Java 25 production sources, Maven coordinates, JPMS identities, canonical artifacts, consumer POM semantics, deterministic build environment, compliance controls, stable CI gate names, release staging, and public command-line behavior.

Develop and validate Gradle alongside Maven on one migration branch. Use Maven as a temporary parity oracle only for behavior it currently implements, while treating the normative specifications and conformance tests as the higher authority. Generate deterministic build-layout and resolved-dependency manifests so policy automation consumes declared Gradle model data instead of parsing executable Kotlin DSL. Cut over only after semantic parity, reproducibility, offline dependency verification, Windows qualification, Linux portability, documentation migration, and active-reference scanning all pass; then remove every active Maven build file and wrapper without retaining a compatibility shim.

## User Stories

1. As a maintainer, I want one authoritative Gradle build, so that Maven and Gradle cannot drift into conflicting definitions of the release.
2. As a build maintainer, I want build logic written in Kotlin DSL, so that shared policy is typed, navigable, and testable.
3. As a Java contributor, I want production sources to remain Java during the build migration, so that language-conversion defects cannot be confused with build defects.
4. As a maintainer, I want the existing six project roles preserved, so that the migration does not silently redesign the repository architecture.
5. As a library consumer, I want the library's group, artifact, and version coordinates preserved, so that dependency declarations do not change.
6. As a modular Java consumer, I want JPMS module names, exports, requirements, and openness preserved, so that module-path integration remains compatible.
7. As a class-path consumer, I want the explicit modular JARs to remain usable on the class path, so that the migration does not narrow supported consumption modes.
8. As a library consumer, I want the generated consumer POM to remain parent-free and semantically equivalent, so that transitive dependency behavior does not change.
9. As a library consumer, I want the binary, sources, and Javadoc artifacts to remain available under the same names, so that development and documentation workflows remain intact.
10. As a CLI operator, I want the thin CLI JAR and its entry point to remain compatible, so that build migration does not alter command-line behavior.
11. As a release operator, I want the canonical release-input set to remain unchanged, so that unqualified artifacts cannot enter a release.
12. As a release operator, I want external runtime dependencies copied with the same names and bytes, so that staged distributions remain auditable.
13. As a benchmark maintainer, I want the standalone benchmark JAR preserved as a build-only artifact, so that benchmarks remain runnable without becoming release content.
14. As a compliance reviewer, I want build-only projects and test dependencies excluded from the production SBOM, so that the SBOM describes shipped software.
15. As a compliance reviewer, I want the existing licensing inventory to remain authoritative, so that Gradle metadata does not weaken provenance review.
16. As a security-conscious contributor, I want dependency and plugin artifacts verified by SHA-256, so that repository content cannot change undetected.
17. As a contributor, I want resolved dependency versions locked, so that builds remain stable across machines and time.
18. As a maintainer, I want dynamic, changing, conflicting, and release-time snapshot dependencies rejected, so that releases cannot resolve unpredictably.
19. As a qualification operator, I want a verified-cache offline build to pass, so that every build input is known before evidence is produced.
20. As a maintainer, I want candidate versions supplied explicitly to every project, so that all artifacts in one build share an identical version.
21. As a contributor, I want the default snapshot version declared once, so that local builds require no manual version setup.
22. As a qualification operator, I want Java tasks to use Java 25 even when Gradle bootstraps on Java 17 or newer, and qualification to use the exact pinned Temurin release, so that byte-producing evidence has a known toolchain identity.
23. As a contributor, I want Java compilation to retain release level, parameter metadata, encoding, and lint behavior, so that class files and diagnostics remain compatible.
24. As a test maintainer, I want unit and integration tests executed separately without relocating existing sources, so that migration churn stays focused.
25. As a test maintainer, I want test class ordering, locale, time zone, encoding, and fork behavior deterministic, so that observations are repeatable.
26. As a native-runtime maintainer, I want task-specific native-access arguments preserved, so that LWJGL and conformance tests continue to execute correctly.
27. As an architecture reviewer, I want existing black-box module and signature tests to remain authoritative, so that Gradle configuration cannot conceal a broken artifact.
28. As a build-policy reviewer, I want Maven-specific structural assertions replaced by equally strong Gradle assertions, so that the migration changes the subject rather than weakening the gate.
29. As a maintainer, I want the canonical complete command to remain `clean verify`, so that the new workflow retains a familiar single entry point.
30. As a CI maintainer, I want the public compile, unit, architecture, formatting, policy, and conformance gate names preserved, so that hosted workflows and evidence remain comparable.
31. As a Linux contributor, I want ordinary compilation, formatting, unit tests, and integration tests to run on Linux, so that platform-neutral work is not unnecessarily Windows-bound.
32. As a Release Qualification operator, I want Conformance Oracle, native packaging, Windows filesystem, and official-tool procedures to remain explicitly Windows-only, so that portability claims stay accurate.
33. As a CI maintainer, I want expected conformance evidence outcomes distinguished from infrastructure failures, so that evidence is retained without masking broken automation.
34. As a contributor, I want formatting behavior and Google Java Format version preserved, so that build migration does not create unrelated source churn.
35. As a maintainer, I want a deterministic aggregate SBOM with the existing logical root identity, so that compliance evidence remains comparable.
36. As a compliance reviewer, I want a deterministic resolved-production-dependency manifest, so that policy can audit Gradle's actual model without parsing Kotlin code.
37. As an automation maintainer, I want a deterministic build-layout manifest, so that scripts can consume declared artifact locations and later stop hard-coding them.
38. As an automation maintainer, I want mature regression-tested PowerShell behavior preserved, so that a build migration does not rewrite unrelated operational algorithms.
39. As a build-logic maintainer, I want Kotlin tasks to declare inputs, outputs, dependencies, and external-process contracts, so that Gradle can reason about orchestration safely.
40. As a contributor, I want generated output kept out of the tracked automation directory, so that `clean` cannot delete repository sources.
41. As an automation maintainer, I want the existing Maven-compatible target layout retained during migration, so that evidence scripts do not all change at once.
42. As a release operator, I want pre-staging compliance, staging, and post-staging audit ordered explicitly, so that incomplete release inputs cannot pass verification.
43. As a release operator, I want the fixed archive timestamp preserved, so that changing reproducibility policy cannot obscure migration parity.
44. As a maintainer, I want two clean Gradle builds to produce byte-identical canonical artifacts, so that the replacement build is independently reproducible.
45. As a migration reviewer, I want Maven and Gradle outputs compared semantically at one source revision and toolchain, so that any compatibility difference is visible before cutover.
46. As a migration reviewer, I want JAR entry payloads, consumer metadata, runtime hashes, SBOM relationships, staging manifests, CLI behavior, and gate outcomes compared, so that compilation alone cannot qualify the migration.
47. As a maintainer, I want unrelated dependencies, Java behavior, formatting, and archive functionality frozen, so that review remains attributable to the build migration.
48. As a maintainer, I want current specifications and operational documentation updated to Gradle, so that future work follows the authoritative build.
49. As a project historian, I want imported issues, comments, research, and existing evidence preserved, so that the repository retains an honest record of the Maven-era implementation.
50. As a future agent, I want active Maven references rejected outside a reviewed historical allowlist, so that stale commands cannot re-enter current instructions.
51. As an issue-tracker user, I want valid consumer-POM requirements and references to Maven Central preserved, so that a terminology sweep does not corrupt accurate requirements.
52. As a local developer, I want Gradle's daemon available for interactive work, so that normal feedback remains responsive.
53. As an evidence reviewer, I want CI and qualification to run without a persistent daemon, so that process state cannot leak between evidence-producing builds.
54. As a CI maintainer, I want checksum-verified distributions and dependencies cached without sharing task outputs, so that CI remains efficient without introducing remote build-result trust.
55. As a privacy-conscious maintainer, I want no automatic build scans, telemetry, or external build service, so that migration does not add undisclosed data flows.
56. As a build maintainer, I want configuration cache and parallel execution deferred until proven safe, so that optimization does not alter task ordering or evidence.
57. As a maintainer, I want cold and warm build performance measured honestly, so that Gradle speed claims are evidence-backed.
58. As a release operator, I want build, dependency, SBOM, staging, reproducibility, Automated Conformance, and Release Qualification evidence regenerated, so that Maven-era evidence cannot qualify Gradle-produced artifacts.
59. As a project historian, I want existing evidence retained unchanged, so that regeneration does not overwrite the basis of earlier decisions.
60. As a maintainer, I want Maven removed completely at cutover, so that no hidden fallback becomes a second authoritative build.
61. As a maintainer, I want the cutover represented by a coherent revertible Git change, so that rollback does not require reconstructing stale Maven files selectively.
62. As a future build maintainer, I want Gradle and plugin upgrades isolated and checksum-reviewed, so that toolchain changes remain independently auditable.
63. As a release operator, I want Gradle to generate publication artifacts without enabling remote publication, so that GitHub Release remains the approved delivery channel.
64. As a maintainer, I want the future self-contained application image excluded from this migration, so that currently unimplemented packaging is not smuggled into parity work.

## Implementation Decisions

- The migration replaces Maven with Gradle 9.7.1 and Kotlin DSL; production and test implementation sources remain Java.
- The Gradle binary distribution and wrapper JAR are pinned to their official SHA-256 checksums and committed wrapper files are policy-tested.
- The build remains a single-version, six-project multi-project build with logical root identity `jbsa-parent`.
- The six project names, roles, Maven coordinates, artifact names, JPMS identities, and consumer-visible contracts remain unchanged.
- Substantial shared policy lives in Kotlin convention plugins in an included build; trivial aggregation remains at the root.
- `jbsa` uses Java library and Maven publication capabilities and is the only project that produces the public library publication set.
- `jbsa-cli` uses Java application conventions for execution but disables incidental application archives and launcher distributions.
- `jbsa-test-support`, `jbsa-conformance-tests`, and `jbsa-benchmarks` remain build-only and are neither installed nor published.
- `jbsa-dist` remains a non-Java staging and audit project; implementing the future application image is not part of the migration.
- The unclassified LWJGL core and LZ4 artifacts remain compile-scope consumer dependencies by using Gradle API semantics; Windows native classifiers remain runtime-only.
- JMH dependencies and annotation processing remain explicit. A pinned Shadow plugin produces the existing build-only standalone benchmark artifact, strips module descriptors, merges service metadata, and retains the JMH entry point.
- The build uses pinned Spotless, CycloneDX, and Shadow plugins plus Gradle's core Java, application, base, and Maven publication capabilities.
- External dependency versions and build-plugin versions are centralized and pinned. Project dependencies are resolved only from Maven Central and plugins only from the Gradle Plugin Portal.
- Individual projects cannot declare additional repositories. Adding a repository is a separate policy decision.
- Every resolvable configuration is locked. Dynamic and changing modules are rejected, version conflicts fail, and a release version rejects snapshot dependencies.
- Strict Gradle dependency verification covers plugins, dependencies, metadata, transitive artifacts, sources, and Javadocs needed by supported tasks using reviewed SHA-256 values.
- The existing compliance inventory remains the authority for licensing, redistribution, and approved production dependencies.
- Gradle emits deterministic, schema-versioned internal manifests for build layout and resolved production dependencies. These manifests are not release assets or public API.
- The dependency manifest records source project, usage or configuration, requested and resolved coordinates, classifier or selected variant, and artifact SHA-256.
- The version defaults to `0.1.0-SNAPSHOT`, can be overridden explicitly with `-Pversion=<candidate>`, is validated once, and is identical across every project.
- Developer builds request an Adoptium Java 25 toolchain and use the pinned Foojay resolver to provision it when no matching installation exists. Windows and Linux/WSL qualification and reproducibility independently bind the exact Temurin `25.0.4.1+1` platform archive and its reviewed SHA-256 rather than trusting mutable remote resolution.
- Java compilation retains `--release 25`, parameter metadata, UTF-8, and all lint diagnostics.
- Normal compilation and tests use Gradle module-path inference. Exceptional classpath or native launches retain explicit arguments and remain covered by black-box tests.
- Existing test source directories remain intact. Unit and integration classes compile once and execute through separate tasks selected by their established naming conventions.
- Tests use one fork, deterministic class-name ordering, UTC, US locale, UTF-8, and the existing per-task native-access settings.
- Test reports use Gradle-native XML and HTML layouts beneath each project's generated-output root; Maven report-directory terminology is not retained.
- Every application or operational artifact keeps its Maven-compatible `target` location during migration. Generated root output cannot use the tracked automation directory.
- The fixed archive timestamp remains `2026-09-03T00:00:00Z`. Maven-to-Gradle comparison ignores archive-envelope differences, but two clean Gradle builds must reproduce complete canonical artifact bytes.
- The generated consumer POM remains parent-free and preserves project metadata and exact dependency semantics. Remote publication tasks are disabled or disconnected from all normal and release gates.
- The aggregate CycloneDX document remains version 1.6, deterministic, without a serial number, rooted at the existing logical parent component, and limited to production components.
- Mature PowerShell conformance, compliance, staging, and operational algorithms remain in place where behavior is regression-tested.
- Kotlin task classes replace Maven invocation, POM-source parsing, and lifecycle coupling while declaring all process inputs, outputs, and ordering.
- The canonical complete local command is `gradlew clean verify`. Standard `assemble`, `check`, and `build` retain narrower Gradle meanings.
- Verification explicitly builds canonical artifacts, generates publication and compliance inputs, runs pre-staging compliance, stages release inputs, and runs the post-staging audit.
- Existing CI gate names—compile, unit, architecture, formatting, policy, and conformance—remain the stable external interface while their implementations invoke explicit Gradle tasks.
- Windows remains authoritative for hosted qualification. Linux runs compilation, formatting, unit tests, and ordinary integration tests as a portability gate.
- CI may cache checksum-verified Gradle distributions and dependency artifacts through a commit-SHA-pinned action. Remote task-output caches, Develocity, automatic build scans, and telemetry are prohibited.
- Local development may use the Gradle daemon. CI, reproducibility, parity, and qualification use `--no-daemon`.
- Configuration cache, parallel execution, and remote build caching remain disabled during parity. Local caching may be considered only after correctness and performance evidence exists.
- Migration occurs on one branch as small reviewable commits. Maven and Gradle coexist only on that branch and only until parity is established.
- The normative specification explicitly adopts the pinned Gradle multi-project build rather than making the build tool abstract.
- Active requirements, requirement bindings, developer documentation, CI commands, operational procedures, and actionable local-issue instructions move to Gradle before cutover.
- Imported issue bodies, comments, canonical historical ticket names, research, observations, and prior evidence remain unchanged. Consumer-POM requirements and the name Maven Central remain where factually correct.
- The cutover removes active POMs, the Maven wrapper, and Maven-only configuration without compatibility shims or a hidden fallback.
- An automated repository scan rejects active Maven build instructions outside a reviewed historical allowlist.
- Rollback means reverting the coherent cutover. The project does not selectively restore Maven alongside Gradle.
- Gradle and plugin upgrades remain manual, isolated, checksum-verified changes that rerun the complete qualification gates.
- Build speed is measured on comparable cold and warm runs but is not claimed or used to waive correctness requirements.

## Testing Decisions

- The primary acceptance seam is the real six-project build invoked through `gradlew clean verify`. This is the highest seam at which task wiring, artifacts, policy, compliance, staging, and failure propagation can be observed together.
- The second system seam is the existing CI-gate launcher with its stable compile, unit, architecture, formatting, policy, and conformance names. Tests assert each gate invokes the intended Gradle lifecycle and preserves its established result and evidence semantics.
- Good migration tests observe produced artifacts, published metadata, process results, reports, staged files, and failure behavior. They do not assert incidental Kotlin DSL structure when the same contract can be tested through the resulting build model or bytes.
- Maven and Gradle run at one pinned source revision, candidate version, and qualification JDK during parity. Maven is not retained as a permanent test dependency.
- The parity harness compares JAR entry sets and payload bytes, JPMS descriptors, public signatures, source and Javadoc payloads, normalized consumer POMs, runtime dependency filenames and hashes, normalized SBOM graphs, notices, staged manifests, CLI launch behavior, and gate outcomes.
- Complete archive-byte identity between Maven and Gradle is not required because archive-envelope implementation details may differ. Complete byte identity between two clean Gradle builds is required for the canonical consumer POM, library binary, sources, Javadocs, and thin CLI artifact set.
- Existing `ModuleArchitectureIT` behavior remains the prior art for verifying descriptors, exports, requirements, absence of leaked third-party/internal signatures, and class-path usability of modular JARs.
- Existing `BuildPolicyIT` behavior remains the prior art for project topology, wrapper identity, dependency policy, consumer POM semantics, Java class version, sources/Javadocs, timestamps, and CI trigger policy. Maven-source assertions are replaced with Gradle model and black-box artifact assertions.
- Existing `CompliancePolicyIT` and compliance-script regression tests remain the prior art for production-only inventory, SBOM reconciliation, artifact-byte identity, notice generation, and post-staging audit ordering.
- Existing `ReproducibilityPolicyIT` and the two-build reproducibility script remain the prior art for deterministic public artifacts. Their Maven invocation and source-version parsing are replaced without weakening the compared artifact set.
- Existing staging regression tests remain the prior art for complete-input success, exact copying, deterministic manifests, stale-file removal, checksum rejection without damaging prior staging, blocked-dependency rejection, and missing-input failure.
- Existing conformance harness tests and Archive Family test tags remain authoritative for Automated Conformance. The migration does not redefine Conformance Cases or treat a successful compile as compatibility evidence.
- The library and CLI test tasks receive built JAR paths through Gradle-provided properties, and integration tasks depend explicitly on those JAR-producing tasks.
- Unit and integration selection is tested so `*Test` and `*IT` execute exactly once in the intended lifecycle. JUnit tags continue to select architecture, build-policy, conformance-harness, and performance gates.
- Test environment assertions cover Java 25 class versions, module-path behavior, native access, deterministic class ordering, locale, time zone, encoding, and one-fork execution.
- The generated consumer POM is parsed as a consumer would parse it. Tests require no parent and exact project metadata, dependency coordinates, scopes, classifiers, and absence of false optionality or exclusions.
- Dependency-policy tests cover repository restrictions, version conflicts, dynamic/changing rejection, snapshot rejection for releases, project-wide group/version equality, lock consistency, strict verification failures, and plugin-version pinning.
- The initial dependency-verification metadata is reviewed against the existing resolved Maven graph, compliance inventory, and independent upstream checksums. A primed-cache offline qualification build proves no undeclared network input remains.
- Build-layout and resolved-dependency manifests have schema tests, deterministic-order tests, path-containment tests, and consumer tests in the existing scripts that use them.
- The clean lifecycle is tested with a sentinel and digest inside the tracked automation tree. It must remove owned generated output while leaving the tracked tree byte-for-byte unchanged.
- Convention-plugin policy and custom task types receive focused unit tests for pure logic and Gradle TestKit functional tests for application, defaulting, overrides, task graphs, generated metadata, and representative failure cases.
- Negative TestKit fixtures require invalid versions, inconsistent project identity, dynamic or changing dependencies, release-time snapshots, dependency conflicts, missing locks, project-local repositories, and unpinned plugins to fail with actionable diagnostics.
- TestKit tests supplement rather than replace the real multi-project build. The real build remains the authority for plugin interaction and cross-project ordering.
- The benchmark standalone JAR is inspected for service merging, main-class metadata, absence of module descriptors, required benchmark classes, and exclusion from publication, staging, and production SBOMs.
- Publication tests prove that no remote repository or reachable remote publication action is configured and that incidental application distributions cannot enter the canonical artifact set.
- The CLI is smoke-tested in thin-JAR form with the preserved module/native arguments. The future self-contained application image is not required for migration acceptance.
- Windows CI runs the complete qualification gates with the pinned Temurin build. Linux CI runs the agreed portable subset and fails on platform-neutral behavior that remains Windows-coupled.
- Both checked-in wrapper scripts must launch on their supported platform, and policy tests verify their line endings, wrapper-JAR checksum, distribution URL, and distribution checksum.
- CI caching tests and policy assertions distinguish dependency/distribution caching from prohibited remote task-output caching and telemetry.
- Evidence-producing tasks run without persistent daemons. Tests ensure expected conformance evidence outcomes remain distinguishable from infrastructure errors and that evidence is published before the final gate result.
- Intended cache-compatible tasks are exercised twice with configuration-cache checking. Evidence, staging, or external-process tasks that cannot yet be compatible must reject that mode with a tested, explicit diagnostic rather than silently misbehave.
- An active-reference test scans specifications, current documentation, automation, CI, tests, and local issue files for Maven build instructions. Its reviewed allowlist covers imported historical bodies/comments, historical issue identities, factual consumer-POM requirements, and Maven Central as a repository proper name.
- Local issue auditing confirms all current actionable build instructions use Gradle. Historical text is not rewritten solely to remove Maven terminology.
- Cutover acceptance requires the parity report, Windows complete gate, Linux portability gate, verified offline build, two-build reproducibility, build-policy and module tests, compliance and staging audits, conformance gates, documentation migration, and active-reference scan to pass together.
- Performance evidence records comparable cold and warm runs on the same machine. It supports honest reporting but does not substitute for any correctness gate.
- Migration-only Maven comparison code is removed after acceptance. Reusable artifact inspectors, Gradle-only reproducibility coverage, and the final normalized parity report remain.

## Out of Scope

- Converting production or test implementation code from Java to Kotlin.
- Changing Archive Family behavior, public archive-library interfaces, BSArch-compatible CLI semantics, or compatibility profiles.
- Redesigning project or JPMS module boundaries.
- Upgrading dependencies, formatting tools, JUnit, JMH, Java, codecs, or native providers unless required for proven Gradle compatibility.
- Implementing the specified future `jlink`/`jpackage` self-contained Windows application image or its ZIP.
- Publishing to Maven Central, GitHub Packages, or any remote Maven repository.
- Enabling automated release publication, signing, or deployment.
- Adding Dependabot, Renovate, or another automated dependency-update service.
- Enabling configuration cache, parallel execution, remote task-output caching, Develocity, build scans, or telemetry.
- Replacing regression-tested PowerShell behavior merely to increase the amount of Kotlin.
- Revising the fixed archive timestamp or adopting a new reproducibility epoch policy.
- Rewriting imported issue bodies, comments, historical research, observations, or prior evidence.
- Correcting unrelated product, archive, or tracker defects beyond the separately authorized #41/#42 map-state correction.
- Claiming Release Qualification, Binary Conformance, or build-speed improvements without newly generated evidence.

## Further Notes

- [ADR-0001](../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md) records the accepted architectural decisions and is authoritative if this specification is later summarized into implementation tickets.
- Gradle 9.7.1 was the latest stable release when the design was accepted and supports running on Java 25. Its binary distribution SHA-256 is `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`; its wrapper-JAR SHA-256 is `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.
- No current local issue body requires rewriting for this migration. Maven-specific occurrences are historical descriptions, valid consumer-POM requirements, or uses of Maven Central as a repository proper name. The cutover scan must repeat this audit against the then-current tracker state.
- The migration resets build, dependency, SBOM, publication, staging, reproducibility, Automated Conformance, and Release Qualification evidence for newly produced artifacts. Historical evidence remains useful provenance but cannot qualify Gradle outputs.
- The final implementation should update the knowledge graph after code changes, while respecting the read-only Reference Snapshot restriction.
