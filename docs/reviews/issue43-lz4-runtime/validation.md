# Implementation verification

## Completion under specification 0.14.0

The final `mvnw.cmd -B -ntp -C clean verify` passed across all seven reactor
modules on 2026-09-10: 424 reported tests, zero failures/errors, nine opt-in
skips. No narrowed test selection was used in that final run.

Fresh local CV1 execution passed all 109 admitted archive cases: 32 BSA 067,
33 Fallout 4 General BA2, and 44 PC DDS BA2. The
[current report](current-cv1.json), SHA-256
`986fa156b4b69d47c003fbe9a25f64aa181ee98aea5e01b8e5c73293b1d42b8c`,
retains the actual executions and rechecked artifact/profile/catalog identities.
The remaining catalog cases are outside this scoped pass, not silently waived.

The maintainer-approved profile packet changed exactly 27 diagnostic profile
fields in 12 goldens. Both reviewed packets, their 218 actual approval records,
and the final source correction are bound by [activation.json](activation.json).
The complete immutable-history audit passed for 145 golden bindings. Active
catalog SHA-256 is
`52387c4c5908bbb9f20254b81f13ad8e2e2241a88104914889c36e27cb6dd696`.
It also validates with all 18 ignored duplicate binaries absent, demonstrating
that clean checkouts use the original tracked synthetic source bytes.

Independent continuation reviews found no remaining blocking Standards or Spec
findings. Runtime-only readiness is complete under JBSA-PERF-003; formal family
and release performance qualification remain required.

## Initial implementation verification

Windows 11 x64, Eclipse Temurin 25.0.4.1+1-LTS, Maven wrapper 3.9.16.

- Fourteen focused raw/frame/native-launch tests pass. Fresh processes cover
  named modules, classpath, absent grants, absent native JARs, absent bindings,
  unsupported platform and rejected caller DLL-path configuration.
- The full `clean verify` run exercised 417 tests before the benchmark module,
  with nine opt-in skips and one remaining SBOM test-fixture failure. That
  failure was corrected by mutating the root JSON component array, then rerun
  successfully. The subsequently reached DDS benchmark adapter was rebound to
  the new exact profile and its tests rerun successfully.
- Final reactor `verify` passed across all seven modules with
  `-Dit.test=CompliancePolicyIT#sbomAuditRejectsUninventoriedTransitiveDependencies`
  and `-Dfailsafe.failIfNoSpecifiedTests=false`: 317 tests, one opt-in skip,
  zero failures/errors. Other integration checks had passed in the full run.
- All 73 Python performance-harness checks pass. The impact selector retains
  all 336 LZ4 assignments and records the separate 1,384-case full trigger.
- Final distribution staging produced 18 accounted inputs; generated SBOM,
  JAR/DLL byte audit and notice synchronization pass. Both staged Java 25 launch
  modes execute. Canonical notice formatting was restaged and re-audited.
- SPDX/REUSE validates the added BSD texts and metadata. Repository-wide lint
  still reports 118 pre-existing unannotated `.scratch` migration files; no
  new implementation or third-party license file appears in that missing list.
- Formatting and graphify AST update completed. The two standards findings
  were corrected. The initial specification sequencing finding was subsequently
  resolved by the accepted 0.14.0 amendment recorded in `review.md`.

Final built library SHA-256:
`10890e43c9a0b52073fdb83761466185b6b295e03d8617253c84f2c373bc9021`.
Final built CLI SHA-256:
`3085508ac877fe6ad4fe175d75e50283b742365cb0b543a60956cb8564a53e8c`.

These engineering checks and the supplemental adapter measurements are not
normative Performance-v1 success. Under specification 0.14.0 they establish
runtime-only readiness; formal family and release performance gates stay open.
