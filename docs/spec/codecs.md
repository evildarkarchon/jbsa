# Codecs

This specification owns the release-pinned internal codec, hashing, and DDS
implementation profile. Archive Family specifications own wire framing and
format-specific codec permission; [Library interface](library-interface.md)
owns caller choices; [Operation semantics](operation-semantics.md) owns public
failures; and [Execution model](execution-model.md) owns scheduling.

## JBSA-CODEC-001

Codec, hashing, DDS, provider, native-handle, buffer, threshold, and dispatch
types **MUST** remain internal to the single `jbsa` artifact. The release
**MUST NOT** expose a provider selector, provider SPI, provider artifact,
generalized LZ4 adapter port, native handle or path, or third-party type through
an exported interface.

This requirement refines [JBSA-BUILD-003](modules-and-build.md#jbsa-build-003),
[JBSA-BUILD-004](modules-and-build.md#jbsa-build-004), and
[JBSA-BUILD-006](modules-and-build.md#jbsa-build-006).

_Source decision: [accepted internal codec and provider boundary](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-002

The initial standard codec stack **MUST** use Java 25 `Deflater` and `Inflater`
for the baseline zlib implementation and one hidden LWJGL 3.4.3 adapter backed
by upstream LZ4 1.10.0 on Windows x64 for raw LZ4 HC and LZ4 frame. LZ4
capability **MUST** be required only for an operation whose valid Archive Family
and on-wire method require LZ4, and JBSA **MUST NOT** substitute one compression
algorithm for another.

Archive Family codec permission and wire framing remain owned by
[JBSA-BSA-001](formats/versioned-bsa.md#jbsa-bsa-001),
[JBSA-BSA-009](formats/versioned-bsa.md#jbsa-bsa-009),
[JBSA-BSA-010](formats/versioned-bsa.md#jbsa-bsa-010),
[JBSA-GNRL-002](formats/general-ba2.md#jbsa-gnrl-002),
[JBSA-GNRL-005](formats/general-ba2.md#jbsa-gnrl-005), and
[JBSA-DX10-004](formats/dds-ba2.md#jbsa-dx10-004).

_Source decision: [accepted zlib and LZ4 implementations](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-003

Bethesda wire-name hashes **MUST** be repository-owned pure-Java
implementations of the algorithms specified by
[JBSA-TES3-003](formats/tes3-bsa.md#jbsa-tes3-003),
[JBSA-BSA-006](formats/versioned-bsa.md#jbsa-bsa-006), and
[JBSA-GNRL-007](formats/general-ba2.md#jbsa-gnrl-007). Internal XXH32 and XXH64
uses **MUST** use Airlift 3.7 and **MUST** remain noncryptographic lookup,
filtering, or data-sharing aids. An XXH value **MUST NOT** replace a wire-name
hash or establish name or payload equality without the owning specification's
normalized-name or byte comparison.

_Source decision: [accepted hashing implementation strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-004

DDS envelope parsing, header and format mapping, mip layout, input analysis, and
canonical header writing **MUST** be implemented internally in pure Java and
**MUST** satisfy [the DDS payload specification](formats/dds-payload.md). The
DDS implementation **MUST NOT** expose a provider seam or claim to encode or
decode BC-compressed pixel blocks.

_Source decision: [accepted internal DDS strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-005

`BethesdaArchives.standard()` **MUST** resolve to one immutable codec profile
fixed by the JBSA release. A caller **MAY** choose only a codec permitted by the
target Archive Family and **MUST NOT** choose a provider, provider version,
compression level, frame flag, size threshold, native path, or fallback order.

An environment toggle **MUST NOT** mutate the standard profile. A provider,
parameter, threshold, fallback, or native-configuration change **MUST** create a
newly qualified release profile rather than silently change an existing one.

_Source decision: [accepted immutable standard codec profile](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-006

Each release **MUST** contain a `META-INF` codec-profile manifest with an opaque
profile identifier and digest and the exact provider versions, parameters,
deterministic size-dispatch rules, and native configuration. Conformance and
benchmark evidence **MUST** record the same identity. A public diagnostic **MAY**
reference the opaque identifier but **MUST NOT** expose provider objects,
provider exception types, or native implementation details.

The manifest filename, digest algorithm, and canonical serialization remain
release implementation details until their owning build specification fixes
them.

_Source decision: [accepted codec profile identity and manifest](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-007

A package-internal jlibdeflate adapter **MUST** remain available only to
build-time qualification paths and **MUST** be absent from the normal CLI
application image unless its exact version and configuration pass Decode
Conformance, Encode Conformance, deterministic-output, performance, memory,
native-loading, packaging, and notice gates.

Its whole-buffer, `int`-bounded path **MUST** be eligible only within a qualified
range and after complete memory credit is acquired. Promotion **MUST** create a
new immutable codec profile and rerun all affected evidence. JBSA **MUST NOT**
retain libdeflate 1.24 solely to pursue byte identity with the Reference
Snapshot; the candidate implementation and its level 12 setting remain profile
inputs rather than public configuration.

_Source decision: [accepted jlibdeflate qualification lane](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-008

Logical codec sizes and positions **MUST** remain `long`, and every provider
limit or narrowing conversion **MUST** be checked before invocation. JDK zlib
**MUST** process large inputs incrementally. LZ4 frames **MUST** stream through
bounded direct buffers. Raw LZ4 **MUST** reject input above the upstream
`0x7E000000` maximum before narrowing and **MAY** split only where the Archive
Family wire layout already defines independent chunks. An oversized
unsplittable raw block **MUST** fail before output effects.

Whole-buffer dispatch thresholds and buffer sizes **MUST** remain internal and
obey [JBSA-IO-005](io-and-publication.md#jbsa-io-005).

_Source decisions: [accepted bounded codec processing](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971), [accepted bounded I/O model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-CODEC-009

After resolving the Archive Family and requested wire codec, JBSA **MUST**
preflight that same-codec capability before destination side effects. A
qualified native-zlib decoder **MAY** fall back to JDK zlib only when that native
provider is unavailable. Invalid compressed data, decoded-size mismatch, or a
provider execution failure **MUST NOT** be retried through another provider.

Encoding **MUST** pin one provider before work and **MUST NOT** fall back
mid-operation. Unavailable native LZ4 **MUST** fail only operations requiring
LZ4 and **MUST NOT** disable stored or applicable zlib operations.

_Source decision: [accepted capability preflight and fallback model](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-010

Codec adapters **MUST** be reentrant, permit concurrent calls, own no executor,
and use independent closeable per-call or per-worker state. Buffer and codec
state reuse **MUST** remain operation- or worker-scoped. Every adapter **MUST**
declare bounded worst-case resource costs to the internal execution model, and
its state **MUST** be closed when the call or worker ends.

_Source decisions: [accepted adapter concurrency](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971), [accepted resource-credit ownership](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-CODEC-011

Required Windows x64 native artifacts and launcher native-access grants **MUST**
be pinned in the Windows application image. A Maven embedder **MUST** supply the
equivalent runtime artifacts and Java 25 native-access policy; JBSA **MUST NOT**
change host-process policy.

Native providers **MUST** load lazily through their JAR-resource extraction when
an operation first requires them, **MUST** remain loaded for the process
lifetime, **MUST NOT** accept a caller-supplied DLL path, and **MUST NOT** attempt
unloading. Before jlibdeflate promotion, its native runtime **MUST NOT** be part
of the normal CLI image.

_Source decision: [accepted native distribution and lifetime](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-CODEC-012

The internal codec seam **MUST** normalize failure data into stable codec,
direction, archive location, expected and actual sizes where applicable,
capability cause, and opaque profile identity. Platform, native-access, or
provider unavailability **MUST** report `CAPABILITY`; invalid compressed data or
decoded-size mismatch **MUST** report `FORMAT`; and an unexpected provider fault
**MUST** report `INTERNAL`.

An ordinary encoding or compression failure **MUST** use diagnostic identifier
`codec.compression-failed`. An unexpected provider fault **MUST** use diagnostic
identifier `codec.provider-fault`. Both have Failure Kind `INTERNAL`, but their
distinct identities **MUST** let callers distinguish a reported compression
failure from an adapter invariant or provider crash.

Provider classes, provider messages, native codes, and implementation exceptions
**MUST** survive only as causes governed by
[JBSA-OPS-001](operation-semantics.md#jbsa-ops-001).

_Source decisions: [accepted provider-neutral failure normalization](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971), [accepted Failure Kind taxonomy](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-CODEC-013

Semantic Decode Conformance and Encode Conformance **MUST** be established for
every applicable codec operation. Compressed Binary Conformance **MUST** be
claimed only for a repeatable Conformance Case that pins the codec profile and
complete configuration; JBSA **MUST NOT** make a blanket cross-provider or
cross-version compressed-byte claim.

Every provider upgrade or profile change **MUST** rerun affected conformance,
determinism, bounded-memory, performance, native-loading, packaging, and notice
evidence before release.

_Source decision: [accepted codec qualification posture](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## Evidence boundaries

The Reference Snapshot's 8 MiB zlib dispatch is a differential-test datum, not a
permanent threshold or public interface value. Exact compressed bytes may vary
by provider and version. The codec-profile manifest and case evidence determine
which, if any, compressed Binary Conformance claim has been earned.
