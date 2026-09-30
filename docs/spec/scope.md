# Scope

This specification fixes the product and qualification boundary inherited from
the completed planning map. Archive Family details, public API behavior, and
qualification case matrices are owned by their respective
[specifications](README.md#specification-index). They may refine this boundary
only as allowed by the framework.

## JBSA-SCOPE-001

Retired in specification `0.19.0`. The former baseline combined the Java 25
target and the Windows 11 x64/NTFS qualified platform with a disclaimer of any
verification or portability claim for other platforms.
[JBSA-SCOPE-010](#jbsa-scope-010) retains the target and qualified platform;
[JBSA-SCOPE-011](#jbsa-scope-011) replaces the disclaimer with a portability
obligation.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted Windows, Java 25, and self-contained CLI baseline](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-SCOPE-002

The supported library **MUST** cover every Archive Family recognized by the
pinned Reference Snapshot, including the decode and encode surfaces designated
by the owning format specifications. Binary Conformance **MUST** remain limited
to individually designated deterministic cases; it is not a blanket
byte-identity claim.

_Source decision: [accepted Reference Snapshot behavior scope](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245)._

## JBSA-SCOPE-003

JBSA **MUST** provide one BSArch-compatible CLI covering the Reference
Snapshot's console operations and options through the public archive-library
interface. GUI-only editing, search, rename, and other BSArchPro workflows
**MUST NOT** become product scope under this specification set.

_Source decision: [accepted CLI scope and public-library seam](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-SCOPE-004

The Reference Snapshot **MUST** remain the read-only `TES5Edit` submodule pinned
at commit `fd1e36020b2b5b6217e553dc0038983146a2e2dd`. Implementation, evidence,
and compatibility claims **MUST NOT** silently follow an unpinned or moving
TES5Edit revision.

_Source decisions: [accepted pinned behavior authority](https://github.com/evildarkarchon/jbsa/issues/2#issuecomment-5508994245), [accepted read-only reference-use boundary](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-SCOPE-005

Each implementation slice **MUST** be accepted through its applicable automated
conformance evidence before it closes. Performance results **MUST NOT** waive a
conformance failure.

_Source decision: [accepted organizing and verification gates](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-SCOPE-006

Manual game or official-tool Release Qualification **MUST** gate the complete
writable-family claim and public release, but **MUST NOT** block unrelated
implementation slices.

_Source decision: [accepted automated/manual gate split](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-SCOPE-007

Hosted GitHub Actions runners **MUST** carry the Automated Conformance claim.
Official game and tool qualification **MUST NOT** run on self-hosted CI or be
represented as part of the hosted Automated Conformance claim.

_Source decision: [accepted hosted and manual claim boundary](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093)._

## JBSA-SCOPE-008

Intermediate implementation outputs **MUST** remain internal or CI artifacts.
The first public release **MUST** satisfy the complete destination, establish the
first immutable Performance Baseline, and pass the separately specified human
qualification and approval gates.

_Source decision: [accepted first-release rule](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-SCOPE-009

For JBSA 1.0, Xbox DDS encode, Xbox canonical reconstruction, and Xbox filename
inference **MUST** be deferred until after version 1.0. Their implementation and
qualification obligations, including matching-Xbox encode, Xbox-target mismatch,
and Xbox reconstruction-selection cases, **MUST NOT** gate the Interface
Candidate or any subsequent 1.0 release gate. Existing unqualified results
**MUST** remain unqualified and **MUST NOT** be represented as passing evidence.

The remaining DDS PC obligations, including rejection of Xbox input when the
encode target is `PC`, **MUST** remain in scope. `DdsTarget.XBOX` **MUST** remain
a reserved public value for the later feature; its presence, internal code, or
local tests **MUST NOT** imply a 1.0 Xbox support or qualification claim. The
detailed Xbox rules retained in the DDS, CLI, and compatibility specifications
apply to that later feature. All first-release completeness claims in this set
**MUST** use this boundary; no other Archive Family or feature is deferred by it.

_Source decision: [post-1.0 Xbox DDS deferral](../../.scratch/jbsa-1-0/issues/41-establish-the-interface-candidate-after-representative-archive-families.md#post-10-xbox-dds-deferral)._

## JBSA-SCOPE-010

JBSA production code and Java-consumable artifacts **MUST** target Java 25. The
qualified platform **MUST** be Windows 11 x64 on NTFS. The shipped CLI may carry
its own Java 25 runtime, so this requirement does not imply that a CLI user
installs Java separately. Qualification, Release Qualification, and every claim
that depends on them apply only to the qualified platform.

_Source decisions: [accepted Windows, Java 25, and self-contained CLI baseline](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247); portability split by maintainer decision in specification `0.19.0`._

## JBSA-SCOPE-011

JBSA production code **MUST NOT** be restricted to one operating system.
Platform-specific code **MAY** be used where it improves functionality on that
operating system, such as Windows file-sharing denial, Windows filesystem
identity, or a native codec provider. Every such path **MUST** have a portable
fallback, selected before the operation's first side effect, that keeps the
operation working on every other operating system. The fallback may offer
weaker platform guarantees when the owning specification allows it, such as
detecting a replaced file instead of preventing the replacement. A
platform-specific path **MUST NOT** make an operation unavailable anywhere
else. The Linux portability build **MUST** pass every test that does not
exercise an explicit platform-specific path.

The CLI **MAY** also be built for Linux as a self-contained `jlink` and
`jpackage` application image that carries its own Java 25 runtime. The Windows
x64 image remains the release package owned by [Distribution](distribution.md).

A pass on another operating system, architecture, filesystem, or Java release,
or through a portable fallback, verifies portability only. It **MUST NOT** be
represented as qualification, Release Qualification, or Binary Conformance
evidence under [JBSA-SCOPE-010](#jbsa-scope-010).

_Source decision: maintainer decision recorded in specification `0.19.0`._
