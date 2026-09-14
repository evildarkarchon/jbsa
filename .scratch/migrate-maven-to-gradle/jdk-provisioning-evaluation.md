# Gradle-managed JDK provisioning evaluation

Evaluated on 2026-09-13 after the Gradle cutover. Developer builds now use the pinned Foojay resolver to provision an Adoptium Java 25 toolchain when no matching installation exists. Windows and Linux/WSL qualification remain separate exact-archive boundaries.

## Decision

Enable `org.gradle.toolchains.foojay-resolver-convention` version `1.0.0` in the product and included builds. Java tasks request Java 25 from `JvmVendorSpec.ADOPTIUM`; Gradle itself may run on Java 17 or newer, which lets a contributor start the wrapper without first installing Java 25.

Adopt the current Eclipse Temurin `25.0.4.1+1` release for hosted and local qualification. Because Gradle 9.7.1's toolchain resolver API returns only a download URI, managed developer provisioning does not replace qualification input verification. Qualification and reproducibility must obtain the platform archive independently, compare it with the reviewed SHA-256 before extraction, and then select that exact JDK.

| Platform | Exact archive | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Windows x64 | `OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip` | 141,167,264 | `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283` |
| Linux/WSL x64 | `OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz` | 141,329,719 | `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e` |

The two digests were read from the official Adoptium API for release `jdk-25.0.4.1+1`. Historical parity and acceptance reports remain unchanged and continue to describe the older JDK that produced those immutable results.

## Resolver design and limitations

The [Foojay resolver documentation](https://github.com/gradle/foojay-toolchains) says selection uses the requested major version, OS, architecture, and vendor, considers only the latest minor version, and prefers a JDK over a JRE. The repository therefore pins the resolver plugin and the Adoptium vendor, while hosted qualification pins the full `25.0.4.1+1` version independently.

The Gradle 9.7.1 [`JavaToolchainDownload`](https://docs.gradle.org/9.7.1/userguide/toolchain_plugins.html) response contains only a download URI. The downloaded JDK archive is outside dependency verification, so `--dependency-verification strict` does not compare it with a repository-declared SHA-256. This is an accepted convenience boundary for developer builds, not permission to use mutable resolver output for qualification, reproducibility, WSL portability evidence, or release artifacts.

Windows and WSL do not share a managed JDK installation. Gradle resolves the platform-specific archive into each environment's own Gradle user home. A Windows-primed cache therefore does not satisfy a fresh WSL offline run; each platform cache must be primed independently, and exact evidence runs must still use the separately verified archive above.

## Isolated plugin review

The prototype declared only `gradlePluginPortal()` and an exact plugin version. `--write-verification-metadata sha256` resolved these artifacts:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `org.gradle.toolchains:foojay-resolver:1.0.0` JAR | 327,154 | `78b86a47dfdf7697c9bd15da78983fd80da7247d6e02fc106bdf07e0388b60a8` |
| `org.gradle.toolchains:foojay-resolver:1.0.0` module metadata | 2,813 | `6190cf0e42e664c11e1ac0a785699ab02b549cea34392c679f36d8a2c550fcb9` |
| `org.gradle.toolchains.foojay-resolver-convention` `1.0.0` marker POM | 715 | `f133249a18754ae6a1d2701d00d849b1afba7b0683ddb5fc1b18a70071cec2fa` |

Strict verification accepted the plugin artifacts. `--write-locks` left the settings lockfiles empty because the plugin DSL's exact version, rather than a lock entry, pins the settings plugin. The plugin artifacts and their checksums are committed in both builds' verification metadata; no other repository is added.

## Developer and cache observations

Measurements used Gradle 9.7.1 on Windows x64, an isolated Gradle user home, an Oracle Java 26 launcher, local JDK detection disabled, and a Java 25 Adoptium toolchain request. They are engineering observations, not performance claims.

| Observation | Result |
| --- | --- |
| First plugin resolution and toolchain provisioning | 15.297 seconds |
| Isolated Gradle user-home footprint after the first run | 447,214,827 bytes |
| Downloaded JDK archive | 141,167,264 bytes |
| Managed `jdks/` cache including archive and extraction | 444,640,693 bytes |
| Warm resolver invocation | 3.492 seconds |
| Primed-cache offline invocation | Passed in approximately 3 seconds |
| Fresh-cache offline invocation | Failed because the settings plugin was unavailable |
| Configuration-cache first invocation | Passed and stored an entry in approximately 3 seconds |
| Configuration-cache second invocation | Passed and reused the entry in approximately 2 seconds |

Provisioning removes the manual Java 25 installation step and reuses the managed JDK effectively after the first run. It adds roughly 424 MiB per platform-specific Gradle user home and still requires network access to prime both plugin and JDK bytes.

The WSL probe started from Ubuntu OpenJDK `25.0.4+7`, which does not satisfy the Adoptium vendor
request. Gradle provisioned Temurin `25.0.4.1+1` into the Linux Gradle user home, compiled the public
library with it, and retained the 141,329,719-byte tarball whose SHA-256 exactly matched
`dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`. The managed Linux `jdks/`
directory occupied 457,851,941 bytes. The complete WSL portability sequence subsequently passed.

## Gate results

The enabled design was checked after implementation:

- Focused foundation and Gradle-runtime policy tests: passed.
- Real multi-project build-policy tests: passed in 1 minute 52 seconds.
- Windows managed provisioning from Oracle Java 26: compiled with managed Temurin `25.0.4.1+1`.
- WSL/Linux managed provisioning: compiled with managed Temurin `25.0.4.1+1`; archive checksum matched.
- WSL/Linux compile, formatting, 80 build-logic/TestKit tests, unit, and integration gates: passed.
- Hosted Windows and Linux policy: downloads the named platform archive, checks the reviewed SHA-256,
  then installs that exact file through `actions/setup-java`'s `jdkfile` mode.
- Primed strict-verification offline `clean verify`: passed in 2 minutes 54 seconds.
- Compatible configuration-cache tasks: stored in 25 seconds and reused in 3 seconds offline.
- Reproducible canonical artifacts: all five retained artifact hashes matched across two clean builds.
- Complete Windows `clean verify`: passed in 2 minutes 46 seconds with all 63 scheduled tasks successful.
