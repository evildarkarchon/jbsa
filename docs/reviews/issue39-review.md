# Issue 39 implementation review

Activation update, 2026-09-08: the maintainer approved the CV1 proposal and its
33 successor cases are now active. See the [activation record](issue39-cv1/activation.json).
The review below records the state before that approval.

Baseline: `a3b242fe8a6493bc57d440a48ee0651f5fa386fa` on
`initial-implementation`. Scope: Fallout 4 General BA2 v1, issue #39.

## Standards

The separate standards review found no documented-standard violation. One
minor unused-parameter finding in the staged writer was fixed. Substantive
methods have documentation, and lifetime and encoding constraints have comments.
Affected API, CLI, and source-planning Javadocs were updated; no existing
explanatory comments were removed.

## Spec

The separate specification review identified incomplete independent metadata
projections and missing executable CV1 evidence. Full public/independent
projections and a 33-case successor proposal now address those findings.
Every proposed case passed against the final packaged artifacts, including
both oracle directions for positive encode cases and filesystem observations
for rejected mutations.

Additional targeted review found and fixed empty-span trailing-byte accounting,
the required synthetic spelling for undecodable present wire names, and
character-based rather than encoded-byte-based ASCII admission. Regressions
cover the reader changes. The ACP 932 encoding case is conditional and skipped
on this machine's non-932 active code page; existing TES3/TES4 packing checks
passed after the source-planning change. Local-oracle timeout cleanup now
terminates and awaits owned child processes.

## Verification

`mvn -B -ntp -C clean verify -Djbsa.ba2.local=true -Djbsa.ba2.performance=true`
passed all seven reactor modules: 357 tests, zero failures/errors, and four
conditional skips. A focused local-corpus check passed again after timeout
cleanup was hardened. Stored/zlib bidirectional oracle comparisons, the
38-entry optional local corpus, independent vectors, 18 MiB bulk/mixed/shared
checkpoints, and a 10,000-entry metadata checkpoint passed.

The [CV1 proposal](issue39-cv1/README.md) remains explicitly untrusted until
maintainer approval and activation. Development timings do not award formal
PV1 or game acceptance. The pinned TES5Edit tree and active conformance catalog
are unchanged.

Remaining findings: Standards 0; Spec 1 qualification gate—explicit approval
of the concrete CV1 successor proposal before activation.
