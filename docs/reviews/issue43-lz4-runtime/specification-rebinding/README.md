# Specification 0.14.0 conformance rebinding proposal

This packet proposes 109 successor golden identities for the 508-case active
catalog. The only golden changes are the case identifier's `-runtime-v1` fixture
suffix and the governing specification digest. Every assertion and original
fixture byte is preserved. Codec, provider, configuration, and runtime profile
identities are unchanged.

The proposal remains untrusted pending review; preparation does not approve
records, activate the catalog, or award conformance. `baseline-catalog.json`
preserves the exact active catalog bytes at preparation. Earlier review packets,
goldens, and approved rebaseline records remain historical evidence.

Reproduce the proposal with `python build/prepare-lz4-spec-review.py` while its
baseline is still active. The script checks case counts, fixture digests,
unaffected identity fields and token mappings, and every unchanged golden field.
The candidate additionally passed `Read-ConformanceCatalog` validation. Negative
checks rejected a changed provider/codec identity, lost case, and removed
configuration mapping.

The pinned activation implementation has a historical default packet directory.
Any authorized activation must explicitly configure its `PACKET` variable to
this directory, supply this packet's exact SHA-256 and the approving maintainer,
and preserve the historical implementation and packet. Then rerun current
conformance evidence; previous passing results do not certify new identities.

This exact proposal was subsequently activated as part of the resumed
specification change. The original review bytes remain unchanged; effective
approval records and final catalog identity are recorded in
[`../activation.json`](../activation.json). Fresh execution then identified the
old diagnostic profile expectations addressed by the separately approved
profile-rebinding packet. Neither packet copies historical PASS results.

Ignored duplicate synthetic binaries are reconstructed only for historical
inspection by `python build/materialize-lz4-historical-sources.py`. The active
catalog uses original tracked synthetic sources and validates with those
duplicates absent.
