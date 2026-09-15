# Issue 46 Starfield DDS BA2 evidence

This concise development record binds the independently authored Starfield DDS fixtures, wire
scanner, public conformance tests, and targeted performance checkpoint to issue 46. It is Assurance
v2 evidence, not a new conformance-v1 or performance-v1 packet.

`qualification.json` records reproducible automated results, including both digest-pinned local
oracle directions and 66 successful observations from `texdiag` built from the recorded DirectXTex
source revision. The bounded independent wire scanner remains a separate corroborating validator.
The opt-in checkpoint writes raw measurements beneath `target/dds-performance-checkpoint/`;
`performance-checkpoint.json` retains the selected lane ranges without inventing portable timing
thresholds.
