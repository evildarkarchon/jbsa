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

One unresolved sequencing finding: JBSA-PERF-003 requires formal qualification
after changing the provider set and before merging codec changes. The retained
39 observations are supplemental adapter measurements, not Performance-v1
acceptance. Direct LZ4 impact comprises 336 catalog cases in BSA 0x69 and
Starfield General/DDS method-3 families; those family consumers are later
tickets. A full profile change also affects the identity of the remaining
1,048 stored/zlib assignments. Existing conformance and performance identities
cannot be silently reused with the new profile digest.

The current code and supplemental evidence must not be represented as clearing
this permanent merge gate until the sequencing is explicitly resolved or the
required formal qualification passes. The implementation request does not by
itself authorize weakening the permanent specification.

## Proposed sequencing clarification — awaiting maintainer decision

For initial, pre-release codec-runtime integration with no new Archive Family
consumer, permit the adapter corpus, malformed-data, resource-credit,
cancellation-delay, determinism, throughput/output-size, launch, and notice
gates to establish readiness for dependent implementation tickets. Retain an
exact affected-case impact manifest. Do not treat unavailable Archive Family
cases as N/A or claim Performance-v1 success. Require formal affected archive
cases before family qualification and the complete matrix before release.

This is a proposed amendment to JBSA-PERF-003 and ticket sequencing, not an
applied specification change or an existing exception.
