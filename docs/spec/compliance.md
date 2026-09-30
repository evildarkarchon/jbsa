# Compliance

This specification owns fixture licensing and provenance, the exclusion of
proprietary content, release license and notice preservation, and Reference
Snapshot attribution. It records engineering controls rather than legal advice.

## JBSA-LIC-001

Retired in specification `0.19.0`. The former rule required Apache-2.0 for
project-authored source and limited what the top-level license covered. It was
retired with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted project-license and source-boundary policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-002

Retired in specification `0.19.0`. The former rule required independent
authorship and barred copying or translating Reference Snapshot source. It was
retired with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decisions: [research-derived adaptation boundary](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted independent-only policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-003

Retired in specification `0.19.0`. The former rule fixed the `jbsa` project
identity and limited descriptive use of the BSArch name. It was retired with the
project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted attribution and naming policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

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

Retired in specification `0.19.0`. The former rule set the admission, pinning,
and audit conditions for open-source and native dependencies. It was retired
with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decisions: [resolved pure-Java/native evidence](https://github.com/evildarkarchon/jbsa/issues/4#issuecomment-5509001000), [accepted native-binary and Maven-distribution policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [accepted internal provider strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-LIC-008

Retired in specification `0.19.0`. The former rule required a provenance
inventory record for every redistributed dependency and native payload. It was
retired with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decisions: [accepted native-byte inventory policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [accepted provider-promotion gate](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-LIC-009

Release packaging **MUST** preserve every applicable license and notice text,
produce an SBOM, and inspect the final JARs, POM, application image, ZIP, native
libraries, documentation artifacts, and other published bytes against the
approved inventories. Any dependency, native payload, fixture, build residue, or
other byte without resolved authorization and provenance **MUST** fail the
release audit.

_Source decisions: [exact-byte audit constraint](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted release-byte policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-010

Retired in specification `0.19.0`. The former rule listed the repository policy
material the project had to carry. It was retired with the project's licensing
and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted policy-material set](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-011

Retired in specification `0.19.0`. The former rule required CI and release gates
to verify licensing, SBOM, fixture provenance, and release bytes. It was retired
with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decisions: [Maven-metadata limitation](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted compliance-gate policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [external-contribution gate retirement](https://github.com/evildarkarchon/jbsa/issues/62)._

## JBSA-LIC-012

Retired in specification `0.19.0`. The former rule required merges and releases
to stop on unresolved adaptation, rights, or provenance questions. It was
retired with the project's licensing and compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decisions: [identified counsel-dependent risks](https://github.com/evildarkarchon/jbsa/issues/3#issuecomment-5508964649), [accepted escalation boundary](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-013

Retired in specification `0.19.0`. The former rule fixed the project copyright
attribution line. It was retired with the project's licensing and compliance
policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted solo-maintainer and contributor policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-014

The pinned Reference Snapshot and revision **MUST** be credited in the project
README, generated documentation, reference-use policy, POM description, and
release notices. Normal CLI help and output **MUST NOT** reproduce the
Conformance Oracle banner; a short attribution in `--version` **MAY** be
included.

_Source decision: [accepted attribution and naming policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

## JBSA-LIC-015

Retired in specification `0.19.0`. The former rule barred requiring a
contributor license agreement. It was retired with the project's licensing and
compliance policy gates.

_Decision: maintainer decision recorded in specification `0.19.0`. Historical source decision: [accepted solo-maintainer and contributor policy](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724)._

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
