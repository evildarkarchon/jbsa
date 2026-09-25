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
.\build\run-ci-gate.ps1 -Gate policy
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
.\gradlew.bat verifyCompliance
```

Check the reproducibility of the library inputs and CLI JAR with two clean builds:

```powershell
.\build\verify-reproducible-build.ps1
```

Build a candidate with `.\gradlew.bat clean verify -Pversion=2.3.4`, then compare that same
version with `.\build\verify-reproducible-build.ps1 -ReactorVersion 2.3.4`. Candidate versions
must be pinned `MAJOR.MINOR.PATCH` values with at most one explicit qualifier; Gradle applies the
validated value consistently to all six projects.

The library build emits its binary JAR, flattened self-contained consumer POM, sources JAR, and
Javadoc JAR. Remote publication is disabled; publication and the Windows application image belong
to later release and distribution issues.

Hosted jobs provide compile, unit, architecture, formatting, and foundational policy evidence.
They do not run games, official tools, or local Release Qualification, and they do not claim that
Automated Conformance is complete.

## Compliance and release inputs

Every `verify` build generates a reproducible CycloneDX 1.6 JSON SBOM at
`target/compliance/jbsa.cdx.json`, regenerates the release copy of
`THIRD-PARTY-NOTICES.md` and the mandatory Reference Snapshot attribution in `RELEASE-NOTES.md`,
and runs the repository compliance audit. The audit checks the maintained
dependency and native-payload inventories, product POM dependencies, notice synchronization,
tracked fixture/native bytes, and SBOM coverage. Selected codec artifacts remain explicitly
non-releaseable until their downstream qualification gates pass.

Run the repository and inventory checks directly with:

```powershell
.\gradlew.bat verifyCompliance --no-daemon
```

To inspect a non-empty release-input directory, first run `verifyCompliance` for the exact candidate,
then pass its Gradle-generated model and a versioned JSON manifest that accounts for every file by
relative path, lowercase SHA-256, kind, and source:

```powershell
$candidate = '2.3.4'
.\gradlew.bat verifyCompliance --no-daemon -Pversion=$candidate
.\build\verify-compliance.ps1 `
  -ReactorVersion $candidate `
  -BuildLayoutManifest .\target\compliance\build-layout.json `
  -ResolvedProductionDependencies .\target\compliance\resolved-production-dependencies.json `
  -ConsumerPomPath .\jbsa\target\publications\library\pom-default.xml `
  -RequireGeneratedArtifacts `
  -GeneratedSbomPath .\target\compliance\jbsa.cdx.json `
  -ReleaseInputRoot .\path\to\release-inputs `
  -ReleaseInputManifest .\path\to\release-inputs.json
```

The audit rejects local/proprietary archive material before manifest processing, rejects native
payloads whose exact digest is not release-approved, recursively inspects JAR/ZIP contents, and
rejects every unmanifested, missing, or checksum-mismatched artifact. It also reconciles all
external SBOM components back to approved inventory entries, including transitives. The hosted
REUSE job separately runs the official REUSE 3.3 metadata lint over every project-owned file
without checking out the separately licensed Reference Snapshot.

The final `jbsa-dist` verification stages the current library JAR, consumer POM, sources and Javadoc
JARs, thin CLI JAR, and required license/notice/SBOM evidence in `jbsa-dist/target/release-inputs`.
It writes `jbsa-dist/target/release-inputs.json` from those exact bytes, then audits both paths
explicitly. Missing staging or evidence fails the gate. The root verification checks repository
and SBOM evidence before this step; it does not inspect a previous build's staging directory.
These inputs prepare later Windows image packaging and do not constitute Release Qualification.

## Platform, native, and evidence boundaries

The portable Java compile and test tasks run on Windows and Linux. Windows filesystem identity,
native-access subprocesses, LZ4 native loading, NTFS publication semantics, application staging,
performance qualification, and Release Qualification remain Windows x64 boundaries. A Linux pass
does not qualify those behaviors. The Gradle tasks grant native access only to their owned test
processes; library embedders and staged-CLI launchers must retain the grants documented in the
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
