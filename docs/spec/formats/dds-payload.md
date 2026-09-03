# DDS payload

This specification owns DDS input analysis, mip partitioning, and canonical
reconstruction for DDS BA2. It treats DDS as a binary envelope around opaque
image bytes; it does not require BC or other pixel decoding.

## JBSA-DDS-001

The DDS payload implementation **MUST** recognize these little-endian envelope
forms:

| Form | Bytes before payload | Structure |
| --- | ---: | --- |
| Legacy | 128 | `DDS ` magic plus 124-byte `DDS_HEADER` |
| DX10 | 148 | legacy form plus 20-byte `DDS_HEADER_DXT10` |
| Xbox | 164 | DX10 form plus 16-byte Xbox extension |

The Xbox extension is additional to the DX10 extension. The implementation
**MUST** expose opaque payload spans without decoding pixels and **MUST NOT**
claim to retain or recover the original input header after DDS BA2 packing.

The base form **MUST** use the standard field layout below; offsets include the
four-byte magic:

| Offset | Field |
| ---: | --- |
| 0 | `DDS ` |
| 4 | `size: u32`, `flags: u32`, `height: u32`, `width: u32` |
| 20 | `pitchOrLinearSize: u32`, `depth: u32`, `mipCount: u32` |
| 32 | eleven reserved `u32` values |
| 76 | 32-byte pixel format: size, flags, FourCC, bit count, and four masks |
| 108 | `caps`, `caps2`, `caps3`, `caps4`, and reserved `u32` values |

The DX10 extension at offset 128 **MUST** contain five `u32` values: DXGI
format, resource dimension, miscellaneous flags, array size, and alpha-mode
flags. The Xbox extension at offset 148 **MUST** contain four `u32` values:
tile mode, base alignment, data size, and XDK version.

_Source decisions: [accepted Reference Snapshot DDS behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted internal DDS-envelope strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-DDS-002

DDS BA2 encode **MUST** require `DDS ` magic, all bytes of the inferred envelope,
`DDS_HEADER.dwSize == 124`, `DDS_PIXELFORMAT.dwSize == 32`, a format allowed by
[JBSA-DDS-012](#jbsa-dds-012), and payload bounds computable with checked arithmetic.
A parseable legacy size deviation **MAY** be inspected as Tolerated Noncanonical
with a stable diagnostic, but **MUST NOT** be emitted.

A PC target **MUST** reject an `XBOX` FourCC and an Xbox target **MUST** reject
non-Xbox input. Because the DX10 BA2 record cannot preserve general arrays,
volumes, or depth, canonical encode **MUST** accept only a two-dimensional
resource with depth one and array size one, including the six faces represented
by the cubemap flag. Broader shapes remain unsupported until a fixture-backed
rule preserves their semantics.

_Source decisions: [accepted Reference Snapshot DDS validation](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted validation semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-DDS-003

Input format derivation **MUST** apply these legacy mappings before consulting
RGB/luminance masks:

| DDS FourCC | DXGI format |
| --- | --- |
| `DXT1` | `BC1_UNORM` |
| `DXT3` | `BC2_UNORM` |
| `DXT5` | `BC3_UNORM` |
| `ATI1` or `BC4U` | `BC4_UNORM` |
| `BC4S` | `BC4_SNORM` |
| `ATI2` or `BC5U` | `BC5_UNORM` |
| `BC5S` | `BC5_SNORM` |
| `DX10` or `XBOX` | numeric `dxgiFormat` from the following DX10 header |

For legacy RGB or luminance data, derivation **MUST** map: 32-bit without alpha
to `B8G8R8X8_UNORM`; 32-bit with red mask `0x000000FF` to
`R8G8B8A8_UNORM`; other 32-bit alpha data to `B8G8R8A8_UNORM`; exact 16-bit
`F800/07E0/001F/0000` masks to `B5G6R5_UNORM`; exact
`7C00/03E0/001F/8000` masks to `B5G5R5A1_UNORM`; other recognized 16-bit
luminance data to `R8G8_UNORM`; 8-bit alpha data to `A8_UNORM`; and other
recognized 8-bit luminance data to `R8_UNORM`. A result of `UNKNOWN` **MUST** be
rejected.

DXGI names in this specification denote their standard Microsoft
`DXGI_FORMAT` numeric values; the `u8` BA2 field **MUST** contain that numeric
value exactly.

_Source decisions: [accepted Reference Snapshot DDS format behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted DDS-envelope implementation](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-DDS-004

The bits-per-pixel value used by the Reference Snapshot partition algorithm
**MUST** be:

| Bits | DXGI formats |
| ---: | --- |
| 128 | `R32G32B32A32_TYPELESS`, `R32G32B32A32_FLOAT`, `R32G32B32A32_UINT`, `R32G32B32A32_SINT` |
| 96 | `R32G32B32_TYPELESS`, `R32G32B32_FLOAT`, `R32G32B32_UINT`, `R32G32B32_SINT` |
| 64 | `R16G16B16A16_*`, `R32G32_*`, `R32G8X24_TYPELESS`, `D32_FLOAT_S8X24_UINT`, `R32_FLOAT_X8X24_TYPELESS`, `X32_TYPELESS_G8X24_UINT`, `Y416`, `Y210`, `Y216` |
| 32 | `R10G10B10A2_*`, `R11G11B10_FLOAT`, `R8G8B8A8_*`, `R16G16_*`, `R32_*`, `D32_FLOAT`, `R24G8_TYPELESS`, `D24_UNORM_S8_UINT`, `R24_UNORM_X8_TYPELESS`, `X24_TYPELESS_G8_UINT`, `R9G9B9E5_SHAREDEXP`, `R8G8_B8G8_UNORM`, `G8R8_G8B8_UNORM`, `B8G8R8A8_*`, `B8G8R8X8_*`, `R10G10B10_XR_BIAS_A2_UNORM`, `AYUV`, `Y410`, `YUY2` |
| 24 | `P010`, `P016` |
| 16 | `R8G8_*`, `R16_*`, `D16_UNORM`, `B5G6R5_UNORM`, `B5G5R5A1_UNORM`, `A8P8`, `B4G4R4A4_UNORM` |
| 12 | `NV12`, `420_OPAQUE`, `NV11` |
| 8 | `R8_*`, `A8_UNORM`, `BC2_*`, `BC3_*`, `BC5_*`, `BC6H_*`, `BC7_*`, `AI44`, `IA44`, `P8` |
| 4 | `BC1_*`, `BC4_*` |
| 1 | `R1_UNORM` |

Here `*` denotes the standard suffix variants of that exact component layout,
not longer names that merely share its prefix; for example, `R32_*` means the
single-component `R32` variants and not `R32G32`. This table defines metadata
math, not encode eligibility. A format not covered by the table **MUST NOT**
enter canonical partitioning. Width and height **MUST** be nonzero and fit
`u16`; normalized mip count, DXGI numeric value, and tile mode **MUST** fit
`u8`. Encode **MUST** reject rather than truncate an unrepresentable value.

_Source decisions: [accepted Reference Snapshot DDS metadata](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted checked DDS-envelope strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-DDS-005

A recognized legacy 24-bit `R8G8B8` input **MUST** be normalized before format
extraction. Every three-byte BGR pixel **MUST** become four-byte
`B8G8R8X8` with the added high byte `0xFF`; the header bit count **MUST** become
32 and pitch **MUST** become `width * 4`. An incomplete pixel or size overflow
**MUST** fail rather than overread or truncate.

_Source decision: [accepted Reference Snapshot DDS normalization](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DDS-006

Canonical mip count **MUST** treat an encoded zero as one. A non-cubemap's
canonical chunk count **MUST** start at one and add one chunk while another mip
exists, fewer than four chunks exist, and both current dimensions are at least
512, halving both dimensions after each added chunk. A cubemap **MUST** use one
chunk regardless of its dimensions or mip count.

_Source decision: [accepted Reference Snapshot DDS chunking](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DDS-007

Starting after the inferred input header, canonical partitioning **MUST** compute
the first isolated mip byte count as `width * height * bitsPerPixel / 8` with
checked arithmetic. Every nonfinal chunk **MUST** contain one mip; its successor
size is the preceding isolated size divided by four using integer division. The
final chunk **MUST** consume the exact remaining payload and cover every
remaining mip. Each resulting chunk is compressed independently under
[JBSA-DX10-004](dds-ba2.md#jbsa-dx10-004).

If this reference formula disagrees with a validated DDS subresource layout,
including block minimums or odd dimensions, encode **MUST NOT** read outside the
source or invent a boundary. That input remains unsupported until a fixture
qualifies a canonical rule.

_Source decisions: [accepted Reference Snapshot DDS partitioning](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted safe validation](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-DDS-008

Canonical PC reconstruction **MUST** emit the 128-byte legacy form only for:

`BC1_UNORM`, `BC2_UNORM`, `BC3_UNORM`, `BC4_SNORM`, `BC4_UNORM`,
`BC5_SNORM`, `BC5_UNORM`, `R8G8B8A8_UNORM`, `B8G8R8A8_UNORM`,
`B8G8R8X8_UNORM`, `B5G6R5_UNORM`, `B5G5R5A1_UNORM`, `R8G8_UNORM`,
`A8_UNORM`, and `R8_UNORM`.

Every other supported PC format **MUST** use the 148-byte DX10 form. A newly
zeroed canonical header **MUST** set magic `DDS `, header size 124, pixel-format
size 32, width, height, depth one, normalized mip count, base flags `CAPS |
PIXELFORMAT | WIDTH | HEIGHT | MIPMAPCOUNT`, and base cap `TEXTURE`. More than
one mip **MUST** add `MIPMAP | COMPLEX`. A cubemap **MUST** add `COMPLEX` and
set `CUBEMAP` plus all six face bits in `dwCaps2`.

For a DX10 header, resource dimension **MUST** be `TEXTURE2D`, array size one,
and the texture-cube misc flag **MUST** match the cubemap bit.

_Source decisions: [accepted Reference Snapshot canonical reconstruction](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted internal DDS writer](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-DDS-009

Canonical PC pixel-format fields and top-level size flags **MUST** follow this
table. `LINEAR` means `DDSD_LINEARSIZE`; `PITCH` means `DDSD_PITCH`.

| DXGI format | Pixel-format encoding | Size flag and value |
| --- | --- | --- |
| `BC1_UNORM` | FourCC `DXT1` | LINEAR, `width * height / 2` |
| `BC2_UNORM` | FourCC `DXT3` | LINEAR, `width * height` |
| `BC3_UNORM` | FourCC `DXT5` | LINEAR, `width * height` |
| `BC4_SNORM` / `BC4_UNORM` | FourCC `BC4S` / `BC4U` | LINEAR, `width * height / 2` |
| `BC5_SNORM` / `BC5_UNORM` | FourCC `BC5S` / `BC5U` | LINEAR, `width * height` |
| `BC1_UNORM_SRGB` | FourCC `DX10` | LINEAR, `width * height / 2` |
| `BC2_UNORM_SRGB`, `BC3_UNORM_SRGB`, `BC6H_UF16`, `BC6H_SF16`, `BC7_UNORM`, `BC7_UNORM_SRGB` | FourCC `DX10` | LINEAR, `width * height` |
| `R8G8B8A8_UNORM` | RGB+alpha, 32 bits, masks `000000FF/0000FF00/00FF0000/FF000000` | PITCH, `width * 4` |
| `B8G8R8A8_UNORM` | RGB+alpha, 32 bits, masks `00FF0000/0000FF00/000000FF/FF000000` | PITCH, `width * 4` |
| `B8G8R8X8_UNORM` | RGB, 32 bits, masks `00FF0000/0000FF00/000000FF/00000000` | PITCH, `width * 4` |
| `B5G6R5_UNORM` | RGB, 16 bits, masks `F800/07E0/001F/0000` | PITCH, `width * 2` |
| `B5G5R5A1_UNORM` | RGB+alpha, 16 bits, masks `7C00/03E0/001F/8000` | PITCH, `width * 2` |
| `R8G8_UNORM` | luminance+alpha, 16 bits, masks `00FF/0000/0000/FF00` | PITCH, `width * 2` |
| `A8_UNORM` | alpha, 8 bits, alpha mask `FF` | PITCH, `width` |
| `R8_UNORM` | luminance, 8 bits, red mask `FF` | PITCH, `width` |
| `R8G8B8A8_UNORM_SRGB`, `B8G8R8A8_UNORM_SRGB`, `B8G8R8X8_UNORM_SRGB` | FourCC `DX10` | PITCH, `width * 4` |

Every writable format in [JBSA-DDS-012](#jbsa-dds-012) is covered by the table.
All arithmetic **MUST** be checked before narrowing.

_Source decision: [accepted Reference Snapshot canonical DDS fields](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DDS-010

Xbox reconstruction **MUST** use the 164-byte form, set the base FourCC to
`XBOX`, clear legacy bit count and channel masks, populate the DX10 format,
resource dimension, array size, and cubemap fields as in
[JBSA-DDS-008](#jbsa-dds-008), copy the stored BA2 tile mode, set Xbox XDK
version `10705`, and leave otherwise unknown canonical Xbox extension fields
zero.

Explicit target metadata or policy **MUST** be the primary PC/Xbox selection.
Inferring Xbox from `_xbox.` in the containing archive filename **MAY** occur
only through the qualified Compatibility Profile when explicit target metadata
is absent.

_Source decisions: [accepted Reference Snapshot Xbox reconstruction](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted filename-inference deviation](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-DDS-011

DDS BA2 reconstruction **MUST** sum recorded `unpackedSize` values with checked
arithmetic, synthesize the applicable canonical header, decode or read every
chunk to its exact recorded size, and append chunk bytes in serialized archive
order. It **MUST NOT** reorder chunks from mip fields or compare the result to
the pre-pack input header. Decode Conformance compares the canonical result with
the Conformance Oracle and an Independent Validator.

_Source decisions: [accepted Reference Snapshot reconstruction](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted conformance assertions](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-DDS-012

The initial DDS BA2 writable-format allow-list **MUST** be exactly:

- `BC1_UNORM`, `BC1_UNORM_SRGB`, `BC2_UNORM`, `BC2_UNORM_SRGB`,
  `BC3_UNORM`, and `BC3_UNORM_SRGB`;
- `BC4_UNORM`, `BC4_SNORM`, `BC5_UNORM`, and `BC5_SNORM`;
- `BC6H_UF16`, `BC6H_SF16`, `BC7_UNORM`, and `BC7_UNORM_SRGB`;
- `R8G8B8A8_UNORM`, `R8G8B8A8_UNORM_SRGB`, `B8G8R8A8_UNORM`,
  `B8G8R8A8_UNORM_SRGB`, `B8G8R8X8_UNORM`, and
  `B8G8R8X8_UNORM_SRGB`; and
- `B5G6R5_UNORM`, `B5G5R5A1_UNORM`, `R8G8_UNORM`, `A8_UNORM`, and
  `R8_UNORM`.

Other numeric DXGI values in the bits-per-pixel table **MAY** be exposed as
decoded metadata, but encode and canonical reconstruction **MUST** report them
as unsupported until a fixture-backed specification revision adds them to this
allow-list. A bits-per-pixel value alone **MUST NOT** imply DDS support.

_Source decisions: [accepted Reference Snapshot DDS behavior and fixture boundary](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted DDS-envelope strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## Fixture-dependent unknowns

The first-mip formula does not explicitly round BC formats to minimum 4-by-4
blocks and then divides subsequent isolated mips by four. Semantic equivalence
for tiny, nonsquare, non-power-of-two, cubemap, missing-mip, and truncated cases
remains fixture-dependent. Unusual DXGI values remain outside the writable
allow-list, and arrays and volumes remain explicitly unsupported by
[JBSA-DDS-002](#jbsa-dds-002).

Because extraction synthesizes rather than preserves headers, equivalence for
alpha mode, typeless formats, arrays, pitch/linear-size edge cases, cubemap caps,
and Xbox extension fields must be compared to reconstructed Conformance Oracle
bytes, not original source DDS bytes. The 48-texture Reference Snapshot corpus
has no assertions or archive goldens and does not close these gaps.

_Research evidence: [DDS validation, chunking, reconstruction, and unknowns](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md), [Java DDS implementation research](https://github.com/evildarkarchon/jbsa/blob/fa8eed0935464a07d10d119f268b2653f3c70e8e/docs/research/java-codec-hashing-dds.md)._
