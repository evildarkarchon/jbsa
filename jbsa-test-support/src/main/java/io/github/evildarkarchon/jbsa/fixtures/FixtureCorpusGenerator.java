package io.github.evildarkarchon.jbsa.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.Adler32;

/**
 * Materializes the small, independently authored JBSA fixture corpus into an empty directory.
 * Generated fixture data is CC0-1.0; this generator implementation remains Apache-2.0.
 */
public final class FixtureCorpusGenerator {
    private static final String GENERATOR_ID = "jbsa-synthetic-fixture-generator";
    private static final String GENERATOR_VERSION = "1";
    private static final String GENERATED_ON = "2026-09-03";
    private static final String REFERENCE_SNAPSHOT_REVISION =
            "fd1e36020b2b5b6217e553dc0038983146a2e2dd";
    private static final String COMMAND =
            "java -cp jbsa-test-support/target/classes "
                    + "io.github.evildarkarchon.jbsa.fixtures.FixtureCorpusGenerator "
                    + "--output <empty-directory>";

    private FixtureCorpusGenerator() {
    }

    /**
     * Runs the deterministic corpus materializer.
     *
     * @param args exactly {@code --output <empty-directory>}
     * @throws IllegalArgumentException if the command-line shape is not exactly the supported form
     * @throws IOException              if the destination cannot be validated or written
     */
    static void main(String[] args) throws IOException {
        if (args.length != 2 || !"--output".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: FixtureCorpusGenerator --output <empty-directory>");
        }
        materialize(Path.of(args[1]));
    }

    /**
     * Writes the complete generated portion of the corpus to a caller-owned empty directory. Existing
     * files are rejected so this ordinary generation seam cannot rebaseline goldens in place.
     *
     * @param outputRoot absent or empty output directory
     * @throws NullPointerException if {@code outputRoot} is null
     * @throws IOException          if the destination is nonempty or an output cannot be written
     */
    public static void materialize(Path outputRoot) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        requireEmptyDestination(outputRoot);
        Files.createDirectories(outputRoot);

        List<Fixture> fixtures = fixtures();
        for (Fixture fixture : fixtures) {
            Path output = outputRoot.resolve(fixture.outputPath());
            Files.createDirectories(output.getParent());
            Files.write(output, fixture.bytes());
        }
        writeText(outputRoot.resolve("generator.json"), generatorJson(fixtures));
        writeText(outputRoot.resolve("manifest.json"), manifestJson(fixtures));
        writeText(outputRoot.resolve("goldens/index.json"), goldenIndexJson(fixtures));
    }

    /**
     * Ensures normal materialization never replaces an existing fixture or golden object.
     *
     * @param outputRoot absent path or existing directory to validate
     * @throws IOException if the path is not a directory or the directory contains any entry
     */
    private static void requireEmptyDestination(Path outputRoot) throws IOException {
        if (!Files.exists(outputRoot)) {
            return;
        }
        if (!Files.isDirectory(outputRoot)) {
            throw new IOException("Fixture output is not a directory: " + outputRoot);
        }
        try (var children = Files.list(outputRoot)) {
            if (children.findAny().isPresent()) {
                throw new IOException("Fixture output directory must be empty: " + outputRoot);
            }
        }
    }

    /**
     * Builds the complete set of compact binary, scenario, and golden recipes.
     */
    private static List<Fixture> fixtures() {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(
                binaryFixture(
                        "selector-tes3",
                        "structural-template",
                        List.of("structural"),
                        "Write the four-byte TES3 selector from JBSA-DET-002.",
                        "selector|tes3|00010000",
                        "artifacts/structural/tes3-selector.bin",
                        HexFormat.of().parseHex("00010000"),
                        Map.of("selector", "00010000")));
        fixtures.add(
                binaryFixture(
                        "selector-bsa-067",
                        "structural-template",
                        List.of("structural"),
                        "Write the BSA selector and little-endian 0x67 version from JBSA-DET-002.",
                        "selector|bsa|version=0x67",
                        "artifacts/structural/bsa-067-selector.bin",
                        HexFormat.of().parseHex("4253410067000000"),
                        Map.of("version", "0x67")));
        fixtures.add(
                binaryFixture(
                        "selector-ba2-gnrl-v1",
                        "structural-template",
                        List.of("structural"),
                        "Write the BTDX, version, and GNRL selector tuple from JBSA-DET-002.",
                        "selector|ba2|version=1|subtype=GNRL",
                        "artifacts/structural/ba2-gnrl-v1-selector.bin",
                        HexFormat.of().parseHex("4254445801000000474e524c"),
                        Map.of("subtype", "GNRL", "version", "1")));

        fixtures.add(
                binaryFixture(
                        "fo4-gnrl-v7-stored",
                        "generated-archive",
                        List.of("structural", "fo4-gnrl-v7", "compression"),
                        "Build a version-7 General BA2 with one stored project-authored payload.",
                        "general-ba2|version=7|method=absent|entries=data/readme.txt:stored:jbsa-v7-stored",
                        "artifacts/archives/fo4-gnrl-v7-stored.ba2",
                        generalBa2(
                                7, null, List.of(entry("data/readme.txt", "jbsa-v7-stored\n", Codec.STORED))),
                        Map.of("archive_family", "fo4-gnrl-v7", "codec", "stored")));
        fixtures.add(
                binaryFixture(
                        "fo4-gnrl-v7-zlib",
                        "generated-archive",
                        List.of("structural", "fo4-gnrl-v7", "compression", "boundary"),
                        "Build a version-7 General BA2 with one complete deterministic zlib stream.",
                        "general-ba2|version=7|method=absent|entries=data/compressed.txt:zlib:32x41",
                        "artifacts/archives/fo4-gnrl-v7-zlib.ba2",
                        generalBa2(7, null, List.of(entry("data/compressed.txt", "A".repeat(32), Codec.ZLIB))),
                        Map.of("archive_family", "fo4-gnrl-v7", "codec", "zlib")));
        fixtures.add(
                binaryFixture(
                        "sf-gnrl-v3-m3-raw-lz4",
                        "generated-archive",
                        List.of("structural", "sf-gnrl-v3-m3", "compression"),
                        "Build a version-3 method-3 General BA2 with one complete raw-LZ4 literal block.",
                        "general-ba2|version=3|method=3|entries=data/raw.txt:raw-lz4:jbsa-starfield",
                        "artifacts/archives/sf-gnrl-v3-m3-raw-lz4.ba2",
                        generalBa2(3, 3, List.of(entry("data/raw.txt", "jbsa-starfield\n", Codec.RAW_LZ4))),
                        Map.of("archive_family", "sf-gnrl-v3-m3", "codec", "raw-lz4", "method", "3")));
        fixtures.add(
                binaryFixture(
                        "sf-gnrl-v3-m3-mixed",
                        "generated-archive",
                        List.of("structural", "sf-gnrl-v3-m3", "compression", "ordering"),
                        "Build a version-3 method-3 General BA2 with one stored and one raw-LZ4 entry.",
                        "general-ba2|version=3|method=3|entries=data/stored.txt:stored:plain,data/compressed.txt:raw-lz4:compressed",
                        "artifacts/archives/sf-gnrl-v3-m3-mixed.ba2",
                        generalBa2(
                                3,
                                3,
                                List.of(
                                        entry("data/stored.txt", "plain\n", Codec.STORED),
                                        entry("data/compressed.txt", "compressed\n", Codec.RAW_LZ4))),
                        Map.of("archive_family", "sf-gnrl-v3-m3", "codec", "mixed", "method", "3")));

        fixtures.add(
                binaryFixture(
                        "malformed-truncated-ba2-header",
                        "malformed-archive",
                        List.of("malformed", "boundary"),
                        "Truncate a recognized version-3 GNRL selector before the remaining header fields.",
                        "malformed|BTDX|version=3|subtype=GNRL|length=12",
                        "artifacts/malformed/truncated-ba2-header.ba2",
                        HexFormat.of().parseHex("4254445803000000474e524c"),
                        Map.of("expected_disposition", "rejected", "fault", "truncated-header")));
        fixtures.add(
                binaryFixture(
                        "malformed-unsupported-v3-method",
                        "malformed-archive",
                        List.of("malformed", "compression"),
                        "Write a complete empty version-3 GNRL header with unsupported compression method 2.",
                        "malformed|BTDX|version=3|subtype=GNRL|count=0|unknown=1|method=2",
                        "artifacts/malformed/unsupported-v3-method.ba2",
                        unsupportedMethodHeader(),
                        Map.of("expected_disposition", "rejected", "method", "2")));

        fixtures.add(
                textFixture(
                        "compression-boundaries",
                        List.of("boundary", "compression"),
                        "Declare compact sizes around raw-LZ4 literal and DEFLATE stored-block boundaries.",
                        "scenario|compression|sizes=0,1,14,15,16,65535,65536",
                        "artifacts/scenarios/compression-boundaries.json",
                        """
                                {"raw_lz4_literal_lengths":[0,1,14,15,16],"deflate_stored_lengths":[0,1,65535,65536]}
                                """,
                        Map.of("unit", "bytes")));
        fixtures.add(
                textFixture(
                        "name-encoding",
                        List.of("name-encoding", "malformed"),
                        "Declare ASCII, Windows-1252, Windows-932, unmappable, and unsafe name bytes.",
                        "scenario|names|ascii=Data/Test.txt|cp1252=636166e9|cp932=836583588367|invalid=81",
                        "artifacts/scenarios/name-encoding.json",
                        """
                                {"ascii":"Data/Test.txt","windows_1252_hex":"636166e92e747874","windows_932_hex":"8365835883672e747874","undecodable_windows_932_hex":"81","unmappable":"emoji-😀.txt","unsafe":["../escape.txt","CON.txt","folder/name:.txt"]}
                                """,
                        Map.of("default_encoding", "windows-1252", "profile_encoding", "windows-932")));
        fixtures.add(
                textFixture(
                        "stable-ordering",
                        List.of("ordering"),
                        "Declare opposite discovery orders with one common normalized logical order.",
                        "scenario|ordering|forward=zeta,Alpha,nested/beta|reverse=nested/beta,Alpha,zeta",
                        "artifacts/scenarios/ordering.json",
                        """
                                {"forward":["zeta.txt","Alpha.txt","nested/beta.txt"],"reverse":["nested/beta.txt","Alpha.txt","zeta.txt"],"expected_logical_order":["Alpha.txt","nested/beta.txt","zeta.txt"]}
                                """,
                        Map.of("comparison", "unicode-scalar")));
        fixtures.add(
                textFixture(
                        "later-source-wins-overlay",
                        List.of("overlay", "ordering"),
                        "Declare two source layers whose later duplicate retains the first insertion ordinal.",
                        "scenario|overlay|base=shared:base,only-base|later=shared:later,only-later",
                        "artifacts/scenarios/overlay.json",
                        """
                                {"sources":[{"id":"base","entries":{"shared.txt":"base","only-base.txt":"base-only"}},{"id":"later","entries":{"shared.txt":"later","only-later.txt":"later-only"}}],"expected":["shared.txt=later","only-base.txt=base-only","only-later.txt=later-only"]}
                                """,
                        Map.of("policy", "later-source-wins")));
        fixtures.add(
                textFixture(
                        "split-boundaries",
                        List.of("split", "boundary", "resource-limit"),
                        "Describe virtual entries around the 2 GiB BSA target without committing giant payloads.",
                        "scenario|split|target=2147483647|algorithm=repeat-sha256-counter|sizes=2147483400,64,2147483648",
                        "artifacts/scenarios/split-boundaries.json",
                        """
                                {"materialization":"virtual","algorithm":"repeat-sha256-counter-v1","split_target":2147483647,"entries":[{"name":"data/near.bin","size":2147483400},{"name":"data/crossing.bin","size":64},{"name":"data/oversized.bin","size":2147483648}],"commit_materialized_payloads":false}
                                """,
                        Map.of("large_payload_policy", "virtual-recipe-only")));
        fixtures.add(
                textFixture(
                        "resource-limit-boundaries",
                        List.of("resource-limit", "boundary"),
                        "Declare each ResourceLimits.standard ceiling at and one unit above the boundary.",
                        "scenario|resource-limits|maxEntries=1000000|maxMetadataBytes=1073741824|maxDecodedBytes=1099511627776|maxScratchBytes=274877906944|maxOutputs=1000000|maxDiagnostics=4096|maxSecondaryFailures=256",
                        "artifacts/scenarios/resource-limit-boundaries.json",
                        """
                                {"maxEntries":[1000000,1000001],"maxMetadataBytes":[1073741824,1073741825],"maxDecodedBytes":[1099511627776,1099511627777],"maxScratchBytes":[274877906944,274877906945],"maxOutputs":[1000000,1000001],"maxDiagnostics":[4096,4097],"maxSecondaryFailures":[256,257]}
                                """,
                        Map.of("boundary_policy", "equal-allowed-next-rejected")));

        fixtures.add(
                goldenFixture(
                        "golden-fo4-gnrl-v7-stored-observation",
                        List.of("structural", "fo4-gnrl-v7"),
                        "Record the independently expected semantic projection for the v7 stored archive.",
                        "golden|fo4-gnrl-v7-stored|entries=1|name=data\\readme.txt|codec=stored|payload=jbsa-v7-stored",
                        """
                                {"archive_family":"fo4-gnrl-v7","wire_version":7,"subtype":"GNRL","entries":[{"name":"data\\\\readme.txt","compression":"stored","payload_utf8":"jbsa-v7-stored\\n"}]}
                                """,
                        Map.of("source_fixture", "fo4-gnrl-v7-stored")));
        fixtures.add(
                goldenFixture(
                        "golden-sf-gnrl-v3-m3-mixed-observation",
                        List.of("structural", "sf-gnrl-v3-m3", "compression", "ordering"),
                        "Record the independently expected semantic projection for the v3 method-3 mixed archive.",
                        "golden|sf-gnrl-v3-m3-mixed|entries=stored,compressed|method=3",
                        """
                                {"archive_family":"sf-gnrl-v3-m3","wire_version":3,"compression_method":3,"subtype":"GNRL","entries":[{"name":"data\\\\stored.txt","compression":"stored","payload_utf8":"plain\\n"},{"name":"data\\\\compressed.txt","compression":"raw-lz4","payload_utf8":"compressed\\n"}]}
                                """,
                        Map.of("source_fixture", "sf-gnrl-v3-m3-mixed")));
        return List.copyOf(fixtures);
    }

    /**
     * Creates one binary fixture record with stable provenance metadata.
     */
    private static Fixture binaryFixture(
            String id,
            String kind,
            List<String> coverage,
            String procedure,
            String recipeInput,
            String outputPath,
            byte[] bytes,
            Map<String, String> parameters) {
        return new Fixture(
                id, kind, coverage, procedure, recipeInput, outputPath, bytes, parameters, false);
    }

    /**
     * Creates one canonical-LF UTF-8 scenario fixture.
     */
    private static Fixture textFixture(
            String id,
            List<String> coverage,
            String procedure,
            String recipeInput,
            String outputPath,
            String text,
            Map<String, String> parameters) {
        return binaryFixture(
                id,
                "scenario",
                coverage,
                procedure,
                recipeInput,
                outputPath,
                canonicalText(text),
                parameters);
    }

    /**
     * Creates a golden observation whose immutable storage path is derived from its bytes.
     */
    private static Fixture goldenFixture(
            String id,
            List<String> coverage,
            String procedure,
            String recipeInput,
            String text,
            Map<String, String> parameters) {
        byte[] bytes = canonicalText(text);
        return new Fixture(
                id,
                "golden-observation",
                coverage,
                procedure,
                recipeInput,
                "goldens/sha256/" + sha256(bytes) + ".json",
                bytes,
                parameters,
                true);
    }

    /**
     * Converts text blocks to the corpus's exact UTF-8/LF representation.
     */
    private static byte[] canonicalText(String text) {
        return (text.strip() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates one immutable General BA2 entry recipe.
     */
    private static GeneralEntry entry(String name, String payload, Codec codec) {
        return new GeneralEntry(name, payload.getBytes(StandardCharsets.UTF_8), codec);
    }

    /**
     * Encodes the specified tiny General BA2 directly from the normative wire-format tables.
     * Compression uses deterministic literal-only streams so no provider output becomes fixture
     * authority.
     *
     * @param version General BA2 wire version; supported fixture recipes use 2, 3, or a FO4 version
     * @param method  version-3 compression method, required for version 3 and absent otherwise
     * @param entries nonempty logical entries in physical record order
     * @return complete General BA2 bytes with payloads followed by the filename table
     * @throws IllegalArgumentException if a version-3 method is absent or an entry name is invalid
     * @throws ArithmeticException      if the compact fixture cannot be represented in one byte array
     */
    private static byte[] generalBa2(int version, Integer method, List<GeneralEntry> entries) {
        int headerSize = version == 3 ? 36 : version == 2 ? 32 : 24;
        int recordsSize = Math.multiplyExact(entries.size(), 36);
        List<byte[]> encodedPayloads = new ArrayList<>();
        int payloadBytes = 0;
        int namesBytes = 0;
        for (GeneralEntry entry : entries) {
            byte[] encoded = encodePayload(entry.payload(), entry.codec());
            encodedPayloads.add(encoded);
            payloadBytes = Math.addExact(payloadBytes, encoded.length);
            namesBytes =
                    Math.addExact(namesBytes, 2 + entry.name().getBytes(StandardCharsets.US_ASCII).length);
        }
        int nameTableOffset = Math.addExact(headerSize + recordsSize, payloadBytes);
        ByteBuffer output =
                ByteBuffer.allocate(Math.addExact(nameTableOffset, namesBytes))
                        .order(ByteOrder.LITTLE_ENDIAN);
        output.put("BTDX".getBytes(StandardCharsets.US_ASCII));
        output.putInt(version);
        output.put("GNRL".getBytes(StandardCharsets.US_ASCII));
        output.putInt(entries.size());
        output.putLong(nameTableOffset);
        if (version == 2 || version == 3) {
            output.putLong(1L);
        }
        if (version == 3) {
            if (method == null) {
                throw new IllegalArgumentException("Version 3 requires a compression method");
            }
            output.putInt(method);
        }

        long payloadOffset = headerSize + recordsSize;
        for (int index = 0; index < entries.size(); index++) {
            GeneralEntry entry = entries.get(index);
            NameParts parts = nameParts(entry.name());
            byte[] encodedPayload = encodedPayloads.get(index);
            output.putInt(ba2Hash(parts.baseName()));
            output.put(extensionBytes(parts.extension()));
            output.putInt(ba2Hash(parts.directory()));
            output.put((byte) 0);
            output.put((byte) 1);
            output.putShort((short) 16);
            output.putLong(payloadOffset);
            output.putInt(entry.codec() == Codec.STORED ? 0 : encodedPayload.length);
            output.putInt(entry.payload().length);
            output.putInt(0xBAADF00D);
            payloadOffset += encodedPayload.length;
        }
        for (byte[] encodedPayload : encodedPayloads) {
            output.put(encodedPayload);
        }
        for (GeneralEntry entry : entries) {
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.US_ASCII);
            output.putShort((short) nameBytes.length);
            output.put(nameBytes);
        }
        return output.array();
    }

    /**
     * Encodes one entry payload with the fixture recipe's complete deterministic stream.
     */
    private static byte[] encodePayload(byte[] payload, Codec codec) {
        return switch (codec) {
            case STORED -> payload.clone();
            case ZLIB -> zlibStoredBlock(payload);
            case RAW_LZ4 -> rawLz4LiteralBlock(payload);
        };
    }

    /**
     * Creates an RFC 1950 stream containing one final, uncompressed DEFLATE block.
     *
     * @param payload uncompressed bytes, limited to one 65,535-byte DEFLATE stored block
     * @return complete zlib stream including header and Adler-32 trailer
     * @throws IllegalArgumentException if the payload exceeds one stored block
     */
    private static byte[] zlibStoredBlock(byte[] payload) {
        if (payload.length > 0xffff) {
            throw new IllegalArgumentException("Fixture zlib block exceeds its 16-bit stored length");
        }
        ByteBuffer output = ByteBuffer.allocate(payload.length + 11).order(ByteOrder.LITTLE_ENDIAN);
        output.put((byte) 0x78);
        output.put((byte) 0x01);
        output.put((byte) 0x01);
        output.putShort((short) payload.length);
        output.putShort((short) ~payload.length);
        output.put(payload);
        Adler32 adler32 = new Adler32();
        adler32.update(payload);
        output.order(ByteOrder.BIG_ENDIAN).putInt((int) adler32.getValue());
        return output.array();
    }

    /**
     * Creates one complete raw-LZ4 block containing literals and no match sequence.
     *
     * @param payload exact literal bytes to encode
     * @return one self-contained raw-LZ4 literal-only block
     */
    private static byte[] rawLz4LiteralBlock(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 8);
        int literalLength = payload.length;
        output.write(Math.min(literalLength, 15) << 4);
        if (literalLength >= 15) {
            int remaining = literalLength - 15;
            while (remaining >= 255) {
                output.write(255);
                remaining -= 255;
            }
            output.write(remaining);
        }
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /**
     * Splits a canonical ASCII General BA2 name into its independently hashed fields.
     *
     * @param name slash-separated complete name containing a folder and nonempty extension-free stem
     * @return lowercase directory, basename, and extension components
     * @throws IllegalArgumentException if the name lacks a folder or usable extension split
     */
    private static NameParts nameParts(String name) {
        int separator = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (separator <= 0 || dot <= separator + 1) {
            throw new IllegalArgumentException(
                    "Fixture General BA2 name requires folder and extension: " + name);
        }
        return new NameParts(
                name.substring(0, separator).toLowerCase(),
                name.substring(separator + 1, dot).toLowerCase(),
                name.substring(dot + 1).toLowerCase());
    }

    /**
     * Computes the General BA2 reflected CRC-32 variant with initial value zero and no final XOR.
     */
    private static int ba2Hash(String value) {
        int crc = 0;
        for (byte current : value.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= Byte.toUnsignedInt(current);
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xEDB88320);
            }
        }
        return crc;
    }

    /**
     * Produces the four lowercase, NUL-padded General BA2 extension bytes.
     */
    private static byte[] extensionBytes(String extension) {
        byte[] output = new byte[4];
        byte[] bytes = extension.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, output, 0, Math.min(bytes.length, output.length));
        return output;
    }

    /**
     * Creates a bounded but unsupported Starfield method header for negative detection coverage.
     */
    private static byte[] unsupportedMethodHeader() {
        ByteBuffer output = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        output.put("BTDX".getBytes(StandardCharsets.US_ASCII));
        output.putInt(3);
        output.put("GNRL".getBytes(StandardCharsets.US_ASCII));
        output.putInt(0);
        output.putLong(0);
        output.putLong(1);
        output.putInt(2);
        return output.array();
    }

    /**
     * Serializes the exported generator recipe catalog in stable recipe and key order.
     */
    private static String generatorJson(List<Fixture> fixtures) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema_version\": 1,\n");
        appendProperty(json, 1, "generator_id", GENERATOR_ID, true);
        appendProperty(json, 1, "generator_version", GENERATOR_VERSION, true);
        appendProperty(json, 1, "algorithm", "independent-normative-wire-recipes-v1", true);
        json.append("  \"recipes\": [\n");
        for (int index = 0; index < fixtures.size(); index++) {
            Fixture fixture = fixtures.get(index);
            json.append("    {\n");
            appendProperty(json, 3, "id", fixture.id(), true);
            appendProperty(json, 3, "kind", fixture.kind(), true);
            appendStringArray(json, 3, "coverage", fixture.coverage(), true);
            appendProperty(json, 3, "input", fixture.recipeInput(), true);
            appendProperty(json, 3, "output_path", fixture.outputPath(), true);
            appendStringMap(json, 3, "parameters", fixture.parameters(), false);
            json.append("    }").append(index + 1 == fixtures.size() ? "\n" : ",\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    /**
     * Serializes fixture provenance and exact input/output digests in stable order.
     */
    private static String manifestJson(List<Fixture> fixtures) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema_version\": 1,\n");
        appendProperty(json, 1, "corpus_id", "jbsa-synthetic-fixtures-v1", true);
        appendProperty(json, 1, "generated_on", GENERATED_ON, true);
        appendProperty(json, 1, "reference_snapshot_revision", REFERENCE_SNAPSHOT_REVISION, true);
        json.append("  \"generator\": {\n");
        appendProperty(json, 2, "id", GENERATOR_ID, true);
        appendProperty(json, 2, "version", GENERATOR_VERSION, true);
        appendProperty(
                json,
                2,
                "implementation",
                "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/FixtureCorpusGenerator.java",
                true);
        appendProperty(json, 2, "implementation_spdx_license", "Apache-2.0", true);
        appendProperty(json, 2, "command", COMMAND, true);
        appendStringMap(json, 2, "options", Map.of("output", "<empty-directory>"), false);
        json.append("  },\n");
        json.append("  \"fixtures\": [\n");
        for (int index = 0; index < fixtures.size(); index++) {
            Fixture fixture = fixtures.get(index);
            json.append("    {\n");
            appendProperty(json, 3, "id", fixture.id(), true);
            appendProperty(json, 3, "kind", fixture.kind(), true);
            appendStringArray(json, 3, "coverage", fixture.coverage(), true);
            appendProperty(json, 3, "creator", "JBSA project contributors", true);
            appendProperty(json, 3, "source", "project-authored generator recipe " + fixture.id(), true);
            appendProperty(json, 3, "spdx_license", "CC0-1.0", true);
            json.append("      \"generation\": {\n");
            appendProperty(json, 4, "procedure", fixture.procedure(), true);
            appendProperty(json, 4, "command", COMMAND, true);
            appendStringMap(json, 4, "options", fixture.parameters(), false);
            json.append("      },\n");
            appendProperty(json, 3, "reference_snapshot_revision", REFERENCE_SNAPSHOT_REVISION, true);
            json.append("      \"oracle_sha256\": null,\n");
            appendProperty(json, 3, "input_sha256", sha256(fixture.recipeInput()), true);
            json.append("      \"output\": {\n");
            appendProperty(json, 4, "path", fixture.outputPath(), true);
            appendProperty(json, 4, "sha256", sha256(fixture.bytes()), false);
            json.append("      },\n");
            appendProperty(json, 3, "redistribution_class", "project-authored-redistributable", false);
            json.append("    }").append(index + 1 == fixtures.size() ? "\n" : ",\n");
        }
        json.append("  ],\n");
        json.append("  \"goldens\": [\n");
        List<Fixture> goldens = fixtures.stream().filter(Fixture::golden).toList();
        for (int index = 0; index < goldens.size(); index++) {
            Fixture golden = goldens.get(index);
            json.append("    {\n");
            appendProperty(json, 3, "id", golden.id(), true);
            appendProperty(json, 3, "sha256", sha256(golden.bytes()), true);
            appendProperty(json, 3, "path", golden.outputPath(), true);
            appendStringArray(
                    json, 3, "source_fixture_ids", List.of(golden.parameters().get("source_fixture")), false);
            json.append("    }").append(index + 1 == goldens.size() ? "\n" : ",\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    /**
     * Serializes the mutable logical-name index separately from immutable content-addressed objects.
     */
    private static String goldenIndexJson(List<Fixture> fixtures) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema_version\": 1,\n  \"objects\": [\n");
        List<Fixture> goldens = fixtures.stream().filter(Fixture::golden).toList();
        for (int index = 0; index < goldens.size(); index++) {
            Fixture golden = goldens.get(index);
            json.append("    {");
            appendInlineProperty(json, "id", golden.id(), true);
            appendInlineProperty(json, "sha256", sha256(golden.bytes()), true);
            appendInlineProperty(json, "path", golden.outputPath(), false);
            json.append("}").append(index + 1 == goldens.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    /**
     * Appends one indented string property.
     */
    private static void appendProperty(
            StringBuilder json, int indent, String name, String value, boolean comma) {
        json.append("  ".repeat(indent));
        appendInlineProperty(json, name, value, comma);
        json.append('\n');
    }

    /**
     * Appends one string property without adding indentation or a line ending.
     */
    private static void appendInlineProperty(
            StringBuilder json, String name, String value, boolean comma) {
        json.append('"').append(escapeJson(name)).append("\": \"");
        json.append(escapeJson(value)).append('"');
        if (comma) {
            json.append(',');
        }
    }

    /**
     * Appends a stable JSON string array.
     */
    private static void appendStringArray(
            StringBuilder json, int indent, String name, List<String> values, boolean comma) {
        json.append("  ".repeat(indent)).append('"').append(escapeJson(name)).append("\": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append('"').append(escapeJson(values.get(index))).append('"');
        }
        json.append(']');
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    /**
     * Appends a string-valued JSON object with lexicographically sorted keys.
     */
    private static void appendStringMap(
            StringBuilder json, int indent, String name, Map<String, String> values, boolean comma) {
        json.append("  ".repeat(indent)).append('"').append(escapeJson(name)).append("\": {");
        int index = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (index++ > 0) {
                json.append(", ");
            }
            json.append('"').append(escapeJson(entry.getKey())).append("\": \"");
            json.append(escapeJson(entry.getValue())).append('"');
        }
        json.append('}');
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    /**
     * Escapes a string for the small canonical JSON documents emitted by this generator.
     */
    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append("\\u%04x".formatted((int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Writes one exact UTF-8 text output after its parent directory has been created.
     */
    private static void writeText(Path output, String text) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    /**
     * Returns a lowercase SHA-256 digest for one recipe identity string.
     */
    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns a lowercase SHA-256 digest for exact output bytes.
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java 25 runtime must provide SHA-256", exception);
        }
    }

    /**
     * Identifies the payload representation used by one General BA2 fixture entry.
     */
    private enum Codec {
        STORED,
        ZLIB,
        RAW_LZ4
    }

    /**
     * Holds one complete logical General BA2 input entry.
     */
    private record GeneralEntry(String name, byte[] payload, Codec codec) {
    }

    /**
     * Holds independently hashed General BA2 name components.
     */
    private record NameParts(String directory, String baseName, String extension) {
    }

    /**
     * Holds one generated output and all provenance needed to serialize its manifest record.
     */
    private record Fixture(
            String id,
            String kind,
            List<String> coverage,
            String procedure,
            String recipeInput,
            String outputPath,
            byte[] bytes,
            Map<String, String> parameters,
            boolean golden) {
    }
}
