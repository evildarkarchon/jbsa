# Implementation verification

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
  were corrected. The specification sequencing finding remains open.

Final built library SHA-256:
`10890e43c9a0b52073fdb83761466185b6b295e03d8617253c84f2c373bc9021`.
Final built CLI SHA-256:
`3085508ac877fe6ad4fe175d75e50283b742365cb0b543a60956cb8564a53e8c`.

These engineering checks and the supplemental adapter measurements are not
normative Performance-v1 success and do not close the unresolved merge gate.
