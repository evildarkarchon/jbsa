# Windows x64 LZ4 runtime qualification

Ticket #43 owns the internal codec runtime and its launch inputs. Final linked
JRE, jpackage image, ZIP, and complete application-image acceptance remain #53.
The staging directory is a developer qualification bundle, not a release image.

## Permanent requirements and evidence

This implementation traces to `docs/spec/requirements.yaml` in specification
0.13.0. It does not change that registry's accepted specification-set identity.

| Requirements | Ticket #43 evidence |
| --- | --- |
| JBSA-CODEC-001, 002, 005, 006 | Non-exported LWJGL adapters; immutable `jbsa-lz4-v1` manifest and SHA-256 sidecar; existing public module consumer and architecture gates |
| JBSA-CODEC-008, 010 | Streaming frame windows, checked raw provider/dispatch limits, complete pre-allocation resource reservations, confined per-call arenas and closed native contexts |
| JBSA-CODEC-009, 011, 012 | Lazy capability preflight, Java 25 launch subprocesses, stable failure fields, runtime artifact and launch-policy staging |
| JBSA-CODEC-013 | Independent wire vectors, malformed data, deterministic output and exact round trips; targeted measured qualification packet |
| JBSA-PERF-001–003, 005–013 | Supplemental provider dispatch measurements and retained profile/corpus identities; full archive Performance-v1 cases remain owned by their Archive Family and release qualification tickets |
| JBSA-LIC-004–011 | Exact JAR/DLL inventories, upstream revisions and full BSD texts, notices, SBOM reconciliation, staging and compliance regression gates |

The [targeted qualification packet](../reviews/issue43-lz4-runtime/targeted-qualification.md)
records the measured dispatch range, resource accounting and cancellation gaps.
Raw HC admits up to 16 MiB decoded input plus its worst-case compressed expansion;
it never splits an unsplittable block. Frames use 64 KiB input/output steps and
admit upstream frame blocks through 4 MiB. Internal callers must provide a
`ResourceBudget` with the declared capacity; exhausted capacity is a policy
failure before source or destination callbacks. Existing Archive Family slices
continue enforcing their own codec matrix; this ticket introduces no new public
codec choice or family support claim.

The qualified profile is `jbsa-lz4-v1`, with manifest SHA-256
`f7221b24458804a454716fb89bbad9f6b3947f8484158e3950e1608e93b4732e`.
The codec, launch, resource, and throughput evidence is recorded under
`docs/reviews/issue43-lz4-runtime`. Inventory approval covers those exact runtime
bytes; the containing ZIP identity reserves the eventual package name and does
not claim that final application-image qualification has already passed.

`compliance/dependency-inventory.json` pins the LWJGL 3.4.3 core and LZ4 binding
JARs and both `natives-windows` classifiers. The native inventory additionally
pins each uncompressed DLL and records upstream LZ4 1.10.0 provenance. Native
JARs remain separate from the thin library JAR. `stage-release-inputs.ps1`
consumes reactor-resolved dependencies under `jbsa-dist/target/runtime-dependencies`,
checks each inventory approval and SHA-256 before replacing staging, and records
the exact coordinates and hashes in the release-input manifest. The existing
compliance verifier recursively audits those JARs and their native contents.

The upstream license texts are retained verbatim in
`compliance/licenses/LWJGL-3.4.3.txt` and `compliance/licenses/LZ4-1.10.0.txt` and
copied under `licenses/` in the bundle. They were fetched from immutable source
commits `30fac9b95f99cda97312232be25ba55297bf9951` and
`ebb370ca83af193212df4dcbadcc5d87bc0de2f0`, respectively. The generated third-party
notice inventory supplements these full copyright, permission, and disclaimer
texts. jlibdeflate and Airlift are not promoted by this ticket.

After a successful reactor verification, launch the staged CLI with PowerShell 7:

```powershell
pwsh -NoProfile -File jbsa-dist/target/release-inputs/jbsa.ps1 -JavaHome C:/OpenJDK/jdk-25
pwsh -NoProfile -File jbsa-dist/target/release-inputs/jbsa.ps1 -JavaHome C:/OpenJDK/jdk-25 -ClassPath
```

The default launch grants native access to `io.github.evildarkarchon.jbsa`
(Windows filesystem operations), `org.lwjgl`, and `org.lwjgl.lz4`. It explicitly
resolves `org.lwjgl.natives` and `org.lwjgl.lz4.natives`: the Java bindings do not
require those resource-only modules. The classpath variant grants `ALL-UNNAMED`.
Both use `--illegal-native-access=deny` and validate Windows x64, Java 25, and
the four runtime JAR hashes before invoking the CLI. Arguments are passed as an
argument array. `launch-policy.json` records the exact runtime bytes.

Maven embedders must provide the same runtime artifacts and native-access grants.
JBSA must never modify host-process native policy. Providers initialize lazily
when an applicable codec operation first needs LZ4, extract their bundled JAR
resources, and retain native libraries for process lifetime. Public callers have
no DLL path or provider selector. Missing grants or unavailable native support
are capability failures; stored and applicable zlib operations remain usable.

Java 25 ordinarily warns and continues without native grants, so the adapter
preflight also checks `Module.isNativeAccessEnabled()` rather than relying on
that default. See [Java 25 native access](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html)
and the [pinned LWJGL loader](https://github.com/LWJGL/lwjgl3/blob/30fac9b95f99cda97312232be25ba55297bf9951/modules/lwjgl/lz4/src/generated/java/org/lwjgl/util/lz4/LibLZ4.java).

`build/test-release-staging.ps1` verifies runtime byte accounting, deterministic
restaging, stale removal, missing inputs, and fail-before-replacement for a
tampered runtime. Codec and native-access subprocess qualification additionally
exercise actual LZ4 operations; merely displaying CLI help does not initialize
the lazy codec and therefore is not codec-loading evidence.
