# Gradle acceptance evidence for `494db4e`

This is the final ticket-12 acceptance record for clean candidate revision
`494db4e7cd3d288a17cecb4c9fc7705adeccacd6`, version `0.1.0-SNAPSHOT`. The
[machine-readable report](report.json) binds the complete ignored evidence tree at
`target/qualification-evidence/494db4e` by SHA-256. Earlier evidence, including
`../../parity/236202b`, remains unchanged historical provenance and does not qualify these
Gradle-produced artifacts.

## Outcome

- Exact Eclipse Temurin `25.0.4+7` Windows qualification used the pinned archive SHA-256
  `7caab7db43bf4b94a2e6252c699e70d90084f9aa7c943cd3414761fd540937ae` and Gradle `9.7.1`.
- Windows cache priming and strict-offline complete verification passed with `--no-daemon`, as did
  the included build, strict checksum negative proof, lockfile consistency, module/architecture and
  Gradle-policy tests, active-reference scan, publication, SBOM, compliance, staging, and
  post-staging audit.
- Maven-to-Gradle normalized parity has zero unexplained differences. The 423 retained raw
  differences are reviewed build-tool representations; only cross-tool archive envelopes are
  ignored. Two clean Gradle builds reproduced all five canonical project artifacts byte for byte.
- Linux portability passed the five hosted-CI-equivalent compile, formatting, build-logic, unit, and
  ordinary integration commands from a clean native Ubuntu WSL2 checkout using the pinned Temurin
  `25.0.4+7` Linux archive.
- The new Release Qualification handoff retains 18 staged inputs plus the release manifest,
  build/dependency/SBOM/compliance outputs, and complete Automated Conformance report before later
  clean phases remove the detached worktree.
- Automated Conformance is honestly `BLOCKED`, not `PASS`: all 508 required Conformance Cases were
  regenerated and are `INVALID` because no public-interface execution adapter is registered. The
  complete local build passes because it correctly preserves this expected product-evidence exit
  policy; it does not turn the observation into a compatibility claim.

## Raw evidence and artifact handoff

The raw evidence is intentionally ignored build output rather than committed binaries:

```text
target/qualification-evidence/494db4e/
├── maven/                         temporary same-revision comparison oracle
├── gradle/                        qualification logs, snapshots, and reports
│   └── release-qualification/
│       ├── manifest.json          digest-bound inventory of 26 retained payload files
│       ├── release-inputs.json    canonical 18-entry staging manifest
│       ├── release-inputs/        actual Gradle-produced artifacts and supporting inputs
│       ├── compliance/            build layout, dependencies, SBOM, notices, release notes
│       └── automated-conformance/ complete 508-case matrix and report
├── linux-portability.log          retained five-command Linux transcript
└── run-linux-portability.sh       exact Linux evidence procedure used on this machine
```

Verify `report.json`'s `rawEvidence.completeTreeSha256`, then verify
`gradle/release-qualification/manifest.json.sha256` and every file row in the manifest before moving
or using the handoff. Do not substitute later build outputs under the same candidate label.

## Manual Windows Release Qualification

Status: **prepared, not performed, not qualified**. Hosted CI and these automated runs do not supply
manual Release Qualification.

The handoff's 23 positive Encode Conformance Cases define the candidate scope for TES3, Versioned
BSA `0x67`, `0x68`, and `0x69`, Fallout 4 General and DDS BA2 v1, and Starfield General/DDS BA2 v2
and v3 method 3. Their exact case, fixture, codec, and configuration identities are enumerated in
`report.json`. Decode-only Fallout 4 v7/v8 variants are not writable-family qualification targets.

When the human-owned JBSA ticket 58 is unblocked, the operator must use Windows 11 x64 on NTFS and
the exact retained candidate/profile. For every applicable writable Archive Family, record the
game or official-tool name/version, environment, archive configuration, representative fixture and
output SHA-256 values, exact commands, observed acceptance, diagnostics, and human sign-off. Keep
proprietary inputs and resulting game assets outside version control. Until those records exist—and
Automated Conformance plus the final application-image prerequisites are complete—the public release
and complete Encode Conformance claim remain blocked.
