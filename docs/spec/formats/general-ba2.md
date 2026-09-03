# General BA2

This specification owns the shared `BTDX` envelope and the `GNRL` payload kind
used by Fallout 4 and Starfield General BA2 Archive Families. DDS BA2 reuses the
envelope, identity prefix, names, hashes, and ordering defined here, then replaces
the General payload record as specified by
[DDS BA2](dds-ba2.md).

## JBSA-GNRL-001

A conforming BA2 **MUST** begin with this envelope:

| Offset | Field |
| ---: | --- |
| 0 | `BTDX` |
| 4 | `version: u32` |
| 8 | four-byte subtype |
| 12 | `entryCount: u32` |
| 16 | `fileNameTableOffset: i64` |
| 24 | versions `2` and `3`: `unknownValueAt24: u64` |
| 32 | version `3`: `compressionMethod: u32` |

Header sizes are 24 bytes for versions `1`, `7`, and `8`, 32 bytes for version
`2`, and 36 bytes for version `3`. General BA2 **MUST** use subtype `GNRL`.
The meaning of `unknownValueAt24` is not established; canonical versions `2`
and `3` **MUST** write it as `1`.

_Source decision: [accepted Reference Snapshot BA2 layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-GNRL-002

The General BA2 family, version, and codec matrix **MUST** be:

| Archive Family | Version/method | Decode | Encode |
| --- | --- | --- | --- |
| FO4 General BA2 | version `1` | stored or zlib | stored or zlib |
| FO4 General BA2 | version `7` or `8` | stored or zlib | no |
| Starfield General BA2 | version `2` | stored or zlib | stored or zlib |
| Starfield General BA2 | version `3`, method `3` | stored or raw LZ4 | stored or raw LZ4 |

Every version `7`/`8` encode request **MUST** fail as unsupported before
destination effects. Version `3` with a method other than `3` **MUST** be
unsupported by default and **MUST NOT** be emitted. A qualified Compatibility
Profile may attempt the Reference Snapshot's zlib fallback with a diagnostic.

_Source decisions: [accepted format and codec matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted codec strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-GNRL-003

Starfield General BA2 encode **MUST** use the shared canonical header fields in
[JBSA-GNRL-001](#jbsa-gnrl-001), emit version `3` with method `3` when raw LZ4
is selected, and emit version `2` with no method field when zlib or stored output
is selected. Stored entries remain permitted in a version-3/method-3 archive;
the method selects the decoder only for entries whose `packedSize` is nonzero.

_Source decisions: [accepted Reference Snapshot Starfield behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted codec matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-GNRL-004

Each `GNRL` entry **MUST** begin with the common prefix from
[JBSA-GNRL-006](#jbsa-gnrl-006) and append this 20-byte payload record, for a
total of 36 bytes:

| Entry offset | Field |
| ---: | --- |
| 16 | `payloadOffset: i64` |
| 24 | `packedSize: u32` |
| 28 | `unpackedSize: u32` |
| 32 | `sentinel: u32` |

Canonical `GNRL` output **MUST** write `chunkCount` one, `chunkHeaderSize` 16,
and `sentinel` `0xBAADF00D`.

_Source decision: [accepted Reference Snapshot General-record layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-GNRL-005

For every General entry, `packedSize == 0` **MUST** mean a stored span of
exactly `unpackedSize` bytes. `packedSize > 0` **MUST** mean a compressed span
of exactly `packedSize` bytes that decodes to exactly `unpackedSize` bytes.
General BA2 **MUST NOT** add a BSA decoded-size prefix to the payload. Canonical
encode **MUST NOT** change a selected compressed entry to stored form merely
because compression expands it.

FO4 versions `1`, `7`, and `8` and Starfield version `2` **MUST** use the zlib
profile from [JBSA-BSA-009](versioned-bsa.md#jbsa-bsa-009). Starfield version
`3` method `3` **MUST** use one raw LZ4 block per compressed entry, at HC level
12 for canonical encode, and **MUST NOT** use an LZ4 frame. Decode **MUST**
consume the complete block and require the exact expected output length.

_Source decisions: [accepted Reference Snapshot compression framing](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted raw-LZ4 profile](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-GNRL-006

The first 16 bytes of every BA2 entry record **MUST** identify the entry as
`baseNameHash: u32`, four extension bytes, `directoryHash: u32`, `modIndex: u8`,
`chunkCount: u8`, and `chunkHeaderSize: u16`. The same identity prefix **MUST**
be used by `GNRL` and `DX10`; their bytes after offset 16 **MUST** be interpreted
only by the selected subtype. Canonical BA2 output **MUST** write `modIndex` as
zero; each subtype owns its canonical chunk count and header size.

_Source decision: [accepted Reference Snapshot BA2 layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-GNRL-007

For canonical encode, split the complete entry name eligible under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012) at its last separator into
directory and filename, then split the filename at its last dot into
extension-free basename and extension. The encoder **MUST** encode the directory,
extension-free basename, and extension using Archive Name Encoding. If any
encoded component contains a byte greater than `0x7f`, canonical encode **MUST**
reject the entry. A Compatibility Profile **MUST NOT** admit such a component
unless it defines the byte-level lowercase mapping and cites qualifying fixtures
for the resulting hash and extension bytes.

For every admitted entry, the encoder **MUST** replace each directory byte
`0x2f` with `0x5c`, then map every byte from `0x41` through `0x5a` in all three
components to that byte plus `0x20`. Canonical encode **MUST** leave every other
byte unchanged; a qualified Compatibility Profile **MUST** additionally apply
its defined non-ASCII mapping. The encoder **MUST** hash the resulting directory
and basename byte sequences independently.
The hash **MUST** use the reflected CRC-32 polynomial `0xEDB88320`, initial
value zero, no final XOR, and the recurrence
`crc = (crc >>> 8) XOR table[(crc XOR byte) AND 0xff]`.

The four extension bytes **MUST** be the first four lowercase extension bytes
without the dot, NUL-padded when shorter. The extension **MUST NOT** participate
in `baseNameHash`. Decode **MUST** preserve stored hashes and extension bytes.
When a usable name's directory, extension-free basename, and extension contain
only Archive Name Encoding bytes at most `0x7f`, a mismatch **MUST** be diagnosed
as tolerated noncanonical rather than silently replaced. Absent a qualified
Compatibility Profile, decode **MUST NOT** make that canonicality judgment for a
name containing a byte greater than `0x7f`; its stored hashes and extension bytes
remain authoritative.

_Source decisions: [accepted Reference Snapshot BA2 hashing](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted tolerated-noncanonical policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517)._

## JBSA-GNRL-008

Canonical BA2 output **MUST** place a filename table after payload data and set
`fileNameTableOffset` to its absolute start. It **MUST** contain exactly one
`u16 byteLength` followed by that many non-NUL name bytes per entry in record
order. Wire names **MUST** use `/`; decoded display names **MUST** use `\`.
Encode **MUST** preserve supplied display-name case, require a folder component,
and reject an encoded name longer than 65,535 bytes or unmappable by the selected
Archive Name Encoding.

A zero or out-of-range filename-table offset **MUST** produce a diagnosed
Tolerated Noncanonical Archive when all records and payloads remain bounded.
For each such entry, the decoded display name **MUST** be
`__jbsa_hash__\d{directoryHash:08x}\e{entryOrdinal:08x}-{baseNameHash:08x}-x{extensionByte0:02x}{extensionByte1:02x}{extensionByte2:02x}{extensionByte3:02x}`.
`entryOrdinal` is the zero-based physical BA2 record ordinal. Each hash is
rendered from its unsigned `u32` value as exactly eight lowercase hexadecimal
digits; each extension byte is rendered as exactly two lowercase hexadecimal
digits in stored wire order, including NUL padding. The synthetic display name
**MUST NOT** be represented as original wire-name bytes or used as a Normalized
Name Identity, duplicate or overlay equality, or canonical repack input.
Original wire-name bytes and Normalized Name Identity are absent, and the
stored hashes, extension bytes, and record ordinal remain authoritative.
Canonical encode **MUST** reject an unresolved synthetic name until the caller
supplies an explicit complete name. Decode **MUST NOT** perform an unchecked
seek.

_Source decisions: [accepted Reference Snapshot name-table behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted noncanonical name-table policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [synthetic-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179540)._

## JBSA-GNRL-009

Decode **MUST** expose BA2 entries in physical record order. General BA2 encode
**MUST NOT** hash-sort entries: records, filename-table strings, and canonical
payload sequencing **MUST** follow Logical Plan Order. Later-source-wins overlay
replacement retains the first insertion ordinal until this format ordering is
applied. A hash match **MUST** be confirmed by equal Normalized Name Identities
under [JBSA-LIB-012](../library-interface.md#jbsa-lib-012).

Worker completion order **MUST NOT** alter record order under a deterministic
profile. Parallel reference output is a semantic-conformance surface, not a
blanket Binary Conformance surface.

_Source decisions: [accepted Reference Snapshot ordering and overlay behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted collision and Binary Conformance policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517)._

## JBSA-GNRL-010

Decode **MUST** validate the envelope, complete General record table, filename
table when present, and all section and payload spans with checked arithmetic.
It **MUST** reject overflow, truncation, negative or out-of-file spans, partial
overlap, duplicate complete names with equal Normalized Name Identities under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012), a `chunkCount` other than
one, invalid compressed data, decoded-size mismatch, and unsupported version/method
combinations. Exact shared spans remain valid. Absolute or traversal names
remain inspectable but **MUST** make extraction ineligible before destination
effects.

A safely ignored Starfield extra-header value other than one, nonzero mod index,
non-16 chunk-header size, non-`0xBAADF00D` sentinel, usable-name hash/extension
mismatch qualified by [JBSA-GNRL-007](#jbsa-gnrl-007), or harmless trailing bytes
**MUST** be a diagnosed Tolerated Noncanonical Archive when bounds and
interpretation remain unambiguous. None of those values is valid encoder output.
Unsafe absolute or traversal names remain inspectable but ineligible for
extraction.

_Source decisions: [accepted noncanonical and malformed-input policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted layered validation](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517)._

## JBSA-GNRL-011

General BA2 encode **MUST** reject an empty entry set, missing-folder, absolute,
traversal, absent or duplicate Normalized Name Identities under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012), overlong or unmappable names, values outside
their wire ranges, unsupported codec/version combinations, and unpopulated
payloads. It **MUST** reserve the exact header and `36 * entryCount` record area,
write nonnegative payload spans outside metadata and names, recompute canonical
names and hashes, and validate the complete staged archive before publication.

_Source decisions: [accepted Reference Snapshot creation behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted safe conformance contract](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517)._

## JBSA-GNRL-012

When sharing is enabled, entries with identical uncompressed bytes **MAY** reuse
one exact payload span. Equality **MUST** be confirmed byte-for-byte after any
size/hash filtering; size plus XXH64 is insufficient. The earliest equivalent
entry in Logical Plan Order **MUST** own the representation. Partial overlaps
**MUST NOT** be introduced as sharing.

_Source decisions: [accepted Reference Snapshot sharing behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted collision-safe equality](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-GNRL-013

BA2 splitting **MUST** default to disabled; zero **MUST** disable it. When
enabled, whole entries **MUST** be assigned in Logical Plan Order using unique
transformed payload bytes plus 200 bytes and display-name length per entry as an
advisory estimate. Exceeding the target closes the preceding nonempty part
before the current entry; a single oversized entry occupies its own part. Part
one keeps the requested name, and part paths **MUST** use the numbered
split-sibling mapping in
[JBSA-IO-008](../io-and-publication.md#jbsa-io-008). Every part **MUST** be an
independent conforming archive.

Exact grouping with sharing or parallel work remains fixture-dependent and
**MUST NOT** receive Binary Conformance without a qualified deterministic case.

_Source decisions: [accepted Reference Snapshot split behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted case-scoped Binary Conformance](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [extensionless split-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179533)._

## Contradictions and fixture-dependent unknowns

The pinned reader parses FO4 versions `7` and `8` with the version-1 General
record, while the downstream implementation issue calls for version-specific
records. Official meanings for ignored or added fields remain fixture-dependent;
changing only a synthetic version number does not establish them. Decode support
and explicit encode rejection are normative, but no unobserved field is invented.

The Reference Snapshot treats every Starfield version-3 method except `3` as
zlib. The accepted safe contract supersedes that as a default: rejection is
normative, and fallback remains profile-only pending a qualifying fixture.

Exact compressed bytes vary by provider/version. Non-ASCII lowercasing, hash and
extension bytes, and canonicality comparisons require fixtures, so canonical
encode rejects non-ASCII Archive Name Encoding bytes and no current Compatibility
Profile qualifies them for BA2 encode. Empty-archive decode disposition and
sharing/concurrent split boundaries also require fixtures. The format
requirements therefore claim semantic cross-decode and exact uncompressed bytes,
not blanket byte identity.

_Research evidence: [General BA2 layout, compression, ordering, contradictions, and unknowns](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md), [`wbBSArchive.pas` BA2 reader](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1420-L1498)._
