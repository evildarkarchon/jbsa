# TES3 walking slice

Issue #37 implements the TES3 portion of specification 0.12.0 through the public
`BethesdaArchives` interface and the thin CLI. Its requirement trace was established
before implementation:

| Requirements | Slice responsibility |
| --- | --- |
| JBSA-TES3-001–006 | Stored wire layout, canonical names/hash/order, checked decoding, safe encoding, sequential split planning |
| JBSA-DET-001–006 | Recognition remains separate from structural validation |
| JBSA-LIB-001–006, 008–012 | Public queries/mutations, owned channels, detached metadata, requests, profiles, limits, normalized identities |
| JBSA-CLI-001–015 | Applicable TES3 information/list/dump/unpack/pack options and observations; other family commands remain unavailable |
| JBSA-CONF-005–006, 008–014 | Independent redistributable fixtures, public consumer tests, semantic differentials, malformed dispositions, diagnostic comparison, independent validation boundary |
| JBSA-CONF-015, 018–019 | Separate Binary Conformance, complete hosted Automated Conformance, and manual Release Qualification gates |
| JBSA-OPS-001–011; JBSA-IO-001–010 | Existing structured outcomes, resource ownership, safe preflight, staging/publication and cancellation |

The public test seams are the issue's expressly requested detect, inspect,
open/openContent, extract and pack operations and the packaged CLI. Tests do not
import archive internals. `Tes3ConformanceIT` is an embedded consumer; the CLI is
the compiled CLI-like consumer. Construction remains explicit about family and
encoding; content ownership follows try-with-resources.

## Evidence and claim boundary

The separate `tests/fixtures/tes3` corpus contains independently authored TES3 wire
fixtures represented by reviewable hexadecimal text. Its generator uses fixed wire
vectors and literal hashes, never the production parser or encoder. Every object
is covered by its provenance manifest and complete-inventory reproduction audit.
Tests decode the text into archive bytes in temporary directories. Small payloads
are project-authored CC0 data, not game assets. Existing foundation corpus,
generator, catalog, registry and golden identities remain unchanged.

Public integration tests are incremental regression evidence. They do not award
the complete `conformance-v1` matrix, broad independent-validator qualification, Binary
Conformance, or game/official-tool Release Qualification. Those claims retain the
existing harness gates and require their own complete evidence. In particular,
single-machine byte equality cannot satisfy JBSA-CONF-015's second-CPU requirement.

The optional local differential uses the existing `Invoke-ConformanceOracle`
adapter, which checks the pinned executable before every invocation, bounds its
lifetime, and retains raw streams. It never fetches an executable or game archive.
Missing local inputs are reported as unavailable; digest mismatch is a failure.
Hosted execution is prohibited even if a local path accidentally exists.

Run the slice evidence with Java 25:

```powershell
.\mvnw.cmd -B -ntp -C -pl jbsa-conformance-tests -am '-Dgroups=tes3' '-Dit.test=Tes3ConformanceIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
# Explicit local opt-in; never enabled by hosted jobs:
.\mvnw.cmd -B -ntp -C -pl jbsa-conformance-tests -am '-Dgroups=tes3' '-Dit.test=Tes3ConformanceIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.tes3.local=true' verify
```

`build/validate-tes3-wire.ps1` independently scans the narrow canonical ASCII,
stored, unshared layout directly from the specification. It does not load product
classes or derive code from the Reference Snapshot. `run-tes3-validator.ps1` runs
it through `Invoke-ConformanceValidator`, recording the PowerShell executable and
scanner digests, adapter version, invocation, accepted input and payload digests,
and normalized result. Its expected two-entry projection is literal. This is
project-authored corroboration for this slice, not third-party tool qualification
or validation of every TES3 layout. The scanner deliberately rejects noncanonical
inputs and sharing instead of implying support for them.

On 2026-09-08, all seven `Tes3ConformanceIT` tests passed with the local oracle
enabled. The digest-pinned oracle decoded JBSA output to the exact source bytes;
JBSA decoded oracle output to the literal names, hashes, sizes and payloads. The
independent scanner accepted both outputs. Both tools produced the 86-byte archive
with SHA-256 `3ecbb6e1bd75c2c88f66869ec9f96bc4c3da1eab7ebd9b86bd7737af116bac4b`.
The run records remain in ignored `target/tes3-local-evidence` and
`target/tes3-validator-evidence`; new runs record fresh evidence. This single
workstation observation does not designate a Binary Conformance case.

`PublicModuleConsumerIT` compiles and runs two named JPMS consumers. Both now call
inspect, detached entry enumeration, owned reading, extract and pack at runtime.
The review exposed two usability needs: detached inspection must enumerate entry
metadata, and consumers invoking Windows filesystem identity must grant native
access to the library module. Both are exercised by the compiled consumers; the
classpath integration JVM grants `ALL-UNNAMED` native access. No public storage or
parser adapter was needed. The source-only baseline consumer methods were replaced
with actual operations, and their obsolete capability-failure expectation removed.

The modular CLI subprocess tests cover help, version and profile digest, safe
argument parsing, pack/list/dump with UTF-8 paths, detached part byte/count records,
unpack with replacement, and explicitly selected profile repeated values,
permissive booleans and legacy split syntax. The CLI entry point's foundation
placeholder documentation was replaced because TES3 commands now execute.

This slice implements TES3 selectors. Cross-family parsing, qualified profile
information exit-zero/banner behavior, packaged Ctrl+C exit normalization, and
interactive stderr progress remain later gates. The shutdown hook requests
cooperative cancellation and waits for settlement. Progress is currently suppressed:
`System.console()` alone cannot establish that stderr is interactive.
