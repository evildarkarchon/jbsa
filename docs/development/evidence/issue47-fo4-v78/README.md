# Issue 47 Fallout 4 BA2 v7/v8 evidence

This development record binds the project-authored General and DDS vectors, independent bounded
wire scanners, public API/CLI tests, digest-pinned oracle decode observations, local shipping-corpus
observations, and decode-only performance checkpoints to issue 47. It is Assurance v2 evidence,
not a new conformance-v1 or performance-v1 packet and not a Binary Conformance claim.

Fallout 4 General producer coverage is intentionally asymmetric. The Creation Kit emits version 8,
while third-party tools commonly retain version 1 for compatibility. No known producer emits a
version-7 General archive. JBSA therefore implements the Reference Snapshot's bounded v7 General
decode path and tests it with independently authored vectors plus the Conformance Oracle, but does
not guarantee shipping compatibility for that unobserved combination. Version-8 General and
versions 7/8 DDS additionally have read-only local shipping-corpus observations. Fallout 4 encoding
remains canonical version 1; every v7/v8 encode request is rejected before source or destination
effects.

`qualification.json` records the reproducible commands and qualification boundary.
`performance-checkpoint.json` retains the measured ranges for metadata, unpack/reconstruction,
random entry opens, and heap-pool peak sums. Raw local-only evidence remains under ignored `target/`
directories and no proprietary game bytes are committed.
