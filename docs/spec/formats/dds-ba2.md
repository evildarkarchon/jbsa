# DDS BA2

This specification owns the `DX10` BA2 payload kind. It reuses the BA2 envelope
from [JBSA-GNRL-001](general-ba2.md#jbsa-gnrl-001), the common identity prefix
from [JBSA-GNRL-006](general-ba2.md#jbsa-gnrl-006), and General BA2's name,
hash, ordering, and split rules. It stores normalized DDS metadata and opaque mip
payloads; DDS envelope behavior is owned by
[DDS payload](dds-payload.md).

## JBSA-DX10-001

The DDS BA2 family, version, and codec matrix **MUST** be:

| Archive Family | Version/method | Decode | Encode |
| --- | --- | --- | --- |
| FO4 DDS BA2 | version `1` | zlib chunks | zlib chunks |
| FO4 DDS BA2 | version `7` or `8` | zlib chunks | no |
| Starfield DDS BA2 | version `2` | zlib chunks | zlib chunks |
| Starfield DDS BA2 | version `3`, method `3` | raw-LZ4 chunks | raw-LZ4 chunks |

Every archive in this matrix **MUST** select subtype `DX10`. Version `7`/`8`
encode, stored DDS encode, codecs outside the listed cell, and version `3`
methods other than `3` **MUST** fail as unsupported before destination effects.
The qualified Compatibility Profile may attempt the Reference Snapshot's
version-3 zlib fallback, but cannot enable stored DDS encode.

The `PC` or `XBOX` DDS target is operation data, is not encoded by the
family/version/subtype/method tuple, and **MUST NOT** alter this matrix or wire
framing. Encode target selection is owned by
[JBSA-DDS-002](dds-payload.md#jbsa-dds-002).

_Source decisions: [accepted format and codec matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted codec strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971), [DDS encode-target clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179549)._

## JBSA-DX10-002

After the 16-byte common BA2 entry identity prefix, each `DX10` entry **MUST**
contain:

| Entry offset | Field |
| ---: | --- |
| 16 | `height: u16` |
| 18 | `width: u16` |
| 20 | `mipCount: u8` |
| 21 | `dxgiFormat: u8` |
| 22 | `flags: u8`; bit zero denotes a cubemap |
| 23 | `tileMode: u8` |

The fixed entry record is therefore 24 bytes, followed immediately by
`chunkCount` 24-byte chunk records. Under the common prefix rule in
[JBSA-GNRL-006](general-ba2.md#jbsa-gnrl-006), canonical `DX10` output **MUST**
set `chunkHeaderSize` to 24.

_Source decision: [accepted Reference Snapshot DDS-record layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DX10-003

Each texture chunk record **MUST** contain:

| Chunk offset | Field |
| ---: | --- |
| 0 | absolute `payloadOffset: i64` |
| 8 | `packedSize: u32` |
| 12 | `unpackedSize: u32` |
| 16 | inclusive `startMip: u16` |
| 18 | inclusive `endMip: u16` |
| 20 | `sentinel: u32` |

Canonical output **MUST** write `sentinel` `0xBAADF00D`. Chunk records and
payloads **MUST** remain in reconstruction order.

_Source decision: [accepted Reference Snapshot DDS-chunk layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DX10-004

Every DDS chunk **MUST** be framed independently. `packedSize == 0` means read
exactly `unpackedSize` stored bytes; `packedSize > 0` means read exactly
`packedSize` bytes and decode exactly `unpackedSize` bytes. There is no inline
decoded-size prefix. All compressed chunks in one archive **MUST** use the codec
selected by its version/method: the zlib stream profile from
[JBSA-BSA-009](versioned-bsa.md#jbsa-bsa-009) or the raw-LZ4 profile from
[JBSA-GNRL-005](general-ba2.md#jbsa-gnrl-005). Raw DEFLATE and LZ4 frames
**MUST NOT** be substituted.

An invalid stream, unconsumed input, or decoded-size mismatch **MUST** be a
format failure. An unavailable required LZ4 capability affects Extraction
Eligibility and **MUST NOT** change Archive Disposition.

_Source decisions: [accepted Reference Snapshot chunk framing](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted codec and failure strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-DX10-005

A conforming DDS entry **MUST** contain one through four chunks. A cubemap
**MUST** contain exactly one. A canonical non-cubemap entry **MUST** cover mips
contiguously from zero: every nonfinal chunk covers one mip `i..i`, and the final
chunk covers `i..mipCount-1`. Decode **MUST** reconstruct payload bytes in
serialized chunk order and **MUST NOT** silently reorder them by `startMip` or
`endMip`.

_Source decision: [accepted Reference Snapshot DDS chunking](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DX10-006

Encode **MUST** compress every DDS chunk with the Archive Family's selected
codec. It **MUST** reject an uncompressed DDS request in every Compatibility
Profile. Decode **MAY** read a bounded stored chunk, but the archive **MUST** be
Tolerated Noncanonical and carry a stable warning; stored chunks are never
canonical encoder output.

_Source decisions: [accepted safe DDS disposition](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted CLI safe default](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-DX10-007

Decode **MUST** validate the envelope, variable texture-record table, filename
table when present, chunk counts, mip ranges, decoded-size representability, and
every absolute chunk span with checked arithmetic. It **MUST** reject overflow,
truncation, negative or out-of-file spans, partial overlap, duplicate complete
names with equal Normalized Name Identities under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012), zero or more than four
chunks, an impossible or noncontiguous mip range,
invalid compressed data, and decoded-size mismatch. Exact shared spans remain
valid. Absolute or traversal names remain inspectable but **MUST** make
extraction ineligible before destination effects.

A nonzero mod index, non-24 chunk-header size, non-`0xBAADF00D` sentinel,
missing filename table, stored chunk, or harmless trailing bytes **MUST** be a
diagnosed Tolerated Noncanonical Archive when every interpretation and span
remains bounded and unambiguous. None is valid canonical output.
For a zero or out-of-range filename-table offset, the exact synthetic-name and
absent-identity rules in
[JBSA-GNRL-008](general-ba2.md#jbsa-gnrl-008) **MUST** apply.

_Source decisions: [accepted noncanonical and malformed-input policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted layered validation](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [synthetic-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179540)._

## JBSA-DX10-008

When sharing is enabled, identical uncompressed chunks **MAY** independently
reuse an earlier exact payload span. Equality **MUST** be confirmed byte-for-byte
after size/hash filtering, and sharing **MUST NOT** alter either entry's mip
metadata. Partial span overlap **MUST NOT** be introduced as sharing. DDS BA2
split outputs **MUST** follow [JBSA-GNRL-013](general-ba2.md#jbsa-gnrl-013) and
**MUST NOT** divide one entry across archive parts.

_Source decisions: [accepted Reference Snapshot sharing and split behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted collision-safe equality](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## Contradictions and fixture-dependent unknowns

The Reference Snapshot CLI can omit DDS compression even though its help and
warnings say uncompressed DDS archives are unsafe, while BSArchPro forces
compression. [JBSA-DX10-006](#jbsa-dx10-006) applies the accepted safe decision:
decode remains possible with a warning, but encode is prohibited.

The pinned reader applies this record shape to FO4 versions `7` and `8`, while
official version-specific meanings remain unqualified. Decode support and encode
rejection are fixed; no additional v7/v8 field semantics are invented. A
Starfield version-3 method other than `3` is likewise profile-only until a
fixture qualifies the fallback.

The Reference Snapshot ignores mod index, declared chunk-header size, sentinel,
and mip-range gaps or order. Exact dispositions for exotic bounded combinations
need malformed fixtures; this specification admits only the accepted harmless
constant cases and rejects ambiguous ranges. Exact compressed bytes remain
case-scoped rather than a blanket conformance claim.

_Research evidence: [DDS BA2 layout, compression, contradiction, and unknowns](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md), [`wbBSArchive.pas` DDS reader](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1445-L1487)._
