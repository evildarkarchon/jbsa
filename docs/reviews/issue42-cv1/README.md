# Issue 42: 0x68 conformance review

**Final proposal execution: 52/52 PASS.**

Catalog SHA-256:
`ef912247053ac719165b1c6e9af90db3b4c3d714245061eed87683791f70aa2d`.
Execution report SHA-256:
`07835137b2a83d413b8c861ebf12ff4385fbacea8d4f5fe0d1cb589f41aac776`.
The [execution report](evidence/review-report.json) binds the packaged artifacts,
runtime, registrations, individual observations and results. The 4.25-GiB
split case completed within its recorded deadline and removed its bulk files.

Standards review found no hard violations; duplicated observation serializers
were removed with their methods. Spec review led to fixes for cumulative
embedded-name metadata charges and stronger exact-diagnostic and cleanup
observations. Both review axes have no remaining findings.

This is a deliberately prepared **untrusted** golden proposal under
[JBSA-CONF-007](../../spec/conformance-v1.md#jbsa-conf-007). It does not modify
the active catalog or approve itself. The source issue remains open until the
required review and activation have completed.

The proposal extends the shared BSA implementation to Fallout 3, New Vegas and
Skyrim LE. It binds specification 0.14.0, the declared codec profile, independently
generated wire fixtures, independently authored expected observations, and the
digest-pinned local oracle. The three CLI selectors use one wire encoding.

`catalog.json` contains proposed successor cases. `review.json` records affected
case identities and source bindings; `pending-records.json` records old/new
golden digests, provenance, rationale and explicit null approvals. Generated
corpus objects stay outside the active fixture catalog until activation.

## Reproduction

Build the product and public adapters with Java 25 before preparing or running
the proposal. Use the real Java installation path for `JAVA_HOME`; the evidence
runner rejects indirect executable paths rather than binding a symlink target
implicitly.

```powershell
python build/prepare-bsa-cv1-review.py --family bsa-068
pwsh -NoProfile -File build/run-bsa-cv1-review.ps1 -Family bsa-068 -OutputDirectory target/issue42-review-new
```

The output directory must be new. Ordinary execution reads proposed golden bytes;
it never repairs mismatches or grants approval. A semantic mismatch must be
investigated against the specification and independent inputs before any
deliberate proposal regeneration.

## Evidence boundaries

Stored and zlib encode cases cross-decode in both oracle directions. Mixed JBSA
output is decoded by the oracle; the oracle CLI's reverse direction uses its
global zlib mode. Independent Python scans corroborate archive structure and
uncompressed content. Additional public scenario adapters observe exact names,
wire metadata, diagnostics, collision ordering, flags, resource limits and
publication behavior. Their expected maps were authored from requirements,
without copying successful candidate output into goldens.

Stored-byte matches are candidate evidence only. No case receives Binary
Conformance designation without the separately required repeatability and CPU
qualification. The current machine does not establish active-ACP-932 Japanese
decoding, game acceptance, official-tool acceptance, or formal PV1 qualification.

The original generic split recipe contains an invalid stored BSA size with bit
30 set. The scenario records its preflight rejection and separately exercises
legal large records across the advisory 2-GiB boundary. Large synthetic payloads
are generated locally and removed after observation.
