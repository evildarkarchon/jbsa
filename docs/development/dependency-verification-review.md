# Dependency verification review

This ledger records the independent checksum review behind every change to Gradle's strict
dependency verification. It continues
[`.scratch/migrate-maven-to-gradle/dependency-verification-review.md`](../../.scratch/migrate-maven-to-gradle/dependency-verification-review.md),
which is read-only history for the Maven-to-Gradle migration and the changes that followed it.

Each entry names the added or changed artifacts, how their checksums were reproduced outside
Gradle's dependency cache, and the SHA-256 of the bound build inputs after the change.
`GradleFoundationFilesTest` requires the current digests of `gradle/verification-metadata.xml`,
`build-logic/gradle/verification-metadata.xml`, `build-logic/gradle.lockfile`, and
`gradle/libs.versions.toml` to appear here. Digests are of the canonical LF checkout that
`.gitattributes` selects for these files, so they agree on Windows and Linux.

## Portable LZ4 provider: lz4-java 1.12.0

`JBSA-CODEC-014` adds `at.yawk.lz4:lz4-java:1.12.0` as the portable pure-Java LZ4 provider. The
library declares it at the same compile scope as LWJGL, so it appears in the `:jbsa`, `:jbsa-cli`,
and `:jbsa-dist` production locks and in the consumer POM, resolved production manifest, SBOM, and
staged CLI inputs. It is approved in `compliance/dependency-inventory.json`, and its nine bundled,
never-loaded JNI libraries are inventoried in `compliance/native-payload-inventory.json`.

| Input | SHA-256 |
| --- | --- |
| `gradle/verification-metadata.xml` | `a7ce42eb094396010bcb9df251c27a2a5e20b57ddf9400606de0275228b9e753` |
| `build-logic/gradle/verification-metadata.xml` | `8e50862798c5efdabb2c732fe2ec76ab1883f5ad130017007b7297f6bd5f619b` |
| `build-logic/gradle.lockfile` | `564d5c86d27d37c61a668fd127cc118e0d92c00b343464d30591aea6ffd9ed52` |
| `gradle/libs.versions.toml` | `8ec35d8ce6170a408698f5d18ece99b1b080a4af69110a7982c0e06cc68c1319` |

Strict resolution requested three new artifacts. Each was freshly downloaded from Maven Central
outside Gradle's dependency cache, and its recomputed SHA-256 matched the published `.sha256`
sidecar:

- the 900,750-byte JAR, `31c287041eab41f2459e93659d49162eff015a0eb858877e1c5e9dcce1a10c26`, which
  also matched its `.sha1` sidecar (`04aeb33effcf8f9e29f4a2e206c224afebd2621d`);
- the 50,413-byte POM, `52eb3192f8a84766d8db6101bdde388e70fbfcc40525d595823cbf1426930b8e`;
- the 4,391-byte Gradle module metadata,
  `98895356a2ff5aac5a07810b61b210fb9fcd1b5793f7a6d3b33f86c7e1c77357`.

The JAR's PGP signature names key `C5D9B9B0683DBD130CA4B8C7BF3FE5E70418D06D`, which
`keys.openpgp.org` did not serve, so the signature was not verified; the checksum review above is
the admission evidence. The artifact declares no runtime dependencies, so no transitive component
entered the graph. The release was built from tag `v1.12.0`
(`b98ff902baf30b591d2a62a2cfa7ef25eb6f5b00`), whose `src/lz4` submodule pins LZ4 1.10.0 at
`ebb370ca83af193212df4dcbadcc5d87bc0de2f0`, the same upstream commit as the native LWJGL adapter.

## jlink module descriptor: extra-java-module-info 1.14.2

`jlink` rejects automatic modules, and lz4-java publishes only `Automatic-Module-Name: org.lz4.java`,
so the library could not be linked into the `JBSA-DIST-005` runtime. The build-logic convention
plugins now depend on `org.gradlex:extra-java-module-info:1.14.2`. `JbsaPublicLibraryPlugin` uses it
to give lz4-java an explicit `org.lz4.java` descriptor. The descriptor exports `net.jpountz.lz4`,
`net.jpountz.util`, and `net.jpountz.xxhash` and requires `jdk.unsupported`, which matches `jdeps`.

The descriptor exists only in the `runtimeClasspath` artifact view that `:jbsa:verifyLinkableRuntime`
links against. Every other classpath has the transform deactivated. The consumer POM, the resolved
production manifest, the SBOM, and the staged CLI inputs therefore still carry the unmodified
lz4-java JAR, `31c287041eab41f2459e93659d49162eff015a0eb858877e1c5e9dcce1a10c26`. The plugin is
added to the build-logic Plugin Portal `exclusiveContent` filter, because it is not published to
Maven Central. `:jbsa` gains one `empty=` lock entry, for the plugin's `javaModulesMergeJars`
configuration.

| Input | SHA-256 |
| --- | --- |
| `gradle/verification-metadata.xml` | `c6c2adf347b6bbc9927ba78e0ea9a31e7dc6597fd38b58ab3d9973c3a27ee786` |
| `build-logic/gradle/verification-metadata.xml` | `1ecc2b2324c9645bae0152d28dbcc589d12863f27b9af230abe46e69f69d645f` |
| `build-logic/gradle.lockfile` | `61cd2a809946fdfbd1c086a4fb845fe97f1d1299626ade668fc925a5591d5981` |
| `gradle/libs.versions.toml` | `88254751303641e77f7bf7184fe5659ca43e706cd41ddd1c7539c44150585dcd` |

Strict resolution requested five artifacts, entered in both verification files. Each was freshly
downloaded outside Gradle's dependency cache, and its recomputed SHA-256 matched the entry:

- from the Gradle Plugin Portal, the 62,626-byte plugin JAR,
  `1f4859cce60a8b03f473b30b34be925d48dee842e772d3785d11f03fdd44ca30`, and its 3,759-byte Gradle
  module metadata, `75cdc59f6b6d4683fa137918ad8b7ad82079030ff53d9766d7253b6e53941137`. The Portal
  publishes no SHA-256 or SHA-512 sidecar for them, so each was checked against its `.sha1` sidecar,
  which matched;
- from Maven Central, the plugin's only runtime dependency, the 126,151-byte
  `org.ow2.asm:asm:9.10.1` JAR, `ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb`,
  and its 2,373-byte POM, `e1b1c3832ca3ac0a9e563c405f4c760118b5556a4920daee14058886197e094d`. Both
  matched their `.sha256` and `.sha1` sidecars;
- the 11,290-byte `org.ow2:ow2:1.5.1` parent POM,
  `321ddbb7ee6fe4f53dea6b4cd6db74154d6bfa42391c1f763b361b9f485acf05`. It matched its `.sha1` sidecar
  and the root file's existing entry, and it is new only to the build-logic file.

Signatures were not verified; the checksum review above is the admission evidence.
