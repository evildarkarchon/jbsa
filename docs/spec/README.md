# JBSA normative specification

This directory is the implementation authority for JBSA requirements. Resolved
issue comments and research remain the rationale and evidence for a requirement,
but they do not override the version-controlled requirement text materialized
here.

_Decision sources: [accepted specification authority and release gates](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [framework acceptance criteria](https://github.com/evildarkarchon/jbsa/issues/24)._

## Specification set

The specification set has one version and status, recorded under
`specification` in `requirements.yaml`. Every Markdown file under this directory
belongs to that set unless it explicitly says that it is informative. A change
to any normative file changes the set as a whole; individual files do not carry
independent versions.

The first materialized set was version `0.1.0`; it is historical, not the
current specification identity. The sole current version is the value recorded
in `requirements.yaml`. Versions use semantic versioning:

- a patch records editorial changes that do not change an obligation;
- a minor version adds or retires requirements, or otherwise changes normative
  meaning before the `1.0.0` approval gate; and
- after `1.0.0`, a major version records an incompatible normative or framework
  change, a minor version adds compatible obligations, and a patch remains
  editorial.

Versions below `1.0.0` are normative while the contract is being completed; they
do not claim the JBSA 1.0 approval or publication gates. The first `1.0.0`
specification requires the explicit human approval gate assigned by the accepted
implementation sequence.

_Decision source: [version-controlled specification and milestone policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## Normative language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**,
**SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **NOT RECOMMENDED**, **MAY**, and
**OPTIONAL** are interpreted as described by
[BCP 14](https://www.rfc-editor.org/info/bcp14) when, and only when, they appear
in uppercase. Lowercase uses of those words are ordinary prose.

Product obligations use those keywords only inside a section owned by one
permanent requirement identifier. Tables and lists inside that section are part
of the requirement when its normative sentence incorporates them. Examples,
rationale, source citations, contradiction notes, and explicitly informative
text are not additional obligations.

Framework rules in this README govern how requirements are interpreted and
maintained. They are not product requirements and therefore do not receive
`JBSA-*` identifiers.

_Decision sources: [permanent requirement and authority policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [framework acceptance criteria](https://github.com/evildarkarchon/jbsa/issues/24)._

## Requirement identity and ownership

A product requirement identifier has the form `JBSA-<NAMESPACE>-###`:

- `JBSA` is literal;
- `<NAMESPACE>` is the uppercase specification-area name; and
- `###` is a zero-padded, three-digit sequence allocated monotonically within
  that namespace, beginning at `001`.

An identifier permanently denotes one obligation. It MUST NOT be reused,
renumbered, or reassigned to another namespace. Gaps are valid. An editorial
clarification that preserves the obligation retains the identifier; a semantic
replacement retires the old identifier and allocates a new one.

Each active identifier has exactly one owner: a stable heading in one normative
Markdown file. Other specifications may link to that owner but MUST NOT repeat
its normative text. `requirements.yaml` contains only traceability metadata and
MUST NOT copy a title, summary, or requirement text.

Retirement never deletes history. The owner becomes a non-normative tombstone,
and the registry entry remains at its original position with
`lifecycle_state: retired` plus a retirement issue and reason. Retired numbers
remain unavailable forever. The historical `0.1.0` set had no retired
identifiers; current lifecycle state is recorded in `requirements.yaml`.

_Decision sources: [accepted identity and non-duplication policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [retirement acceptance criterion](https://github.com/evildarkarchon/jbsa/issues/24)._

## Registry contract

[`requirements.yaml`](requirements.yaml) is the machine-readable traceability
registry. `schema_version` versions its data shape independently of the
specification set. Requirements are ordered by identifier and each entry has
exactly these required fields:

- `id`: the permanent requirement identifier;
- `owner`: `document` and stable `anchor` values locating the one normative
  owner;
- `source_decisions`: stable links to the originating accepted decision or its
  explicit supersession, each either a tracker ticket reference or, for migrated
  historical entries, an original external link retained as archival
  provenance. An empty list means the decision is recorded only in the owning
  section and version history;
- `lifecycle_state`: `active` or `retired`;
- `verification_class`: one of `document-review`, `automated-test`,
  `build-verification`, `conformance-case`, `performance-case`,
  `release-qualification`, or `release-audit`;
- `implementation_tickets`: tracker ticket references responsible for
  satisfying or deliberately migrating the requirement. An empty list means no
  ticket owns the work; and
- `test_evidence`: stable test selectors, case identifiers, or repository paths
  to evidence. An empty list means that no evidence is claimed yet.

A retired entry additionally has a `retirement` mapping with a `reason` and, when
a ticket tracked the retirement, a tracker ticket reference as `ticket`.

This is a single-maintainer project, so a tracker ticket is never required.
When one is cited, a tracker ticket reference is either the canonical GitHub
issue URL `https://github.com/evildarkarchon/jbsa/issues/<number>` or a
repository-relative path to an existing record in the read-only historical
tracker under `.scratch/`. Historical paths stay valid; they are not rewritten
when their work resumes on GitHub.

Registry consumers MUST reject duplicate identifiers, missing owners, owner
anchors that do not match the identifier, unknown lifecycle or verification
values, a missing retirement reason, cited ticket references in neither accepted
form, and normative-text fields such as `title`, `summary`, or `text`.

_Decision sources: [accepted registry contents](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [machine-readable registry acceptance criterion](https://github.com/evildarkarchon/jbsa/issues/24)._

## Other identity domains

Requirement identifiers are distinct from identities owned by verification and
behavior specifications:

- `CV1-*` identifies a `conformance-v1` Conformance Case;
- `PV1-*` identifies a `performance-v1` Performance Case;
- a Conformance Diagnostic keeps the stable diagnostic identity assigned by its
  owning behavior specification; and
- a Compatibility Deviation keeps the domain identity assigned by its owning
  Compatibility Profile.

Those identities MUST NOT be allocated from a `JBSA-*` namespace or registered
as requirements. A requirement may cite them in `test_evidence` without changing
their identity domain.

_Decision source: [accepted separation of requirement, case, diagnostic, and deviation identities](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## Authority and conflict handling

Authority is applied in this order:

1. this README governs interpretation, identity, lifecycle, registry, and change
   control;
2. the owning section of an active requirement governs its product obligation;
3. `requirements.yaml` governs traceability metadata and lifecycle without
   supplying product behavior; and
4. source decisions, research, the Reference Snapshot, the Conformance Oracle,
   fixtures, tests, and generated evidence explain or verify requirements but do
   not silently override them.

No "newest text wins" rule applies between active requirements. A more specific
requirement refines a general one only when it links to that requirement and both
can be satisfied. Any other contradiction blocks implementation or release until
an explicit decision changes the specification and affected evidence is reset.
Unknown behavior is recorded as unknown or deferred; it is not inferred from a
neighboring requirement, incidental tool behavior, or an unpinned dependency.

_Decision sources: [specification authority and gate-reset policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [no-invention acceptance criterion](https://github.com/evildarkarchon/jbsa/issues/24)._

## Change control

Every normative change MUST:

1. record the accepted decision in the owning section's decision line, linking a
   tracker ticket only when one exists;
2. update the owning Markdown section, registry metadata, and specification-set
   version together;
3. preserve existing identifiers unless the old obligation is retired;
4. identify affected verification evidence; and
5. reset every affected gate when a requirement changes after its evidence was
   recorded.

Public-interface changes use compiled consumers, architecture checks, and fresh
affected conformance evidence; there is no separate Interface Freeze approval or
source-hash baseline. A change that reveals unresolved product behavior records
the unknown and stops at that boundary rather than inventing a default.

_Decision sources: [retire Interface Freeze](../../.scratch/remove-source-hashing/issues/01-retire-interface-freeze.md), [affected-gate reset policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [framework change-control acceptance criterion](https://github.com/evildarkarchon/jbsa/issues/24); tracker tickets made optional by maintainer decision in specification `0.19.0`._

## Known contradiction and deferred specifics

The Java-25-required ZIP and shipped `.cmd` launcher in the original module
decision were explicitly superseded. The active decision is a self-contained,
compressed JDK 25 `jlink` runtime and `jpackage` application image with
`jbsa.exe`; `jbsa.cmd` is only an unsupported build-tree convenience.

_Supersession sources: [original module decision and partial supersession](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5521260730), [accepted CLI packaging decision](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

This foundation fixes Gradle 9.7.1, Kotlin DSL, and centralized exact
build-plugin pinning. It intentionally does not duplicate the selected external
plugin versions, audited dependency inventory entries, native payload
identities, or the JDK 25 vendor/build identity. Those release inputs remain
governed by their owning implementation or distribution requirement. Archive
Family, DDS payload, library behavior, CLI behavior, qualification,
distribution, and release gates are owned by the specifications below. The
[accepted review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)
resolve the additional DDS-size, archive-name, split-preflight, and progress
contradictions found during publication review. No other unresolved
contradiction was found in that review batch. The
[accepted `0.8.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5525693059)
subsequently resolve the profile digest, staged release identity, Normalized
Name Identity, diagnostic, split-name, synthetic-name, automatic-flag, DDS
target, and standard Resource Limits gaps found on pull request #61. The
[accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947)
resolve matching performance units, undecodable wire-name display and
disposition, qualified-Windows extraction eligibility, filesystem `PackSource`
name mapping and directory order, and complete annotation-boundary verification.
The
[accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048)
resolve CLI filter and source-list selection, directory-source indirections,
unsafe-name diagnostic identities, failure null ordering, versioned-BSA hash
collisions, stored Starfield-v3 coverage, and mandatory progress observations.
The
[accepted `0.12.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5550691183)
complete the required diagnostic identities, replace the DDS partition rule
with block-rounded mip boundaries, and specify a TES3 full-hash collision
tie-breaker. The DDS replacement retires `JBSA-DDS-007` in favor of
`JBSA-DDS-013`; the remaining clarifications retain their owning identifiers.

The Reference Snapshot's archive-information error/zero-exit contradiction is a
CLI-only evidence boundary owned by the Compatibility Profile; no Archive
Family requirement promotes it to format behavior. The research contains no
authoritative numeric performance baseline. Performance thresholds and evidence
are owned by the performance specification rather than inferred from format
code.

## Specification index

- [Scope](scope.md)
- [Modules and build](modules-and-build.md)
- [Compliance](compliance.md)
- Library behavior:
  - [Library interface](library-interface.md)
  - [Operation semantics](operation-semantics.md)
  - [I/O and publication](io-and-publication.md)
  - [Codecs](codecs.md)
  - [Execution model](execution-model.md)
- Consumer behavior:
  - [Compatibility profiles](compatibility-profiles.md)
  - [BSArch-compatible CLI](bsarch-cli.md)
- Qualification and release:
  - [Assurance v2](assurance-v2.md)
  - [Conformance v1 frozen history](conformance-v1.md)
  - [Performance v1 frozen history](performance-v1.md)
  - [Distribution](distribution.md)
  - [Release gates](release-gates.md)
- Archive formats:
  - [Detection and shared wire conventions](formats/detection.md)
  - [TES3 / Morrowind BSA](formats/tes3-bsa.md)
  - [TES4 BSA (versioned BSA)](formats/versioned-bsa.md)
  - [General BA2](formats/general-ba2.md)
  - [DDS BA2](formats/dds-ba2.md)
  - [DDS payload](formats/dds-payload.md)
