# Reference compression selection

Research date: 2026-09-08. Reference Snapshot: TES5Edit
`fd1e36020b2b5b6217e553dc0038983146a2e2dd`, verified against the local read-only
submodule. These are behavioral observations, independently summarized from
the pinned source; no reference implementation was copied or adapted.

## Conclusion

The reference distinguishes **whether each file is compressed** from **which
codec the archive uses**. Versioned BSA and BA2 creation accept a per-file
Boolean compression array. This does not permit choosing arbitrary codecs for
different entries of one archive. TES3 is the exception: its creation path does
not assign those per-file compression choices, and its supported codec is
`None`. [Creation interface and Boolean lookup](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1546-L1555),
[TES3 creation](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1627-L1657),
[versioned-BSA assignment](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1660-L1674),
[BA2 assignment](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1734-L1751).

The archive selects one supported codec, using its family default when no
codec has been selected. Oblivion and FO3-family BSA use zlib, SSE uses LZ4
frame, and Starfield's available codec choices are still archive-level choices.
For Starfield output, that selection also affects the wire version. A missing
entry in the Boolean array defaults to uncompressed at the low-level creation
boundary. [Family codec declarations](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L176-L187),
[codec selection and version selection](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1573-L1621).

## Wire behavior and CLI policy

The writer conditionally compresses using the selected archive codec. For
versioned BSA, a compressed record carries its decoded-size prefix; the file
toggle records the difference between the file choice and the archive default.
The inspected write path does not change a compressed choice to stored merely
because compression increases size. These observations support mixed stored
and compressed entries, not mixed codec algorithms.
[Payload compression and framing](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1980-L2028).

BSArch's `-z` enables compression and optionally selects an archive codec; the
branch excludes TES3. The multisource packer then derives file choices from
that global enablement and an asset-path policy: Sound, Voice, Music and
Strings classes are excluded, except `.fuz` and `.hkx`. Therefore `-z` does
not mean every reference CLI input is necessarily compressed.
[CLI selection](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L158-L171),
[multisource selection](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2818-L2822),
[asset exclusion policy](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L797-L803).

## DDS qualification

Maintainer clarification, 2026-09-08: Fallout 4 and Starfield crash when a BA2
DDS archive contains files using `STORED` (called `STORE` in the clarification).
This is the game-compatibility reason for the canonical prohibition in
[JBSA-DX10-006](../spec/formats/dds-ba2.md#jbsa-dx10-006), including per-entry
overrides. It is maintainer-supplied compatibility evidence, not a game test
performed during this implementation.

The low-level reference packer forwards an entry's Boolean choice to its DDS
payload chunks. Its temporary uncompressed DDS-header chunk is an internal
packing representation, not evidence that canonical wire texture chunks may
be stored. The raw reference capability is broader than JBSA's accepted
canonical policy. [Reference DDS preparation](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2489-L2503),
[header and payload chunk choices](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L2538-L2549).

JBSA-DX10-006 explicitly requires compression for every canonical DDS chunk,
rejects stored DDS encode requests in every compatibility profile, and admits
bounded stored chunks only on decode with a tolerated-noncanonical warning.
Thus a generic per-entry options map must still enforce each target family's
codec and direction restrictions; it cannot enable stored DDS encoding.
[JBSA DDS policy](../spec/formats/dds-ba2.md#jbsa-dx10-006).

## Provider behavior versus the JBSA profile

The reference's zlib compression path uses libdeflate at sizes up to and
including 8 MiB and its other zlib implementation above that boundary. Its
default levels are respectively 12 and 9. This is internal provider dispatch
within the same wire codec, not per-entry user choice of compression algorithm.
[Threshold and levels](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L41-L48),
[zlib dispatch](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L328-L343).

JBSA deliberately specifies Java 25 zlib with canonical level 9 for its
baseline profile. Matching decoded bytes and framing does not establish
compressed-byte identity with either reference provider; such identity needs
individually qualified evidence. [JBSA codec baseline](../spec/codecs.md#jbsa-codec-002),
[BSA zlib profile](../spec/formats/versioned-bsa.md#jbsa-bsa-009),
[codec qualification](../spec/codecs.md#jbsa-codec-013).
