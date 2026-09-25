# Replace expanded assurance catalogs with Assurance v2

JBSA will replace the active `conformance-v1` and `performance-v1` expanded proof catalogs with Assurance v2: compact, versioned rules generate stable behavioral scenarios, while performance qualification selects representative materially distinct implementation paths by risk. The expanded catalogs imposed repository, review, and AI-context costs far beyond the assurance they added, and they coupled stable semantic expectations to volatile run and specification identities.

Assurance v2 preserves hard, deterministic `PASS`, `FAIL`, and `INVALID` outcomes; semantic goldens; surface-specific reference authority; independent validators; malformed-input and safety coverage; and separate manual Release Qualification. It changes how this evidence is planned and retained: scenario expectations describe behavior, each run binds volatile identities in an Evidence Capsule, and bulky raw evidence lives in local build output or attached CI/release artifacts rather than the active source tree.

Performance qualification will cover throughput, random access, peak memory, parallel scaling, and output size through a small set of representative implementation-path scenarios. A scaling scenario carries its complete worker vector, and output-size evidence is derived from the corresponding pack run. After the first release, the current released JBSA baseline is the primary same-machine comparator; Conformance Oracle comparisons remain a focused canary rather than an exhaustive product matrix.

Existing CV1 and performance-v1 material remains immutable historical provenance and does not qualify a new candidate. In particular, interrupted or incomplete `0x69` BSA evidence is recorded as incomplete rather than completed retroactively. The [Assurance v2 candidate contract](../development/assurance-v2.md) remains informative until the required shadow comparison and normative cutover complete.

_Decision source: [accepted Assurance v2 replacement](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._
