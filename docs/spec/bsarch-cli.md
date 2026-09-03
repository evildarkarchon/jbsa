# BSArch-compatible CLI

This specification owns the supported `jbsa` command line and its process-level
observations. Archive semantics remain owned by the public library and Archive
Family specifications; compatibility deviations are owned by
[Compatibility profiles](compatibility-profiles.md).

## JBSA-CLI-001

The `jbsa` CLI **MUST** be a thin consumer of
`BethesdaArchives.standard()` through [JBSA-LIB-001](library-interface.md#jbsa-lib-001).
Archive information, `-list`, and `-dump` **MUST** use one detached `inspect(Path)`
result; `pack` **MUST** construct one immutable `PackRequest`; `unpack` **MUST**
construct one immutable `ExtractRequest`; and both mutations **MUST** use
`OperationControl` under [JBSA-LIB-010](library-interface.md#jbsa-lib-010).

The CLI **MUST** own only argument parsing, path presentation, stream rendering,
console signal handling, process status, and launch behavior. It **MUST NOT**
access an internal parser, codec, provider, scheduler, storage port, or
implementation metadata as prohibited by
[JBSA-BUILD-005](modules-and-build.md#jbsa-build-005). The first release
**MUST NOT** add JSON output, selected-extraction syntax, provider selection, resource
tuning, or another command surface.

_Source decision: [accepted public-library CLI seam](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-002

The safe parser **MUST** accept exactly these operation forms:

```text
jbsa [--compatibility-profile=bsarch-1.0/v1] pack <source1+source2+...> <archive> [options]
jbsa [--compatibility-profile=bsarch-1.0/v1] unpack <archive> [existing-directory] [options]
jbsa [--compatibility-profile=bsarch-1.0/v1] <archive> [-list] [-dump]
```

Commands, switch names, enumerated values, and legacy family aliases **MUST** be
case-insensitive; path operands **MUST** retain their supplied characters. There
**MUST NOT** be a separate `info` verb. `-dump` **MUST** include the list, and
specifying `-list` with `-dump` in either order **MUST** emit each entry record
once. Required operands **MUST** precede operation switches.

The single pack-source operand **MUST** split at each literal `+`. The v1 CLI
**MUST NOT** provide quoting or escaping for a literal `+` within one source
path; this restriction **MUST NOT** apply to the public library interface.

In the safe default, the parser **MUST** reject unknown switches, extra operands,
duplicate value switches, repeated or conflicting family switches, switches
interspersed with required operands, missing values, invalid values, duplicate
operation switches other than the `-list`/`-dump` pair, and inapplicable
combinations. Operation switches **MUST** use `-`; another legacy prefix
**MUST NOT** be accepted without direct oracle qualification. Profile parsing
deviations are owned by
[JBSA-COMPAT-005](compatibility-profiles.md#jbsa-compat-005).

_Source decision: [accepted invocation grammar and strict parser](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-003

The global administrative and safety options **MUST** be exactly `--help`,
`--version`, `--compatibility-profile`, `--replace`, and `--no-progress`.
No arguments and the standalone first argument `--help` **MUST** print help to
stdout and return zero. Standalone first argument `--version` **MUST** print the
JBSA artifact version plus every supported BSArch contract/profile identifier
and digest to stdout and return zero. Extra arguments to either form **MUST** be
a usage error in the safe default.

`--compatibility-profile=bsarch-1.0/v1` **MUST** occur at most once and before
the command or archive operand. `--replace` and `--no-progress` **MUST** occur in
the trailing options of an applicable mutating operation. Another first-position
switch, an unsupported profile value, or an inapplicable global option **MUST**
be a usage error. Help synopses and option names **MUST** remain stable;
explanatory prose **MAY** improve.

_Source decisions: [accepted administrative option behavior](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [accepted digest-identified profile policy](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-CLI-004

Every `pack` invocation **MUST** contain exactly one selector from this table and
**MUST** map it to the listed target:

| Selector | Target Archive Family |
| --- | --- |
| `-tes3` | TES3 / Morrowind BSA |
| `-tes4` | TES4 / Oblivion BSA, version `0x67` |
| `-fo3`, `-fnv`, or `-tes5` | FO3/FNV/Skyrim LE BSA, version `0x68` |
| `-sse` | SSE/Skyrim AE BSA, version `0x69` |
| `-fo4` | Fallout 4 General BA2, version `1` |
| `-fo4dds` | Fallout 4 DDS BA2, version `1` |
| `-sf1` | Starfield General BA2; version/method follows the codec |
| `-sf1dds` | Starfield DDS BA2; version/method follows the codec |

The three `0x68` aliases **MUST** preserve the accepted spelling for CLI
observations without creating distinct encoded Archive Families. Unsupported
encode directions **MUST** fail according to
[JBSA-BSA-001](formats/versioned-bsa.md#jbsa-bsa-001),
[JBSA-GNRL-002](formats/general-ba2.md#jbsa-gnrl-002), and
[JBSA-DX10-001](formats/dds-ba2.md#jbsa-dx10-001).

_Source decision: [accepted pack family selectors](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-005

The pack compression switch **MUST** accept bare `-z`, `-z:zlib`, `-z:lz4`, or
`-z:lz4f`. For a non-DDS family, omitted `-z` **MUST** request stored payloads;
bare `-z` **MUST** select zlib for `-tes4`, the `0x68` aliases, `-fo4`, and
`-sf1`, and LZ4-frame for `-sse`.

For `-fo4dds`, omitted or bare `-z` **MUST** select zlib. For `-sf1dds`, omitted
or bare `-z` **MUST** select raw LZ4 and therefore Starfield version `3`, method
`3`. Explicit zlib **MUST** select Starfield version `2`; explicit raw LZ4
**MUST** select version `3`, method `3`. The CLI **MUST** apply the same
version selection to `-sf1` when a codec is selected.

An explicit codec **MUST** be accepted only where the matrices in
[JBSA-BSA-001](formats/versioned-bsa.md#jbsa-bsa-001),
[JBSA-GNRL-002](formats/general-ba2.md#jbsa-gnrl-002), and
[JBSA-DX10-001](formats/dds-ba2.md#jbsa-dx10-001) support it. `-tes3` **MUST**
reject every `-z` form. No invocation or profile **MUST** request uncompressed
DDS encoding under [JBSA-DX10-006](formats/dds-ba2.md#jbsa-dx10-006).

_Source decisions: [accepted compression switch defaults](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [accepted mandatory DDS compression](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-CLI-006

`-split:0` **MUST** disable splitting, and `-split:1` through `-split:8` **MUST**
select that many GiB. When omitted, BSA output **MUST** use the split target
owned by [JBSA-TES3-006](formats/tes3-bsa.md#jbsa-tes3-006) or
[JBSA-BSA-015](formats/versioned-bsa.md#jbsa-bsa-015), and BA2 output **MUST**
use [JBSA-GNRL-013](formats/general-ba2.md#jbsa-gnrl-013). The safe parser
**MUST** reject an empty, non-decimal, negative, or out-of-range split value.

Sharing and multithreading **MUST** default to enabled. The safe parser **MUST**
accept only `-share:yes|no` and `-mt:yes|no`. Omitted or `yes` multithreading
**MUST** map to `WorkerSelection.AUTOMATIC`; `no` **MUST** map to `UP_TO(1)` as
defined by [JBSA-SCHED-001](execution-model.md#jbsa-sched-001).

`-af` and `-ff` **MUST** accept an optional `0x` prefix followed by one or more
unsigned hexadecimal digits in the `u32` range, only for versioned BSA targets.
In the safe default, zero **MUST** be a literal override; mandatory and impossible
flag combinations **MUST** be rejected under
[JBSA-BSA-012](formats/versioned-bsa.md#jbsa-bsa-012) through
[JBSA-BSA-014](formats/versioned-bsa.md#jbsa-bsa-014). `-f` **MUST** accept a
nonempty comma-separated list of case-insensitive basename masks, where `*`
matches zero or more characters and `?` matches one character; an empty mask
**MUST** be rejected.

_Source decision: [accepted split, sharing, worker, flag, and filter switches](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-007

Each pack-source component **MUST** become an ordered public `PackSource` for a
directory, individual loose file, or existing Bethesda Archive. Archive sources
**MUST** be decoded through the public library before repacking, and the CLI
**MUST** preserve operand order so [JBSA-LIB-008](library-interface.md#jbsa-lib-008)
owns overlay replacement and first-insertion position.

In the safe default, every declared source **MUST** exist, be readable, and be
recognizable before publication; a filter leaving no entries **MUST** fail.
Source discovery **MUST** complete before staging. Every planned archive part,
target predecessor or backup, and library-owned staging path **MUST** be excluded
from recursive source traversal. Explicitly naming any output artifact as a
source **MUST** fail, including an existing target beneath a source directory
when `--replace` is selected.

Recursive discovery **MUST NOT** follow filesystem indirections. It **MUST** use
portable Java NIO no-follow and identity facilities where sufficient and
**MAY** use an internal provider-specific check only when those facilities
cannot establish the required fact. An operation whose provider cannot prove
safe no-follow or stable identity behavior **MUST** fail as `CAPABILITY` under
[JBSA-OPS-004](operation-semantics.md#jbsa-ops-004). This CLI behavior
**MUST NOT** expose NTFS or reparse-point terminology through the public library.

_Source decision: [accepted sources, overlays, and filesystem behavior](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-008

An explicit unpack destination **MUST** already exist and be a directory. When
it is omitted, the CLI **MUST** use the archive's containing directory; a bare
relative archive with no parent component **MUST** resolve that destination
against the process working directory.

In the safe default, pack and unpack **MUST** select target policy `FAIL` from
[JBSA-IO-010](io-and-publication.md#jbsa-io-010). On either command,
`--replace` **MUST** select `REPLACE`. Profile-specific implicit replacement is
owned by [JBSA-COMPAT-005](compatibility-profiles.md#jbsa-compat-005).

The CLI **MUST NOT** pre-normalize an archive entry into eligibility or bypass
the extraction preflight and containment contract in
[JBSA-OPS-003](operation-semantics.md#jbsa-ops-003) and
[JBSA-IO-009](io-and-publication.md#jbsa-io-009).

_Source decision: [accepted extraction destination, replacement, and containment behavior](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-009

Archive information **MUST** begin with a stable, locale-independent summary
containing the supplied archive path, Archive Family, applicable wire version,
subtype and compression method, entry count, compressed-entry count and codec,
applicable target, and applicable archive and file flags.

`-list` **MUST** then emit each decoded display name once, one per line, in
archive order. `-dump` **MUST** emit each display name followed by every
applicable public typed metadata value: family-specific directory and name
hashes, uncompressed and packed sizes, physical offsets, compression state, and
DDS dimensions, DXGI format, cubemap state, mip ranges, and logical chunk facts.
Record labels and order **MUST** be stable. Padding, exact whitespace, and
explanatory prose **MUST NOT** be conformance fields.

_Source decision: [accepted information, list, and dump records](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-010

After successful `pack`, the CLI **MUST** report every published archive part in
Logical Plan Order with normalized absolute path, byte size, and entry count.
After successful `unpack`, it **MUST** report the normalized absolute destination
and published-entry count. Both commands **MUST** render their retained
diagnostics in the order established by
[JBSA-OPS-005](operation-semantics.md#jbsa-ops-005).

_Source decision: [accepted successful mutation records](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-011

On failure or cancellation, the CLI **MUST** render the Primary Failure, bounded
Secondary Failures, retained diagnostics, and every affected Artifact State in
Logical Plan Order. It **MUST** render every exact Residual Artifact path from
[JBSA-OPS-011](operation-semantics.md#jbsa-ops-011).

In the safe default, successful human records **MUST** go to stdout, while
warnings and failures **MUST** go to stderr. Stable diagnostics **MUST** begin
with `Warning: [<diagnostic-id>]` or
`Error: [<failure-kind-or-diagnostic-id>]` and include deterministic structured
location and value data. Human explanations, provider messages, and exception
class names **MUST NOT** be conformance fields. Profile stream deviations are
owned by [JBSA-COMPAT-005](compatibility-profiles.md#jbsa-compat-005).

All redirected and interactive text **MUST** use UTF-8. Elapsed time, line
endings, exact whitespace, worker narration, progress repaint cadence, and
exception message text **MUST NOT** be CLI conformance fields.

_Source decisions: [accepted failure, diagnostic, artifact, and stream records](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [accepted structured CLI Observations](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-CLI-012

Interactive progress **MUST** be derived only from the semantic Progress
Snapshots defined by [JBSA-OPS-007](operation-semantics.md#jbsa-ops-007) and
**MUST** preserve their phase and metric order. The CLI **MAY** coalesce
snapshots and **MUST** render progress only to stderr while that stream is
attached to an interactive console. Redirection or `--no-progress` **MUST**
suppress progress without changing the operation or its records.

Any displayed count, ratio, or percentage **MUST** derive from the snapshot's
completed and optional-total values. The CLI **MUST NOT** reproduce the
reference's off-by-one percentages or treat repaint cadence as semantic.

_Source decision: [accepted progress presentation](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-013

While packaged `pack` or `unpack` is active, the first Ctrl+C **MUST** request
Cooperative Cancellation through `OperationControl` without interrupting
workers. The CLI **MUST** wait for the library's publication, rollback, cleanup,
and settled outcome defined by
[JBSA-OPS-009](operation-semantics.md#jbsa-ops-009). Repeated Ctrl+C **MUST NOT**
have a stronger contractual effect.

External forced termination, console-window closure, logoff, machine shutdown,
or power loss **MUST NOT** carry a CLI cleanup or exit-status guarantee.

_Source decision: [accepted packaged cancellation behavior](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-014

The CLI **MUST** return exactly these process exit statuses:

| Status | Meaning |
| ---: | --- |
| `0` | successful operation, help, or version |
| `2` | invalid invocation |
| `1` | operational failure, including Diagnostic Policy rejection, partial publication, or cleanup failure |
| `130` | Cooperative Cancellation accepted before the applicable Publication Commit |

An accepted warning **MUST NOT** change success. If cancellation loses the
established outcome race or arrives after the applicable Publication Commit,
the CLI **MUST** return the actual committed success or failure instead of 130.
The archive-information exception to these statuses is owned only by
[JBSA-COMPAT-005](compatibility-profiles.md#jbsa-compat-005).

_Source decision: [accepted process exit statuses](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-CLI-015

The canonical conformance target **MUST** be the `jbsa.exe` launcher in the
self-contained application image owned by
[JBSA-BUILD-008](modules-and-build.md#jbsa-build-008). It **MUST** preserve the
process arguments, working directory, UTF-8 standard streams, exit status, and
Ctrl+C behavior specified above, and it **MUST** host the JVM in the one
application process without an application-launcher child process.

An unsupported build-tree `jbsa.cmd` **MAY** receive smoke-level parity checks,
but **MUST NOT** be a release artifact or the canonical CLI Conformance target.

_Source decision: [accepted canonical Windows launcher](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._
