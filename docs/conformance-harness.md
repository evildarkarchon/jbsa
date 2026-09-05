# Conformance Case harness

Issue #31 implements the build-only CV1 runner. The permanent trace is
`docs/spec/requirements.yaml`: JBSA-CONF-001/002/003/005/006/007/017 own this
infrastructure. The runner also provides the comparison, contradiction, differential,
validator, and reporting mechanisms used by JBSA-CONF-004 and 008–019. Product case
completion remains #50; Binary Conformance qualification remains #56, and manual
Release Qualification remains #58.

The catalog has 508 required cases and 93 targeted coverage items. It assigns every
base family/direction/codec cell and targeted scenario to stable assertion identifiers.
Unsupported combinations are negative cases. There are no waived cases or aggregate
scores. The immutable scenario descriptors describe inputs that later archive slices
must materialize; a descriptor is never evidence that the scenario ran.

Run the harness checks with the pinned Java 25 build:

```powershell
.\mvnw.cmd -B -ntp -C '-Dgroups=conformance-harness' verify
```

Produce a complete product case report in a new output directory:

```powershell
pwsh -File build/run-conformance.ps1 -OutputDirectory target/conformance-run-1
```

Exit `0` means every hosted case passed, `1` means a case did not pass, and `2`
means catalog/report integrity failed. `matrix.json` deterministically lists every
case once. `report.json` includes ordered assertion outcomes, exact candidate,
configuration, fixture and golden identities, environment, non-gating times, and
evidence references. Raw streams and compared artifacts are retained by SHA-256.
The runner never rewrites expected data after failure.

The current product modules are skeletons. No production adapters are registered,
and most materialized fixtures and complete expected observations are still absent.
Their cases report `INVALID`; `automated_conformance` is false. The two small
existing golden observations are fixture foundation data and cannot pass the full
semantic projection checks. The hosted `conformance` job tests the harness and
uploads the complete case report. Its successful harness checks do not award
Automated Conformance. Issue #50 must require the runner's exit `0` for that claim.

## Adapter boundary

`run-conformance.ps1 -RegistrationPath <json> -CodecProfilePath <json>` loads a
registration document with `cases`, each containing a known `case_id`, `command`,
and `validators`. Duplicate and unknown case identifiers are rejected. The command
has `executable`, its lowercase SHA-256, literal `arguments`, and `inputs`, an array
of `{path, sha256}` records for its script/JAR dependencies. These are trusted,
reviewed test adapters. They must call only the public library exports or the
packaged CLI. They must not inspect or import library internals. Adapter dependency
pins are checked before and after execution; future product adapters belong in
`jbsa-conformance-tests` and inherit its architecture checks.

The command receives `-Request <path>`, a JSON request identifying the exact case,
candidate artifacts, codec profile, configuration, phase and repository root. It
emits a JSON object with `case_id` and `assertions`, each having `assertion_id` and
`observed`. Candidate output never supplies its own expected values. Nonzero
adapter exit, malformed JSON, missing/duplicate/unknown assertions, absent fixture
bytes or changed pins invalidate the case. A packaged CLI's actual nonzero result
belongs inside its structured CLI Observation; the observing adapter exits zero
when it successfully captures that result.

The case binds an immutable assertion golden with `contract`, `case_id`,
`configuration_sha256`, `specification_sha256`, and an `assertions` array of
`{assertion_id, kind, expected}`. Supported kinds are `semantic`, `diagnostic`,
`cli`, and `exact`. Golden fixture/generator/provenance bindings are verified
through the catalog and synthetic corpus audit. Complete semantic projections
include ordered entries, original names and optional identities, hashes, sizes,
flags, diagnostics and exact payload digests, plus family-specific metadata.
Offsets, padding and incidental layout do not enter semantic equality. Diagnostic
wording and exception class names are excluded; CLI Observations retain stable
exit/stream/record/artifact/tree behavior while raw streams remain diagnostic evidence.
The specification identity is the SHA-256 of canonical JSON for the catalog's
complete `specification_set` binding array, so a changed normative document makes
the associated expected observations stale.

## Local oracle and independent validation

Hosted mode never uses `tests/fixtures/local` or the oracle. No hosted job fetches
either. Local mode permits the dedicated `Invoke-ConformanceOracle` adapter to use
only `tests/fixtures/local/oracle/BSArch.exe`. It checks the canonical digest before
every invocation. Absence is `UNAVAILABLE`; mismatch is `INVALID` without execution.
Its source correspondence is recorded as user-attested because the Delphi build
cannot be cryptographically reproduced. No executable enters the evidence bundle.

Independent validator registrations pin tool `identity`, `path`, `sha256`,
`adapter_version`, `kind` (`archive` or `dds`), and the declarations
`independent: true`, `derived_from_reference: false`. An archive validator must
corroborate accepted archive cases, and DirectXTex must validate reconstructed DDS.
Each registration supplies `input_role`, `assertion_id`, and literal `arguments`;
`{input}` selects the produced artifact. The validator emits `{projection: ...}`.
Its exit, raw streams, invocation, input/output digests and normalized result are
retained. Missing validators or a disagreement invalidate the affected case; a
majority vote cannot override its authority.

Positive encode cases execute a second public adapter process with phase
`oracle-to-jbsa` and a pinned `oracle_archive` input from the golden. It must decode
to the same projection and exact `source_payloads` filesystem manifest. The first
process produces the candidate archive; applicable independent validators consume
that output. Local mode additionally runs BSArch with the registered standalone
`{input}` and `{output}` arguments and compares its extracted source bytes. Hosted
mode can consume a committed oracle attestation only when `oracle_candidate_sha256`
matches the produced archive and `oracle_candidate_result` is `PASS`. If fresh
candidate bytes lack that attestation, the case remains `INVALID` until a separate
local refresh and approved golden review establish it. The harness never invents
an oracle observation for new candidate bytes.

`tests/conformance/contradictions.json` records unresolved contradictions as
`{id, case_ids, observations}` with at least two conflicting, authority-labelled
observations. Every referenced case is blocked. Resolution requires a reviewed
specification correction or approved Compatibility Deviation, with its context
recorded in version control; the runner has no waiver switch.

`Test-ConformanceBinaryEvidence` checks only a supplied case and exact configuration.
It requires ordered inputs, `-split:0`, `-share:no`, `-mt:no`, pinned codec/provider,
five fresh matching oracle runs, matching candidate bytes and cross-decode evidence,
repeated on two distinct Windows x64 CPUs. It never awards a family-wide claim or
manual Release Qualification. Actual multi-machine evidence is supplied separately
under #56; the harness tests use synthetic evidence to check rejection behavior.

## Immutable inputs and rebaselines

Catalog fixture/configuration token mappings are content-addressed. Changing an
existing case's identity or either digest requires a new CV1 identifier. Golden
creation and replacement are separate reviewed operations, using the record schema
in `tests/fixtures/synthetic/rebaseline-record.schema.json`. Such records bind old
and new hashes, source fixtures, oracle, generator, full configuration, affected
cases, rationale, semantic difference and explicit maintainer approval. Ordinary
execution cannot create or approve these records.

`build/verify-conformance-rebaseline.ps1 -BaselineCatalog <historical-catalog.json>`
compares a candidate against an explicit historical catalog and audits records in
`tests/conformance/rebaselines`. CI obtains that catalog from the exact PR base or
previous push commit. A first catalog uses an explicit empty baseline. It rejects
in-place identity changes, stale golden evidence and unapproved replacements.
The full review-record `configuration` contains `case_configuration` (the complete
configuration descriptor), `generator_configuration` (the fixture's generation
procedure and options), and `specification_set` (all governing document bindings).

All new conformance inputs are project-authored CC0-1.0 data. Build scripts and
Java verification code are Apache-2.0. The catalog, oracle and evidence do not
become product dependencies or release artifacts.
