# Compliance

This specification owns project licensing, independent reference use, fixture
rights, dependency and native-byte provenance, contribution policy, and release
input gates. It records engineering controls rather than legal advice; material
that crosses an escalation boundary stays excluded until the required decision
or qualified review exists.

## JBSA-LIC-001

Apache-2.0 **MUST** govern independently authored Java source and project-owned
test and fixture-generator code. The top-level license **MUST NOT** be represented
as relicensing the read-only `TES5Edit` submodule, CC0-1.0 fixture data, or
separately licensed third-party content.

_Source decision: [accepted project-license and source-boundary policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-002

JBSA implementation **MUST** be independently authored from observable behavior,
documented format facts, synthetic tests, and recorded citations. Contributors
**MUST NOT** copy, mechanically translate, or preserve distinctive Reference
Snapshot source structure, comments, or tables. No MPL adaptation lane is
authorized; a proposed exception **MUST** stop before merge and obtain a fresh
explicit decision and qualified legal review where appropriate.

_Source decisions: [research-derived adaptation boundary](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted independent-only policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-003

The project, packages, Maven artifacts, and executable **MUST** use the `jbsa`
identity. “BSArch-compatible” **MAY** be used only descriptively with an
independent and unaffiliated statement. Project materials **MUST NOT** use
TES5Edit or BSArch logos or imply endorsement.

_Source decision: [accepted attribution and naming policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-004

Project-authored synthetic fixture inputs, generated archives, manifests, and
normalized observations **MUST** use CC0-1.0; their Java test and generator code
**MUST** remain Apache-2.0. A third-party test vector **MUST NOT** be committed or
distributed without an explicit redistribution grant and review.

_Source decision: [accepted golden-fixture licensing policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-005

Proprietary game archives, extracted game assets, derived archives containing
those assets, Conformance Oracle outputs containing protected
material, and the Conformance Oracle **MUST NOT** be committed or released. A
legally obtained local game corpus **MAY** be used only as ignored, read-only
evidence and **MUST NOT** enter a committed, assembled, or published artifact.

_Source decisions: [accepted forbidden-content policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [provisioned ignored local-corpus boundary](https://github.com/evildarkarchon/jbsa/issues/6#issuecomment-5517505754)._

## JBSA-LIC-006

Every committed fixture **MUST** record its creator or source, SPDX license,
generation procedure and exact command/options, Reference Snapshot revision,
Conformance Oracle digest when used, input and output SHA-256 hashes, generation
date, and redistribution class.

_Source decision: [accepted fixture-provenance policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-007

Maintained open-source Maven dependencies **MAY** be used when their selected
versions and redistributed bytes satisfy this specification. Native dependencies
**MUST NOT** be admitted without evidence that pure Java cannot satisfy the
applicable conformance or performance contract. An admitted native dependency
**MUST** be pinned and audited, remain behind a non-public boundary replaceable
without changing the exported library interface, and leave the main `jbsa`
artifact thin. Only an explicitly authorized platform/provider artifact or the
Windows CLI assembly **MAY** redistribute native bytes, and only after the exact
artifact contents have been audited.

_Source decisions: [resolved pure-Java/native evidence](https://github.com/evildarkarchon/jbsa/issues/4#issuecomment-5509001000), [accepted native-binary and Maven-distribution policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [accepted internal provider strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-LIC-008

Every redistributed dependency or native payload **MUST** record coordinates and
classifier, version, cryptographic hash, license, required notices, upstream
source and build provenance, and every release artifact containing its bytes.
Opaque or unresolved payloads **MUST** be rejected. An optional native provider
under qualification, including jlibdeflate before promotion, **MUST NOT** enter
the normal CLI ZIP until all applicable conformance, performance, memory,
native-loading, and notice gates pass.

_Source decisions: [accepted native-byte inventory policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [accepted provider-promotion gate](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-LIC-009

Release packaging **MUST** preserve every applicable license and notice text,
produce an SBOM, and inspect the final JARs, POM, application image, ZIP, native
libraries, documentation artifacts, and other published bytes against the
approved inventories. Any dependency, native payload, fixture, build residue, or
other byte without resolved authorization and provenance **MUST** fail the
release audit.

_Source decisions: [exact-byte audit constraint](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted release-byte policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-010

Required repository policy material **MUST** comprise the Apache-2.0 `LICENSE`,
applicable exact license texts under `LICENSES/`, per-file SPDX/REUSE metadata,
`THIRD-PARTY-NOTICES.md`, a compact `CONTRIBUTING.md`, and
`docs/reference-use.md`. `NOTICE` **MUST** be reserved for notices whose licenses
require propagation and **MUST NOT** become a general acknowledgements file.

_Source decision: [accepted policy-material set](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-011

CI and release gates **MUST** verify SPDX/REUSE metadata, dependency and native
licensing, SBOM generation, fixture provenance, and inspection of final release
bytes. A clean metadata or Maven validation result **MUST NOT** be treated by
itself as proof of license compliance.

_Source decisions: [Maven-metadata limitation](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted compliance-gate policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [external-contribution gate retirement](https://github.com/evildarkarchon/jbsa/issues/62)._

## JBSA-LIC-012

Merge or release **MUST** stop when proposed material involves Reference Snapshot
adaptation, unclear third-party fixture rights, proprietary content, opaque
native provenance, non-descriptive TES5Edit/BSArch branding, or unresolved
substantial-similarity, trademark, patent, or EULA concerns. Uncertain material
**MUST** remain excluded until an explicit decision and any appropriate qualified
review resolve it.

_Source decisions: [identified counsel-dependent risks](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted escalation boundary](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-013

Project copyright **MUST** be attributed as `Copyright 2026 evildarkarchon`.

_Source decision: [accepted solo-maintainer and contributor policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-014

The pinned Reference Snapshot and revision **MUST** be credited in the project
README, generated documentation, reference-use policy, POM description, and
release notices. Normal CLI help and output **MUST NOT** reproduce the
Conformance Oracle banner; a short attribution in `--version` **MAY** be
included.

_Source decision: [accepted attribution and naming policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-015

The project **MUST NOT** require a contributor license agreement.

_Source decision: [accepted solo-maintainer and contributor policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-016

_Retired in specification 0.11.0 by [issue #62](https://github.com/evildarkarchon/jbsa/issues/62).
The maintainer-specific sign-off exemption became unnecessary when JBSA stopped requiring DCO
sign-off._

## JBSA-LIC-017

_Retired in specification 0.11.0 by [issue #62](https://github.com/evildarkarchon/jbsa/issues/62).
External pull requests are now assessed directly by the maintainer without mandatory DCO sign-off
or provenance declarations._

## Deferred compliance inputs

No dependency, native payload, or third-party fixture is approved merely by
appearing in a planning decision. The selected LWJGL 3.4.3/LZ4 1.10.0 and
Airlift 3.7 versions are selected-but-unaudited inputs, not unknowns and not
compliance approval. Exact inventory coordinates, classifiers, payload hashes,
license texts, notices, source/build provenance, redistribution grants, and
containing artifacts remain unverified until an inventory supplies evidence.
The framework records that absence rather than inferring approval.

_Source decision: [accepted codec and dependency versions](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._
