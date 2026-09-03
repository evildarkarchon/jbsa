# Performance v1

This specification owns the local `performance-v1` qualification contract:
Performance Case identity, the deterministic Benchmark Corpus, paired execution,
metrics, numerical gates, baseline lifecycle, and evidence. It measures only
behavior already accepted by [Conformance v1](conformance-v1.md); it does not
change the [format](README.md#specification-index), [codec](codecs.md),
[I/O](io-and-publication.md), or [execution](execution-model.md) contracts.

## JBSA-PERF-001

Every Performance Case **MUST** have an immutable identifier serialized exactly
as
`PV1-<surface>.<workload>.<family-or-layout>.<codec-provider>.<workers>`.
Each placeholder is a lowercase ASCII token matching
`[a-z0-9]+(?:-[a-z0-9]+)*`; the period is the field separator and is forbidden
inside a token. The case manifest **MUST** contain exactly these identity fields:

| Field | Value |
| --- | --- |
| `case_id` | the serialized identifier |
| `contract` | `performance-v1` |
| `surface` | `pack-throughput`, `unpack-throughput`, `random-metadata`, `random-payload`, `pack-memory`, `unpack-memory`, `pack-output`, `pack-scaling`, or `unpack-scaling` |
| `workload` | the Benchmark Corpus workload token |
| `archive_family_or_layout` | the `<family-or-layout>` token |
| `codec_provider` | the `<codec-provider>` token |
| `workers` | `w1`, `w2`, `w4`, `w8`, `w16`, `automatic`, or `none` |

The manifest **MUST** map the family-or-layout token to exact wire selectors and
map the codec-provider token to one codec, provider, codec-profile digest, and
provider configuration. `none` **MUST** be used only when a provider or worker
count is structurally irrelevant. An identity field or its digest mapping
**MUST NOT** change in place; a changed mapping creates a new Performance Case.

_Source decisions: [accepted Performance Case key](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [issue 27 PV1 identity acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-PERF-002

A Performance Case **MUST** run only after every Conformance Case applicable to
its input, operation, codec/provider configuration, worker mode, and output has
passed for the exact candidate. Each executed case **MUST** have exactly one
outcome:

- `PASS`: every applicable correctness, identity, evidence, and numerical gate
  passed;
- `FAIL`: a confidence interval is conclusively beyond a gate, produced output
  is invalid, or a deterministic output-size or Binary Conformance gate failed;
  or
- `INVALID`: a prerequisite or identity mismatched, environmental noise remained
  excessive, instrumentation failed, a confidence interval remained
  inconclusive, or required evidence was incomplete.

A structurally inapplicable matrix cell **MUST** be `N/A` and **MUST NOT** create
a Performance Case. A qualification passes only when every required case is
`PASS`; `INVALID`, percentages, composites, aggregate scores, expected failures,
and waivers **MUST NOT** count as success.

This requirement refines [JBSA-SCOPE-005](scope.md#jbsa-scope-005).

_Source decisions: [accepted correctness prerequisite and outcome rules](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [issue 27 invalid-run acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-PERF-003

Normative performance qualification **MUST** run locally on Windows x64 and NTFS
and **MUST NOT** run in GitHub Actions, on a project self-hosted runner, or as
part of ordinary `mvn verify`. A full qualification **MUST** run before every
release and after a Benchmark Corpus, JVM identity, provider set or
configuration, protocol, or Performance Baseline change. A targeted
qualification **MUST** run before merging a change to codec implementation,
archive I/O, buffering, concurrency, native-provider implementation within the
same provider identity, or the public random-access path; its impact manifest
**MUST** select every affected Performance Case and its required comparators.
A short developer smoke suite **MAY** run elsewhere but **MUST NOT** supply
acceptance evidence.

_Source decisions: [accepted local execution and requalification triggers](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [issue 27 targeted/full qualification acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-PERF-004

Reference Performance Qualification **MUST** execute the candidate and
Conformance Oracle together on the same available Windows x64/NTFS machine; a
post-first-release qualification **MUST** also execute the current
digest-pinned Performance Baseline in those same rounds. Acceptance **MUST** use
within-round candidate/oracle and candidate/baseline ratios, never a comparison
with stored absolute timings or a result from another machine. CPU, memory,
storage, Windows, firmware, and power-plan details **MUST** be captured as
non-normative diagnostic context. A particular CPU, memory configuration,
storage device, or Windows build **MUST NOT** define acceptance. The first
release **MUST** compare candidate with oracle and use the
passing candidate artifacts to establish the first baseline.

_Source decision: [accepted hardware-neutral paired authority](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-005

The initial process and in-process environment **MUST** use Eclipse Temurin
`25.0.4.1+1` HotSpot x64, with the distribution SHA-256 recorded by the protocol
manifest. CLI cases **MUST** use the shipping launcher and production flags,
including the release's selected provider and native-access configuration, and
**MUST NOT** add benchmark-only tuning. In-process cases **MUST** use JMH 1.37
with exactly `-Xms4g -Xmx4g -XX:+AlwaysPreTouch -XX:+UseG1GC`. A JVM major,
vendor, patch, distribution digest, garbage collector, option, codec-profile,
provider version, or provider-configuration mismatch **MUST** make the run
`INVALID`. Adoption of another Java 25 security update **MUST** rerun candidate,
oracle, and baseline together on that JVM; historical absolute timings remain
evidence only.

_Source decision: [accepted pinned software environment](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-006

The Benchmark Corpus **MUST** commit versioned generators, algorithm and seed
identities, manifests, and small structural templates, but **MUST NOT** commit
multi-gigabyte materialized workloads or proprietary game archives. Generation,
including compression-neutral source construction, **MUST** occur outside every
timed region. The corpus manifest **MUST** state the generator and corpus
versions, algorithm names and versions, seeds, relative paths, exact byte length
and SHA-256 of every generated file, logical totals, structural metadata, and a
digest over the canonical manifest. Materialization **MUST** fail on any byte,
length, path, or digest mismatch. Proprietary local archives **MAY** corroborate
a release result but **MUST NOT** become a gating case or part of the Benchmark
Corpus identity.

_Source decisions: [accepted deterministic corpus construction](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [accepted proprietary-fixture boundary](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-PERF-007

The `performance-v1` Benchmark Corpus **MUST** define exactly these required
workloads, where one MiB is `1,048,576` bytes and one GiB is
`1,073,741,824` bytes:

| Workload token | Required content |
| --- | --- |
| `metadata-100k` | 100,000 nested small files totaling exactly 256 MiB |
| `mixed-10k` | 10,000 files totaling exactly 2 GiB, with manifest-fixed compressible, pseudorandom, and extension-based no-compress proportions |
| `bulk-compressible` | eight deterministic structured files of exactly 256 MiB each |
| `bulk-incompressible` | eight files of exactly 256 MiB each generated by the manifest-pinned pseudorandom algorithm and seed |
| `dds-mipmapped` | 256 valid generated textures totaling exactly 2 GiB and spanning materially distinct block formats, dimensions, mip counts, cubemaps, and archive chunk boundaries |
| `shared-content` | 10,000 names over 5,000 distinct payloads totaling exactly 1 GiB logically |

Each workload manifest **MUST** enumerate its exact content mix and structural
coverage so the totals and stated distinctions are machine-verifiable.

_Source decision: [accepted Benchmark Corpus workloads](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-008

The Performance Case manifest **MUST** cover this gating matrix:

- `mixed-10k` for every writable Archive Family and codec combination in
  [JBSA-CONF-008](conformance-v1.md#jbsa-conf-008);
- `metadata-100k` for every materially distinct TES3, classic BSA, SSE BSA,
  General BA2, and DDS BA2 indexing or layout path;
- `bulk-compressible` and `bulk-incompressible` for every stored, zlib,
  LZ4-frame, and raw-LZ4 provider path;
- `dds-mipmapped` for every writable DDS BA2 version and codec path;
- `shared-content` for representative classic-BSA and General-BA2 paths, each
  with sharing enabled and disabled;
- a stored, incompressible versioned-BSA case whose output crosses the 2 GiB
  split boundary;
- manifest-sized medium-scale unpack cases for each decode-only FO4 General and
  DDS BA2 version-7 and version-8 layout; and
- parallel-scaling cases on `bulk-compressible`, `mixed-10k`, and
  `dds-mipmapped` for every applicable pack and unpack path.

A case **MAY** satisfy multiple rows, but every listed family, layout, codec,
provider, sharing mode, and direction lane **MUST** pass independently.

_Source decisions: [accepted Performance Case gating matrix](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [issue 27 deterministic matrix acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-PERF-009

Base pack cases **MUST** invoke candidate, baseline, and Conformance Oracle with
equivalent semantics represented by:

```text
pack <source> <archive> -<family> [-z:<codec>] -split:<limit> -share:no -mt:<yes|no>
```

TES3 and versioned-BSA cases **MUST** use `-split:2`; all BA2 cases **MUST** use
`-split:0`. Sharing cases **MUST** differ only by changing `-share:no` to
`-share:yes`. Single-threaded cases **MUST** use `-mt:no`; the oracle comparator
for a candidate automatic or multiworker case **MUST** use `-mt:yes` because the
oracle exposes no explicit worker count. The case manifest **MUST** provide the
exact family, codec, sharing, split, and threading switch mapping for each
executable.

Unpack cases **MUST** give all compared executables the same
Conformance-Oracle-produced archive and equivalent semantics represented by:

```text
unpack <archive> <empty-destination> -mt:<yes|no>
```

Every path **MUST** be passed as one correctly quoted argument, and each process
invocation **MUST** use a fresh empty output path.

_Source decision: [accepted reference and candidate invocation protocol](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-010

A full process qualification **MUST** begin after a reboot and a ten-minute idle
period with the High performance power plan active, background CPU below 2%, at
least 20% free space on the benchmark volume, normal security software active,
and no concurrent build, synchronization, or foreground workload. Normative
measurements **MUST** use a warmed filesystem page cache produced by pre-reading
the exact inputs before warmup rounds; an after-reboot cold observation **MAY**
be retained but **MUST NOT** gate. The harness **MUST** verify oracle, candidate,
baseline, corpus, and protocol digests before invocation and **MUST** report a
mismatch or failed environmental precondition as `INVALID`.

External monotonic wall time **MUST** include process creation, JVM startup, the
operation, and process exit. Corpus generation, cache warming, setup, cleanup,
hashing, correctness and artifact validation, and result serialization **MUST**
remain outside the timed interval. BSArch-printed timing **MUST NOT** be used.

_Source decision: [accepted cache, environment, and timed-region protocol](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-011

Each process-level case **MUST** discard two complete warmup rounds and collect
seven measured rounds. A round **MUST** execute each required comparator once;
the candidate, baseline, and oracle order **MUST** rotate as a balanced design,
or candidate and oracle order **MUST** alternate for the first release. Ratios
**MUST** be formed only from observations in the same round. The reported point
estimate **MUST** be the median paired ratio, and the 95% interval **MUST** be the
two-sided nonparametric percentile bootstrap interval over paired ratios, with
the resampling count and seed pinned in the protocol manifest.

Relative dispersion **MUST** be the median absolute deviation of paired ratios
divided by the absolute median ratio. If the seven-round interval straddles an
acceptance boundary, with one bound passing and one bound failing, or relative
dispersion exceeds `0.05`, the case **MUST** be extended to a total of fifteen
measured rounds. After fifteen rounds, an interval still straddling the boundary
or dispersion still exceeding `0.05` **MUST** produce `INVALID`. For a
lower-bound gate, an interval wholly below the boundary **MUST** produce `FAIL`;
for an upper-bound gate, an interval wholly above the boundary **MUST** produce
`FAIL`.

_Source decision: [accepted balanced sampling and confidence protocol](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-012

In-process random-access cases **MUST** alternate candidate and baseline JMH
JARs. Each artifact **MUST** run three forks, five two-second warmup iterations
per fork, and ten two-second measurement iterations per fork. Setup **MUST** be
trial-scoped, a manifest-pinned seed **MUST** select entries, and results **MUST**
be consumed to prevent dead-code elimination. JMH `SampleTime` **MUST** supply
random-access latency percentiles, `Throughput` **MUST** supply operations per
second, and allocation or memory profiling **MUST** run separately from
unprofiled timing. JMH JSON and every fork and iteration sample **MUST** be
retained.

_Source decision: [accepted JMH protocol](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-013

Performance evidence **MUST** compute metrics as follows:

| Metric | Definition |
| --- | --- |
| pack throughput | uncompressed source MiB divided by external process wall seconds |
| unpack throughput | logical extracted MiB divided by external process wall seconds |
| random metadata access | lookup operations per second and p50, p95, and p99 latency |
| random payload access | read operations per second and p50, p95, and p99 latency, reported separately from metadata lookup |
| peak memory | peak private committed bytes, peak working set, and Java heap high-water |
| parallel scaling | speedup `T1 / Tn` and efficiency `(T1 / Tn) / n`, where `T1` and `Tn` are paired wall times |
| output size | exact produced bytes, candidate/oracle ratio and difference, and candidate/baseline ratio and difference when a baseline exists |

For split versioned-BSA output, produced bytes **MUST** be the sum of every
published part. Each metric **MUST** retain raw samples, medians, relative
dispersion, applicable confidence bounds, corpus and protocol digests, complete
case configuration, all tool/JVM/provider identities, and the non-normative
environment fingerprint.

_Source decision: [accepted performance metrics and evidence fields](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-014

Every gating pack-throughput and unpack-throughput case **MUST** run in both
single-threaded and automatic/multithreaded modes where supported. The lower 95%
confidence bound of candidate/oracle throughput **MUST** be at least `0.80` in
each case. The oracle **MUST** be measured only at `-mt:no` and `-mt:yes`; an
explicit JBSA worker count **MUST** compare with `-mt:no` for one worker and
`-mt:yes` for multiple workers.

_Source decision: [accepted oracle throughput gate](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-015

Because the Conformance Oracle has no equivalent random-entry operation, the
first Performance Baseline **MUST** satisfy all of these internal gates:

- increasing the metadata index from 10,000 to 100,000 entries increases median
  lookup latency by no more than `1.5x`;
- fixed-size stored-entry p99 lookup/read latency is no more than `5x` its
  median;
- compressed-entry p99 latency within one manifest-defined payload-size class
  is no more than `10x` its median; and
- warm-cache random payload throughput is at least `0.50x` size-matched
  sequential extraction throughput.

Every ratio **MUST** compare like corpus, family/layout, codec/provider, and
worker configurations except for the stated independent variable. A case that
cannot establish the pairing **MUST** be `INVALID`.

_Source decision: [accepted first-release random-access bootstrap gates](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-016

Peak-memory cases **MUST** use a separate Windows Job Object harness, sample
private committed bytes and working set every 50 ms, read final Win32 peak
counters, and derive Java heap high-water from JFR GC heap summaries. Each case
**MUST** run five repetitions; the maximum observed value **MUST** gate and the
median **MUST** be reported. All applicable ceilings **MUST** pass:

- candidate peak private committed bytes and candidate peak working set each
  **MUST NOT** exceed the corresponding oracle value plus
  `max(512 MiB, 25% of logical input bytes)`;
- when a baseline exists, neither candidate measure **MUST** exceed
  `max(1.10 * baseline, baseline + 64 MiB)`; and
- Java heap high-water **MUST NOT** exceed
  `512 MiB + 2 * largest uncompressed entry size`.

Instrumentation failure, a missing peak counter, or missing JFR evidence **MUST**
make the case `INVALID`.

_Source decision: [accepted peak-memory measurement and gates](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-017

Every pack-output case **MUST** pass all applicable deterministic size gates:

- a Binary Conformance case **MUST** equal the qualified oracle output byte for
  byte;
- any other stored case **MUST NOT** exceed the oracle by more than
  `max(65,536 bytes, 0.005 * oracle bytes)`;
- a compressed or DDS case **MUST NOT** exceed the oracle by more than
  `max(1,048,576 bytes, 0.05 * oracle bytes)`; and
- when a Performance Baseline exists, candidate output **MUST NOT** exceed it by
  more than `max(65,536 bytes, 0.01 * baseline bytes)`.

All comparisons **MUST** use exact integer byte counts; a fractional allowance
**MUST** be rounded down before applying the maximum. A split output **MUST** use
the sum defined by [JBSA-PERF-013](#jbsa-perf-013).

_Source decision: [accepted output-size gates](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-018

JBSA parallel-scaling cases **MUST** measure worker counts `1`, `2`, `4`, `8`,
and `16` when the count does not exceed available logical processors; an
unavailable count is structurally `N/A`. Gates **MUST** apply through
`min(8, available logical processors)` and require paired median speedup over one
worker of at least `1.50x` at two workers, `2.50x` at four workers, and `4.00x`
at eight workers. A measured count above eight is observational but **MUST NOT**
be more than 5% slower in throughput than the best measured lower count. Every
reported count **MUST** include speedup and efficiency, and failure at one count
**MUST NOT** be averaged with another count.

_Source decision: [accepted worker-count and scaling gates](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-019

Against the current Performance Baseline, each matching case **MUST** pass every
applicable regression gate: the lower 95% confidence bound of candidate/baseline
pack, unpack, and random-access throughput is at least `0.95`; the upper 95%
confidence bound of candidate/baseline random-access p50 latency is at most
`1.05`; and the corresponding p95 and p99 upper bounds are at most `1.10`.
Peak memory and output size **MUST** also pass
[JBSA-PERF-016](#jbsa-perf-016) and [JBSA-PERF-017](#jbsa-perf-017). A missing
matching baseline case or comparator identity mismatch **MUST** make the case
`INVALID`, not remove the gate.

_Source decision: [accepted baseline regression gates](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706)._

## JBSA-PERF-020

An optimization **MUST NOT** trade away or silently demote Binary Conformance.
A byte-changing optimization **MUST** be disabled by the designated case's
pinned provider and configuration or rejected for that case; an optimized path
**MUST** pass the complete Binary Conformance protocol in
[JBSA-CONF-015](conformance-v1.md#jbsa-conf-015) before it may receive that
claim. Performance, conformance, memory, and binary results **MUST** remain
separate by provider and immutable codec-profile identity; a provider **MUST NOT**
borrow another provider's evidence.

This requirement refines [JBSA-CODEC-013](codecs.md#jbsa-codec-013).

_Source decisions: [accepted Binary Conformance optimization constraint](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [accepted provider qualification boundary](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-PERF-021

A Performance Baseline **MUST** be an immutable, versioned, digest-pinned bundle
containing the released CLI distribution, standalone JMH JAR, dependency and
provider inventory, launcher configuration, Benchmark Corpus identity, JVM
identity, protocol identity, and the accepted result set. The first passing full
qualification **MUST** establish the first baseline. A proposed replacement
**MUST** run beside the candidate and old baseline and pass every applicable
old-baseline gate before approval. Rebaselining **MUST** record old and new
bundle and result digests, corpus, machine/JVM diagnostic, provider, complete
results, rationale, affected cases, and explicit maintainer approval.

A necessary safety or Conformance change that cannot pass an existing gate
**MUST** produce a new accepted versioned performance-contract decision; routine
rebaseline **MUST NOT** absorb the regression.

_Source decisions: [accepted baseline lifecycle and rebaseline policy](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [accepted affected-gate reset policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-PERF-022

Every qualification **MUST** retain the committed protocol manifest and
Benchmark Corpus definition; machine-readable JSON with every raw sample,
paired ratio, median, dispersion, confidence interval, metric result, and case
outcome; a concise Markdown case matrix; oracle, baseline, candidate, JVM,
corpus, codec-profile, provider, and protocol digests; and compressed raw
streams, JFR data, and memory traces. The JSON **MUST** contain `case_id`,
`contract`, all identity-field mappings from [JBSA-PERF-001](#jbsa-perf-001),
comparator identities, prerequisite Conformance Case identifiers, environment
fingerprint, round order, raw observations, derived metrics, gate identifiers
and bounds, and outcome. The harness **MUST** reject missing, duplicate, or
unknown required case identifiers.

Accepted baseline bundles, raw attachments, and reports **MUST** be ancillary
GitHub Release assets and verification evidence, not product artifacts, and
**MUST** be linked from the relevant qualification issue or release record.

_Source decisions: [accepted performance evidence and publication boundary](https://github.com/evildarkarchon/jbsa/issues/14#issuecomment-5518983706), [accepted product/evidence separation](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._
