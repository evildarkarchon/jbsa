# Conformance evidence after the Interface Candidate specification change

## Recorded activation

The maintainer approved review
`a2e9702750796da6cad78a0e36458475c14bbdaf9b2d3da69c16825710b93c3c` on
2026-09-10. Its 109 assertion objects were activated after the rebaseline
verifier passed; see the [activation receipt](../../development/evidence/issue41-activation.json).
The preparation procedure below is retained for that historical input catalog.

A subsequent [source-location correction](../../development/evidence/issue41-source-repair.json)
reuses original tracked synthetic bytes for 16 already-unqualified cases. All
109 admitted case records and all three deferred Xbox case records remain
exactly unchanged. The correction needed zero golden replacements and makes
the active catalog usable without duplicate binary fixtures outside the
synthetic corpus.

The historical packet's nine raw binary copies are deliberately ignored.
Their exact bytes already exist in the tracked synthetic corpus. To inspect the
frozen historical packet, reconstruct those copies with:

```powershell
python build/materialize-interface-historical-sources.py
```

The materializer checks source hashes and limits writes to the recorded
historical artifact directory. Current catalog execution uses the tracked
original sources directly. The frozen packet and preparation/activation tools
retain their reviewed bytes; their old active-catalog precondition intentionally
prevents repeating activation against the later catalog.

## Historical preparation procedure

The governing specification is bound as a complete set in the active CV1
catalog. Every executable assertion golden also embeds the SHA-256 of that set.
Updating the catalog's specification hashes alone therefore makes its existing
goldens stale, even when the expected archive observations are unchanged.

`build/conformance-execution.ps1` checks that embedded hash before execution.
`build/verify-conformance-rebaseline.ps1` also rejects reuse of a historical
golden after any specification-set change. Neither check should be weakened to
make an Interface Candidate build pass. Existing approved evidence remains
historical evidence for its original specification set.

Run `python build/prepare-interface-candidate-review.py` after the specification
edits are final. It writes an **untrusted proposal** under
`conformance-rebinding/`, with the exact old/new specification bindings, active
catalog digest, every proposed old/new golden digest and an assertion digest.
The successor assertion objects preserve the original observations exactly;
only their case identifier and specification digest change. The generator never
reads current JBSA output and never edits the active catalog or approved corpus.
Its output does not assert that the retained observations satisfy a changed
requirement: that judgment remains part of review.

There are 109 executable assertion objects in the currently admitted BSA 067,
Fallout 4 General BA2 and Fallout 4 DDS BA2 evidence. Two additional synthetic
structural objects cover other General BA2 families; they have no executable
CV1 assertion contract and are listed separately without invented qualification.
TES3 currently has no golden bindings in the active CV1 catalog.

The packet includes the successor catalog, copied source bytes, corpus manifests,
fixture descriptor tokens, case identifiers and complete pending rebaseline
records. Changing a token's bound provenance in place would violate the
catalog's immutable-identity check. Maintainer-approved rebaseline records
must bind the old/new digests, source fixtures, oracle, generator, complete
configuration and affected cases required by
[JBSA-CONF-007](../../spec/conformance-v1.md#jbsa-conf-007). The user-authorized
post-1.0 deferral does not itself approve replacement golden bytes.

After explicit approval of the packet, execute:

```powershell
$reviewDigest = (Get-FileHash docs/reviews/issue41-interface/conformance-rebinding/review.json -Algorithm SHA256).Hash.ToLowerInvariant()
python build/activate-interface-candidate-review.py --approver evildarkarchon --review-sha256 $reviewDigest
```

The command verifies the reviewed bytes, materializes the supplied approval,
runs `build/verify-conformance-rebaseline.ps1` against the included historical
catalog, and publishes the active catalog only after validation succeeds. The
command is an approval-bearing operation and has not been executed during
proposal preparation. Then execute the full verification suite to produce
current conformance results. Until then, the proposed hashes and objects are
review material, not active PASS evidence.
