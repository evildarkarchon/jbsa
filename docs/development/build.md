# Building JBSA

The source build requires a Java 25 JDK. The checked-in wrapper downloads the pinned Maven 3.9.16
distribution and verifies its SHA-256 checksum. JBSA qualifies Windows 11 x64 on NTFS; successful
builds elsewhere do not create a portability or support claim.

## Reactor

The single `0.1.0-SNAPSHOT` reactor contains the deep `jbsa` library, thin `jbsa-cli` consumer, and
build-only `jbsa-test-support`, `jbsa-conformance-tests`, `jbsa-benchmarks`, and `jbsa-dist`
projects. The build-only projects contain verification and qualification tooling and are not product
release artifacts. See [local performance qualification](performance.md) for explicit corpus,
paired-run and JMH commands; ordinary Maven verification runs only small harness checks.

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
.\mvnw.cmd -B -ntp -C clean verify
```

Check the reproducibility of the library inputs and CLI JAR with two clean builds:

```powershell
.\build\verify-reproducible-build.ps1
```

For a candidate built with `-Drevision=2.3.4`, compare that same version with
`.\build\verify-reproducible-build.ps1 -ReactorVersion 2.3.4`.

The library build emits its binary JAR, flattened self-contained consumer POM, sources JAR, and
Javadoc JAR. Maven deployment is disabled; publication and the Windows application image belong to
later release and distribution issues.

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
.\build\verify-compliance.ps1
```

To inspect a non-empty release-input directory, provide a versioned JSON manifest that accounts for
every file by relative path, lowercase SHA-256, kind, and source:

```powershell
.\build\verify-compliance.ps1 `
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
