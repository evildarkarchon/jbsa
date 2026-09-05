# Conformance v1

This specification owns the `conformance-v1` verification contract: Conformance
Case identity, authority, fixtures, comparisons, evidence, and the claims that
may be made from that evidence. It verifies rather than restates behavior owned
by the [library](library-interface.md), [operation](operation-semantics.md),
[I/O](io-and-publication.md), [codec](codecs.md),
[execution](execution-model.md), [format](README.md#specification-index),
[compatibility-profile](compatibility-profiles.md), and
[CLI](bsarch-cli.md) specifications.

## JBSA-CONF-001

Every Conformance Case **MUST** have an immutable identifier serialized exactly
as
`CV1-<family>.<operation>.<fixture-or-scenario>.<codec>.<configuration>`.
Each placeholder is a lowercase ASCII token matching
`[a-z0-9]+(?:-[a-z0-9]+)*`; the period is the field separator and is forbidden
inside a token. The case manifest **MUST** contain exactly these identity fields:

| Field | Value |
| --- | --- |
| `case_id` | the serialized identifier |
| `contract` | `conformance-v1` |
| `archive_family` | the `<family>` token |
| `operation` | the `<operation>` token |
| `fixture` | the `<fixture-or-scenario>` token |
| `codec` | the `<codec>` token |
| `configuration` | the `<configuration>` token |

The manifest **MUST** map every token to a human-readable description and map
the fixture and configuration tokens to immutable content digests. `global`
**MUST** be used as the family token only for a command or scenario that has no
Archive Family, and `none` **MUST** be used as the codec token only where no
codec applies. Every DDS encode configuration description and digest **MUST**
bind its exact `DdsTarget`. An identity field or its digest mapping **MUST NOT**
be changed in place; a change creates a new Conformance Case identifier.

_Source decisions: [accepted Conformance Case key](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 CV1 identity acceptance](https://github.com/evildarkarchon/jbsa/issues/27), [DDS encode-target clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179549)._

## JBSA-CONF-002

Each matrix cell **MUST** be classified as `REQUIRED` or `N/A`. `N/A` **MUST**
be used only when the combination is structurally inapplicable and therefore has
no Conformance Case. An unsupported direction, codec, option, or value **MUST**
instead be a `REQUIRED` negative Conformance Case asserting explicit rejection.

An executed Conformance Case **MUST** have exactly one result:

- `PASS`: every applicable assertion passed and all required evidence exists;
- `FAIL`: at least one applicable assertion conclusively failed;
- `UNAVAILABLE`: a prescribed local tool or manual environment was not
  available, so the case was not executed; or
- `INVALID`: execution occurred but a prerequisite, identity, fixture,
  instrumentation, or evidence check was invalid or incomplete.

`UNAVAILABLE` and `INVALID` **MUST NOT** count as success. A required set passes
only when every member is `PASS`; percentages, aggregate scores, expected
failures, waivers, and "mostly conformant" results **MUST NOT** be used.

_Source decisions: [accepted hard-gate and applicability rules](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 case-level reporting acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-CONF-003

Conformance evidence **MUST** apply authority by observed surface in this order:

1. the normative specification set governs all product behavior and claims;
2. for valid archive semantics, the shared archive implementation in the
   [pinned Reference Snapshot](scope.md#jbsa-scope-004) is the reference
   authority;
3. for actual BSArch command behavior and emitted command output, the
   digest-pinned Conformance Oracle is the reference authority;
4. Binary Conformance compares only outputs produced by a qualified
   Conformance Oracle under the case's exact configuration;
5. game or official-tool acceptance is established only by manual Release
   Qualification; and
6. an Independent Validator corroborates the applicable authority and never
   silently overrides it.

Evidence from a lower item **MUST NOT** override an applicable higher item.
There is no single linear oracle independent of the surface being judged.

_Source decisions: [accepted surface-specific authority](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [specification authority framework](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-CONF-004

A contradiction among the normative contract, applicable reference authority,
fixture, oracle observation, or Independent Validator **MUST** make every
affected case `INVALID` until an accepted specification correction explains it
or an approved Compatibility Deviation expressly governs it. The case evidence
**MUST** identify the conflicting observations and their authorities. An
unknown, incidental behavior, unpinned dependency result, or neighboring case
**MUST NOT** be promoted into an expected value.

_Source decisions: [accepted contradiction and deviation boundary](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted no-invention rule](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-CONF-005

The sole initial Conformance Oracle **MUST** have this exact identity:

| Property | Required value |
| --- | --- |
| local path | `tests/fixtures/local/oracle/BSArch.exe` |
| product | BSArch v1.0 x64 |
| SHA-256 | `4C34FE4173A2BD04BA52D5A6357348256EE424573785085FDAFAAB524CF7B0C2` |
| pinned source revision | `fd1e36020b2b5b6217e553dc0038983146a2e2dd` |

The harness **MUST** verify the SHA-256 before every use and report a mismatch as
`INVALID`, not execute the mismatched binary. The executable **MUST NOT** be
committed, embedded in a product or evidence bundle, or released. Evidence
**MUST** record that source correspondence is user-attested and cannot be
cryptographically reproduced from the unavailable Delphi build. A replacement
oracle **MUST** receive a new identity and pass the rebaseline controls in
[JBSA-CONF-007](#jbsa-conf-007).

This requirement refines [JBSA-LIC-005](compliance.md#jbsa-lic-005),
[JBSA-LIC-006](compliance.md#jbsa-lic-006), and
[JBSA-SCOPE-004](scope.md#jbsa-scope-004).

_Source decisions: [accepted oracle identity and provenance limitation](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [oracle attestation](../reference/bsarch-oracle.md)._

## JBSA-CONF-006

Every committed conformance input, structural template, manifest, oracle-derived
archive, expected observation, and expected output **MUST** use project-authored
redistributable content, carry provenance and SHA-256 digests, and be addressed
by content digest. A golden object's bytes **MUST** be immutable during normal
test execution, and a case manifest **MUST** bind the golden digest, generator
identity, generator configuration, Conformance Oracle digest when used, and the
source fixture digests. A test failure **MUST NOT** create, replace, or approve
expected output.

Fixture use and provenance also **MUST** satisfy
[JBSA-LIC-005](compliance.md#jbsa-lic-005) and
[JBSA-LIC-006](compliance.md#jbsa-lic-006).

_Source decisions: [accepted golden-data rules](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted fixture provenance policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-CONF-007

Golden creation or replacement **MUST** occur only through a separate,
deliberately selected rebaseline operation. Its review record **MUST** contain
the old and new SHA-256 digests, source fixture digests, Conformance Oracle
digest, generator and full configuration, affected Conformance Case identifiers,
rationale, observed semantic difference, and explicit maintainer approval.
Rebaseline output **MUST** remain untrusted until review and **MUST NOT** be
written by an ordinary test or accepted solely because it matches current JBSA
output. An oracle, generator, fixture, configuration, or governing-requirement
change **MUST** invalidate and regenerate all affected golden evidence under
this control.

_Source decisions: [accepted deliberate rebaseline control](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted affected-gate reset policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-CONF-008

The `conformance-v1` base direction-and-codec matrix **MUST** contain every row
below. `decode and encode` creates a mandatory base fixture for each direction;
`decode only` creates a mandatory decode fixture and a mandatory negative encode
case.

| Family token | Archive Family and selectors | Direction | Codec token and content |
| --- | --- | --- | --- |
| `tes3` | TES3 / Morrowind BSA | decode and encode | `stored` |
| `bsa-067` | TES4 / Oblivion BSA `0x67` | decode and encode | `stored`, `zlib`, `mixed` |
| `bsa-068` | FO3/FNV/Skyrim LE BSA `0x68` | decode and encode | `stored`, `zlib`, `mixed` |
| `bsa-069` | SSE/Skyrim AE BSA `0x69` | decode and encode | `stored`, `lz4-frame`, `mixed` |
| `fo4-gnrl-v1` | FO4 General BA2 version 1 | decode and encode | `stored`, `zlib` |
| `fo4-dx10-v1` | FO4 DDS BA2 version 1 | decode and encode | `zlib` |
| `fo4-gnrl-v7` | FO4 General BA2 version 7 | decode only | `stored`, `zlib` |
| `fo4-gnrl-v8` | FO4 General BA2 version 8 | decode only | `stored`, `zlib` |
| `fo4-dx10-v7` | FO4 DDS BA2 version 7 | decode only | `zlib` |
| `fo4-dx10-v8` | FO4 DDS BA2 version 8 | decode only | `zlib` |
| `sf-gnrl-v2` | Starfield General BA2 version 2 | decode and encode | `stored`, `zlib` |
| `sf-gnrl-v3-m3` | Starfield General BA2 version 3, method 3 | decode and encode | `raw-lz4`, `mixed` |
| `sf-dx10-v2` | Starfield DDS BA2 version 2 | decode and encode | `zlib` |
| `sf-dx10-v3-m3` | Starfield DDS BA2 version 3, method 3 | decode and encode | `raw-lz4` |

For `sf-gnrl-v3-m3`, `mixed` **MUST** select raw LZ4 and each direction's
fixture **MUST** contain at least one stored entry with `packedSize == 0` and at
least one raw-LZ4-compressed entry with `packedSize > 0` in the same
version-3/method-3 archive.

Version-7 and version-8 fixtures **MUST** record their observed codec, and the
mandatory fixture set **MUST** cover every codec token listed above. All
cross-family codecs, stored DDS encode, TES3 compression, other Starfield
version-3 methods, and other unsupported combinations **MUST** have explicit
negative cases. The matrix verifies, and **MUST NOT** broaden, the direction and
codec permissions owned by
[JBSA-TES3-001](formats/tes3-bsa.md#jbsa-tes3-001),
[JBSA-BSA-001](formats/versioned-bsa.md#jbsa-bsa-001),
[JBSA-GNRL-002](formats/general-ba2.md#jbsa-gnrl-002), and
[JBSA-DX10-001](formats/dds-ba2.md#jbsa-dx10-001).

_Source decisions: [accepted mandatory format and codec matrix](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 matrix acceptance](https://github.com/evildarkarchon/jbsa/issues/27), [accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048)._

## JBSA-CONF-009

In addition to every base case in [JBSA-CONF-008](#jbsa-conf-008), the case
manifest **MUST** assign targeted cases that collectively cover:

- empty, single-entry, multi-entry, nested, and zero-length inputs;
- path case, forward-slash and backslash forms, ASCII, Windows-1252, and
  Windows-932 wire names, unmappable encode names, undecodable present wire-name
  components with their exact diagnostic, ordinal synthetic display name,
  retained bytes, and absent identity, deterministic ASCII lowercasing,
  non-ASCII lowercase rejection and qualified-profile behavior, and Normalized
  Name Identity rejection for empty or repeated segments, `.` or `..` segments,
  trailing space or dot, colon, and non-ASCII case pairs;
- every versioned-BSA directory/file-name presence-flag combination and its
  absent-component and synthetic-display-name metadata, plus explicit embedded-
  name framing for `0x68` and `0x69`, canonical prefix derivation, and bounded
  prefix/index mismatch; `.dds` empty-stem encode rejection and decode
  retention; and every automatic flag-classifier row, root-over-extension and
  first-extension-match precedence, no-match handling, family mask, special
  `0x67` XML rule, and archive-flag consequence; plus two distinct colliding
  folders with multiple entries whose basename hashes interleave and distinct
  basenames with equal hashes within one folder, asserting canonical folder-byte
  and basename-byte tie-breaks, separate contiguous folder blocks, and stable
  order across reversed source and worker configurations;
- compression size boundaries, mixed compression, complete codec consumption,
  and decoded-size mismatch;
- distinct TES3 names with the same full hash, asserting the canonical-name
  byte tie-breaker in [JBSA-TES3-003](formats/tes3-bsa.md#jbsa-tes3-003) for
  records and payload sequencing across reversed source and worker orders;
- DDS writable formats, materially distinct dimensions and mip counts, both the
  8-byte and 16-byte BC block classes at `1x1` and `5x7`, an odd-dimension
  multichunk mip chain, nonsquare mips with one dimension reaching one first,
  cubemaps, exact partition sizes and missing/extra-payload rejection under
  [JBSA-DDS-013](formats/dds-payload.md#jbsa-dds-013), canonical
  reconstruction, chunk boundaries, both `PC` and `XBOX` encode targets with
  matching inputs, both mismatch rejections, CLI DDS-selector-to-`PC` mapping,
  and explicit, default, and qualified-profile reconstruction selection;
- zero and out-of-range filename tables for General and DDS BA2, including the
  exact ordinal-disambiguated synthetic display names and absent original
  wire-name bytes and Normalized Name Identities;
- directory-root-relative, individual-file-basename, existing-archive, explicit-
  name, and generated-entry `PackSource` mappings; directory expansion that is
  identical across opposite filesystem creation orders and repeated
  materializations; direct-library and CLI omission of descendant file and
  directory indirections without target access, linked-root `SOURCE` rejection,
  unprovable-classification `CAPABILITY` rejection, and regular-file-to-link
  mutation rejection; later-source-wins overlay order; filtering; sharing on
  and off; and splitting on and off, including a BSA crossing the 2 GiB split
  target and a transformed-size- or sharing-dependent later-part collision under
  `FAIL` before destination staging,
  extension-bearing, extensionless, leading-dot, and trailing-dot split names,
  with no destination effects and complete scratch cleanup;
- qualified-Windows extraction rejection for invalid filename characters and
  reserved device basenames with case, extension, and U+00B9/U+00B2/U+00B3
  variants, plus accepted neighbors such as `COM0`, `COM10`, and `CONSOLE`, while
  retaining any otherwise present Normalized Name Identity;
- every `ResourceLimits.standard()` ceiling exactly at and one unit above its
  boundary, CLI use of the standard value, and direct-library overrides;
- sequential repetition, worker-limit variation, and parallel semantic
  equivalence;
- Primary Failure ordering for every present/absent combination of logical
  ordinal, diagnostic identifier, and structured location;
- exact progress phase-and-metric sets for `pack`, atomic-set or new-root
  `extract`, and existing-tree `extract`, including zero-unit success,
  pre-cancellation, interruption in each phase, cleanup after non-success, late
  cancellation, and observer failure with no later callback, while treating
  callback thread identity and cadence as non-semantic;
- every rejection and tolerated-noncanonical class owned by the format and
  operation specifications;
- every registered Compatibility Deviation in both safe-default and activated
  profile modes; and
- every CLI operation, valid switch, invalid switch, exit result, stream rule,
  and filesystem result owned by the CLI specification, including `-f`
  whole-basename inclusion, union, ASCII-fold, wildcard, empty-mask, no-mask,
  and no-match behavior and leading, trailing, and repeated `+` rejection before
  path conversion or source discovery.

The manifest **MUST** map each coverage item to at least one Conformance Case and
each case to its applicable assertion identifiers. A case **MAY** cover multiple
items; a full Cartesian product is not required.

_Source decisions: [accepted targeted interaction coverage](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 complete-matrix acceptance](https://github.com/evildarkarchon/jbsa/issues/27), [accepted review-driven coverage clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [normalized-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [split-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179533), [synthetic-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179540), [automatic-flag clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179544), [DDS target clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179549), [resource-limit clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179557), [undecodable-wire-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812807), [Windows-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812819), [filesystem-source-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812829), [directory-order review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812837), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947), [accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048), [accepted `0.12.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5550691183)._

## JBSA-CONF-010

A Decode Conformance assertion **MUST** compare the applicable semantic
projection from this list: Archive Family and wire version; BA2 subtype and
compression method; entry count and serialized order; decoded names; original
wire-name byte presence and values; Normalized Name Identity presence and
values; wire hashes; logical, stored, and decoded sizes; compression
state; flags; Conformance Diagnostics; and exact uncompressed payload bytes.
Versioned-BSA projections **MUST** additionally include archive and file flags,
family-specific folder and file hashes, folder/file ordering, and embedded-name
behavior. General-BA2 projections **MUST** additionally include entry hashes and
extensions, chunk count, sizes, and payload bytes. DDS-BA2 projections **MUST**
additionally include dimensions, DXGI format, mip, cubemap, tile, and logical
chunk-range metadata and the canonical reconstructed DDS bytes owned by
[JBSA-DDS-008](formats/dds-payload.md#jbsa-dds-008) through
[JBSA-DDS-012](formats/dds-payload.md#jbsa-dds-012).

Physical offsets, padding, ignored constants, compressed bytes, and incidental
layout **MUST NOT** participate in semantic equality unless a separately
qualified Binary Conformance assertion expressly includes them.

_Source decisions: [accepted semantic Decode Conformance projection](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [synthetic-name clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179540)._

## JBSA-CONF-011

Every positive Encode Conformance Case **MUST** execute both differential
directions: JBSA decodes the Conformance Oracle's output to the expected semantic
projection, and the Conformance Oracle plus every applicable Independent
Validator decodes JBSA output to that same projection. The case **MUST** compare
exact source payload bytes after both directions and apply every format-specific
metadata assertion from [JBSA-CONF-010](#jbsa-conf-010). A JBSA self-round-trip,
successful creation, or comparison only with another JBSA component **MUST NOT**
establish Encode Conformance.

Every DDS encode case **MUST** bind and use the same `DdsTarget` in both
differential directions. Any containing archive filename needed for qualified
`_xbox.` reconstruction **MUST** also be fixed and digest-bound by the case.

_Source decisions: [accepted cross-direction Encode Conformance rule](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [DDS encode-target clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179549)._

## JBSA-CONF-012

Malformed-input coverage **MUST** assert one of the three dispositions owned by
the format and operation specifications: conforming, diagnosed Tolerated
Noncanonical Archive, or rejected. The case matrix **MUST** exercise illegal
family/version/subtype/method tuples, impossible counts, checked-arithmetic
overflow, out-of-range and truncated spans, decompression or decoded-size
mismatch, partial overlap, equal Normalized Name Identities, exact shared spans,
unsafe absolute and traversal names, qualified-Windows-invalid characters and
reserved device basenames, undecodable present wire-name components, safely
ignorable constants, missing or out-of-range name tables, bounded TES3 name-
offset inconsistency, usable-name hash mismatch, and harmless trailing bytes
wherever the owning format makes the class applicable.

Expected disposition and diagnostics **MUST** be taken from
[JBSA-OPS-002](operation-semantics.md#jbsa-ops-002),
[JBSA-OPS-003](operation-semantics.md#jbsa-ops-003),
[JBSA-DET-006](formats/detection.md#jbsa-det-006),
[JBSA-TES3-004](formats/tes3-bsa.md#jbsa-tes3-004),
[JBSA-BSA-014](formats/versioned-bsa.md#jbsa-bsa-014),
[JBSA-GNRL-010](formats/general-ba2.md#jbsa-gnrl-010), and the corresponding
[DDS BA2](formats/dds-ba2.md) and [DDS payload](formats/dds-payload.md)
requirements. Extraction cases for every unsafe-name diagnostic identifier
assigned by [JBSA-OPS-003](operation-semantics.md#jbsa-ops-003) **MUST** assert
the no-write preflight and containment behavior in
[JBSA-IO-009](io-and-publication.md#jbsa-io-009), a checked `POLICY` outcome
rather than an unchecked host-path exception, and no destination effects. This
requirement **MUST NOT** make tolerated input valid encoder output.

Every expected diagnostic in these cases **MUST** use the exact identifier
assigned by its owning behavior requirement.

_Source decisions: [accepted malformed-input outcomes and classes](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted layered validation](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [diagnostic-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179522), [undecodable-wire-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812807), [Windows-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812819), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947), [accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048)._

## JBSA-CONF-013

Library diagnostic comparison **MUST** compare stable identifier, severity,
operation, affected entry or field, and relevant structured values, and
**MUST NOT** compare human wording or implementation exception-class names. CLI
compatibility **MUST** compare a structured CLI Observation containing exact
exit status, stream placement where designated stable, deterministically ordered
semantic records, published and residual artifact states, and the extracted
filesystem tree and file bytes. Raw stdout and stderr **MUST** be retained for
diagnosis, while elapsed time, progress repaint cadence, line endings, incidental
whitespace, and other nondeterministic presentation **MUST** be normalized or
excluded unless the [CLI specification](bsarch-cli.md) designates a stable
phrase or presentation rule.

Diagnostic comparison refines
[JBSA-OPS-005](operation-semantics.md#jbsa-ops-005) and
[JBSA-OPS-006](operation-semantics.md#jbsa-ops-006); artifact comparison uses
[JBSA-OPS-011](operation-semantics.md#jbsa-ops-011).

_Source decision: [accepted diagnostic and CLI Observation comparison](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-CONF-014

At least one Independent Validator that is neither xEdit nor derived from the
Reference Snapshot **MUST** corroborate the TES3, versioned-BSA, General-BA2,
and DDS-BA2 family groups. DirectXTex **MUST** validate canonical reconstructed
DDS results. A validator record **MUST** pin tool identity and digest, adapter
version, invocation, accepted input and output digests, and normalized result.
A disagreement **MUST** invalidate the affected case and block the relevant
release claim pending investigation; it **MUST NOT** silently replace the
applicable authority or be resolved by majority vote. Game and Creation Kit
acceptance **MUST** remain manual Release Qualification rather than an
Independent Validator result.

_Source decision: [accepted independent-validation and release boundary](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-CONF-015

Binary Conformance **MUST** be awarded only to a named CV1 fixture and exact
configuration, never to an Archive Family, codec, provider, or release in
general. Each candidate **MUST** use ordered inputs, `-split:0`, `-share:no`,
`-mt:no`, the pinned provider and codec profile, and otherwise identical
configuration; produce byte-identical output in five fresh Conformance Oracle
runs; produce JBSA output byte-identical to that qualified oracle output; pass
both cross-decode directions; and repeat the required identity confirmation on
a second Windows x64 CPU with all machine and software identities recorded.

The initial candidate set **MUST** be TES3 stored output, stored versioned-BSA
output for each writable version, and stored General-BA2 output for each
writable family. A compressed or DDS case **MAY** be added only after its exact
codec/provider configuration independently proves repeatability and byte
identity. Failure to earn or retain Binary Conformance **MUST NOT** be hidden by
changing the case, provider, configuration, or claim scope in place.

_Source decisions: [accepted case-scoped Binary Conformance protocol](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 binary qualification acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-CONF-016

Determinism evidence **MUST** run every designated sequential case repeatedly
and compare semantic projections, diagnostics, artifact ordering, and exact
bytes where the case asserts deterministic output. Parallel cases **MUST** run
all applicable worker configurations and prove semantic equivalence to the
sequential case, deterministic public ordering, bounded resource behavior, and
the cancellation/cleanup outcomes owned by
[JBSA-SCHED-008](execution-model.md#jbsa-sched-008),
[JBSA-SCHED-010](execution-model.md#jbsa-sched-010), and
[JBSA-OPS-009](operation-semantics.md#jbsa-ops-009). Scheduling-dependent
physical order or compressed bytes **MUST NOT** be treated as a Compatibility
Deviation or Binary Conformance evidence unless that exact parallel case has
independently passed [JBSA-CONF-015](#jbsa-conf-015).

_Source decisions: [accepted sequential and parallel conformance coverage](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted deterministic execution contract](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-CONF-017

The machine-readable result for each executed Conformance Case **MUST** contain
`case_id`, `contract`, the case-manifest digest, candidate artifact and codec
profile digests, active Compatibility Profile identity or `none`, fixture and
golden digests, oracle identity and observations when applicable, validator
identities and observations when applicable, environment identity, start time,
duration as non-gating context, result, and an ordered `assertions` array. Each
assertion **MUST** contain a stable assertion identifier, applicability, result,
expected and observed structured values or their artifact digests, and evidence
references. Raw streams, filesystem manifests, and compared artifacts **MUST**
be retained by digest. The harness **MUST** emit a deterministic matrix mapping
every required case to exactly one result and **MUST** reject missing, duplicate,
or unknown case identifiers.

_Source decisions: [accepted case-level deterministic evidence](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [issue 27 hosted-CI case reporting acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-CONF-018

The Automated Conformance claim **MUST** mean that every mandatory
`conformance-v1` case runnable on a hosted Windows GitHub Actions runner is
present and `PASS` for the exact candidate, specification-set version, case
manifest, fixtures, golden set, codec profile, and Compatibility Profile mode
named by the claim. Hosted CI **MUST** use committed redistributable goldens and
Independent Validators, **MUST NOT** contain or fetch the Conformance Oracle or
proprietary game assets, and **MUST NOT** run games, official tools, performance
qualification, or manual Binary Conformance. Oracle-refresh cases may be
`UNAVAILABLE` locally when the digest-pinned executable is absent, but their
hosted golden consumers **MUST** still pass. A missing, failed, unavailable, or
invalid hosted case **MUST** prevent the claim.

This requirement refines [JBSA-SCOPE-005](scope.md#jbsa-scope-005) and
[JBSA-SCOPE-007](scope.md#jbsa-scope-007).

_Source decisions: [accepted hosted Automated Conformance boundary](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted implementation gate sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-CONF-019

JBSA **MUST** keep Automated Conformance, Binary Conformance, and Release
Qualification as separate claims with separate case evidence. A complete Encode
Conformance claim **MUST** additionally have current manual Windows game or
official-tool Release Qualification for every writable Archive Family, recording
the exact candidate, tool or game version, fixture and output digests,
configuration, observed result, environment, and operator approval. Missing,
stale, ambiguous, or failed manual evidence **MUST** block the complete writable
family and release claims without changing hosted Automated Conformance results.
Official games and tools **MUST NOT** run in GitHub-hosted Actions or on project
self-hosted runners.

This requirement refines [JBSA-SCOPE-006](scope.md#jbsa-scope-006) and
[JBSA-SCOPE-007](scope.md#jbsa-scope-007).

_Source decisions: [accepted three-claim and manual qualification model](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted release gate sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._
