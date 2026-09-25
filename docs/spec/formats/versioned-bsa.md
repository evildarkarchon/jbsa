# TES4 BSA (versioned BSA)

This specification owns the format lineage colloquially known as TES4 BSA: all
post-TES3 `BSA\0` Archive Families, covering wire versions `0x67`, `0x68`, and
`0x69`. The normative term *versioned BSA* keeps that umbrella distinct from the
specific TES4 / Oblivion Archive Family at `0x67`. This specification uses the shared conventions in
[JBSA-DET-006](detection.md#jbsa-det-006). The three versions share a model but
remain distinct Archive Families.

## JBSA-BSA-001

The versioned-BSA direction and codec matrix **MUST** be:

| Archive Family | Version | Folder record | Stored | Compressed | Decode | Encode |
| --- | --- | ---: | --- | --- | --- | --- |
| TES4 / Oblivion BSA | `0x67` | 16 bytes | yes | zlib stream | yes | yes |
| FO3/FNV/Skyrim LE BSA | `0x68` | 16 bytes | yes | zlib stream | yes | yes |
| SSE/Skyrim AE BSA | `0x69` | 24 bytes | yes | LZ4 frame | yes | yes |

Each family **MUST** support archives containing stored entries, compressed
entries, or both. A codec from another row, including raw LZ4 for `0x69`,
**MUST** be rejected before destination effects.

_Source decisions: [accepted format and codec matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted codec strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-BSA-002

A conforming versioned BSA **MUST** begin with this 36-byte header:

| Offset | Field |
| ---: | --- |
| 0 | `BSA\0` |
| 4 | `version: u32` |
| 8 | `folderRecordsOffset: u32` |
| 12 | `archiveFlags: u32` |
| 16 | `folderCount: u32` |
| 20 | `fileCount: u32` |
| 24 | `folderNamesLength: u32` |
| 28 | `fileNamesLength: u32` |
| 32 | `fileFlags: u32` |

Canonical output **MUST** set `folderRecordsOffset` to 36. Counts, encoded name
lengths, and all derived section positions **MUST** agree with the serialized
sections.

_Source decision: [accepted Reference Snapshot layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-BSA-003

For versions `0x67` and `0x68`, each folder record **MUST** be
`folderHash: u64`, `fileCount: u32`, `folderOffset: u32`. Each folder block
**MUST**, when archive flag `0x0001` is set, begin with an `u8` byte length that
includes the terminator, the folder name bytes and NUL, followed immediately by
`fileCount` 16-byte file records. When the flag is clear, the block **MUST**
begin directly with its file-record sequence and `folderNamesLength` **MUST** be
zero. Each file record **MUST** be `nameHash: u64`,
`sizeAndCompressionToggle: u32`, `dataOffset: u32`.

In a conforming archive, `folderOffset` **MUST** equal the folder block's
physical offset plus `fileNamesLength`, which is zero when the global file-name
table is absent. When archive flag `0x0001` is set, `folderNamesLength` **MUST**
include every folder byte and NUL terminator but exclude the one-byte length
prefixes.

_Source decisions: [accepted Reference Snapshot BSA layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted name-presence clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)._

## JBSA-BSA-004

Version `0x69` **MUST** use a 24-byte folder record:
`folderHash: u64`, `fileCount: u32`, zero `u32`, `folderOffset: u32`, zero
`u32`. Its folder blocks and file records **MUST** otherwise use
[JBSA-BSA-003](#jbsa-bsa-003). Canonical encode **MUST** write both padding
fields as zero; nonzero padding is noncanonical and **MUST NOT** be emitted.

_Source decision: [accepted Reference Snapshot BSA layout](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-BSA-005

When archive flag `0x0002` is set, a versioned BSA **MUST** contain exactly
`fileCount` NUL-terminated basename strings after all folder blocks, in
folder/file-record order, and `fileNamesLength` **MUST** equal their complete
byte extent. When the flag is clear, the global basename table **MUST** be
absent and `fileNamesLength` **MUST** be zero. Folder and file records, hashes,
record order, and absolute file-record `dataOffset` values remain present in
every flag combination. Absence required by a clear presence flag is conforming
and **MUST NOT** produce a missing-name-section diagnostic.

For canonical encode, split the complete entry name eligible under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012) at its final separator into
a nonempty folder and basename and encode both components using Archive Name
Encoding. For the final-extension split in
[JBSA-BSA-006](#jbsa-bsa-006), a basename with no `0x2e` byte uses its complete
byte sequence as the stem. If the final `0x2e` is the first basename byte, the
extension-free stem is empty and canonical encode **MUST** reject the entry. If
either component contains a byte greater than `0x7f`,
canonical encode **MUST** reject the entry. A Compatibility Profile **MUST NOT**
admit such a component unless it defines the byte-level lowercase mapping and
cites qualifying fixtures for the resulting stored name and BSA hash bytes.

For every admitted entry, the encoder **MUST** replace each folder byte `0x2f`
with `0x5c`, then map every byte from `0x41` through `0x5a` in both components
to that byte plus `0x20`. Canonical encode **MUST** leave every other byte
unchanged; a qualified Compatibility Profile **MUST** additionally apply its
defined non-ASCII mapping. The encoder **MUST** store the resulting folder and
basename bytes and compute their hashes separately.

Decode **MUST** retain each present folder and basename as original wire-name
bytes and associate it, its hash, record, and payload by serialized order. A
component whose section is absent **MUST** be represented as absent, not as an
empty byte string. When either index component is absent, the decoded display
name **MUST** be
`__jbsa_hash__\f{folderOrdinal:08x}-{folderHash:016x}\e{entryOrdinal:08x}-{nameHash:016x}`,
where `folderOrdinal` is the zero-based folder-record ordinal, `entryOrdinal` is
the zero-based global file-record ordinal, and all hexadecimal digits are
lowercase. That marked synthetic name **MUST NOT** be represented as original
wire bytes or used as Normalized Name Identity, duplicate or overlay equality,
or canonical repack input; Normalized Name Identity is absent, and the stored
hash pair and ordinals remain authoritative record identity. Canonical encode
**MUST** reject an unresolved synthetic name until the caller supplies an
explicit complete name. Embedded full names under
[JBSA-BSA-011](#jbsa-bsa-011) remain independent payload-framing metadata and
**MUST NOT** change index-section parsing or fabricate absent index bytes.

_Source decisions: [accepted Reference Snapshot name and layout behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted Archive Name Encoding](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted deterministic-name and presence clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [empty-stem rejection clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179502)._

## JBSA-BSA-006

The BSA name hash **MUST** be computed from the normalized folder or basename
bytes produced by [JBSA-BSA-005](#jbsa-bsa-005). For a basename, split off the
final extension including its dot; folder hashes use no extension. The canonical
basename-hash procedure applies only when that split leaves a nonempty stem.
Decode **MUST** retain a present empty-stem basename and its stored hash as
authoritative and **MUST NOT** perform or diagnose a canonical hash comparison
for that component. Form the low word from the final stem byte, the
penultimate stem byte when the stem has more than two bytes, the stem length
modulo 256, and the first stem byte, in increasing byte significance. Apply
these low-word masks: `.kf` `0x00000080`, `.nif` `0x00008000`, `.dds`
`0x00008080`, and `.wav` `0x80000000`.

For the high word, use two accumulators initialized to zero. Process the stem's
second byte through the byte two positions before its final byte in one and all
extension bytes in the other, each using `h = byte + 65599 * h` modulo `2^32`;
the high word is their sum modulo `2^32`. Version `0x67` **MUST** interpret
bytes above 127 as `byte - 256` in those recurrences; versions `0x68` and `0x69`
**MUST** interpret them unsigned. The high word and low word together form the
`u64` hash.

Absent a qualified Compatibility Profile, decode **MUST** perform a canonical
hash comparison only for a present folder or basename containing Archive Name
Encoding bytes at most `0x7f`. For a component containing a byte greater than
`0x7f`, its original wire bytes and stored hash remain authoritative and decode
**MUST NOT** diagnose a canonical hash mismatch.

_Source decisions: [accepted Reference Snapshot hash behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted deterministic lowercase clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [empty-stem rejection clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179502)._

## JBSA-BSA-007

Canonical versioned-BSA encode **MUST** first partition post-overlay entries by
the canonical folder byte sequence produced by [JBSA-BSA-005](#jbsa-bsa-005).
Folder groups **MUST** be ordered by unsigned folder hash and then, when hashes
are equal, by canonical folder bytes in unsigned-octet lexicographic order: the
lower octet at the first difference sorts first, and an exact prefix sorts
first. Entries within each folder group **MUST** be ordered by unsigned basename
hash and then, when hashes are equal, by canonical basename bytes using the same
ordering.

Exactly one folder record and folder block **MUST** be emitted for each folder
group. Folder records and blocks **MUST** follow folder-group order; file records
within each block **MUST** follow that group's entry order; and the global
basename table **MUST** concatenate those same per-group entry orders. Equal
hashes **MUST NOT** establish folder or entry identity; Normalized Name
Identities under [JBSA-LIB-012](../library-interface.md#jbsa-lib-012) **MUST** be
compared before treating entries as duplicates or overlays. Canonical pack
preflight **MUST** reject two surviving entries that encode to the same canonical
folder-and-basename byte pair.

_Source decisions: [accepted Reference Snapshot ordering](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted collision-safe identity](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048)._

## JBSA-BSA-008

An entry is compressed exactly when archive flag `0x0004` XOR file-record bit
`0x40000000` is true. `0x40000000` is bit 30, not the high `u32` bit; decode
**MUST** remove only that bit to obtain the record byte count. The record byte
count **MUST** include every embedded-name prefix and decoded-size prefix that
precedes the payload.

A stored entry contains its payload bytes directly. A compressed entry contains
`decodedSize: u32` followed by one complete family codec stream. Decode **MUST**
consume the complete record framing and codec stream and produce exactly
`decodedSize` bytes. Canonical encode **MUST NOT** switch an entry to stored form
merely because compression makes it larger.

_Source decision: [accepted Reference Snapshot compression framing](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-BSA-009

The versioned-BSA zlib profile **MUST** be an RFC 1950 zlib-wrapped DEFLATE
stream, never raw DEFLATE. The initial canonical standard profile **MUST** encode
at level 9. Decode and Encode Conformance **MUST** require complete stream
consumption and exact uncompressed bytes. Compressed-byte identity **MUST** be
claimed only by an individually qualified Binary Conformance case whose provider
and parameters are pinned.

_Source decisions: [accepted Reference Snapshot zlib framing](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted initial standard codec profile](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-BSA-010

The versioned-BSA LZ4-frame profile **MUST** contain one LZ4 frame with
compression level 12, independent blocks, a maximum block size of 4 MiB,
auto-flush enabled, content and block checksums disabled, no content-size or
dictionary identifier, and otherwise default LZ4F frame preferences. Decode
**MUST** reject a frame that leaves record bytes unconsumed or produces fewer
or more bytes than the `decodedSize` prefix. This profile **MUST NOT** accept or
emit a raw LZ4 block.

_Source decisions: [accepted Reference Snapshot LZ4-frame profile](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted LZ4 strategy and parameters](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-BSA-011

For `0x68` and `0x69`, archive flag `0x0100` **MUST** prefix each record payload
with an `u8` byte length and that many full-name bytes without a NUL. For
canonical encode, the full-name bytes **MUST** be exactly the canonical folder
bytes produced by [JBSA-BSA-005](#jbsa-bsa-005), followed by one `0x5c` byte,
followed by the canonical basename bytes produced by that requirement. They use
the selected Archive Name Encoding and its applicable byte-level lowercase
mapping; canonical encode **MUST NOT** use source or display spelling or another
joining separator. The prefix precedes the decoded-size field when the entry is
compressed.

Decode **MUST** retain the embedded full-name bytes as distinct payload-framing
metadata. When both corresponding index components are present, decode **MUST**
compare the embedded bytes byte-for-byte with the original folder wire-name
bytes, one `0x5c` byte, and the original basename wire-name bytes. When either
component is absent, decode **MUST NOT** use the embedded bytes to fabricate that
component or a Normalized Name Identity, as required by
[JBSA-BSA-005](#jbsa-bsa-005). Versions `0x68` and `0x69` **MUST** decode and
encode this framing when the flag is present. Version `0x67` **MUST NOT** infer
that framing solely from flag `0x0100` until the contradiction in the evidence
boundary is resolved.

_Source decision: [accepted Reference Snapshot embedded-name behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-BSA-012

Versioned BSA archive flags **MUST** retain these wire meanings:

| Bit | Meaning | Bit | Meaning |
| ---: | --- | ---: | --- |
| `0x0001` | directory names | `0x0002` | file names |
| `0x0004` | archive compression default | `0x0008` | retain directory names |
| `0x0010` | retain file names | `0x0020` | retain file-name offsets |
| `0x0040` | Xbox 360 | `0x0080` | startup strings |
| `0x0100` | embedded file names | `0x0200` | XMem marker |
| `0x0400` | unnamed bit 10 |  |  |

File flags **MUST** retain `0x0001` meshes, `0x0002` textures, `0x0004` menus,
`0x0008` sounds, `0x0010` voices, `0x0020` shaders, `0x0040` trees, `0x0080`
fonts, and `0x0100` miscellaneous. Canonical output **MUST** set archive flags
`0x0001` and `0x0002`. The `0x0200` marker in a `0x67` archive **MUST NOT**
change its zlib codec.

_Source decision: [accepted Reference Snapshot flag behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-BSA-013

Automatic file flags **MUST** use this ordered classifier. The class name is
descriptive; the last column is the exact initial file-flag contribution.

| Class | Normalized root | Fallback final extensions | Contribution |
| --- | --- | --- | ---: |
| Mesh | `meshes` | `.nif`, `.kf`, `.kfm`, `.egm`, `.egt`, `.tri`, `.psa`, `.hkt`, `.hkx`, `.ssf`, `.btr`, `.bto`, `.btt`, `.dtl` | `0x0001` |
| Texture | `textures` | `.dds`, `.tga`, `.png` | `0x0002` |
| Material | `materials` | `.bgsm`, `.bgem` | `0x0100` |
| Geometry | `geometries` | `.mesh` | `0x0100` |
| Voice | `sound\voice` | `.lip`, `.wav`, `.xwm`, `.mp3`, `.ogg`, `.fuz` | `0x0010` |
| Sound | `sound` | `.wav`, `.xwm`, `.ogg` | `0x0008` |
| Music | `music` | `.xwm`, `.mp3` | `0x0100` |
| Script source | `scripts\source` | `.psc` | `0x0100` |
| SSE script source | `source\scripts` | `.psc` | `0x0100` |
| Script | `scripts` | `.pex`, `.psc` | `0x0100` |
| Strings | `strings` | `.strings`, `.ilstrings`, `.dlstrings` | `0x0100` |
| SpeedTree | `trees` | `.spt` | `0x0040` |
| Video | `video` | `.bik`, `.bk2` | `0x0100` |
| LOD settings | `lodsettings` | `.lodsettings`, `.dlodsettings`, `.lod` | `0x0001 \| 0x0100` |
| Distant LOD | `distantlod` | `.cmp`, `.lod` | `0x0001 \| 0x0100` |
| Interface | `interface` | `.swf`, `.png`, `.txt` | `0x0100` |
| Program | `programs` | `.swf` | `0x0100` |
| Menus | `menus` | `.xml`, `.htm`, `.txt`, `.scc`, `.bat` | `0x0020` |
| Font | `fonts` | `.fnt`, `.tex` | `0x0080` |
| Facegen | `facegen` | `.ctl` | `0x0100` |
| LS data | `lsdata` | `.dat` | `0x0100` |
| Shaders | `shaders` | `.sdp` | `0x0100` |
| Shader effects | `shadersfx` | `.fxp` | `0x0100` |
| Grass | `grass` | `.gid` | `0x0100` |
| Pre-visibility | `vis` | `.uvd` | `0x0100` |
| Sequence | `seq` | `.seq` | `0x0100` |
| Dialogue views | `dialogueviews` | `.xml` | `0x0100` |
| Book art | `bookart` | `.dds`, `.tga` | `0x0100` |
| Icon | `icons` | `.dds`, `.tga` | `0x0100` |
| Splash | `splash` | `.dds`, `.tga` | `0x0100` |
| No match | not applicable | not applicable | `0x0100` |

Classification **MUST** use the canonical lowercase, backslash-separated bytes
from [JBSA-BSA-005](#jbsa-bsa-005). A root `R` matches only a complete `R`
component or a name beginning `R\`. The encoder **MUST** first select the first
matching root row in table order. Only when no root matches, it **MUST** select
the first row in table order whose listed extension equals the basename's final
extension. No match selects the final row. Contributions from all entries
**MUST** be combined with bitwise OR. The apparently counterintuitive Menus
`0x0020` and Shaders `0x0100` contributions are intentional reproductions of
the pinned classifier, not corrected labels. For `0x67` only, every `.xml`
basename **MUST** additionally contribute menu bit `0x0004`, regardless of its
selected class.

After classification, non-`0x67` output **MUST** clear menu, shader, and font
bits `0x0004 | 0x0020 | 0x0080`; `0x69` output **MUST** additionally clear
miscellaneous bit `0x0100`. Automatic archive flags **MUST** begin with `0x0603`
for `0x67` and `0x0003` for `0x68` or `0x69`. They **MUST** add startup-strings
bit `0x0080` when the final file flags contain mesh bit `0x0001`, retain-name
bit `0x0010` when any entry classified as Script or Sound, and compression bit
`0x0004` when any entry is compressed. Voice, Music, Script source, and SSE
script source **MUST NOT** trigger retain-name by themselves. Every other
automatic archive bit, including embedded-name bit `0x0100`, **MUST** remain
clear.

Automatic embedded-name behavior affected by the contradictions below **MUST**
remain unselected until differential fixture evidence resolves it. This
restriction applies only to automatic selection; an explicit `0x0100` override
for `0x68` or `0x69` **MUST** use
[JBSA-BSA-011](#jbsa-bsa-011).

Zero-valued flag overrides and negative split values are request/CLI policy and
**MUST NOT** be inferred from the wire format.

_Source decisions: [accepted Reference Snapshot flag inference](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted contradiction policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [automatic-classification clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179544)._

## JBSA-BSA-014

Decode **MUST** validate header counts and lengths, flag-conditioned name-section
presence and lengths, folder-record and name-table relationships, absolute
payload spans, record framing, codec consumption, and decoded sizes with checked
arithmetic. It **MUST** reject overflow, truncation, out-of-file or partially
overlapping spans, duplicate complete names with equal Normalized Name
Identities under [JBSA-LIB-012](../library-interface.md#jbsa-lib-012), impossible
framing, unsupported codecs, and decoded-size mismatch. Exact shared payload
spans remain valid. Absolute or traversal names remain inspectable but
**MUST** make extraction ineligible before destination effects. A stored-hash
mismatch established for any present usable wire-name component under
[JBSA-BSA-006](#jbsa-bsa-006) **MUST** be a diagnosed Tolerated Noncanonical
Archive and **MUST NOT** be emitted. Synthetic display names from
[JBSA-BSA-005](#jbsa-bsa-005) **MUST NOT** participate in either check.
A bounded byte mismatch between an embedded full name and its complete present
index name under [JBSA-BSA-011](#jbsa-bsa-011) **MUST** produce a stable warning
and a Tolerated Noncanonical Archive when record and payload bounds remain
unambiguous; the encoder **MUST NOT** emit that mismatch.

Encode **MUST** reject an empty entry set, an entry without a folder component,
an empty extension-free basename stem, unmappable names, unpopulated payloads,
and values that cannot fit their wire fields. An encoded folder name **MUST** be
at most 254 bytes because its `u8`
length includes the NUL; an embedded full name **MUST** be at most 255 bytes
because its `u8` length excludes a terminator. Aggregate folder-name and
basename tables **MUST** fit their `u32` header lengths. A file-record byte count,
including framing, **MUST** fit `u32` with bit `0x40000000` clear before the
compression toggle is applied.

Inspection **MUST** retain stable warnings for file offsets beyond signed 2 GiB,
cubemap textures without embedded names, non-textures with embedded names, and
uncompressed `0x69` entries with embedded names.

The diagnostics required by this section **MUST** use `WARNING` severity and
exactly the identifiers and scopes below. Stored and expected values **MUST**
follow [JBSA-OPS-005](../operation-semantics.md#jbsa-ops-005).

| Condition | Diagnostic identifier | Scope and structured location |
| --- | --- | --- |
| Usable folder-name stored-hash mismatch | `bsa.folder-hash-mismatch` | Once per affected folder, at its `folderHash` field |
| Usable basename stored-hash mismatch | `bsa.file-hash-mismatch` | Once per affected entry, at its file hash field |
| Embedded-name/index-name mismatch | `bsa.embedded-name-mismatch` | Once per affected entry, at its embedded-name byte span |
| Payload start beyond signed 2 GiB | `bsa.payload-offset-over-signed-2gib` | Once per affected entry, at its payload start |
| Cubemap texture without embedded names | `bsa.cubemap-without-embedded-name` | Once per affected entry, at its payload |
| Non-texture with embedded names | `bsa.nontexture-with-embedded-name` | Once per affected entry, at its embedded-name byte span |
| Uncompressed `0x69` entry with embedded names | `bsa.uncompressed-sse-embedded-name` | Once per affected entry, at its embedded-name byte span |

_Source decisions: [accepted tolerated-noncanonical and rejection policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted layered validation](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted name-validation clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [empty-stem rejection clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179502), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [accepted `0.12.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5550691183)._

## JBSA-BSA-015

The default versioned-BSA split target **MUST** be `2,147,483,647` bytes; zero
**MUST** disable splitting. The advisory estimate **MUST** include packed bytes,
200 bytes per entry, encoded-name length, and a possible embedded-name length
for `0x68` and `0x69`. A single oversized entry **MUST** occupy its own part,
and part paths **MUST** use the numbered split-sibling mapping in
[JBSA-IO-008](../io-and-publication.md#jbsa-io-008).

Shared payload offsets **MAY** be emitted only after size and byte equality are
confirmed. Parallel completion order and sharing-dependent split grouping
**MUST NOT** be used for Binary Conformance; deterministic cases use the
qualified sequential plan.

_Source decisions: [accepted Reference Snapshot split and sharing behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted case-scoped Binary Conformance](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [extensionless split-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179533)._

## TES4 / Oblivion BSA `0x67` evidence boundary

The Reference Snapshot initializes `0x67` automatic archive flags with embedded
names (`0x0100`), XMem (`0x0200`), and unnamed bit 10 (`0x0400`), but its reader
and writer neither consume nor emit an embedded-name prefix for `0x67`. This
contradiction blocks canonical automatic `0x0100` behavior and Binary
Conformance assertions involving it until a differential fixture establishes
the intended bytes. Safe automatic `0x67` output therefore leaves `0x0100`
clear while retaining the uncontradicted `0x0200` and `0x0400` base bits;
explicit `0x0100` remains available only for `0x68` and `0x69`.

The signed-byte hash recurrence is deterministic for stored bytes, but canonical
non-ASCII lowercasing depends on a selected Compatibility Profile. The ASCII
mapping is specified; Windows-1252 non-ASCII, active-code-page, and Windows-932
results require their mandatory fixtures before broader claims.

## Other contradictions and fixture-dependent unknowns

The Reference Snapshot's attempted non-texture embedded-name fix clears file
flag `0x0100` (miscellaneous) rather than archive flag `0x0100` (embedded names).
The normative flag requirements do not emulate that apparent wrong-field
assignment; changing them requires an accepted differential Compatibility
Deviation.

Exact compressed bytes remain provider/version-dependent. Name length overflow,
malformed folder offsets, duplicate names, and permissive truncation behavior
lack fixture authority. Canonical equal-hash ordering is specified by
[JBSA-BSA-007](#jbsa-bsa-007), but collision cases remain excluded from Binary
Conformance until qualifying differential fixtures exist.
Parallel byte order and split boundaries are intentionally not a Binary
Conformance surface. These gaps remain explicit rather than inheriting whatever
the Reference Snapshot happens to do on one malformed or scheduled execution.

_Research evidence: [versioned-BSA layouts, flags, compression, contradictions, and unknowns](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md), [`wbBSArchive.pas` BSA reader](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1370-L1418), [`wbHash.pas` BSA hash](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L201-L258)._
