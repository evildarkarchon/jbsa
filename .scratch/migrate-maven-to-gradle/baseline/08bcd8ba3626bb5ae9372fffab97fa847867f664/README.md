# Maven parity baseline at `08bcd8b`

This retained baseline captures Maven behavior for candidate `0.1.0-SNAPSHOT` on the single
`initial-implementation` migration branch. It was produced from clean revision
`08bcd8ba3626bb5ae9372fffab97fa847867f664` in a detached worktree using Apache Maven 3.9.16 and
the hosted qualification pin, Eclipse Temurin `25.0.4+7`, on Windows 11 x64. The capture verified
the official JDK distribution archive SHA-256
`7caab7db43bf4b94a2e6252c699e70d90084f9aa7c943cd3414761fd540937ae` before extraction.

The authoritative machine-readable record is [maven-baseline.json](maven-baseline.json). The
build-tool-neutral semantic subset is [artifact-inspection.json](artifact-inspection.json), and the
seven reactor dependency graphs and complete command streams are retained beside them.

## Outcome

- The build-tool-neutral inspector's matching and deliberate-mismatch tests passed inside the
  exact-revision worktree before Maven ran.
- Output materialization, compile, unit, architecture, formatting, and policy passed.
- Module-path `--version` launched successfully and reported `JBSA 0.1.0-SNAPSHOT` plus the active
  compatibility-profile digest.
- The full `clean verify` baseline failed: a generated BSA manifest differed from the committed
  manifest, and two conformance-harness PowerShell checks exceeded their 120-second limits.
- The focused conformance gate likewise recorded both harness timeouts. The standalone conformance
  observation completed with its established exit status `1`, recorded as `BLOCKED` without
  claiming Automated Conformance.
- Class-path `--version` failed with a `NullPointerException` because the CLI asks the unnamed module
  for a module descriptor. This is captured behavior, not a Gradle parity expectation or an
  authorized product fix.

The report inventories five canonical archives by entry and uncompressed payload digest, both JPMS
descriptors and public signatures, the normalized consumer POM and CycloneDX graph, four runtime
dependencies, 18 staged files and their deterministic manifest, generated notices, all gate results,
and both CLI launch observations. The benchmark standalone contract records its JMH main class,
merged service descriptors, benchmark classes, absence of module descriptors, and exclusion from
the production SBOM and staging. Whole-ZIP hashes are retained as provenance but excluded from
cross-tool semantic comparison.

## Normative gaps

The `normativeGaps` register in the baseline and [baseline protocol](../../baseline-protocol.md)
separately records requirements that Maven does not implement. These include complete verified and
locked resolution with an offline proof, deterministic build-layout and resolved-production
manifests, candidate-version validation, exact qualification-JDK enforcement by the build, Linux
portability, and Gradle-specific foundation policy. None is blessed as expected parity.
