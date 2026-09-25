# Issue 38: proposed TES4 CV1 evidence

**Approved and activated.** The maintainer explicitly approved these 32 golden
records. [activation.json](activation.json) records the approval, the exact
reviewed bundle, and the merged active catalog. The activation preserves the
General BA2 cases approved after this proposal was prepared. This scoped
approval does not claim whole-release Automated Conformance.

The proposal, pending records, and original comparison summary below remain
unchanged as historical evidence of what was reviewed. Approved review records
are in `tests/conformance/rebaselines/` and are bound by `activation.json`.

The authoring command below is historical: do not regenerate these approved
goldens during normal verification. Further expected-byte changes require a new
deliberate review. The runtime review command remains usable to refresh executable
registrations; its historical untrusted label does not supersede this approval.

`review.json` identifies the exact proposed catalog, fixture provenance, 32 new
case identifiers and golden digests. `pending-records.json` contains the complete
draft review records, with approval deliberately absent. A zero old digest means
there was no previously accepted golden for that newly introduced case.

The original fixture descriptors pin `binding.state = missing`. Updating those
bytes under their existing names would violate JBSA-CONF-001. The proposal uses
new fixture tokens and case identifiers, records the old-to-new mapping, retains
old immutable descriptor objects, and replaces the 32 active assignments only
if the proposal is approved. Explicit base-matrix metadata preserves every
family/direction/codec obligation, including unsupported-codec rejection.

The independent Java fixture generator produces 26 hexadecimal wire vectors in
`tests/fixtures/bsa067`. The separate Python expectation parser and explicitly
authored failure recipes produce the proposed goldens; no golden is copied from
current JBSA output. Initial comparisons exposed missing diagnostic expectations
and an observer bug that counted structural evidence repeatedly across payload
assessments. Those discrepancies were reviewed against the public contracts and
the declared wire recipes before regenerating this untrusted proposal.

Successful decode cases compare full public metadata, original name components,
identities, serialized order, compression facts, exact payload hashes and stable
diagnostics. Structurally rejected inputs use an explicit rejection assertion;
they cannot expose a successful archive projection. Coverage assertions remain
present and compare the complete owning outcome. The XML case exercises packing
and its canonical unsigned-hash order and special file flag.

Rejected encode and extraction cases compare the complete before/after working
tree, including the traversal fixture's path outside its extraction destination.
Regression checks demonstrate that returning the right failure while leaving a
file or escaped output fails comparison. The runner extracts its codec profile
from the selected JAR and verifies its sidecar and source identity; a stale JAR
cannot inherit the digest of a newer source manifest. The actual Java executable
digest and version are also recorded.

Stored, zlib and mixed encode cases execute both differential directions and an
independent scanner. The oracle produces stored or global-zlib input archives;
the mixed candidate explicitly requests a stored second entry. Each direction
has its own complete expected metadata and the same exact payload tree. No claim
is made that the oracle CLI accepts JBSA's new per-entry option or that compressed
bytes match across providers. Oracle archive hex and invocation receipts are in
`oracle/`; the executable is never included.

Reproduce proposal authoring and review observations with Java 25 available:

```powershell
python build/prepare-bsa-cv1-review.py
pwsh -NoProfile -File build/run-bsa-cv1-review.ps1 -OutputDirectory target/bsa-cv1-review-new
```

The runner rejects reused output directories, checks immutable bindings, and
labels its report `UNTRUSTED_PENDING_MAINTAINER_APPROVAL`. A comparison `PASS`
means the proposed expectation matched; it does not mean the expectation is
approved. Candidate artifact and codec-profile digests are retained in the
report. After approval, the exact reviewed proposal and review records can be
activated and checked by the ordinary immutable-rebaseline audit.

Each review run also exports `registrations.json` with the exact candidate
dependencies, executable digests, public adapter and independent validator
bindings. After approval and catalog activation, the ordinary runner can consume
those same runtime registrations:

```powershell
pwsh -NoProfile -File build/run-conformance.ps1 -Mode Local -RegistrationPath target/bsa-cv1-review-new/registrations.json -CodecProfilePath jbsa/src/main/resources/META-INF/jbsa-codec-profile.json -OutputDirectory target/conformance-after-approval
```

First run the review command to materialize the bound oracle archives from the
committed hexadecimal observations into `target/bsa-cv1-review-inputs`. Runtime
registrations must be regenerated when any pinned executable or adapter changes.
The ordinary full catalog will still report cases without runtime registrations
as `INVALID`; these 32 scoped cases cannot award whole-release conformance.

Approval is required by [JBSA-CONF-007](../../spec/conformance-v1.md#jbsa-conf-007):
“Golden creation or replacement **MUST** occur only through a separate,
deliberately selected rebaseline operation,” with “explicit maintainer approval.”
