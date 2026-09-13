# Gradle parity and offline qualification at `236202b`

This compact report binds the measured ticket-10 proof to clean source revision
`236202b8203d35340fe364c669cf18be07a054e6`, candidate `0.1.0-SNAPSHOT`, and the exact Eclipse
Temurin `25.0.4+7` archive with SHA-256
`7caab7db43bf4b94a2e6252c699e70d90084f9aa7c943cd3414761fd540937ae`.

The machine-readable summary is [report.json](report.json). Large raw logs and normalized snapshots
remain generated evidence under `target/qualification-evidence/236202b`; their hashes are bound in
the compact report and they are intentionally not committed ahead of ticket 12's final evidence
regeneration.

## Outcome

- Maven and Gradle were captured from separate clean detached worktrees at the same revision,
  candidate version, and JDK bytes.
- All normalized normative seams matched. The raw comparison retained 423 explained build-tool
  representation differences and ignored only whole-archive envelope hashes.
- Two clean Gradle builds produced byte-identical consumer POM, library binary, sources, Javadocs,
  and thin CLI artifacts.
- A freshly primed strict-verification cache ran the complete product and included-build closures
  offline. A deliberately invalid checksum failed, and all ten lockfiles remained byte-identical.
- Compatible compile/test/formatting tasks stored then reused a configuration-cache entry.
  Evidence, staging, and external-process tasks rejected the mode explicitly; it remains disabled by
  default.
- Compile, unit, architecture, formatting, policy, conformance, and Gradle policy gates passed. Maven's
  policy failure was not promoted to expected Gradle behavior. Automated Conformance remained a
  matching `BLOCKED` observation and makes no qualification claim.
- Identical non-test artifact/benchmark/formatting commands measured 27.112 seconds from clean
  outputs and 26.764 seconds immediately warm on the same machine. These observations support no
  speed claim and waive no correctness gate.

The operator stopped the orchestration after the final policy-suite run rather than execute two
additional policy-bearing timing runs. The replacement timing pair excluded tests; every preceding
qualification assertion had already passed and is digest-bound in the compact report.
