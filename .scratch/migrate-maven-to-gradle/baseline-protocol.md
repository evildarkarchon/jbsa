# Maven-to-Gradle parity capture protocol

This protocol freezes the Maven oracle without making Maven normative. The machine-readable settings
are in [baseline-protocol.json](baseline-protocol.json); the approved migration specification and
ADR remain authoritative when they disagree with observed Maven behavior.

Run every Maven and Gradle candidate from a clean, detached Git worktree created at the same full
commit, on the single `initial-implementation` migration branch, with candidate version
`0.1.0-SNAPSHOT`, Apache Maven `3.9.16`, and the Eclipse Temurin `25.0.4+7` Windows x64 JDK archive
whose SHA-256 is `7caab7db43bf4b94a2e6252c699e70d90084f9aa7c943cd3414761fd540937ae`.
Give each build tool a different worktree and copy
its outputs into a new evidence directory before starting the other build. Never compare mutable
files in either live output tree, never reuse one tool's generated outputs, and never overwrite a
prior evidence directory.

For the Maven baseline on Windows:

```powershell
.\build\capture-maven-parity-baseline.ps1 `
  -QualificationJdkArchive 'C:\path\to\OpenJDK25U-jdk_x64_windows_hotspot_25.0.4_7.zip' `
  -OutputDirectory .\.scratch\migrate-maven-to-gradle\baseline\<full-commit>
```

The command records the full revision, branch, candidate version, JDK and Maven identities; complete
command logs and per-project resolved dependency graphs; stable gate and CLI outcomes; semantic JAR
entry payloads, JPMS descriptors and public signatures; source/Javadoc contents; normalized consumer
POM and CycloneDX graph; runtime filenames and hashes; notices; and staged bytes and manifest.

Cross-tool comparison deliberately ignores the whole-archive SHA-256 because ZIP envelope choices
may differ. It compares every entry name, size, and uncompressed SHA-256 instead. The later Gradle
reproducibility gate still requires complete byte identity between two clean Gradle builds for the
canonical consumer POM, library binary, sources, Javadocs, and thin CLI.

The baseline report carries a separate `normativeGaps` register. A missing Maven behavior is never
converted into an expected Gradle result, and unfinished product qualification or the future
self-contained application image is not migration work.
