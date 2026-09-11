# Ticket 43 implementation review

Review base: `0ca37d5a02b9ae9b7adccffd4f2cb667339a65f5`.
The two independent code-review axes examined the staged implementation before
commit. Historical conformance packets remain unchanged.

## Standards

Two hard findings were corrected: the copied upstream BSD texts now have exact
REUSE overrides and SPDX license files, and the checkpoint measurement method
has method documentation. An optional helper-naming smell was considered;
the shared frame status helper remains internal and distinguishes encode/decode
context creation, so no additional abstraction was introduced.

## Specification

The initial review identified a sequencing finding: JBSA-PERF-003 required formal
qualification after changing the provider set and before merging codec changes. The retained
39 observations are supplemental adapter measurements, not Performance-v1
acceptance. Direct LZ4 impact comprises 336 catalog cases in BSA 0x69 and
Starfield General/DDS method-3 families; those family consumers are later
tickets. A full profile change also affects the identity of the remaining
1,048 stored/zlib assignments. Existing conformance and performance identities
cannot be silently reused with the new profile digest.

The maintainer resumed the proposed sequencing change on 2026-09-10.
Specification 0.14.0 now permits this initial runtime-only implementation to
finish after its local adapter gates. The formal archive performance gates
remain open; the measurements are not reclassified as Performance-v1 success.

## Accepted sequencing clarification

For initial, pre-release codec-runtime integration with no new Archive Family
consumer, permit the adapter corpus, malformed-data, resource-credit,
cancellation-delay, determinism, throughput/output-size, launch, and notice
gates to establish readiness for dependent implementation tickets. Retain an
exact affected-case impact manifest. Do not treat unavailable Archive Family
cases as N/A or claim Performance-v1 success. Require formal affected archive
cases before family qualification and the complete matrix before release.

This amendment is applied in JBSA-PERF-003. It is limited to required-runtime
integration before Interface Freeze and the first release, with no Archive
Family codec consumer added and no existing codec algorithm or archive behavior
changed apart from required profile-identity rebinding. Historical results are
not relabelled as current qualification evidence.

## Golden profile identity review

Fresh CV1 execution under the new profile initially produced 97 passes and 12
failures. All 27 failed assertion comparisons differed solely at diagnostic
`values.profile`: the inherited expectations named `jbsa-jdk-zlib-v1`, while
JBSA-CODEC-006 and JBSA-CODEC-012 require the current `jbsa-lz4-v1` identity.
The exact differences are retained in `profile-identity-differences.json`.

The maintainer explicitly approved profile packet SHA-256
`353e3843a95874f21a971d503f3e14fbc66179db067c17f721c4c0eafd125e0c` on
2026-09-10. Independent recursive comparison verified that only those 27 fields
in 12 goldens and successor case IDs change; all other expected values are
unchanged. The values derive from the pinned old/new profile manifests, not
from candidate output. Approval records retain the reviewed semantic difference;
supplemental JSON paths remain in the immutable packet because the strict
approval-record schema does not permit that extra annotation field.

Both specification and profile rebaseline packets remain immutable. The
complete chain passes `verify-conformance-rebaseline.ps1` with 145 reviewed
golden bindings. A separate source-location correction changes only 16
unqualified cases and preserves all 109 admitted cases exactly, keeping copied
binary data out of the repository's restricted review locations.

The continuation's Standards review found no hard violations. Its optional
maintainability observation concerns the profile preparer's source-based reuse
of the hash-bound specification preparer; neither frozen source nor reviewed
expectations were changed to refactor that historical preparation dependency.
