# Publish the specification framework and requirement registry

Status: none
State: closed
GitHub issue: #24
Source: https://github.com/evildarkarchon/jbsa/issues/24
Author: evildarkarchon
Created: 2026-09-03T06:53:20Z
Source updated: 2026-09-05T08:49:09Z
Closed: 2026-09-03T07:44:35Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: none
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Create the normative documentation framework that fixes authority, versioning, requirement identity, scope, build policy, and compliance rules before production implementation begins.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-SCOPE-*
- JBSA-BUILD-*
- JBSA-LIC-*

## Acceptance

- Create docs/spec/README.md with normative-keyword rules, precedence, versioning, change control, and the permanent JBSA-<NAMESPACE>-### identifier policy.
- Create docs/spec/requirements.yaml as the non-duplicating registry of requirement owner, source decision, lifecycle state, verification class, implementing issue, and test evidence.
- Create normative scope, modules-and-build, and compliance specifications covering the accepted Windows/Java baseline, reactor seams, licensing, reference-use, fixture, dependency, native-byte, and release-input policies.
- Record retired identifiers without reuse or renumbering and keep Conformance Case, Performance Case, diagnostic, and deviation identities distinct from requirement identifiers.
- Link every normative statement to its originating resolved decision and identify contradictions or unknowns without inventing behavior.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

### evildarkarchon — 2026-09-03T10:03:39Z

Source: https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451

## Accepted review clarifications

The current specification review identified five internal inconsistencies. Accept these corrections for the next specification-set revision:

- `JBSA-DDS-009` computes block-compressed top-level `dwPitchOrLinearSize` from rounded 4-by-4 block counts. BC1/BC4 use 8 bytes per block; BC2/BC3/BC5/BC6H/BC7 use 16. This intentionally corrects Reference Snapshot reconstruction for dimensions below four or not divisible by four; `JBSA-DDS-007` remains the separate encode-partition boundary.
- Versioned BSA and TES3 canonical encode use a byte-defined ASCII normalization: encode first, reject any byte above `0x7f` unless a fixture-qualified Compatibility Profile defines the non-ASCII byte mapping, map `/` to `\`, and map `A` through `Z` by adding `0x20`. Decode does not assert a canonical hash mismatch for unresolved non-ASCII names.
- Versioned-BSA archive bits `0x0001` and `0x0002` condition directory-name prefixes and the global basename table respectively. The corresponding length is zero when a section is absent. Decode retains absent components as absent and exposes a deterministic, explicitly synthetic display label derived from hashes and serialized ordinals; it never treats that label as original bytes, name identity, or canonical repack input.
- Mutating operations use two preflight gates when exact split paths depend on transformed sizes or sharing: source/plan preflight precedes work; bounded scratch-only stabilization may then determine membership; complete output-count, containment, and collision preflight precedes destination-adjacent staging and every destination effect. This clarification supersedes any reading of the issue 12 or issue 15 resolutions that requires dynamic split targets before their sizes exist.
- The synchronous caller remains the coordinator and sequences and awaits serialized progress delivery, but observer invocation thread identity remains unspecified. This resolves the conflicting progress bullets in the issue 13 and issue 15 resolutions in favor of the public non-guarantee while preserving serial order, blocking backpressure, observer failure handling, and cleanup.

Affected verification must cover rounded BC sizes, all versioned-BSA name-presence flag combinations and synthetic metadata, ASCII/non-ASCII case behavior, late split-target collision preflight, and progress ordering without asserting callback thread identity. Existing implementing issues remain responsible; no recorded test evidence exists to reset.

### evildarkarchon — 2026-09-03T12:23:35Z

Source: https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5525693059

## Accepted review clarifications for specification 0.8.0

The latest PR #61 review identified eleven additional identity and determinism gaps. Accept these corrections for specification-set version `0.8.0`:

- The canonical `bsarch-1.0/v1` payload includes the LF immediately before the end marker. Its SHA-256 is `9577D821C40982E7F988D311D5BA7CF55B0F098AF2C3F5FD5C5A531360DDE1C4`; the prior digest incorrectly trimmed that LF.
- Every gate binds the immutable source/profile identity it consumes. Packaging-input identity and detached artifact/checksum digests are required for a Packaging Gate `PASS` and downstream gates, but do not block pre-Packaging gates before those values exist.
- Versioned-BSA canonical encode rejects a basename whose final-extension split leaves an empty stem. Decode retains such a basename and stored hash without attempting a canonical comparison.
- Detached inspection gains `inspect(Path, OpenOptions)`; the one-argument overload is exactly `OpenOptions.standard()`. The CLI propagates the selected Compatibility Profile and standard limits through the option-bearing call.
- Normalized Name Identity has one locale-independent definition: decode without replacement, `/` to `\`, reject empty/absolute/repeated separators, `.`/`..`, NUL, colon, and trailing space/dot segments, then fold only ASCII `A`-`Z`. No Unicode, locale, host-path, filesystem, or short-name normalization participates.
- TES3 warning identifiers are `tes3.name-offset-inconsistency`, `tes3.trailing-data`, `tes3.stored-hash-mismatch`, `tes3.unsupported-asset-root`, and `tes3.payload-offset-over-signed-2gib`, with deterministic archive- or entry-scoped locations.
- Split paths use the requested destination for part one. Later ASCII part numbers are inserted before the final dot of the final path element, including a leading or trailing dot, or appended when no dot exists. Thus `archive`, `.archive`, and `archive.` produce `archive2`, `2.archive`, and `archive2.` for part two.
- A missing/out-of-range BA2 filename table uses the exact ordinal-disambiguated `__jbsa_hash__` spelling specified in `JBSA-GNRL-008`, with hashes as unsigned lowercase hex and all four extension bytes hex-encoded in wire order. Original wire bytes and Normalized Name Identity are absent.
- Automatic Versioned-BSA flags use the complete ordered pinned root/extension classifier, root before extension and first match wins. The safe `0x67` base keeps `0x0200|0x0400` but leaves contradicted automatic embedded-name bit `0x0100` clear pending fixture resolution.
- `PackRequest` carries an explicit `PC` or `XBOX` DDS target for DDS BA2 and never infers encode target from input or filename. The v1 CLI maps both DDS family selectors to `PC` and adds no Xbox switch. Optional `OpenOptions` target and the qualified `_xbox.` filename fallback remain reconstruction-only.
- `ResourceLimits.standard()` is fixed at: 1,000,000 entries; 1 GiB metadata; 1 TiB decoded bytes; 256 GiB scratch; 1,000,000 outputs; 4,096 diagnostics; and 256 Secondary Failures. Accounting is semantic rather than Java-allocation based, equality with a ceiling is allowed, and the CLI must use these values independent of environment or profile. Direct callers may supply other immutable values.

Conformance coverage must exercise the exact boundaries, mappings, identifiers, missing identities, target combinations, and one-over-limit cases above. Existing implementation issues remain responsible; no completed requirement evidence exists to reset.

### evildarkarchon — 2026-09-03T22:17:13Z

Source: https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947

## Accepted review clarifications for specification 0.9.0

The latest PR #61 review identified five specification gaps and one build-verification gap. Accept these corrections for specification-set version `0.9.0`:

- Random payload throughput remains reported as read operations per second, but every comparison with sequential unpack throughput uses logical MiB per second. A random read counts only after the selected entry reaches normal EOF with its exact manifest-declared uncompressed length; the harness converts completed logical bytes with `bytes / 1,048,576 / elapsed seconds`.
- A required wire-name component that cannot decode without replacement remains inspectable when the archive is otherwise bounded. Decode retains every original component byte, emits a stable warning and Tolerated Noncanonical Archive disposition, assigns the exact ordinal-based `__jbsa_wire__` synthetic display name, and leaves Normalized Name Identity absent.
- The qualified-Windows invalid-character and reserved-device-basename rules are extraction-only eligibility checks applied before host-`Path` conversion and every destination effect. They produce `POLICY` failure but do not remove an otherwise valid Normalized Name Identity or make the archive name ineligible for canonical packing.
- A detected directory `PackSource` maps regular files relative to the supplied directory root; a detected individual loose file uses its final path element; an existing archive preserves its decoded entry names and archive order; explicitly named and generated entries use their caller-supplied complete names. No `Data` ancestor, working directory, or common source ancestor is inferred.
- Directory discovery completes before overlay processing and expands in deterministic Normalized Name Identity and exact-name scalar order. Filesystem enumeration, host collation, locale, worker selection, and completion order cannot affect Logical Plan Order.
- `JBSA-BUILD-006` verification inspects Java class-file annotation attributes rather than only runtime reflection. It covers runtime-visible and runtime-invisible declaration, parameter, and type-use annotations on caller-visible API positions, including annotation-carried type references.

Conformance coverage must exercise the conversion boundary, partial-read exclusion, undecodable-byte synthetic metadata and disposition, Windows-invalid no-write preflight while retaining identity, every filesystem source mapping, and directory enumeration-order independence. Existing implementing issues remain responsible. All affected requirement `test_evidence` lists are empty except `JBSA-BUILD-006`; that packaged-artifact test is strengthened in the same change, so no stale completed evidence remains to reset.


### evildarkarchon — 2026-09-04T00:17:27Z

Source: https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048

## Accepted follow-up review clarifications for specification 0.10.0

The latest PR #61 review identified eight remaining selection, identity, traversal, ordering, and progress gaps. Accept these corrections for specification-set version `0.10.0`:

- CLI `-f` masks are inclusive whole-basename masks. An entry is retained when any comma-separated mask matches, so multiple masks form a union; absence of `-f` retains all entries. Matching folds only ASCII `A` through `Z`, and empty masks remain invalid.
- A pack-source operand split at `+` must contain only nonempty components. A leading, trailing, or repeated separator is an invalid invocation rejected before host-`Path` conversion, source discovery, or `PackSource` construction, in every profile.
- Directory `PackSource` discovery is no-follow library behavior. A linked root fails source-shape preflight as `SOURCE`; descendant symbolic links, qualified-Windows junctions, and equivalent indirections are omitted without reading or traversing their targets; unprovable no-follow classification or stable identity is `CAPABILITY`; and a planned regular file that becomes an indirection is `SOURCE` and is never opened through the link.
- Absolute, traversal-segment, and qualified-Windows-invalid archive names retain inspectable warning diagnostics `archive-name.absolute-path`, `archive-name.traversal-segment`, and `archive-name.windows-invalid-segment`. Each applicable condition is entry-scoped; extraction still rejects it as `POLICY` before output creation without changing Archive Disposition.
- Primary Failure ordering uses present values before absent values independently for logical ordinal, diagnostic identifier, and structured location. Two absent values compare equal at that key and comparison continues.
- Canonical versioned-BSA encode groups entries by canonical folder bytes, orders groups by unsigned folder hash then unsigned-octet folder bytes, and orders each group's entries by unsigned basename hash then unsigned-octet basename bytes. This preserves contiguous folder blocks under hash collision. A surviving duplicate canonical folder/basename byte pair is rejected before output creation rather than given an unstable order.
- The Starfield General BA2 version-3/method-3 base matrix adds a `mixed` case. Raw LZ4 remains selected while each direction proves at least one stored `packedSize == 0` entry and one raw-LZ4 `packedSize > 0` entry in the same archive; a standalone stored selection remains version 2.
- Progress has exact phase-and-metric pairs and phase-entry rules. Pack and atomic/new-root extract use `PREFLIGHT/ENTRIES`, `PROCESSING/ENTRIES`, `PROCESSING/BYTES`, `PUBLISHING/ARTIFACTS`, and `CLEANUP/ARTIFACTS`; existing-tree extract omits `PUBLISHING` and reports its per-file commits through `PROCESSING/ARTIFACTS`. Pre-cancelled and unchecked-invalid requests enter no phase, split stabilization stays in preflight, every applicable successful zero-unit pair still emits its required snapshot, interrupted pairs do not synthesize completion, cleanup is entered after every started operation, and an observer is not called again after it throws.

Conformance coverage must exercise the exact mask union and no-mask behavior, empty source components, direct-library and CLI no-follow source cases, all three unsafe-name identifiers, every optional Primary Failure key combination, colliding BSA folders and basename hashes, mixed Starfield v3 stored/compressed framing, and each progress surface's exact pair set across success, zero work, failure, cancellation, cleanup, and observer failure. Existing implementing issues remain responsible; all affected requirement `test_evidence` lists are empty, so no completed evidence needs reset.


### evildarkarchon — 2026-09-05T08:49:09Z

Source: https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5550691183

PR #61 review follow-up, authorized by the maintainer's request to address all valid review comments: complete stable diagnostic identifiers for required format warnings; define canonical unsigned-byte TES3 name ordering for full-hash ties; and replace the DDS reference bits-per-pixel partition restriction with block-rounded mip sizes so the required 1x1 and 5x7 BC encode cases are implementable. Retire the changed DDS requirement, record its replacement, and increment the specification version under the registry lifecycle rules. The existing accepted behavior otherwise remains in force.



