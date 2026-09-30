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
