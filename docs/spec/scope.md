# Scope

This specification fixes the product and qualification boundary inherited from
the completed planning map. Archive-family details, public API behavior, and
qualification case matrices are owned by later specifications and may refine
this boundary only as allowed by the framework.

## JBSA-SCOPE-001

JBSA production code and Java-consumable artifacts **MUST** target Java 25. The
qualified platform **MUST** be Windows 11 x64 on NTFS. The shipped CLI may carry
its own Java 25 runtime, so this requirement does not imply that a CLI user
installs Java separately. Other operating systems, architectures, filesystems,
and Java releases **MAY** work, but this specification set makes no support,
verification, or portability claim for them.

_Source decision: [accepted Windows, Java 25, and self-contained CLI baseline](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

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
