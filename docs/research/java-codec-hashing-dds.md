# Java codec, hashing, and DDS implementation research

Date: 2026-09-02
Scope: Java 25, Maven, Windows 11 x64; the pinned `TES5Edit` tree was inspected read-only.

## Decision

Use an internal provider boundary and pin the following implementation stack:

| Capability | Selected implementation | Status |
| --- | --- | --- |
| zlib decode/encode | `com.fulcrumgenomics:jlibdeflate:0.1.0`, backed by libdeflate 1.25; `java.util.zip` fallback | Provisional native preference, gated by corpus benchmarks and native-loading tests |
| LZ4 raw HC and LZ4 frame HC | `org.lwjgl:lwjgl-lz4:3.4.3` plus LWJGL core and the `natives-windows` classifiers | Preferred; it bundles the same upstream LZ4 1.10.0 used by the Reference Snapshot |
| XXH32/XXH64 | `io.airlift:aircompressor-v3:3.7` pure-Java hashers | Preferred; no native code is needed on Windows |
| DDS | Small repository-owned, pure-Java header/parser/writer with opaque mip payloads | Preferred; no evaluated Java dependency covers both the required writer and Bethesda/Xbox metadata |

This keeps native code out of the public archive API. The library can select providers internally, expose diagnostics about the selected provider, and retain pure-Java fallbacks. Native code is justified for exact current LZ4 HC behavior and likely for the zlib performance objective, but not for hashing or ordinary DDS header handling.

## What the Reference Snapshot actually requires

The pinned Reference Snapshot is [`TES5Edit` commit `fd1e360`](https://github.com/TES5Edit/TES5Edit/tree/fd1e36020b2b5b6217e553dc0038983146a2e2dd). Its implementation establishes more specific requirements than the names “zlib” and “LZ4” imply:

- zlib means the **RFC 1950 zlib wrapper**, not raw DEFLATE. For in-memory inputs up to 8 MiB, the reference calls `libdeflate_zlib_compress` at level 12; above that threshold it calls zlib at level 9. Decompression uses `libdeflate_zlib_decompress`. See [`wbCompression.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas) and its pinned [`libdeflate-pas` dependency](https://github.com/ElminsterAU/libdeflate-pas/tree/c044fe1a7b0e2e9930c6a5110c7bd194a4872c91), whose build scripts select libdeflate v1.24.
- LZ4 archive blocks are raw blocks produced with `LZ4_compress_HC` at level 12 and decoded with `LZ4_decompress_safe`. LZ4 frames use compression level 12, independent 4 MiB blocks, `autoFlush = 1`, and no content checksum. The reference's pinned [`lz4-delphi` dependency](https://github.com/ElminsterAU/lz4-delphi/tree/6d6244eb768797c1a5aa6346848e6ee68d096e0f) identifies its upstream version as LZ4 1.10.0.
- XXH64 with seed zero is used as an internal lookup/deduplication hash; XXH32 and XXH64 wrappers are also present. These are the classic XXH32/XXH64 algorithms, not XXH3. See [`wbHash.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas). The pinned Delphi dependency carries xxHash 0.6.5, but the classic digest is platform-independent and stable by specification.
- DDS work is primarily container work: validate the magic/basic/DX10/XBOX headers, derive legacy or DXGI format metadata, determine bits per pixel and mip sizes, convert the unsupported legacy 24-bit RGB case to 32-bit BGRX, split/rejoin opaque mip payloads, and synthesize a header during BA2 extraction. It does not require decoding BC texture pixels. See [`wbDDS.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas) and the pack/unpack paths in [`wbBSArchive.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas).

Those distinctions rule out several otherwise attractive dependencies.

## zlib and libdeflate

### Preferred native provider: jlibdeflate 0.1.0

[`com.fulcrumgenomics:jlibdeflate:0.1.0`](https://central.sonatype.com/artifact/com.fulcrumgenomics/jlibdeflate/0.1.0) is the best ready-made Windows binding found. Its [project documentation](https://github.com/fulcrumgenomics/jlibdeflate/tree/v0.1.0) states and its source implements:

- raw DEFLATE, zlib, and gzip APIs;
- compression levels 0 through 12;
- Java 11 or newer, so Java 25 is within its supported bytecode/runtime envelope;
- bundled Windows x86-64, Linux, and macOS native libraries in one JAR;
- byte-array and direct-`ByteBuffer` APIs, `AutoCloseable` compressor/decompressor lifetimes, and one instance per thread;
- libdeflate v1.25.

The upstream libdeflate project now lists `jlibdeflate` among its Java bindings, while cautioning users to verify that bindings carry a current libdeflate release. See the [official libdeflate project](https://github.com/ebiggers/libdeflate). Both the binding and libdeflate use the MIT license; Maven Central reports no runtime dependencies for the binding artifact.

The fit is close but not byte-identical by construction: the Reference Snapshot pins libdeflate 1.24 while jlibdeflate bundles 1.25. The [official libdeflate API](https://github.com/ebiggers/libdeflate/blob/v1.25/libdeflate.h) explicitly says that different library versions may produce different valid compressed bytes for the same input and level. Therefore:

- use decompressed-byte equivalence as the normal conformance assertion;
- pin `jlibdeflate` and record its bundled libdeflate version in benchmark/golden metadata;
- if a particular golden case truly requires byte-for-byte libdeflate 1.24 output, add a narrowly scoped provider built against 1.24 instead of weakening the general conformance rule.

The project is unusually new: Maven Central has only release 0.1.0. It is promising rather than battle-hardened. Keep it behind the provider boundary and require malformed-input, parallel-use, repeated-load, temp-directory, antivirus/file-lock, and shutdown tests before making it the unconditional default.

### JDK-only fallback

Java 25's [`Deflater`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/zip/Deflater.html) and `Inflater` are the correct fallback. The JDK API produces the zlib wrapper when `nowrap` is false, accepts levels 0–9, consumes arrays or `ByteBuffer`s incrementally, and reports byte totals as `long`. It therefore supports a single logical zlib stream larger than a Java array and requires no external dependency or native-access permission from the application.

It cannot reproduce libdeflate level 12's ratio or output. It is nevertheless fully suitable for decoding, for a no-native runtime profile, and for very large entries that exceed a whole-buffer binding's `int`-indexed API. It should also be the fallback when the native DLL cannot load.

### Performance evidence and limit

The [libdeflate project](https://github.com/ebiggers/libdeflate) describes itself as a whole-buffer implementation that is substantially faster than zlib, especially on x86 and ARM, and supplies its own `benchmark` program. The jlibdeflate binding uses critical array access or direct-buffer addresses to avoid copying, but its public lengths and `ByteBuffer.remaining()` values are `int`; a single call is therefore below 2 GiB and must materialize the complete input and output. Use a pool of explicitly closed compressor instances, one per worker, and direct buffers for concurrent workloads.

The reference's 8 MiB libdeflate/zlib crossover is evidence, not a permanent Java threshold. Mirror it initially for differential testing, then retain or change it only from JMH/corpus measurements on the target Windows hardware.

## LZ4 raw blocks, HC, and frames

### Preferred provider: LWJGL LZ4 3.4.3

Use these Maven artifacts, all at exactly 3.4.3:

```xml
<dependency>
  <groupId>org.lwjgl</groupId>
  <artifactId>lwjgl</artifactId>
  <version>3.4.3</version>
</dependency>
<dependency>
  <groupId>org.lwjgl</groupId>
  <artifactId>lwjgl-lz4</artifactId>
  <version>3.4.3</version>
</dependency>
<dependency>
  <groupId>org.lwjgl</groupId>
  <artifactId>lwjgl</artifactId>
  <version>3.4.3</version>
  <classifier>natives-windows</classifier>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>org.lwjgl</groupId>
  <artifactId>lwjgl-lz4</artifactId>
  <version>3.4.3</version>
  <classifier>natives-windows</classifier>
  <scope>runtime</scope>
</dependency>
```

Maven Central's [LWJGL BOM metadata](https://central.sonatype.com/artifact/org.lwjgl/lwjgl-bom) includes the LZ4 binding and Windows classifiers. Release 3.4.3 is current, and [LWJGL documents Java 25 FFM support](https://github.com/LWJGL/lwjgl3) beginning with 3.4.0. Its generated [LZ4 API](https://javadoc.lwjgl.org/org/lwjgl/util/lz4/package-summary.html) exposes raw compression/decompression, `LZ4_compress_HC`, compression levels, LZ4F preference structs, one-shot frames, and streaming frame contexts. Inspection of the 3.4.3 source and a runtime probe both reported upstream LZ4 **1.10.0**, exactly matching the Reference Snapshot.

This is the only current, Maven-published candidate evaluated that combines all of the following:

- Windows x86-64 native packaging;
- raw `LZ4_compress_HC` level 12 and `LZ4_decompress_safe`;
- `LZ4F_preferences_t` fields needed to reproduce independent 4 MiB blocks, level 12, and auto-flush;
- one-shot and streaming frame APIs;
- the same upstream LZ4 version as the reference.

Direct buffers are required. Raw LZ4 uses C `int` sizes and the upstream maximum input is about 2.11 GiB (`0x7E000000`). LZ4F exposes `size_t` and streaming contexts, so arbitrarily large logical frame streams can be processed through bounded buffers. Archive-level code must never cast an unchecked file size to `int`.

The [official LZ4 project](https://github.com/lz4/lz4) reports that normal LZ4 prioritizes compression speed, while LZ4 HC trades compression speed for ratio and shares the same fast decoder. Its published benchmark is only directional—it is not evidence for this application's corpus or JVM boundary overhead. Run project-owned JMH and end-to-end archive benchmarks.

LWJGL is BSD-3-Clause and its bundled LZ4 is BSD-2-Clause. Redistributions must retain both notices.

### Pure-Java candidates and why they are not the primary writer

[`io.airlift:aircompressor-v3:3.7`](https://central.sonatype.com/artifact/io.airlift/aircompressor-v3/3.7) is actively maintained, compiled for Java 25, Apache-2.0, and provides pure-Java raw LZ4 plus a fixed independent-4-MiB LZ4 frame implementation. However, its compressor implements the fast raw algorithm, not `LZ4_compress_HC`, and its native loader packages only Linux/macOS libraries. Its Deflate classes use **raw** DEFLATE (`nowrap = true` or libdeflate's raw API), so they are not a substitute for the reference zlib wrapper. See the [3.7 source](https://github.com/airlift/aircompressor/tree/3.7).

It remains useful as a pure-Java LZ4 decoder/fallback, but output from its writer should be tested for semantic interoperability rather than reference-byte identity.

[`org.lz4:lz4-java:1.8.0`](https://github.com/lz4/lz4-java/releases/tag/1.8.0) has pure-Java HC, frame, XXH32, and XXH64 implementations and used to be the obvious all-Java choice. It embeds upstream LZ4 1.9.3, was built for old Java levels, and the repository was archived on 2025-12-02. Do not adopt an archived codec as the primary implementation for a new Java 25 library.

[`org.apache.commons:commons-compress:1.28.0`](https://central.sonatype.com/artifact/org.apache.commons/commons-compress/1.28.0) is maintained and Apache-2.0. Its [LZ4 package](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/lz4/package-summary.html) supports raw blocks and frames, and its default frame parameters happen to be independent 4 MiB blocks, but it has no `LZ4_compress_HC(level)` equivalent. It is a sound interoperability oracle, not the performance/reference writer.

## xxHash

Use Airlift's pure-Java `XxHash32Hasher` and `XxHash64Hasher` from `io.airlift:aircompressor-v3:3.7`. The [project documentation](https://github.com/airlift/aircompressor/tree/3.7) exposes seeded one-shot and streaming APIs for both classic variants and states a Java 22+ little-endian requirement; release 3.7 itself is built for Java 25. On Windows its unsupported native path cleanly falls back to the Java implementation.

The [official xxHash specification](https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md) requires a given XXH32 or XXH64 variant to produce identical results across CPU, OS, width, and endianness, and permits arbitrary-length streaming input. The [official project](https://github.com/Cyan4973/xxHash) publishes much higher native benchmark figures, but those do not justify JNI/FFM overhead for this use. Measure the Java implementation in the archive deduplication workload before considering a native hasher.

No native adapter is needed for hash conformance or large inputs. The hashers are not cryptographic and must only be used for lookup/deduplication; equality still needs size and, where correctness matters, byte comparison.

## DDS

### Implement the required envelope internally

The required DDS module is a binary envelope parser/writer, not an image decoder. Microsoft documents the layout as a four-byte magic, 124-byte `DDS_HEADER`, optional 20-byte `DDS_HEADER_DXT10`, then surface/mip data. It also documents minimum validation and the pitch rules for block-compressed and linear formats. See the [Microsoft DDS programming guide](https://learn.microsoft.com/en-us/windows/win32/direct3ddds/dx-graphics-dds-pguide) and [`DDS_HEADER_DXT10`](https://learn.microsoft.com/en-us/windows/win32/direct3ddds/dds-header-dxt10).

A small little-endian implementation should:

- validate the standard sizes and magic without rejecting tolerated legacy variants;
- retain original legacy pixel masks/FourCC metadata where needed;
- model the DX10 extension and the 36-byte XBOX extension used by Bethesda tooling;
- calculate mip spans with checked `long` arithmetic, including BC block minimums for dimensions below 4;
- expose payload slices without decoding pixels;
- write deterministic 128/148/164-byte headers as appropriate;
- implement the reference's narrow 24-bit RGB-to-32-bit BGRX conversion separately from parsing.

Microsoft's current MIT-licensed [`DirectXTex/DDS.h`](https://github.com/microsoft/DirectXTex/blob/main/DirectXTex/DDS.h) is an especially useful independent specification oracle: it asserts the standard structure sizes and defines the XBOX header, tile mode, alignment, data size, and XDK version fields. DirectXTex also has a full [DDS reader/writer and header encoder](https://github.com/microsoft/DirectXTex/wiki/DirectXTex), but it is a Windows C++/NuGet/vcpkg component, not a Maven Java dependency. Keep it as an external oracle or optional future native texture-conversion provider, not as a dependency for ordinary archive pack/unpack.

### Evaluated Java libraries

`io.github.ititus:dds:4.0.0` is a maintained Java 21+, MIT-licensed reader on [Maven Central](https://central.sonatype.com/artifact/io.github.ititus/dds/4.0.0). Its [source](https://github.com/iTitus/dds/tree/v4.0.0/dds/src/main/java/io/github/ititus/dds) has useful legacy/DXGI derivation logic and loads mip resources, but it offers no writer, does not model the XBOX extension, and materializes all resources in heap buffers. It may serve as a test oracle for ordinary PC DDS files but does not satisfy the production contract.

`com.twelvemonkeys.imageio:imageio-dds:3.13.1` is current on [Maven Central](https://central.sonatype.com/artifact/com.twelvemonkeys.imageio/imageio-dds/3.13.1), but the project's [format table](https://github.com/haraldk/TwelveMonkeys) marks DDS as read-only. ImageIO's goal is rasterization to `BufferedImage`, which loses the opaque compressed payload/chunking model and introduces `java.desktop`. It is not suitable for the core archive path.

No native code is necessary unless the product later adds texture transcoding, BC encoding/decoding, or PC/Xbox swizzle conversion. Those operations are distinct from archive conformance and should use a separately optional provider or external tool.

## Determinism and upgrade policy

Decoding is deterministic at the byte level. Encoding needs a narrower promise:

1. Pin exact codec artifacts and record provider/version/parameters in benchmark and golden metadata.
2. Require repeated calls with the same version, parameters, input, and worker ordering to produce the same bytes.
3. Do not promise compressed-byte stability across codec upgrades. Upstream libdeflate explicitly disclaims it, and LZ4 compressor changes can likewise alter valid output.
4. Use byte-for-byte differential assertions only where the codec version and call shape match the Reference Snapshot. LWJGL 3.4.3/LZ4 1.10.0 is the strongest such case; jlibdeflate 1.25 versus reference 1.24 is not.
5. Gate every dependency upgrade with decode-equivalence, game/tool acceptance, output-size, throughput, peak-memory, and deterministic-repeat tests.

Ordering and concurrency above the codec layer also affect full archive bytes. A deterministic archive mode must assign output order/offsets before parallel compression rather than letting worker completion order decide layout.

## Windows native packaging and Java 25

Both selected native libraries self-extract DLLs from JAR resources. This is convenient but needs explicit operational handling:

- Java 25 treats `System.load`, JNI use, and FFM downcalls as restricted native access. Applications should grant the relevant named modules, or use `--enable-native-access=ALL-UNNAMED` for a classpath deployment. Oracle documents the launcher, manifest, `JDK_JAVA_OPTIONS`, and module-path choices in [Restricted Methods](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html).
- The example CLI can carry `Enable-Native-Access: ALL-UNNAMED` in its executable-JAR manifest. Library consumers must control this policy themselves; the public library API must not silently modify process-wide settings.
- Test execution from paths containing spaces and non-ASCII characters, a read-only temp directory, antivirus scanning, concurrent class-loader initialization, multiple application class loaders, and loaded-DLL cleanup on Windows.
- Publish only x86-64 Windows natives in the initial runtime profile. Keep classifier/platform selection explicit so future Windows ARM64 or portable profiles do not accidentally ship every platform.
- Surface a diagnostic that reports active providers and native versions. A native load failure should either select the documented fallback or fail a “strict reference-performance” mode clearly.

## Reproducible probes performed

The probes ran on Windows 11 x64 with Maven 3.9.16 and Eclipse Temurin Java 25.0.4.1. They were deliberately small compatibility probes, not performance benchmarks.

| Probe | Result |
| --- | --- |
| Resolve `com.fulcrumgenomics:jlibdeflate:0.1.0` from Maven Central and inspect JAR | `native/windows-x86_64/jlibdeflate.dll` present |
| jlibdeflate level-12 zlib, 143,360-byte repeated-text input, two encodes | Equal outputs, 474 bytes; zlib round-trip equal |
| Resolve `io.airlift:aircompressor-v3:3.7` and run on Java 25 | XXH32(empty, seed 0) = `02cc5d05`; XXH64(empty, seed 0) = `ef46db3751d8e999`, matching canonical vectors |
| Resolve LWJGL 3.4.3 core/LZ4 plus `natives-windows` | Runtime `LZ4_versionString()` = `1.10.0` |
| LWJGL `LZ4_compress_HC` level 12, same input, two encodes | Equal outputs, 607 bytes; raw round-trip equal |
| LWJGL LZ4F with level 12, auto-flush, independent 4 MiB blocks, two encodes | Equal outputs, 622 bytes; frame header starts `04224d186050fb` |

Reproduce dependency resolution with:

```powershell
mvn dependency:get '-Dartifact=com.fulcrumgenomics:jlibdeflate:0.1.0'
mvn dependency:get '-Dartifact=io.airlift:aircompressor-v3:3.7'
mvn dependency:get '-Dartifact=org.lwjgl:lwjgl:3.4.3'
mvn dependency:get '-Dartifact=org.lwjgl:lwjgl:3.4.3:jar:natives-windows'
mvn dependency:get '-Dartifact=org.lwjgl:lwjgl-lz4:3.4.3'
mvn dependency:get '-Dartifact=org.lwjgl:lwjgl-lz4:3.4.3:jar:natives-windows'
```

These probes establish Java 25 loading, API shape, canonical hash values, local repeatability, and round-trip behavior. They do **not** establish reference byte equality or representative performance. Those require the project conformance corpus and benchmark ticket.

## Required follow-up gates

Before implementation locks these dependencies into a release:

1. Differentially encode/decode corpus entries with the Reference Snapshot for all zlib, raw LZ4, and LZ4F paths, including empty/tiny/incompressible inputs and exact 4 MiB frame boundaries.
2. Verify whether LWJGL 3.4.3 produces byte-identical LZ4 HC and frame output to the Windows Reference Snapshot for the same call shape.
3. Benchmark JDK zlib versus jlibdeflate across the actual 8 MiB crossover and representative BA2/BSA data; include allocation, peak direct/heap memory, and parallel scaling.
4. Test malformed/truncated compressed data and verify that every decoder consumes exactly the expected compressed and uncompressed lengths.
5. Build DDS golden tests from standard, DX10, cubemap, legacy luminance, BC1–BC7, 24-bit RGB, and XBOX-header samples; compare metadata and synthesized headers with both the Reference Snapshot and DirectXTex.
6. Run the native providers under Java 25 with `--illegal-native-access=deny` plus the intended explicit grant, from the packaged example CLI and a plain library consumer.

## One-line map gist

Adopt a provider-isolated stack: jlibdeflate with JDK fallback for zlib, LWJGL's exact-version Windows LZ4 1.10.0 binding for HC/frame semantics, Airlift's pure-Java XXH32/64, and a small internal DDS envelope implementation; gate native defaults and byte-level claims with differential corpus and benchmark results.
