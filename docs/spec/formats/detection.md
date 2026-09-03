# Detection and shared wire conventions

This specification owns bounded recognition of Bethesda Archives and the wire
conventions shared by the format specifications. Detection identifies encoded
selectors; it does not decide whether a requested operation is supported or
whether the rest of an archive is conforming.

## JBSA-DET-001

Detection **MUST** produce exactly one Detection Status:

- `UNRECOGNIZED` when the available prefix conclusively is not a Bethesda
  Archive;
- `INDETERMINATE` when the available bytes begin a recognized selector but end
  before all selectors needed to identify an Archive Family are present;
- `SUPPORTED_FAMILY` when the complete selector tuple identifies a supported
  Archive Family; or
- `UNSUPPORTED_VARIANT` when recognizable Bethesda selectors identify an
  unsupported or illegal magic, wire-version, BA2-subtype, or compression-method
  combination.

Detection **MUST** retain every observed selector and **MUST NOT** assign an
Archive Disposition or imply validation beyond recognition.

_Source decision: [accepted bounded-detection and validation semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-DET-002

Detection **MUST** recognize these byte selectors at offset zero:

| Selector | Additional selectors | Minimum identifying prefix |
| --- | --- | ---: |
| `00 01 00 00` | none | 4 bytes |
| `42 53 41 00` (`BSA\0`) | `u32` wire version at offset 4 | 8 bytes |
| `42 54 44 58` (`BTDX`) | `u32` wire version at offset 4 and four-byte subtype at offset 8 | 12 bytes |

An empty input or a prefix that differs from all three selectors is
`UNRECOGNIZED`. A proper leading fragment of a selector, or a complete `BSA\0`
or `BTDX` selector without its required following selectors, is
`INDETERMINATE`.

_Source decisions: [accepted Reference Snapshot behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted bounded-detection semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-DET-003

The supported selector-to-family mappings **MUST** be exactly:

| Archive Family | Magic | Wire version | BA2 subtype |
| --- | --- | --- | --- |
| TES3 / Morrowind BSA | `00 01 00 00` | none | none |
| TES4 / Oblivion BSA | `BSA\0` | `0x67` | none |
| FO3/FNV/Skyrim LE BSA | `BSA\0` | `0x68` | none |
| SSE/Skyrim AE BSA | `BSA\0` | `0x69` | none |
| FO4 General BA2 | `BTDX` | `1`, `7`, or `8` | `GNRL` |
| FO4 DDS BA2 | `BTDX` | `1`, `7`, or `8` | `DX10` |
| Starfield General BA2 | `BTDX` | `2` or `3` | `GNRL` |
| Starfield DDS BA2 | `BTDX` | `2` or `3` | `DX10` |

Any other magic/version/subtype tuple **MUST** be `UNSUPPORTED_VARIANT`.
Supported directions and codecs are separately owned by
[JBSA-TES3-001](tes3-bsa.md#jbsa-tes3-001),
[JBSA-BSA-001](versioned-bsa.md#jbsa-bsa-001),
[JBSA-GNRL-002](general-ba2.md#jbsa-gnrl-002), and
[JBSA-DX10-001](dds-ba2.md#jbsa-dx10-001). A present Starfield version-3 method
other than `3` is an unsupported variant under those family requirements.

_Source decision: [accepted Reference Snapshot selector matrix](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-DET-004

Archive Family, wire version, BA2 subtype, and BA2 compression method **MUST**
remain separate values. Detection **MUST NOT** collapse FO4 versions `1`, `7`,
and `8`, collapse Starfield versions `2` and `3`, treat `GNRL` and `DX10` as one
payload kind, or infer encode support from successful recognition.

_Source decisions: [accepted Reference Snapshot family model](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted format-direction matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-DET-005

Binary detection **MUST NOT** use a filename or extension. A path-oriented
candidate check **MAY** recognize `.bsa` and `.ba2` case-insensitively, but that
check is not Detection Status and **MUST NOT** override the encoded selectors.
Magic/version pairs and BA2 magic/version/subtype pairs not present in
[JBSA-DET-003](#jbsa-det-003) **MUST** be `UNSUPPORTED_VARIANT`, even where the
Reference Snapshot happens to dispatch a wire version independently of its
magic.

_Source decisions: [accepted Reference Snapshot behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted safe conformance contract](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-DET-006

Unless a format requirement states otherwise, `u8`, `u16`, `u32`, `u64`, and
`i64` denote fixed-width little-endian wire integers; four-byte magic, subtype,
FourCC, and extension fields denote bytes in displayed order. Archive names are
wire byte strings, not host paths. Decode **MUST** retain original name bytes;
the deterministic Archive Name Encoding is Windows-1252, and encode **MUST**
reject unmappable names rather than replace bytes silently. A Compatibility
Profile may select the active Windows ANSI code page without changing console
encoding or path-containment rules.

_Source decisions: [accepted Reference Snapshot wire behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted Archive Name Encoding](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## Evidence boundaries

The Reference Snapshot maps a recognized version independently after reading the
magic. Cross-family magic/version pairs can consequently enter an unrelated
parser. [JBSA-DET-005](#jbsa-det-005) normalizes that behavior as an unsupported
variant rather than preserving an unsafe dispatch accident.

Malformed-input acceptance beyond the bounded prefix remains fixture-dependent.
Complete count, name, span, overlap, and payload validation belongs to the
identified Archive Family and the operation's declared Validation Extent.

_Research evidence: [normalized recognition limits](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md#recognition-limits), [`wbBSArchive.pas` selector dispatch](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1313-L1343)._
