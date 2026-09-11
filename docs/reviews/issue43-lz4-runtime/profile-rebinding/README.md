# Diagnostic codec profile rebaseline

This separate review packet preserves the active specification-only rebinding
packet and every historical admitted golden. It proposes 109 successor goldens
and retains all 508 catalog cases. Fixture bytes, providers, operations and the
governing specification digest are unchanged.

The only expectation edits are 27 diagnostic `values.profile` leaves across
12 goldens, replacing `jbsa-jdk-zlib-v1` with `jbsa-lz4-v1`. Each proposal and
pending review record enumerates its exact RFC 6901 JSON paths. All 109 goldens
receive successor case identities ending in fixture suffix `-profile-v1` so
their provenance can be reviewed and admitted as a complete packet.

The old identity comes from the packaged manifest at immutable commit
`0ca37d5a02b9ae9b7adccffd4f2cb667339a65f5`; the new identity comes from the
current packaged manifest source. Both exact manifests are retained under
`authority/` and digest-bound by `review.json`. Candidate execution output is
never read by the generator or used to derive expectations.

Generation validated successor golden equality against the constrained
transformation and preserved case counts and fixture digests. An additional
independent recursive JSON diff confirmed exactly 109 case identity changes,
27 diagnostic profile leaves, and zero other golden field edits. The packet
remains pending review; generation does not activate it or establish runtime
qualification.

The maintainer subsequently approved this exact packet's SHA-256
`353e3843a95874f21a971d503f3e14fbc66179db067c17f721c4c0eafd125e0c`.
Activation used `build/activate-lz4-profile-review.py`: the legacy activator
rejected the supplemental JSON-path annotation under the strict approval
schema. The new activator preserves all reviewed golden bytes and records the
schema-defined approval fields; the annotation remains in the immutable review
packet. [`../activation.json`](../activation.json) binds the actual activation
tool, approved records, and final catalog. The original proposal retains its
pre-approval status as historical evidence.

Generator: `build/prepare-lz4-profile-review.py`. It reuses the independently
bound specification packet generator's corpus/catalog construction with the
new suffix; the source generator is preserved unchanged and separately bound.
