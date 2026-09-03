# Modules and build

This specification owns the Maven reactor, Java/JPMS seams, dependency exposure,
and build outputs. The Java and operating-system qualification baseline is owned
by [JBSA-SCOPE-001](scope.md#jbsa-scope-001). Detailed launcher behavior and
release qualification are owned by the distribution and release-gate
specifications.

## JBSA-BUILD-001

The Maven build **MUST** use one single-version reactor with exactly the following
baseline projects and roles:

| Reactor project | Packaging and role |
| --- | --- |
| `jbsa-parent` | Root `pom` aggregator and inherited build policy; build-only and not released. |
| `jbsa` | The one deep archive-library JAR and the only supported Java library interface. |
| `jbsa-cli` | Thin CLI consumer JAR containing argument parsing, presentation, process-exit handling, and launch code. |
| `jbsa-test-support` | Build-only shared corpus, Conformance Oracle, fixture, and validator support; neither a product interface nor a release artifact. |
| `jbsa-conformance-tests` | Build-only black-box conformance suite exercising the public `jbsa` interface. |
| `jbsa-benchmarks` | Build-only benchmark suite. |
| `jbsa-dist` | Build-only assembly project producing the Windows x64 distribution. |

_Source decision: [accepted reactor and artifact seams](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-002

All reactor projects **MUST** use one version and the group
`io.github.evildarkarchon`. The production coordinates and JPMS module identities
**MUST** be:

| Maven coordinate | JPMS module |
| --- | --- |
| `io.github.evildarkarchon:jbsa` | `io.github.evildarkarchon.jbsa` |
| `io.github.evildarkarchon:jbsa-cli` | `io.github.evildarkarchon.jbsa.cli` |

_Source decision: [accepted coordinates and module identities](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-003

The build **MUST** keep archive behavior in the single deep `jbsa` artifact and
**MUST NOT** create modules per Archive Family or codec. The initial release
**MUST NOT** expose a provider SPI or create a provider artifact. A future
provider module requires an explicit specification change establishing a real
replaceable seam; a placeholder module is prohibited.

_Source decisions: [accepted module-depth rule](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669), [accepted internal codec/provider strategy](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971)._

## JBSA-BUILD-004

Both production JARs **MUST** contain explicit `module-info.java` descriptors and
remain usable on the class path. The library module **MUST** export
`io.github.evildarkarchon.jbsa` and only public subpackages justified by the
library-interface specification; implementation packages under
`io.github.evildarkarchon.jbsa.internal.*` **MUST NOT** be exported. The CLI
module **MUST NOT** export packages, and the packaged launcher **MUST** use the
module path.

_Source decision: [accepted JPMS and package policy](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-005

`jbsa-cli` **MUST** depend only on the exported `jbsa` interface for archive
operations. CLI code **MUST NOT** access internal parsers, codecs, providers,
schedulers, storage ports, or implementation metadata. Cross-module test-support
types **MUST** remain build-internal and carry no compatibility promise for
library consumers.

_Source decisions: [accepted consumer and test-support seams](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669), [accepted CLI public-library seam](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-BUILD-006

Third-party types **MUST NOT** appear in exported library interfaces. Maven POMs
**MUST** declare actual dependencies accurately, while JPMS descriptors
**SHOULD** use ordinary non-transitive `requires` by default. `requires
transitive` **MAY** be used only when a dependency is deliberately part of the
public interface. Maven `optional` **MAY** be used only for a truly optional
feature and **MUST NOT** conceal a required implementation dependency.

_Source decision: [accepted dependency-exposure policy](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-007

Source child POMs **MUST** inherit build policy from `jbsa-parent`. Because that
parent is not released, the release build **MUST** emit a flattened,
self-contained consumer POM for `jbsa` with accurate dependency metadata. The
released library inputs **MUST** include the `jbsa` binary JAR, flattened POM,
sources JAR, and Javadoc JAR. Shared POM metadata **MUST** record the project
name, a description crediting the pinned Reference Snapshot, project URL,
Apache-2.0 license, developer, SCM, Java 25 release, and accurate dependencies.

_Source decision: [accepted parent, flattened-POM, and library-artifact policy](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-008

`jbsa-dist` **MUST** produce the canonical
`jbsa-cli-<version>-windows-x64.zip` as a self-contained, portable,
single-process Windows application image with `jbsa.exe` and a compressed JDK 25
runtime built by `jlink` and `jpackage`. It **MUST NOT** require a separately
installed Java runtime. `jbsa.cmd` **MAY** exist only as an unsupported
build-tree testing convenience and **MUST NOT** be shipped as the release
launcher.

_Source decisions: [explicit partial supersession of the original ZIP](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5521260730), [accepted self-contained packaging decision](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247)._

## JBSA-BUILD-009

Public build outputs **MUST** be anonymous GitHub Release assets rather than a
Maven repository. Each release input set **MUST** contain the Windows CLI ZIP,
the four library artifacts named by [JBSA-BUILD-007](#jbsa-build-007), SHA-256
checksums, applicable project license and notice files, audited third-party
notices, an SBOM, and provenance metadata. The thin CLI JAR **MUST NOT** be
advertised as a standalone asset. Maven Central and GitHub Packages publication,
Maven deployment metadata, and detached PGP signatures are outside this
specification set.

_Source decision: [accepted anonymous-release and release-metadata policy](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669)._

## JBSA-BUILD-010

The source build **MUST** expose entry points for its applicable automated
evidence, and hosted Windows CI **MUST** invoke those entry points without
claiming local Release Qualification.

_Source decisions: [accepted hosted conformance boundary](https://github.com/evildarkarchon/jbsa/issues/10#issuecomment-5518347093), [accepted foundation and evidence-gate sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## Deferred build inputs

The exact Maven plugin versions, audited dependency inventory entries, native
payload identities, runtime-module set, `jpackage` configuration, and JDK 25
vendor/build identity are intentionally not selected here. They remain inputs
to their owning implementation and distribution requirements and cannot be
inferred from the module layout.
