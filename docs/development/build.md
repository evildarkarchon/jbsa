# Building JBSA

The source build requires a Java 25 JDK. The checked-in wrapper downloads the pinned Maven 3.9.16
distribution and verifies its SHA-256 checksum. JBSA qualifies Windows 11 x64 on NTFS; successful
builds elsewhere do not create a portability or support claim.

## Reactor

The single `0.1.0-SNAPSHOT` reactor contains the deep `jbsa` library, thin `jbsa-cli` consumer, and
build-only `jbsa-test-support`, `jbsa-conformance-tests`, `jbsa-benchmarks`, and `jbsa-dist`
projects. The build-only projects are scaffolds for later implementation issues and are not release
artifacts.

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

The library build emits its binary JAR, flattened self-contained consumer POM, sources JAR, and
Javadoc JAR. Maven deployment is disabled; publication and the Windows application image belong to
later release and distribution issues.

Hosted jobs provide compile, unit, architecture, formatting, and foundational policy evidence.
They do not run games, official tools, or local Release Qualification, and they do not claim that
Automated Conformance is complete.
