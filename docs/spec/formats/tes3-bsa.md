# TES3 / Morrowind BSA

This specification owns the unversioned TES3 Archive Family. It uses the shared
wire and Archive Name Encoding conventions from
[JBSA-DET-006](detection.md#jbsa-det-006).

## JBSA-TES3-001

TES3 / Morrowind BSA **MUST** decode and encode the unversioned
`00 01 00 00` Archive Family and **MUST** store every payload without
compression. The format exposes no codec selector or compressed-entry state; a
compression hint that reaches this format encoder **MUST NOT** change the
emitted payload or layout. Public request and CLI validation may reject such a
hint before it reaches the encoder.

_Source decisions: [accepted Reference Snapshot behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted conformance matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-TES3-002

A conforming TES3 archive **MUST** have these consecutive sections:

| Section | Wire form |
| --- | --- |
| Header | magic `00 01 00 00`, `hashOffset: u32`, `fileCount: u32` |
| File records | `fileCount` records of `size: u32`, `relativeDataOffset: u32` |
| Name offsets | `fileCount` values of `nameOffset: u32` |
| Name block | `fileCount` NUL-terminated Archive Name Encoding strings |
| Hash table | `fileCount` values of `nameHash: u64` |
| Data | stored payload byte spans |

`hashOffset` **MUST** locate the hash table relative to byte 12. The absolute
payload base **MUST** be the byte immediately after the hash table, and each
`relativeDataOffset` **MUST** be relative to that base. Canonical output **MUST**
write cumulative name offsets relative to the start of the name block.

_Source decision: [accepted Reference Snapshot behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-TES3-003

For canonical encode, the encoder **MUST** encode the complete entry name
eligible under [JBSA-LIB-012](../library-interface.md#jbsa-lib-012) using Archive
Name Encoding. If the encoded name contains a byte greater than
`0x7f`, canonical encode **MUST** reject the entry. A Compatibility Profile
**MUST NOT** admit such a name unless it defines the byte-level lowercase mapping
and cites qualifying fixtures for the resulting stored name and TES3 hash bytes.

For every admitted entry, the encoder **MUST** replace each byte `0x2f` with
`0x5c`, then map every byte from `0x41` through `0x5a` to that byte plus `0x20`.
Canonical encode **MUST** leave every other byte unchanged; a qualified
Compatibility Profile **MUST** additionally apply its defined non-ASCII mapping.
The resulting byte string is the canonical TES3 name and **MUST** be stored and
used as the `nameHash` input.

For hashing, split the canonical byte string after `floor(length / 2)` bytes.
The low 32-bit accumulator starts at zero and XORs
each first-half byte shifted left by successive `0, 8, 16, 24` bit positions,
wrapping the shift modulo 32. The high 32-bit accumulator applies the same XOR
to each second-half byte, then rotates the accumulator right by the low five
bits of that shifted byte after each XOR. The resulting `u64` is the high
accumulator followed by the low accumulator.

Canonical metadata records **MUST** be ordered by the unsigned low 32 bits of
`nameHash`, then by the unsigned high 32 bits, and then, when the full `nameHash`
is equal, by canonical TES3 name bytes in unsigned-octet lexicographic order:
the lower octet at the first difference sorts first, and an exact prefix sorts
first. Name records, name offsets, hash records, file records, and canonical
payload sequencing **MUST** use that same entry order.

_Source decisions: [accepted Reference Snapshot hashing and ordering](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted deterministic lowercase clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [accepted `0.12.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5550691183)._

## JBSA-TES3-004

Decode **MUST** validate the TES3 counts, `hashOffset`, derived name and hash
sections, data base, and every relative payload span with checked arithmetic.
It **MUST** reject overflow, truncation, out-of-file or partially overlapping
payload spans, and duplicate names when each compared wire name has an equal
Normalized Name Identity under
[JBSA-LIB-012](../library-interface.md#jbsa-lib-012). Exact shared payload spans
remain valid. Absolute or traversal names remain inspectable but **MUST** make
extraction ineligible before destination effects.

A bounded name-offset inconsistency or harmless trailing data **MUST** produce a
stable warning and a Tolerated Noncanonical Archive when the sequential name
block boundaries and every payload remain unambiguous and bounded. A stored-hash
mismatch computed from a name eligible for canonical byte mapping under
[JBSA-TES3-003](#jbsa-tes3-003) **MUST** have the same disposition. Absent a
qualified Compatibility Profile, decode **MUST NOT** make that canonicality
judgment for a name containing a byte greater than `0x7f`; its original wire
bytes and stored hash remain authoritative. The encoder **MUST NOT** emit those
tolerated conditions. Absolute or traversal names remain inspectable
but do not become eligible extraction paths.
Inspection **MUST** also retain stable warnings for asset roots unsupported by
Morrowind and for payload offsets beginning beyond signed 2 GiB.
Name-hash matches **MUST** be confirmed with Normalized Name Identities, and
payload-hash matches **MUST** be confirmed with exact bytes, before overlay or
sharing identity is accepted.

The TES3-specific Conformance Diagnostics in this requirement **MUST** use
exactly these identifiers and `WARNING` severity:

| Condition | Diagnostic identifier | Scope and structured location |
| --- | --- | --- |
| Bounded name-offset inconsistency | `tes3.name-offset-inconsistency` | Once per affected entry, at its `nameOffset` field |
| Harmless trailing data | `tes3.trailing-data` | Once per archive, at the complete trailing byte span |
| Usable-name stored-hash mismatch | `tes3.stored-hash-mismatch` | Once per affected entry, at its `nameHash` field |
| Asset root unsupported by Morrowind | `tes3.unsupported-asset-root` | Once per affected entry, at its name |
| Payload start beyond signed 2 GiB | `tes3.payload-offset-over-signed-2gib` | Once per affected entry, at its payload start |

Stored and expected values relevant to each condition **MUST** remain
canonically represented structured values under
[JBSA-OPS-005](../operation-semantics.md#jbsa-ops-005).

_Source decisions: [accepted tolerated-noncanonical and rejection policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted layered validation semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted name-validation clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [diagnostic-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179522), [undecodable-wire-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812807), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947)._

## JBSA-TES3-005

TES3 encode **MUST** reject an empty entry set, absent or duplicate Normalized
Name Identities under [JBSA-LIB-012](../library-interface.md#jbsa-lib-012),
unmappable names, values that do not fit their `u32` fields, and any plan whose
sections or payload spans overflow the destination bounds. Stored hashes and
name offsets **MUST** be recomputed from canonical names; source hash or offset
bytes **MUST NOT** be copied into new output.

_Source decisions: [accepted Reference Snapshot creation behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted safe conformance contract](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517)._

## JBSA-TES3-006

The default TES3 split target **MUST** be `2,147,483,647` bytes; zero **MUST**
disable splitting. Sequential splitting **MUST** follow Logical Plan Order and
use the Reference Snapshot's advisory estimate of packed payload bytes plus 200
bytes and encoded-name length per entry. A part **MAY** exceed the target, and a
single oversized entry **MUST** occupy its own part. Part one keeps the requested
name; part paths **MUST** use the numbered split-sibling mapping in
[JBSA-IO-008](../io-and-publication.md#jbsa-io-008). Parallel or sharing-dependent
grouping **MUST NOT** receive a Binary Conformance claim until fixture evidence
fixes the exact boundary behavior.

_Source decisions: [accepted Reference Snapshot split behavior](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted case-scoped Binary Conformance](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [extensionless split-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179533)._

## Evidence boundaries

The active Windows ANSI code page used by the Reference Snapshot is not encoded
in a TES3 archive. Canonical non-ASCII lowercasing and hash comparison therefore
remain fixture-dependent outside an explicit, qualified Compatibility Profile.

The Reference Snapshot does not systematically exercise corrupt counts,
offsets, overlaps, duplicate names, or truncated strings. The safe rejection and
tolerated-noncanonical boundary above follows the accepted conformance decision;
more permissive malformed-input behavior must not be inferred from incidental
stream failures.

Exact split grouping under concurrency and hash-based sharing remains
fixture-dependent. The Reference Snapshot's hash-only comparison is an apparent
defect, not a compatibility requirement.

_Research evidence: [TES3 layout, hashing, ordering, splitting, and unknowns](https://github.com/evildarkarchon/jbsa/blob/316c1c1cce735afbd291bebd00c82f29a89a4be7/docs/research/reference-snapshot-archive-behavior.md), [`wbHash.pas` TES3 hash](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L169-L195)._
