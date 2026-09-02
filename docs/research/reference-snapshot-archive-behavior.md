# Reference Snapshot archive behavior

## Resolution

The Java implementation should treat the pinned TES5Edit snapshot at commit
`fd1e36020b2b5b6217e553dc0038983146a2e2dd` as a family of related behavioral
contracts, not as only the `BSArch.dpr` command line. The normalized contract is:

1. Decode every archive variant the shared reader recognizes: TES3 BSA; BSA
   versions `0x67`, `0x68`, and `0x69`; and `BTDX` `GNRL`/`DX10` BA2 versions
   `1`, `2`, `3`, `7`, and `8` subject to the family/version combinations below.
2. Encode the variants the shared writer actually emits: TES3; BSA `0x67`,
   `0x68`, and `0x69`; FO4 `GNRL`/`DX10` BA2 version `1`; and Starfield
   `GNRL`/`DX10` BA2 version `2` for zlib or version `3` for raw LZ4.
   FO4 versions `7` and `8` are decode-only in this snapshot.
3. Make archive operations reusable independently of the CLI: detection,
   metadata/listing, case-insensitive entry lookup, extraction, creation,
   compression, DDS reconstruction, multi-source overlay, filtering, data
   sharing, and size-based splitting are all implemented below the two front
   ends. The shared archive type/API surface establishes that boundary
   ([`TES5Edit/Core/wbBSArchive.pas:27-37`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L27-L37),
   [`TES5Edit/Core/wbBSArchive.pas:285-312`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L285-L312),
   [`TES5Edit/Core/wbBSArchive.pas:315-420`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L315-L420)).
4. For valid inputs, use the shared archive implementation as the primary
   behavioral authority. Use BSArch CLI behavior as the command-surface
   authority and BSArchPro, xEdit's resource-container caller, changelog, and
   fixtures as corroborating or gap-filling evidence. Do not reproduce an
   apparent defect until a differential test proves that compatibility requires
   it; all discovered contradictions are listed explicitly below.

The source tree contains no automated archive conformance suite. It contains a
48-file DDS corpus in
[`TES5Edit/BSArch/test-textures.7z`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/test-textures.7z)
and commented, machine-specific smoke procedures rather than runnable tests
([`TES5Edit/BSArch.dpr:456-523`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L456-L523),
[`TES5Edit/BSArch.dpr:525-617`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L525-L617)).
Consequently, every unknown called out below needs a golden or differential
fixture before it can become a byte-level requirement.

### Broader consumer evidence

The rest of the snapshot confirms which capabilities belong in the reusable
library. The xEdit scripting API exposes container enumeration, extraction,
override counting, existence checks, unsorted/possibly duplicate listing, and
direct resource data access; the Assets Browser and Assets Manager consume
those operations rather than parsing archives themselves
([`TES5Edit/Build/Edit Scripts/xEditAPI.pas:529-544`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Build/Edit%20Scripts/xEditAPI.pas#L529-L544),
[`TES5Edit/Build/Edit Scripts/Assets browser.pas:49-78`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Build/Edit%20Scripts/Assets%20browser.pas#L49-L78)).
SNIFF also accepts a BSA/BA2 as an input directory, enumerates shared
`TwbBSArchive` entries, and passes entry handles to processors
([`TES5Edit/Sniff/frmMain.pas:819-833`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Sniff/frmMain.pas#L819-L833)).
Its texture processor reads dimensions, format, mip/chunk sizes, and cubemap
state directly from DDS BA2 entry metadata without first reconstructing a DDS,
while ordinary archives use full extraction
([`TES5Edit/Sniff/Proc/ProcFindTextures.pas:270-318`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Sniff/Proc/ProcFindTextures.pas#L270-L318)).
This is direct evidence that archive entry metadata and per-entry reading are
consumer-facing capabilities, not CLI presentation concerns.

## Normalized archive-family matrix

All scalar wire fields written through `WriteByte`, `WriteWord`, `WriteCardinal`,
`WriteInt64`, and `WriteUInt64` use the Windows/Delphi native little-endian
representation. Strings are ANSI bytes with one of three encodings: 8-bit
length including an optional terminator, 16-bit length without a terminator, or
NUL termination. The exact helpers are in
[`TES5Edit/Core/wbStreams.pas:77-116`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbStreams.pas#L77-L116)
and
[`TES5Edit/Core/wbStreams.pas:119-180`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbStreams.pas#L119-L180).

| Archive Family | Recognition and emitted version | Normalized layout and names | Compression | Ordering and important semantics |
|---|---|---|---|---|
| **TES3 / Morrowind BSA** | Four-byte magic `00 01 00 00`; no version. Reader and writer both support it. | 12-byte header (`magic`, `hashOffset`, `fileCount`), then `fileCount` pairs of `u32 size`/`u32 relativeDataOffset`, `u32` name offsets, NUL-terminated ANSI names, `u64` hashes, then data. Reader walks the sections sequentially and computes the absolute data base rather than seeking through each name offset. Writer lowercases names. | None. | Writer hashes normalized lowercase backslash paths, sorts by low 32 bits then high 32 bits of the 64-bit hash, and stores relative data offsets. Single-threaded split-packer input is alphabetically sorted with the stated intent that repacked vanilla archives be binary identical. See [`TES5Edit/Core/wbBSArchive.pas:530-552`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L530-L552), [`TES5Edit/Core/wbBSArchive.pas:1345-1367`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1345-L1367), [`TES5Edit/Core/wbBSArchive.pas:1626-1656`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1626-L1656), and [`TES5Edit/Core/wbBSArchive.pas:1801-1827`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1801-L1827). |
| **TES4 / Oblivion BSA** | Magic `BSA\0`, version `0x67`; reader and writer. | 36-byte common BSA header. Each folder record is `u64 hash`, `u32 fileCount`, `u32 offset`. Folder blocks contain an 8-bit-length/NUL-terminated folder name followed by 16-byte file records (`u64 nameHash`, `u32 sizeAndCompressionBit`, `u32 dataOffset`). A global table of NUL-terminated basenames follows the folder blocks. Every entry must have a folder part when encoding. | zlib stream, default and only supported codec. A compressed entry begins with `u32 uncompressedSize`; the record's high size bit is interpreted by XOR with the archive-level compressed flag. | Entries are lowercased and sorted by folder hash then file hash. Oblivion uses the signed-byte variation of the TES4 hash. Auto flags begin with directory names, file names, embedded names, XMem, and unknown bit 10, then infer file categories and other flags. See [`TES5Edit/Core/wbBSArchive.pas:545-580`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L545-L580), [`TES5Edit/Core/wbBSArchive.pas:1370-1418`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1370-L1418), and [`TES5Edit/Core/wbBSArchive.pas:1660-1730`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1660-L1730). |
| **FO3/FNV/Skyrim LE BSA** | Magic `BSA\0`, version `0x68`; the CLI aliases `-fo3`, `-fnv`, and `-tes5` to one family. Reader and writer. | Same 36-byte header, 16-byte folder records, folder/name blocks, and file records as `0x67`. If embedded-file-name flag `0x0100` is set, the data begins with an 8-bit-length ANSI full entry name without a terminator. | zlib stream. The uncompressed-size prefix and archive/file compression XOR are the same as `0x67`. | Entries are lowercased and sorted by folder/file hashes, using the unsigned-byte TES5 variation of the hash. See [`TES5Edit/BSArch.dpr:145-155`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L145-L155), [`TES5Edit/Core/wbBSArchive.pas:1996-2028`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1996-L2028), and [`TES5Edit/Core/wbHash.pas:201-258`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L201-L258). |
| **SSE/Skyrim AE BSA** | Magic `BSA\0`, version `0x69`; reader and writer. | Common 36-byte header, but each folder record is 24 bytes: `u64 hash`, `u32 count`, `u32 zero padding`, `u32 offset`, `u32 zero padding`. Remaining folder/name/file layout is the BSA layout above. Embedded names use the same data prefix as `0x68`. | LZ4 frame (`LZ4F`), default and only supported codec. The uncompressed-size prefix and compression-bit XOR remain BSA-style. | Entries use unsigned-byte TES5 hashes and hash ordering. Auto flags remove the miscellaneous file flag. Texture-only archives gain embedded names; the non-packer path forces compression for SSE embedded-name textures. See [`TES5Edit/Core/wbBSArchive.pas:1383-1417`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1383-L1417), [`TES5Edit/Core/wbBSArchive.pas:1698-1729`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1698-L1729), and [`TES5Edit/Core/wbBSArchive.pas:176-187`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L176-L187). |
| **FO4 General BA2** | Magic `BTDX`, versions `1`, `7`, and `8`, subtype `GNRL`; all three read, only version `1` written. | 24-byte header (`BTDX`, `u32 version`, `GNRL`, `u32 fileCount`, `i64 fileNameTableOffset`). Each 36-byte entry is `u32 basenameHash`, four extension bytes, `u32 directoryHash`, `u8 modIndex`, `u8 chunkCount`, `u16 headerSize`, then one 20-byte chunk (`i64 offset`, `u32 packedSize`, `u32 unpackedSize`, `u32 BAADF00D`). `chunkCount` must be one. Optional names are 16-bit-length ANSI strings and are written with `/`; the reader normalizes them to `\`. If the table offset is absent/out of range, synthetic hash-based names are exposed. | zlib stream. `packedSize == 0` means stored; otherwise it is compressed. | Directory and extension-free basename are hashed separately with Bethesda CRC32 after lowercase/slash normalization. Writer does not sort BA2 entries. Reader support for versions 7/8 is corroborated as read/extract support by the changelog. See [`TES5Edit/Core/wbBSArchive.pas:1420-1498`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1420-L1498), [`TES5Edit/Core/wbBSArchive.pas:1595-1599`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1595-L1599), [`TES5Edit/Core/wbBSArchive.pas:1874-1921`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1874-L1921), and [`TES5Edit/whatsnew.md:693-696`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/whatsnew.md#L693-L696). |
| **FO4 DDS BA2** | `BTDX`, versions `1`, `7`, and `8`, subtype `DX10`; all three read, only version `1` written. | Same 24-byte BA2 header. Each file starts with hashes/extension/mod-index fields plus `u8 chunkCount`, `u16 headerSize`, then `u16 height`, `u16 width`, `u8 mipCount`, `u8 DXGI format`, `u8 flags`, and `u8 tileMode`. Each texture chunk is 24 bytes: `i64 offset`, packed/unpacked `u32` sizes, start/end mip `u16`, and `BAADF00D`. Names table behavior is the same as General. | zlib per mip chunk. Every compressed chunk has nonzero `packedSize`; no separate uncompressed-size prefix. | Valid input must be DDS data. Cubemaps use one chunk. Other textures use up to four chunks based on dimensions/mips; extraction synthesizes a DDS header and concatenates unpacked chunks. Details are in the DDS section below. See [`TES5Edit/Core/wbBSArchive.pas:1466-1487`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1466-L1487), [`TES5Edit/Core/wbBSArchive.pas:1600-1604`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1600-L1604), and [`TES5Edit/Core/wbBSArchive.pas:1922-1938`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1922-L1938). |
| **Starfield General BA2** | `BTDX`, version `2` or `3`, subtype `GNRL`; reader and writer. | FO4 BA2 header plus an extra `u64` for v2/v3 (writer sets it to `1`; reader discards it). Version 3 adds `u32 compressionMethod`. Entries and names use the General layout. | Version 2 is zlib. Version 3 method `3` selects raw LZ4; every other value falls back to zlib in the reader. Writer emits v3 only when raw LZ4 is selected, otherwise v2. General's default is zlib; allowed codecs are zlib and raw LZ4. | Same BA2 hashing, names, and unsorted entry behavior. See [`TES5Edit/Core/wbBSArchive.pas:63-68`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L63-L68), [`TES5Edit/Core/wbBSArchive.pas:1434-1443`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1434-L1443), [`TES5Edit/Core/wbBSArchive.pas:1605-1613`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1605-L1613), and [`TES5Edit/Core/wbBSArchive.pas:1897-1906`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1897-L1906). |
| **Starfield DDS BA2** | `BTDX`, version `2` or `3`, subtype `DX10`; reader and writer. | Starfield extended header plus the DDS entry/chunk layout. | Default raw LZ4, with zlib also allowed. Selecting raw LZ4 emits v3/method 3; zlib emits v2. | Same DDS validation, chunking, reconstruction, hashing, and names as FO4 DDS. See [`TES5Edit/Core/wbBSArchive.pas:1614-1620`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1614-L1620) and [`TES5Edit/Core/wbBSArchive.pas:176-187`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L176-L187). |

### Recognition limits

The loader first accepts only the three magics above, then maps only BSA/BA2
versions `0x67`, `0x68`, `0x69`, `1`, `2`, `3`, `7`, and `8`. It rejects unknown
magic/version values and rejects BA2 subtypes other than `GNRL` and `DX10`
([`TES5Edit/Core/wbBSArchive.pas:1313-1343`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1313-L1343),
[`TES5Edit/Core/wbBSArchive.pas:1421-1431`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1421-L1431)).
Filename extension is not used for binary format detection; it is used only by
`IsArchive`, which recognizes `.bsa` and `.ba2` case-insensitively
([`TES5Edit/Core/wbBSArchive.pas:188-199`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L188-L199),
[`TES5Edit/Core/wbBSArchive.pas:1040-1049`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1040-L1049)).

## Hashes, names, lookup, and ordering

### On-disk hashes

- TES3 lowercases and changes `/` to `\`, converts to ANSI, then applies its
  split-half XOR/rotate 64-bit algorithm
  ([`TES5Edit/Core/wbHash.pas:169-195`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L169-L195)).
- BSA hashes directory and basename separately. Both are lowercased. File
  extension participates in special low-bit masks for `.kf`, `.nif`, `.dds`,
  and `.wav`, and in the high SDBM-like accumulation. Oblivion enables signed
  treatment for bytes above 127; later BSA families do not
  ([`TES5Edit/Core/wbHash.pas:201-253`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L201-L253)).
- BA2 hashes the lowercase normalized directory and extension-free basename
  separately with Bethesda's zero-seeded, non-final-XOR CRC32 loop. Extension
  is stored as at most the first four lowercased bytes
  ([`TES5Edit/Core/wbHash.pas:256-267`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L256-L267),
  [`TES5Edit/Core/wbBSArchive.pas:1733-1751`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1733-L1751)).

The reader retains stored hashes rather than validating them. The commented
hash smoke procedure compares calculated values to archive values but does not
assert, run by default, or ship inputs
([`TES5Edit/BSArch.dpr:456-523`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L456-L523)).

### Consumer lookup and normalization

Independent of wire hashes, loaded entries receive a case-insensitive XXH64
lookup hash. `FileByName` compares only this hash, not the string, so both case
differences and the theoretical XXH64 collision are treated as identity
([`TES5Edit/Core/wbHash.pas:146-166`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L146-L166),
[`TES5Edit/Core/wbBSArchive.pas:1502-1509`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1502-L1509),
[`TES5Edit/Core/wbBSArchive.pas:2271-2280`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2271-L2280)).
Folder listing is a case-insensitive prefix match after removing a trailing
delimiter; it is not a path-segment-boundary match
([`TES5Edit/Core/wbBSArchive.pas:2282-2293`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2282-L2293)).

`GetAssetName` recognizes `data`/`data files` and a catalog of known asset roots,
normalizes `/` and repeated separators to `\`, but mostly preserves source
case in the returned name. If no root is discoverable, it infers an asset root
from the extension, falling back to `meshes`; absolute paths contribute only
their basename in that fallback
([`TES5Edit/Core/wbBSArchive.pas:465-505`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L465-L505),
[`TES5Edit/Core/wbBSArchive.pas:698-763`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L698-L763)).
TES3/BSA creation then lowercases names; BA2 creation preserves the supplied
display name while hashing case-insensitively
([`TES5Edit/Core/wbBSArchive.pas:1628-1637`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1628-L1637),
[`TES5Edit/Core/wbBSArchive.pas:1664-1677`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1664-L1677),
[`TES5Edit/Core/wbBSArchive.pas:1733-1751`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1733-L1751)).

### Overlay and source ordering

Multi-source packing accepts loose files, recursive folders, and existing BSA or
BA2 archives. Later sources replace the source pointer of an earlier
case-insensitive matching asset, so **later source wins** while retaining that
asset's first insertion position. The matching is XXH64-only. Duplicate source
archive paths are ignored case-insensitively
([`TES5Edit/Core/wbBSArchive.pas:385-419`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L385-L419),
[`TES5Edit/Core/wbBSArchive.pas:2780-2827`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2780-L2827),
[`TES5Edit/Core/wbBSArchive.pas:2829-2882`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2829-L2882)).
xEdit's independent consumer corroborates the precedence rule: it accumulates
containers in registration order but returns resource data by walking matching
containers in reverse, so the later container wins
([`TES5Edit/Core/wbBSA.pas:168-217`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSA.pas#L168-L217),
[`TES5Edit/Core/wbBSA.pas:287-316`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSA.pas#L287-L316)).

Folder enumeration order is whatever `TDirectory.GetFiles(..., soAllDirectories)`
returns. BSA families subsequently hash-sort metadata; BA2 does not. With
multithreaded split packing, preload completion changes chain and archive order.
BSArchPro explicitly warns that identical inputs can produce a different
checksum each time due to random packed-file ordering
([`TES5Edit/Core/wbBSArchive.pas:2837-2846`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2837-L2846),
[`TES5Edit/Core/wbBSArchive.pas:2651-2711`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2651-L2711),
[`TES5Edit/BSArch/frmPack.dfm:163-173`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmPack.dfm#L163-L173)).
Therefore multithreaded byte identity is explicitly *not* reference behavior;
semantic equality and separately measured deterministic single-thread cases are
the appropriate conformance levels.

## Compression contract

Supported/default codecs are fixed per family: TES3 none; TES4/FO3 zlib; SSE
LZ4F; FO4 General/DDS zlib; Starfield General zlib then raw LZ4; Starfield DDS
raw LZ4 then zlib. The first allowed codec is the default
([`TES5Edit/Core/wbBSArchive.pas:176-187`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L176-L187),
[`TES5Edit/Core/wbBSArchive.pas:1014-1026`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1014-L1026)).

- **zlib** means zlib-wrapped DEFLATE. Inputs at most 8 MiB use libdeflate
  level 12; larger inputs use Delphi zlib level 9. Decompression uses
  libdeflate's zlib decoder
  ([`TES5Edit/Core/wbCompression.pas:40-52`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L40-L52),
  [`TES5Edit/Core/wbCompression.pas:121-154`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L121-L154),
  [`TES5Edit/Core/wbCompression.pas:328-341`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L328-L341)).
- **raw LZ4** uses high-compression level 12 and the raw block APIs; decode
  requires the exact expected output length
  ([`TES5Edit/Core/wbCompression.pas:157-176`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L157-L176)).
- **LZ4F** uses an independent-block frame, maximum 4 MiB block size, autoflush,
  and level 12. Decode rejects leftover input or a short output
  ([`TES5Edit/Core/wbCompression.pas:187-240`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L187-L240)).

Compression is unconditional when selected; the writer does not fall back to
stored data when compressed bytes are larger. For BSA, it writes an
uncompressed-size prefix and represents the per-file decision by XORing the
record high bit with the archive-wide compressed flag. For BA2, nonzero packed
size alone marks compression
([`TES5Edit/Core/wbBSArchive.pas:1979-2028`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1979-L2028),
[`TES5Edit/Core/wbBSArchive.pas:2157-2193`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2157-L2193)).

When global compression is enabled, sound, voice, music, and strings are left
stored, except `.fuz` and `.hkx`. The CLI documents the same policy and the
changelog specifically records allowing `.fuz` compression
([`TES5Edit/Core/wbBSArchive.pas:797-803`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L797-L803),
[`TES5Edit/Core/wbBSArchive.pas:2818-2825`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2818-L2825),
[`TES5Edit/BSArch.dpr:404-407`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L404-L407),
[`TES5Edit/whatsnew.md:436-440`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/whatsnew.md#L436-L440)).

## DDS BA2 contract

Specialized DDS archives store texture metadata and mip bytes, not the original
DDS header. Extraction therefore promises the Reference Snapshot's canonical
reconstruction, not byte-for-byte recovery of the input DDS header.

### Validation and metadata

Packing requires `DDS ` magic and enough bytes for the inferred base/DX10/Xbox
header. A PC target rejects Xbox FourCC; an Xbox target rejects non-Xbox input.
24-bit `R8G8B8` input is converted to 32-bit `B8G8R8X8`. Unknown DXGI format is
rejected
([`TES5Edit/Core/wbDDS.pas:398-436`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L398-L436),
[`TES5Edit/Core/wbBSArchive.pas:2060-2086`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2060-L2086),
[`TES5Edit/Core/wbDDS.pas:936-956`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L936-L956)).
Width/height are narrowed to 16 bits, mip count and DXGI format to 8 bits, and
zero mip count is canonicalized to one. The archive records a cubemap bit and
tile mode
([`TES5Edit/Core/wbBSArchive.pas:2078-2092`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2078-L2092)).

### Chunking

Default maximum chunk count is four; the threshold is 512 by 512. Start with
one chunk, and while another mip exists, fewer than four chunks exist, and both
current dimensions are at least 512, add a chunk and halve both dimensions.
Cubemaps always use one chunk. Every chunk except the last contains one mip;
the last contains all remaining bytes/mips. Initial mip size is
`width * height * bitsPerPixel / 8`, subsequent single-mip sizes divide by four,
and the final chunk consumes the exact remainder
([`TES5Edit/Core/wbBSArchive.pas:946-952`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L946-L952),
[`TES5Edit/Core/wbBSArchive.pas:972-985`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L972-L985),
[`TES5Edit/Core/wbBSArchive.pas:2088-2119`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2088-L2119)).

### Reconstruction

Extraction allocates a base DDS header, adds a DX10 header for formats outside
the reference's DX9-compatible set, and adds both DX10 and Xbox headers for
Xbox output. It synthesizes flags, format masks/FourCC, pitch/linear size,
dimension, array size, cubemap fields, and Xbox XDK version `10705`, then
appends decompressed chunks in archive order
([`TES5Edit/Core/wbDDS.pas:246-265`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L246-L265),
[`TES5Edit/Core/wbDDS.pas:574-607`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L574-L607),
[`TES5Edit/Core/wbDDS.pas:609-785`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L609-L785),
[`TES5Edit/Core/wbBSArchive.pas:2195-2227`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2195-L2227)).
The synthesized cubemap fields intentionally use what the source calls the
correct layout instead of Archive2's invalid one
([`TES5Edit/Core/wbDDS.pas:600-606`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L600-L606)).

Xbox reconstruction is inferred from the **archive filename** containing
`_xbox.`, not solely from entry metadata; the recorded tile mode is written into
the synthesized Xbox header only in that case
([`TES5Edit/Core/wbBSArchive.pas:2195-2213`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2195-L2213)).
The CLI never assigns `ArchiveTarget`, whose zero/default enum value is PC, so
it can unpack Xbox textures but does not expose Xbox DDS packing. The changelog
explicitly distinguishes BSArch unpack support from BSArchPro pack/conversion
support
([`TES5Edit/Core/wbBSArchive.pas:27-30`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L27-L30),
[`TES5Edit/Core/wbBSArchive.pas:946-952`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L946-L952),
[`TES5Edit/whatsnew.md:197-203`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/whatsnew.md#L197-L203)).
BSArchPro can optionally invoke Creation Kit's `xtexconv.exe` to convert PC DDS
to Xbox before packing; this GUI-only external conversion is evidence for the
target distinction, not part of BSArch CLI functionality
([`TES5Edit/BSArch/frmMain.pas:248-295`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmMain.pas#L248-L295)).

## Flags and inferred policy

BSA archive flags are bits 0 through 10: directory names, file names, compressed,
retain directory names, retain file names, retain file-name offsets, Xbox 360,
retain startup strings, embedded file names, XMem codec, and an unnamed bit 10.
File flags classify meshes, textures, menus, sounds, voices, shaders, trees,
fonts, and miscellaneous
([`TES5Edit/Core/wbBSArchive.pas:556-593`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L556-L593)).

Auto-detection starts with directory/file names; Oblivion additionally starts
with embedded name, XMem, and bit 10. It derives file flags from asset roots or
extensions, adds retain-name for scripts and sounds, embedded-name for a
texture-only archive, startup-strings for meshes, and the archive compressed
flag when any entry is compressed. Non-Oblivion removes menu/shader/font bits;
SSE removes miscellaneous
([`TES5Edit/Core/wbBSArchive.pas:1056-1124`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1056-L1124)).
Explicit CLI `-af`/`-ff` values replace inferred values when nonzero, while the
writer always ORs directory/file-name bits into an explicit archive flag value
([`TES5Edit/BSArch.dpr:183-189`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L183-L189),
[`TES5Edit/Core/wbBSArchive.pas:1715-1721`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1715-L1721)).
Zero cannot explicitly override either flag set because zero means "no override."

Warnings are observable library behavior. They cover unsupported TES3 asset
folders; BSA file offsets beyond signed 2 GiB; cubemap textures without embedded
names; non-textures with embedded names; uncompressed SSE entries with embedded
names; and uncompressed DDS BA2 textures
([`TES5Edit/Core/wbBSArchive.pas:1223-1269`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1223-L1269)).

## Data sharing and split behavior

Sharing is enabled by default in the CLI. The archive writer identifies existing
data by uncompressed size plus XXH64 and reuses the earlier offset/size fields;
it does **not** compare bytes after a hash match. DDS chunks can share
independently, but reporting counts a shared DDS file only for its first mip
chunk
([`TES5Edit/BSArch.dpr:183-184`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L183-L184),
[`TES5Edit/Core/wbBSArchive.pas:1271-1310`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1271-L1310)).
The split packer also hashes complete uncompressed source bytes to avoid counting
an identical already-loaded file twice toward the current archive's rough size
estimate
([`TES5Edit/Core/wbBSArchive.pas:2552-2571`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2552-L2571)).

Default split size is signed 32-bit maximum (`2,147,483,647`) for TES3 and BSA,
and zero/no split for BA2. CLI `-split:N` uses integer GiB, caps only the upper
bound at 8, and permits zero to disable splitting
([`TES5Edit/Core/wbBSArchive.pas:1001-1007`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1001-L1007),
[`TES5Edit/BSArch.dpr:173-179`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L173-L179)).
The split is advisory, not an exact maximum: the packer adds packed chunk sizes,
200 bytes per file, name length, and possible embedded-name length; when the
estimate exceeds the target it rolls the already-loaded chain into an archive.
A single oversized file gets its own archive. Subsequent outputs insert `2`,
`3`, and so on before the extension
([`TES5Edit/Core/wbBSArchive.pas:2421-2430`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2421-L2430),
[`TES5Edit/Core/wbBSArchive.pas:2573-2606`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2573-L2606)).

## Validation, warnings, and failure semantics

Core creation rejects an empty file list, unsupported family, unsupported codec,
and entries with no folder part for BSA/BA2. Saving rejects entries that were
never packed. Specialized DDS packing adds the validation above
([`TES5Edit/Core/wbBSArchive.pas:1546-1576`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1546-L1576),
[`TES5Edit/Core/wbBSArchive.pas:1667-1677`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1667-L1677),
[`TES5Edit/Core/wbBSArchive.pas:1737-1749`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1737-L1749),
[`TES5Edit/Core/wbBSArchive.pas:1794-1882`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1794-L1882)).

Core reading validates magic/version/subtype and requires one GNRL chunk, but
the source's BA2 `headerSize` checks are commented out; mod index and
`BAADF00D` tails are read/discarded without validation. A missing or out-of-range
BA2 filename table is accepted and exposed using synthetic hash names
([`TES5Edit/Core/wbBSArchive.pas:1445-1498`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1445-L1498)).
The reader does not proactively validate counts, offsets, overlapping spans,
name offsets, stored hashes, path traversal, duplicate names, or section bounds;
some malformed inputs will fail only through stream reads or allocation errors.
These omissions describe the snapshot; they are not a requirement to expose
unsafe filesystem behavior.

BSArchPro adds front-end checks absent from the CLI/core: archive names must be
relative, valid, and contain a folder; non-ASCII names warn; unwanted extensions
warn; compressed sound/music/strings warn; duplicate asset names are rejected;
and overwriting an input archive is blocked
([`TES5Edit/BSArch/frmMain.pas:1713-1858`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmMain.pas#L1713-L1858),
[`TES5Edit/BSArch/frmPack.pas:181-228`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmPack.pas#L181-L228)).

## BSArch CLI behavior matrix

The command is case-insensitive for `pack`/`unpack` and switches. A switch can
be bare or `name:value`; value matching scans all arguments
([`TES5Edit/Core/wbCommandLine.pas:38-65`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCommandLine.pas#L38-L65),
[`TES5Edit/BSArch.dpr:372-380`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L372-L380)).

| Operation | Inputs and defaults | Output/failure behavior |
|---|---|---|
| `pack <source1+source2+...> <archive>` | Exactly one family switch is selected by first match in this priority: `tes3`, `tes4`, `fo3`, `fnv`, `tes5`, `sse`, `fo4`, `fo4dds`, `sf1`, `sf1dds`. Sources split on `+`; each may be file, recursive folder, BSA, or BA2. Later source wins. | Missing source/output/type or no valid assets is an invalid-argument failure. Processing can be parallel (default) or single-threaded. First processing error stops work and sets exit code 1; save failure is rethrown with `Archive saving error`. Success prints output names, archive sizes/counts, shared-space metrics, and warnings. See [`TES5Edit/BSArch.dpr:129-207`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L129-L207) and [`TES5Edit/BSArch.dpr:209-272`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L209-L272). |
| `-z[:type]` | Absent means store all files. Present without value selects family default. Values are `zlib`, `lz4`, or `lz4f`, but unsupported family/codec combinations fail. Global compression still excludes sound/voice/music/strings except `.fuz`/`.hkx`. TES3 ignores `-z`. | Compression is applied per entry/chunk as above. See [`TES5Edit/BSArch.dpr:157-171`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L157-L171). |
| `-split:N` | Integer GiB; values above 8 become 8, 0 disables. Omitted means signed-2-GiB default for BSA/TES3 and no split for BA2. | Numbered archives use suffixes starting with `2`. Invalid text becomes 0; negative values are not rejected in the source. |
| `-f:mask[,mask...]` | Filters against each asset's basename using Windows path pattern matching. Blank masks are discarded. | Nonmatching assets are silently omitted. See [`TES5Edit/Core/wbBSArchive.pas:2773-2799`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2773-L2799). |
| `-share:yes\|no` | Default on; only value `no` disables. | Identical-by-size-and-XXH64 data reuses offsets and is reported. |
| `-mt:yes\|no` | Default on; only value `no` disables. | Parallel ordering is nondeterministic by design. |
| `-af:hex`, `-ff:hex` | Optional BSA flag overrides. `0x` prefix is optional; zero acts as no override. | Invalid hex fails. Directory/file-name archive bits are still forced. |
| `unpack <archive> [existing-folder]` | Destination defaults to the archive's directory; an explicitly supplied directory must already exist. Multithreading defaults on and `-mt:no` disables it. | Creates entry parent directories, then writes each entry to `destination + entryName`. It does not sanitize archive names before concatenation. First processing error sets exit code 1; success prints elapsed time. See [`TES5Edit/BSArch.dpr:276-351`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L276-L351). |
| `<archive> [-list\|-dump]` | An existing first argument selects info mode. Base info includes name, normalized format, version except TES3, entry count, compressed count/codec, and BSA flags. `-list` lists names; `-dump` also prints family-specific entry/chunk metadata. | Warnings are printed. The operation catches load/inspection errors locally, prints `Error: ...`, and returns without setting nonzero exit status—an apparent defect discussed below. See [`TES5Edit/BSArch.dpr:95-126`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L95-L126) and [`TES5Edit/Core/wbBSArchive.pas:1186-1221`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1186-L1221). |

Top-level uncaught failures print exception class/text and set exit code 1
([`TES5Edit/BSArch.dpr:619-635`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L619-L635)).
Banner wording, progress carriage returns, elapsed-time rendering, and whitespace
are presentation details rather than library behavior.

## Reference performance characteristics

These are baselines to measure, not architecture that Java must copy:

- Pack and unpack default to parallel work. Codec work and source reads release
  the archive synchronization lock; serialized stream updates reacquire it
  ([`TES5Edit/BSArch.dpr:214-239`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L214-L239),
  [`TES5Edit/BSArch.dpr:316-340`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L316-L340),
  [`TES5Edit/Core/wbBSArchive.pas:1950-1960`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1950-L1960),
  [`TES5Edit/Core/wbBSArchive.pas:1974-1994`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1974-L1994)).
- Split packing is a load/compress/write pipeline. Two of every three scheduler
  ticks prefer draining preloaded data to reduce memory while one keeps loading
  and compressing
  ([`TES5Edit/Core/wbBSArchive.pas:2651-2696`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2651-L2696)).
- The implementation materializes complete loose files, complete extracted
  archive entries, and complete compressed/decompressed buffers in memory
  ([`TES5Edit/Core/wbBSArchive.pas:2137-2227`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2137-L2227),
  [`TES5Edit/Core/wbBSArchive.pas:2726-2743`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2726-L2743)).
- Entry lookup, source-name override detection, and packed-data sharing scan
  linearly. Repeated lookup/dedup over many entries can therefore become
  quadratic even though hashes make each comparison cheap
  ([`TES5Edit/Core/wbBSArchive.pas:1271-1310`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1271-L1310),
  [`TES5Edit/Core/wbBSArchive.pas:2271-2280`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2271-L2280),
  [`TES5Edit/Core/wbBSArchive.pas:2801-2809`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2801-L2809)).

Java may improve indexing, streaming, allocation, and scheduling as long as the
normalized names, overlay result, emitted metadata, decompressed bytes, warnings,
and deterministic-mode ordering remain conformant. Performance comparisons must
separate codec time, disk I/O, memory peak, concurrency, and output size because
the reference trades them against one another.

## Contradictions and apparent defects

These are not silently promoted to requirements.

1. **CLI DDS compression is documented but not enforced.** CLI help says FO4 DDS
   must always use `-z`, and library warnings say an uncompressed DDS archive can
   crash the game. The CLI nevertheless leaves all files uncompressed when `-z`
   is absent. BSArchPro forcibly marks all DDS entries compressed. Conformance
   should preserve valid compressed DDS behavior and reject or explicitly warn
   on an unsafe uncompressed DDS encode rather than infer that the omission is
   desired
   ([`TES5Edit/BSArch.dpr:395-407`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L395-L407),
   [`TES5Edit/Core/wbBSArchive.pas:1260-1267`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1260-L1267),
   [`TES5Edit/BSArch/frmMain.pas:1924-1927`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmMain.pas#L1924-L1927)).
2. **Info errors exit successfully.** `DoInfo` catches and prints errors without
   setting `ExitCode`, bypassing the top-level exit-1 handler. This conflicts
   with the changelog's broad “Return a non-zero exit code on error” statement.
   Treat it as an apparent CLI defect unless differential compatibility
   explicitly selects it
   ([`TES5Edit/BSArch.dpr:95-126`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L95-L126),
   [`TES5Edit/whatsnew.md:197-209`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/whatsnew.md#L197-L209)).
3. **The non-texture embedded-name fix appears to clear the wrong field.** The
   comment says to clear embedded names, an archive flag, but the assignment
   clears that bit from `FileFlags` instead of header/archive flags. Because bit
   `0x0100` is also `FILE_MISC`, it may instead remove the miscellaneous file
   category. This requires a differential fixture before emulation
   ([`TES5Edit/Core/wbBSArchive.pas:1715-1729`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1715-L1729)).
4. **Oblivion auto-flags embedded names but its writer does not emit the embedded
   name prefix.** Auto-detection adds `ARCHIVE_EMBEDNAME` for every Oblivion BSA,
   while `PackData` writes the prefix only for FO3/SSE. Establish whether games
   or official archives interpret this flag differently for v0x67 before making
   byte-level assertions
   ([`TES5Edit/Core/wbBSArchive.pas:1056-1063`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1056-L1063),
   [`TES5Edit/Core/wbBSArchive.pas:1996-2004`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1996-L2004)).
5. **BA2 structural constants are written but not validated.** The reader ignores
   mod index, header-size mismatch, and tail mismatch. Robust Java decoding may
   diagnose these without rejecting files that the reference accepts; strict
   and compatible validation modes may need separate policy
   ([`TES5Edit/Core/wbBSArchive.pas:1445-1486`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1445-L1486)).
6. **Data identity is hash-only.** Both name overlay and byte sharing trust XXH64
   without a confirming string/byte comparison. The Java library should confirm
   equality after the hash for correctness while preserving normal-case output;
   only a crafted collision test could distinguish this from the snapshot.
7. **Xbox decode depends on the archive filename.** Valid Xbox texture metadata
   can reconstruct as PC DDS when the containing BA2 lacks `_xbox.`. This is an
   apparent coupling defect; retain the filename heuristic only as a compatibility
   fallback after explicit target metadata/policy.
8. **Multithreaded output is intentionally nondeterministic.** This is documented
   reference behavior, but it conflicts with using byte equality as a universal
   conformance metric. Semantic conformance is the gate; byte comparison belongs
   to pinned single-thread, pinned-codec fixtures.

## Unknowns requiring fixtures or broader primary evidence

1. **FO4 BA2 v7/v8 record differences.** The snapshot accepts versions 7/8 with
   the v1 parser and changelog promises read/extract, but never emits them and
   contains no checked-in archive fixture. Obtain legal v7/v8 General and DX10
   samples and determine whether currently ignored fields have version-specific
   meaning.
2. **Starfield version/method combinations.** Reader treats any v3 method except
   `3` as zlib; writer emits zlib only as v2. Test v3/method 0 and unknown method
   values against official tools/game behavior before deciding validation.
3. **Exact compression bytes.** libdeflate/zlib/LZ4 versions are submodules and
   encoder output can vary by library/version even at the same level. Pin
   golden byte equality only where the Java codec demonstrably matches; always
   require cross-decoding and uncompressed-byte equality.
4. **Non-ASCII name encoding and signed hash behavior.** Names are converted to
   process ANSI strings, the GUI warns against non-ASCII, and the active Windows
   code page is not encoded in the archive. Build code-page fixtures before
   promising behavior beyond ASCII
   ([`TES5Edit/BSArch/frmMain.pas:1740-1765`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch/frmMain.pas#L1740-L1765)).
5. **Malformed-input acceptance.** Counts, offsets, overlap, duplicate names,
   folder boundaries, truncated strings, and path traversal are not
   systematically validated. Generate malformed archives and record reference
   outcomes, but define secure extraction independently so “conformance” cannot
   authorize writes outside the destination.
6. **DDS mip math edge cases.** The first chunk size uses width × height × bits
   per pixel without explicit block rounding, then divides by four. Test tiny,
   nonsquare, non-power-of-two, array, volume, unusual DXGI, cubemap, missing-mip,
   and truncated DDS inputs. The included DDS corpus covers 48 named format and
   cubemap/no-mipmap examples but supplies no assertions or archive goldens.
7. **DDS original-header expectations.** Because extraction synthesizes rather
   than preserves headers, determine game/tool equivalence for alpha mode,
   typeless formats, arrays, pitch/linear size, cubemap caps, and Xbox header
   fields. Compare reconstructed bytes to BSArch output, not original source DDS
   bytes.
8. **Split boundaries under concurrency and sharing.** The estimate is rough and
   completion order changes grouping. Golden tests should assert the exact
   single-thread grouping algorithm and only size/safety/semantic properties for
   multithreaded runs.
9. **Zero flag override and negative split values.** The implementation makes
   zero mean “auto” and does not reject negative `-split`. These invalid or
   ambiguous inputs need an explicit CLI compatibility choice, not automatic
   propagation into the library API.
10. **No authoritative performance baseline is present.** The reference exposes
    multithreaded processing and whole-file buffering, but no benchmarks or
    numeric targets. Performance thresholds must be established by a separate,
    reproducible Windows 11 x64 comparison corpus.

## Conformance consequences for the Java design

- Model Archive Family separately from on-disk version and BA2 subtype. A single
  enum that collapses FO4 v1/v7/v8 or Starfield v2/v3 will make decode-only and
  codec constraints hard to express.
- Keep display name, normalized lookup key, and wire hashes distinct. Do not use
  host `Path` normalization for archive names.
- Separate parser validation from extraction-path safety. Preserve readable
  archives where the reference ignores harmless constants, but never allow an
  entry name to escape a chosen destination.
- Expose codec providers and parameters so conformance can pin zlib wrapper,
  raw-LZ4 versus LZ4-frame, compression level, and deterministic mode.
- Treat specialized DDS extraction as canonical reconstruction. Preserve all
  stored metadata and make target-platform inference explicit; archive filename
  is compatibility evidence, not a sound primary model.
- Make source overlays stable and case-insensitive, with later source winning,
  but confirm string/byte equality after hashes. Preserve first insertion order
  unless an Archive Family requires hash sorting.
- Provide explicit sequential/deterministic and parallel/performance modes.
  Conformance fixtures should run sequentially; performance benchmarks may use
  parallel mode and assert semantic output.
- Represent warnings as structured library results so both CLI and other
  consumers can render the same diagnoses without duplicating archive logic.

These consequences follow the snapshot's actual library-consumer pattern: both
BSArch and BSArchPro use `wbBSArchive`, while xEdit consumes the same archive
reader through `wbBSA`. The changelog confirms that xEdit's former BSA/BA2 code
was replaced with BSArch code and that this broadened TES3 and texture support
([`TES5Edit/whatsnew.md:1398-1404`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/whatsnew.md#L1398-L1404)).
