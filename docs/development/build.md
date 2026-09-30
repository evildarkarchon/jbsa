# Building JBSA

The source build requires Java 17 or newer to run Gradle. Java tasks request an Adoptium Java 25
toolchain; the pinned Foojay resolver provisions it when no matching local installation exists. The
checked-in wrapper downloads Gradle 9.7.1 and verifies its SHA-256 checksum. Hosted qualification
and reproducibility use exact, separately checksummed Eclipse Temurin `25.0.4.1+1` archives for
Windows and Linux/WSL. JBSA qualifies Windows 11 x64 on NTFS; successful builds elsewhere do not
create a portability or support claim.

## Multi-project build

The single `0.1.0-SNAPSHOT` build contains the deep `jbsa` library, thin `jbsa-cli` consumer, and
build-only `jbsa-test-support`, `jbsa-conformance-tests`, `jbsa-benchmarks`, and `jbsa-dist`
projects. The build-only projects contain verification and qualification tooling and are not product
release artifacts. See [local performance qualification](performance.md) for explicit corpus,
paired-run and JMH commands; ordinary Gradle verification runs only small harness checks.

## Deterministic entry points

Run the same gates used by hosted Windows CI from the repository root:

```powershell
.\build\run-ci-gate.ps1 -Gate compile
.\build\run-ci-gate.ps1 -Gate unit
.\build\run-ci-gate.ps1 -Gate architecture
.\build\run-ci-gate.ps1 -Gate formatting
```

Run the complete local build once before committing:

```powershell
.\gradlew.bat clean verify
```

Use narrower Gradle lifecycle and project tasks while iterating. `assemble` creates artifacts,
`check` runs a project's ordinary verification, and `build` combines those narrower meanings; none
substitutes for the repository's complete `verify` task. Examples:

```powershell
.\gradlew.bat :jbsa:test
.\gradlew.bat :jbsa-conformance-tests:architectureTest
.\gradlew.bat spotlessCheck
```

For project-authored archive fixtures, use the [generation and JUnit verification
workflow](archive-fixtures.md).

Build a candidate with `.\gradlew.bat clean verify -Pversion=2.3.4`. Candidate versions
must be pinned `MAJOR.MINOR.PATCH` values with at most one explicit qualifier; Gradle applies the
validated value consistently to all six projects.

The library build emits its binary JAR, flattened self-contained consumer POM, sources JAR, and
Javadoc JAR. Remote publication is disabled; publication and the Windows application image belong
to later release and distribution issues.

Hosted jobs provide compile, unit, architecture, formatting, and conformance evidence.
They do not run games, official tools, or local Release Qualification, and they do not claim that
Automated Conformance is complete.

## Compliance material

Every `verify` build generates a reproducible CycloneDX 1.6 JSON SBOM at
`target/compliance/jbsa.cdx.json`. The dependency and native-payload inventories, license texts,
and notices under `compliance/` are maintained by hand; no build task or hosted gate audits them.
The hosted REUSE job runs the official REUSE 3.3 metadata lint over every project-owned file
without checking out the separately licensed Reference Snapshot.

## Platform, native, and evidence boundaries

The portable Java compile and test tasks run on Windows and Linux. Windows filesystem identity,
native-access subprocesses, LZ4 native loading, NTFS publication semantics, application staging,
performance qualification, and Release Qualification remain Windows x64 boundaries. A Linux pass
does not qualify those behaviors.

Off Windows, reading, packing, extraction, and publication run through portable providers rather
than failing closed: `PathIdentity` takes no-follow NIO attributes and the provider file key (plus
birth time for regular files, because POSIX reuses inodes), its destination pin detects root
replacement instead of denying it, and `ArchiveInput` opens without the Windows-only
`NOSHARE_WRITE`/`NOSHARE_DELETE` options. `JBSA-SCOPE-011` requires this portable mode:
platform-specific code is allowed where it improves behavior on its OS, but every such path needs a
portable fallback, chosen before the first side effect, that keeps the operation working
everywhere else. The mode stays unqualified under `JBSA-SCOPE-010`, and the Windows baseline
behavior is unchanged.

LZ4 follows the same rule. `Lz4Runtime.preflight` pins one provider for the process before the
first side effect (`JBSA-CODEC-015`): the native LWJGL adapter where it loads, otherwise
lz4-java's pure-Java `safeInstance()` provider (`JBSA-CODEC-014`). It never switches afterwards,
and invalid data is never retried through the other provider. The portable provider reserves heap
instead of native memory and carries its own profile identities (`jbsa-lz4-portable-v1` and
`jbsa-bsa-069-lz4-portable-v1`), so its evidence is never mistaken for the qualified native
profile. It cannot decode linked-block LZ4 frames and reports them as `CAPABILITY
codec.unavailable` with `capabilityCause=dependent-blocks`. The versioned-BSA profile always writes
independent blocks. Tests that exercise an explicit Windows boundary (native LZ4 loading or its
cross-provider comparison, the active ANSI code page behind the compatibility profile, sharing
denial, the deny-delete pin, junctions, 8.3 short names, drive roots, or the kernel32 identity
provider itself) carry `@EnabledOnOs(OS.WINDOWS)` with a one-line comment naming that boundary.
Every other test must pass on both hosts. The Gradle tasks grant native access only to their owned
test processes; library embedders and staged-CLI launchers must retain the grants documented in the
[public interface guide](contract-baseline.md) and [Windows LZ4 guide](windows-lz4-runtime.md).

CI, reproducibility, parity, and qualification run with `--no-daemon`. Keep remote build-result
caching, Develocity, automatic build scans, and telemetry disabled; dependency/distribution caches
contain only checksum-verified inputs. Regenerate build, dependency, SBOM, staging,
reproducibility, Automated Conformance, and Release Qualification evidence for the exact candidate
rather than carrying forward evidence for earlier build outputs.

Treat Gradle, wrapper, and plugin upgrades as isolated manual changes. Review official distribution
checksums and every new dependency-verification entry, update locks deliberately, and rerun the
complete Windows and Linux gates plus qualification appropriate to the affected boundary. Rollback
means reverting the coherent Gradle cutover or upgrade; do not restore a second build alongside it.
