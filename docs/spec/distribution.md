# Distribution

This specification owns assembly and verification of the Windows x64 release
image. The product boundary, reactor, released library inputs, and public
release channel remain owned by [JBSA-SCOPE-001](scope.md#jbsa-scope-001),
[JBSA-BUILD-007](modules-and-build.md#jbsa-build-007),
[JBSA-BUILD-008](modules-and-build.md#jbsa-build-008), and
[JBSA-BUILD-009](modules-and-build.md#jbsa-build-009). CLI behavior remains
owned by the [BSArch-compatible CLI specification](bsarch-cli.md), and exact
redistribution authorization remains owned by [Compliance](compliance.md).

## JBSA-DIST-001

Every release-candidate assembly **MUST** use one recorded packaging-input
identity containing the candidate version and Git commit; Windows and x64
identity; the full JDK 25 vendor, version, and build; the Maven version; the
codec-profile identifier and digest required by
[JBSA-CODEC-006](codecs.md#jbsa-codec-006); and cryptographic digests for the JDK
distribution, application modules, runtime dependencies, and native payloads.
The assembly **MUST** fail before packaging when an input is missing, mutable,
unresolved, or does not match that identity.

_Source decisions: [accepted pinned packaging flow](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [application-image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-002

The distribution assembly **MUST** consume the production JARs, flattened
consumer POM, sources JAR, Javadoc JAR, runtime dependencies, and distribution
inputs produced by one successful clean Maven reactor build of the exact
candidate identity. It **MUST NOT** substitute an artifact from another build,
local repository state, or an independently rebuilt module. The consumed
library artifacts **MUST** first satisfy the output contract in
[JBSA-BUILD-007](modules-and-build.md#jbsa-build-007).

_Source decisions: [accepted reactor and artifact seams](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669), [application-image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-003

The pinned JDK's `jdeps.exe` **MUST** derive the initial runtime-module roots
from the CLI root module and the complete application runtime module path using
this command shape, with every placeholder resolved and recorded by the build:

```text
<jdk>\bin\jdeps.exe --print-module-deps \
  --module-path <cli-library-and-complete-runtime-dependency-module-path> \
  --module io.github.evildarkarchon.jbsa.cli
```

The build **MUST NOT** suppress unresolved dependencies with
`--ignore-missing-deps`. A nonzero exit, diagnostic, unresolved module, or
missing runtime dependency **MUST** fail assembly rather than produce a partial
module seed.

_Source decisions: [accepted jdeps-first packaging chain](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [runtime-graph review acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-004

`jdeps` output **MUST** be treated as a review seed rather than proof of runtime
completeness. The release-candidate module review **MUST** record the raw output,
the canonical sorted module-root set, the resolved transitive closure, and a
rationale and evidence owner for every explicit addition required by reflection,
service loading, resources, native providers, or other dynamic behavior. The
build **MUST** compare both roots and closure with the previously approved
candidate or release and **MUST** fail on any unexplained addition, removal, or
identity change.

_Source decisions: [accepted runtime-module review](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [runtime-graph review acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-005

The pinned JDK's `jlink.exe` **MUST** create the candidate runtime from the
reviewed module roots with ZIP compression using this command shape:

```text
<jdk>\bin\jlink.exe \
  --module-path <jdk-jmods-and-reviewed-application-module-path> \
  --add-modules <reviewed-comma-separated-module-roots> \
  --compress=2 \
  --output <linked-runtime>
```

Any additional `jlink` option **MUST** be part of the packaging-input identity.
After linking, the runtime's `java.exe --list-modules` result **MUST** equal the
reviewed resolved closure, and the image **MUST NOT** proceed to `jpackage` when
the module list, runtime metadata, or tool exit differs from the recorded
expectation.

_Source decisions: [accepted jlink packaging step](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [linked-runtime acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-006

The pinned JDK's `jpackage.exe` **MUST** create the console application image
from that exact linked runtime using this command shape:

```text
<jdk>\bin\jpackage.exe \
  --type app-image \
  --win-console \
  --runtime-image <linked-runtime> \
  --module-path <cli-library-and-complete-runtime-dependency-module-path> \
  --module io.github.evildarkarchon.jbsa.cli/<main-class> \
  --name jbsa \
  --app-version <candidate-version> \
  --dest <application-image-output>
```

Every additional launcher or JVM option, including applicable native-access
configuration required by
[JBSA-CODEC-011](codecs.md#jbsa-codec-011), **MUST** be explicit in the recorded
command and packaging-input identity. `jpackage` **MUST** consume the already
linked runtime and **MUST NOT** create or select a different runtime implicitly.

_Source decisions: [accepted jpackage application-image step](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [application-image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-007

After `jpackage` and before any image qualification, the assembly **MUST** set
exactly one effective `win.norestart=true` key in the generated
`app\jbsa.cfg` `[Application]` section and then parse the resulting file to
verify the effective value. A missing section, conflicting or duplicate key,
unreadable configuration, or launcher configuration not attributable to the
pinned JDK **MUST** fail packaging. The assembly **MUST NOT** represent
`win.norestart` as a public `jpackage` option or a user-configurable JBSA
setting.

_Source decisions: [accepted win.norestart configuration](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [single-process image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-008

The postconfigured application image **MUST** contain `jbsa.exe`, the generated
`app\jbsa.cfg`, the modular CLI and library, every required runtime dependency,
the exact linked runtime, every approved required Windows x64 native payload,
and applicable license and notice texts. Its recursive inventory **MUST** map
each file to a recorded build input, JDK component, generated launcher output,
or compliance-approved document. It **MUST NOT** contain `jbsa.cmd`, a full
development JDK, Maven, test or benchmark modules, fixtures, the Conformance
Oracle, local game material, build caches, credentials, debug residue, or any
other unaccounted byte.

This requirement refines [JBSA-BUILD-008](modules-and-build.md#jbsa-build-008),
[JBSA-LIC-005](compliance.md#jbsa-lic-005), and
[JBSA-LIC-009](compliance.md#jbsa-lic-009) without changing their artifact and
authorization rules.

_Source decisions: [accepted self-contained image and launcher supersession](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5521260730), [accepted Windows release package](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [image-content acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-009

Qualification of the postconfigured image **MUST** invoke `jbsa.exe` directly
and exercise every CLI operation and both supported compatibility modes through
the packaged modules. It **MUST** verify argument forwarding, arguments and
paths containing spaces and non-ASCII text, process working-directory behavior,
interactive and redirected UTF-8 standard streams, documented exit statuses,
all required runtime modules, and native loading for every codec present in the
release profile. Expected behavior **MUST** come from the
[BSArch-compatible CLI specification](bsarch-cli.md) and applicable
[conformance-v1 cases](conformance-v1.md), not from build-tree launcher output.

_Source decisions: [accepted packaged-image gates](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [packaged behavior acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-010

Packaged process qualification **MUST** demonstrate that one invocation of
`jbsa.exe` hosts the JVM in that same application process and creates no
application-launcher child process. A long-running mutation operation **MUST**
demonstrate that the first Ctrl+C reaches that process, requests the Cooperative
Cancellation behavior owned by the CLI and
[JBSA-OPS-009](operation-semantics.md#jbsa-ops-009), and allows publication,
rollback, and cleanup to settle before exit. Repeated Ctrl+C **MUST** be checked
against the CLI contract and **MUST NOT** rely on launcher self-restart or a
second process to obtain a stronger outcome.

_Source decisions: [accepted single-process and Ctrl+C contract](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [single-process image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-011

The canonical `jbsa-cli-<version>-windows-x64.zip` **MUST** contain exactly one
top-level `jbsa\` directory holding the verified postconfigured application
image. ZIP entry names **MUST** be relative, use `/` separators, and be unique
under case-insensitive Windows comparison; they **MUST NOT** be absolute,
traversing, symbolic-link, or alternate-data-stream entries. Extracting the ZIP
on the qualified platform **MUST** reproduce the verified image file inventory
and SHA-256 digest of every regular file. No image byte **MAY** change between
image qualification and ZIP creation.

_Source decisions: [accepted canonical self-contained ZIP](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5521260730), [accepted application-image ZIP](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [ZIP assembly acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-012

The complete release-candidate artifact set **MUST** have a machine-readable
manifest that names and SHA-256 hashes the ZIP, every library input owned by
[JBSA-BUILD-007](modules-and-build.md#jbsa-build-007), and every checksum,
license, notice, SBOM, provenance, and evidence asset owned by
[JBSA-BUILD-009](modules-and-build.md#jbsa-build-009), except for that manifest
and the human-readable checksum file defined below. The checksum file **MUST**
use lowercase hexadecimal SHA-256, one relative asset name per line, and stable
lexical asset-name order. The manifest and checksum file **MUST** omit themselves
and each other. The Packaging Gate **MUST** record and verify their final SHA-256
digests as detached gate evidence outside the public candidate asset set. Every
listed hash **MUST** be verified from the final bytes, and every other public
candidate asset **MUST** appear exactly once in both inventories.

_Source decisions: [accepted release metadata set](https://github.com/evildarkarchon/jbsa/issues/8#issuecomment-5518106669), [accepted retained release metadata](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [artifact-set acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-013

Final artifact verification **MUST** reopen and structurally inspect every JAR,
POM, application-image file, ZIP entry, native library, license, notice, SBOM,
provenance record, and checksum artifact rather than trusting successful tool
exit alone. It **MUST** verify the production JPMS descriptors and module path,
standalone consumer POM resolution, expected source and Javadoc classifiers,
runtime and application inventories, ZIP extraction parity, internal manifest
identities, and cross-artifact version and digest agreement. Any malformed,
missing, duplicated, stale, unapproved, or unexplained content **MUST** fail the
candidate.

_Source decisions: [accepted release-byte inspection](https://github.com/evildarkarchon/jbsa/issues/7#issuecomment-5517829724), [accepted image verification](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [artifact verification acceptance](https://github.com/evildarkarchon/jbsa/issues/53), [exact-byte audit acceptance](https://github.com/evildarkarchon/jbsa/issues/57)._

## JBSA-DIST-014

A clean-machine smoke qualification **MUST** extract the final ZIP on Windows 11
x64 with NTFS, with no installed JDK, Maven, build tree, local dependency cache,
or developer native-library path available to the application. It **MUST** run
help, version, archive information, list, dump, pack, and unpack; cover every
runtime codec capability in the selected release profile; verify representative
success, usage error, operational failure, and Cooperative Cancellation exits;
and fail on a missing module, missing native payload, external Java dependency,
unexpected child launcher, or lookup outside the extracted image.

_Source decisions: [accepted packaged-image and clean-machine gates](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [clean-machine acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-DIST-015

Distribution evidence **MUST** identify the exact input manifest, commands,
module review, image inventory, ZIP inventory, artifact manifest, environment,
test selectors, process-tree observation, and resulting digests. A change to the
JDK vendor/build, packaging command, runtime-module roots or closure, codec or
native profile, application byte, launcher configuration, ZIP byte, or release
asset **MUST** invalidate the affected image, ZIP, and artifact verification and
require a rebuilt candidate; an unexplained change **MUST NOT** be accepted by
updating expected evidence.

_Source decisions: [accepted toolchain requalification and module-delta policy](https://github.com/evildarkarchon/jbsa/issues/16#issuecomment-5521258247), [reproducible audit acceptance](https://github.com/evildarkarchon/jbsa/issues/57)._
