# Compatibility profiles

This specification owns selection, identity, immutability, and deviation
membership for compatibility behavior. The safe behavior being deviated from
remains owned by the applicable CLI, library, operation, I/O, execution, and
Archive Family requirements. A profile changes only the behavior explicitly
listed here.

## JBSA-COMPAT-001

JBSA **MUST** use the safe normative behavior of this specification set when no
Compatibility Profile is selected. It **MUST NOT** infer a profile from the
executable name, environment, configuration files, input archive, output name,
locale, active code page, or previously executed operation.

A Compatibility Profile **MUST** change only its registered deviations. Every
unlisted behavior **MUST** continue to follow its owning requirement, and an
unknown profile identifier **MUST** be rejected before archive or destination
effects.

_Source decisions: [accepted safe normative default](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted explicit CLI profile selection](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-COMPAT-002

Every published Compatibility Profile **MUST** have an immutable identifier,
revision, ordered deviation set, and SHA-256 content digest. Changing a
deviation's membership, meaning, safety disposition, or ordering **MUST** create
a new profile revision; a published revision **MUST NOT** be mutated.

The initial profile identifier **MUST** be `bsarch-1.0/v1`. Its canonical
profile payload is the UTF-8, LF-normalized byte sequence strictly between the
`profile-payload-start` and `profile-payload-end` marker lines below, excluding
both marker lines and their terminating line feeds. The LF terminating the final
payload line immediately before `profile-payload-end` is part of the payload.
Its digest **MUST** be
`9577D821C40982E7F988D311D5BA7CF55B0F098AF2C3F5FD5C5A531360DDE1C4`,
represented as 64 uppercase hexadecimal digits.
Release metadata and the CLI version record **MUST** expose both the profile
identifier and this digest.

_Source decisions: [accepted immutable digest-identified profiles](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted immutable initial bundle](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [payload-digest clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179486)._

## JBSA-COMPAT-003

A direct library caller **MUST** select a Compatibility Profile through
`OpenOptions` supplied to `inspect` or `open`, or through the applicable
immutable `ExtractRequest` or `PackRequest`. The CLI **MUST** select
`bsarch-1.0/v1` only through the exact global option
`--compatibility-profile=bsarch-1.0/v1` before the command or archive operand,
as refined by [JBSA-CLI-003](bsarch-cli.md#jbsa-cli-003).

Selection **MUST** activate the complete bundle as one unit. JBSA **MUST NOT**
provide an environment variable, configuration file, executable alias, or
per-deviation switch that activates the profile or any subset of it.

_Source decisions: [accepted profile and deviation policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted all-or-nothing CLI activation](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [profile-aware inspection clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179510)._

## JBSA-COMPAT-004

Every Compatibility Deviation **MUST** retain a stable profile-owned identifier
and title, affected Archive Family, operation, and surface, reproducible fixture
and pinned-oracle evidence, normative and deviating behavior, compatibility
justification, safety analysis, tests for both behaviors, and explicit
maintainer approval. It **MUST** be revalidated after a change to the Conformance
Oracle, relevant codec, profile implementation, or affected behavior.

A deviation **MUST NOT** be removed from a published profile. Retirement from a
later profile requires an intentional compatibility revision or evidence that
no supported consumer needs it. Missing evidence or a contradiction **MUST**
block the affected Conformance Case and compatibility claim rather than cause
the implementation to infer behavior.

_Source decision: [accepted Compatibility Deviation evidence and lifecycle](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

<!-- profile-payload-start -->
## JBSA-COMPAT-005

The `bsarch-1.0/v1` profile **MUST** contain exactly these CLI deviations. The
safe behavior column links to its owner and is informative; the profile behavior
column is the deviation.

| Deviation | Surface | Safe behavior owner | `bsarch-1.0/v1` behavior |
| --- | --- | --- | --- |
| `BSARCH-1.0-V1-CLI-REPEATED-VALUE` — first repeated value wins | CLI parsing | [JBSA-CLI-002](bsarch-cli.md#jbsa-cli-002) | After the profile selector, the first recognized occurrence of a repeated value-bearing operation switch supplies the value; later occurrences are ignored. |
| `BSARCH-1.0-V1-CLI-FAMILY-PRIORITY` — family priority | CLI packing | [JBSA-CLI-004](bsarch-cli.md#jbsa-cli-004) | Multiple family switches select the first family present in this priority order, independent of argument order: `-tes3`, `-tes4`, `-fo3`, `-fnv`, `-tes5`, `-sse`, `-fo4`, `-fo4dds`, `-sf1`, `-sf1dds`. |
| `BSARCH-1.0-V1-CLI-IGNORED-ARGUMENT` — ignored tail arguments | CLI parsing | [JBSA-CLI-002](bsarch-cli.md#jbsa-cli-002) | Unknown switches and extra arguments after the required operands are ignored. Required operands and the profile selector position remain strict. |
| `BSARCH-1.0-V1-CLI-BOOLEAN-NO` — permissive boolean values | CLI packing and unpacking | [JBSA-CLI-006](bsarch-cli.md#jbsa-cli-006) | For `-share` and `-mt`, only a case-insensitive value exactly equal to `no` disables the feature; a missing or other value enables it. |
| `BSARCH-1.0-V1-CLI-SPLIT-PARSE` — legacy split parsing | CLI packing | [JBSA-CLI-006](bsarch-cli.md#jbsa-cli-006) | A nonempty non-integer value becomes zero, values above eight become eight, and a negative integer becomes a negative GiB target. A negative target places each nonempty logical entry in its own whole-entry part. A missing value behaves as an omitted switch. |
| `BSARCH-1.0-V1-CLI-ZERO-FLAGS` — zero means automatic | CLI BSA packing | [JBSA-CLI-006](bsarch-cli.md#jbsa-cli-006) | A zero `-af` or `-ff` value requests automatic flag selection instead of a literal zero override. |
| `BSARCH-1.0-V1-CLI-UNUSABLE-SOURCE` — omit unusable sources | CLI packing | [JBSA-CLI-007](bsarch-cli.md#jbsa-cli-007) | An unusable declared source is omitted; packing continues when the resulting overlay retains at least one usable entry and otherwise fails. |
| `BSARCH-1.0-V1-CLI-STDOUT` — reference stream placement | CLI records | [JBSA-CLI-011](bsarch-cli.md#jbsa-cli-011) | The qualified reference banner, warnings, and errors are written to stdout; interactive progress remains presentation-only. |
| `BSARCH-1.0-V1-CLI-INFO-ZERO` — archive-info error exits zero | CLI archive information | [JBSA-CLI-014](bsarch-cli.md#jbsa-cli-014) | A qualified archive-information failure prints its error using profile stream placement and returns exit status zero. |
| `BSARCH-1.0-V1-CLI-LEGACY-REPLACE` — implicit replacement | CLI mutation | [JBSA-CLI-008](bsarch-cli.md#jbsa-cli-008) | Pack and unpack select the qualified legacy replacement behavior without requiring `--replace`. |

The deviations in this table **MUST** preserve structured diagnostics, operation
outcomes, Artifact States, and residual-path reporting supplied by the public
library even where the reference CLI did not expose equivalent structure.

_Source decisions: [accepted CLI deviation set](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [accepted initial profile quirks](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-COMPAT-006

The `bsarch-1.0/v1` profile **MUST** also contain exactly these archive-library
deviations:

| Deviation | Surface | Safe behavior owner | `bsarch-1.0/v1` behavior |
| --- | --- | --- | --- |
| `BSARCH-1.0-V1-NAME-ACTIVE-ANSI` — active ANSI name encoding | archive names | [JBSA-DET-006](formats/detection.md#jbsa-det-006) | Snapshot the active Windows ANSI code page at operation start and use it as Archive Name Encoding. Original decoded wire bytes remain retained; unmappable encode input remains an error. Console encoding is unchanged. |
| `BSARCH-1.0-V1-DDS-XBOX-NAME` — Xbox filename inference | DDS reconstruction | [JBSA-DDS-010](formats/dds-payload.md#jbsa-dds-010) | When explicit PC/Xbox target metadata is absent, `_xbox.` in the containing archive filename selects Xbox reconstruction. Explicit metadata always wins. |
| `BSARCH-1.0-V1-SF3-ZLIB-FALLBACK` — Starfield v3 fallback | General and DDS BA2 decode | [JBSA-GNRL-002](formats/general-ba2.md#jbsa-gnrl-002) and [JBSA-DX10-001](formats/dds-ba2.md#jbsa-dx10-001) | An otherwise bounded Starfield version-3 archive whose method is not `3` attempts the qualified zlib fallback and emits a stable diagnostic. Failed zlib validation remains a format failure. |

These deviations **MUST NOT** change selector recognition, wire-name retention,
explicit target precedence, span validation, or exact decoded-size validation
owned by the linked requirements.

_Source decisions: [accepted archive-library dispositions](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted complete profile bundle](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-COMPAT-007

No Compatibility Profile **MUST** weaken extraction containment or publication
safety in [JBSA-IO-008](io-and-publication.md#jbsa-io-008) through
[JBSA-IO-014](io-and-publication.md#jbsa-io-014). `bsarch-1.0/v1` **MUST NOT**
enable uncompressed DDS BA2 encoding prohibited by
[JBSA-DX10-006](formats/dds-ba2.md#jbsa-dx10-006), hash-only name or payload
equality, scheduling-dependent JBSA output, off-by-one progress, exception-class
or message formatting, timing or exact-whitespace fidelity, or creation of
`BSArchException.log`.

The profile **MUST NOT** change UTF-8 console encoding, exact residual-artifact
reporting, deterministic failure ordering, bounded cleanup, or the established
failure-versus-cancellation outcome race. Reference behavior that conflicts
with one of these exclusions is outside the profile, not an unregistered
deviation.

_Source decisions: [accepted safety limit on deviations](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted profile exclusions](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._
<!-- profile-payload-end -->
