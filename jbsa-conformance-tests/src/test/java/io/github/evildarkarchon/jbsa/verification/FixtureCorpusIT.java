package io.github.evildarkarchon.jbsa.verification;

import io.github.evildarkarchon.jbsa.fixtures.FixtureCorpusGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("build-policy")
final class FixtureCorpusIT {
    /**
     * Runs the public, read-only fixture audit command against one corpus root.
     *
     * @param corpusRoot corpus directory to audit
     * @return observable process exit status and merged output
     * @throws Exception if the process cannot be managed within its deadline
     */
    private static AuditResult runFixtureAudit(Path corpusRoot) throws Exception {
        Process process =
                new ProcessBuilder(
                        "pwsh",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        reactorRoot().resolve("build/verify-fixture-corpus.ps1").toString(),
                        "-CorpusRoot",
                        corpusRoot.toString())
                        .redirectErrorStream(true)
                        .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Fixture corpus audit timed out");
        return new AuditResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Loads a fixture as a little-endian General BA2 and verifies its fixed selectors.
     *
     * @param archive exact fixture path to read
     * @return a little-endian buffer positioned independently of the absolute selector reads
     * @throws IOException if the fixture cannot be read
     */
    private static ByteBuffer readAndValidateGeneralBa2(Path archive) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bytes.get(0, magic);
        assertEquals("BTDX", new String(magic, StandardCharsets.US_ASCII));
        byte[] subtype = new byte[4];
        bytes.get(8, subtype);
        assertEquals("GNRL", new String(subtype, StandardCharsets.US_ASCII));
        return bytes;
    }

    /**
     * Lists every generated regular file using a stable path-relative order.
     *
     * @param root materialized corpus root to traverse
     * @return relative regular-file paths sorted by their path strings
     * @throws IOException if the tree cannot be traversed
     */
    private static List<Path> relativeFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * Writes an independent comparison copy used to prove committed bytes were not mutated.
     *
     * @param temporaryRoot test-owned directory that receives the copy
     * @param bytes         original committed bytes captured before staged tampering
     * @return path to the independent copy
     * @throws IOException if the copy cannot be written
     */
    private static Path writeComparisonCopy(Path temporaryRoot, byte[] bytes) throws IOException {
        Path comparison = temporaryRoot.resolve("committed-golden-before.json");
        Files.write(comparison, bytes);
        return comparison;
    }

    /**
     * Deletes only the test-owned temporary tree after handles have been closed.
     *
     * @param root exact temporary root created by the current test
     * @throws IOException if traversal or deletion fails
     */
    private static void deleteTree(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Resolves the repository root supplied by the Maven integration-test configuration.
     *
     * @return canonical repository root
     * @throws IOException if the configured root cannot be resolved
     */
    private static Path reactorRoot() throws IOException {
        return Path.of(System.getProperty("jbsa.reactor.root")).toRealPath();
    }

    /**
     * Verifies the committed synthetic corpus satisfies JBSA-LIC-004 through JBSA-LIC-006.
     *
     * @throws Exception if the repository audit cannot be started or does not finish in time
     */
    @Test
    void syntheticFixtureCorpusPassesProvenanceAudit() throws Exception {
        AuditResult result = runFixtureAudit(reactorRoot().resolve("tests/fixtures/synthetic"));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Fixture corpus verification passed"), result.output());
    }

    /**
     * Verifies fresh materialization exactly reproduces the committed generated object set.
     *
     * @throws Exception if staging or comparison cannot be completed
     */
    @Test
    void generatorReproducesCommittedFixtureObjects() throws Exception {
        Path temporaryRoot = Files.createTempDirectory("jbsa-fixture-materialization-");
        try {
            Path generated = temporaryRoot.resolve("synthetic");
            FixtureCorpusGenerator.materialize(generated);

            Path committed = reactorRoot().resolve("tests/fixtures/synthetic");
            List<Path> generatedFiles = relativeFiles(generated);
            assertTrue(generatedFiles.size() > 10, "Expected a nontrivial generated fixture corpus");
            for (Path relativePath : generatedFiles) {
                assertEquals(
                        -1L,
                        Files.mismatch(generated.resolve(relativePath), committed.resolve(relativePath)),
                        () -> "Generated fixture drift: " + relativePath);
            }

            assertThrows(IOException.class, () -> FixtureCorpusGenerator.materialize(generated));
        } finally {
            deleteTree(temporaryRoot);
        }
    }

    /**
     * Verifies the read-only audit rejects a staged golden whose bytes no longer match its digest.
     *
     * @throws Exception if staging, mutation, or the audit process cannot be managed
     */
    @Test
    void fixtureAuditRejectsGoldenDriftWithoutChangingCommittedObjects() throws Exception {
        Path temporaryRoot = Files.createTempDirectory("jbsa-fixture-tamper-");
        Path committed = reactorRoot().resolve("tests/fixtures/synthetic");
        Path committedGolden =
                committed.resolve(
                        "goldens/sha256/67c4a0360a01638bd122cba9e22f2839139813684b8d7e07e24cc190a72c82e8.json");
        byte[] committedBytes = Files.readAllBytes(committedGolden);
        try {
            Path staged = temporaryRoot.resolve("synthetic");
            FixtureCorpusGenerator.materialize(staged);
            for (String schema :
                    List.of(
                            "fixture-manifest.schema.json",
                            "generator.schema.json",
                            "rebaseline-record.schema.json")) {
                Files.copy(committed.resolve(schema), staged.resolve(schema));
            }
            Path stagedGolden = staged.resolve(committed.relativize(committedGolden));
            Files.writeString(stagedGolden, "tampered", StandardCharsets.UTF_8);

            AuditResult result = runFixtureAudit(staged);
            assertTrue(result.exitCode() != 0, result.output());
            assertTrue(result.output().contains("checksum mismatch"), result.output());
            assertEquals(
                    -1L, Files.mismatch(committedGolden, writeComparisonCopy(temporaryRoot, committedBytes)));
        } finally {
            deleteTree(temporaryRoot);
        }
    }

    /**
     * Verifies the independently authored General BA2 fixtures carry the exact unused variants.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    void generatedGeneralBa2FixturesCoverUnusedVersionAndCodecCombinations() throws IOException {
        Path archives = reactorRoot().resolve("tests/fixtures/synthetic/artifacts/archives");

        ByteBuffer v7Stored = readAndValidateGeneralBa2(archives.resolve("fo4-gnrl-v7-stored.ba2"));
        assertEquals(7, v7Stored.getInt(4));
        assertEquals(1, v7Stored.getInt(12));
        assertEquals(0, v7Stored.getInt(24 + 24));

        ByteBuffer v7Zlib = readAndValidateGeneralBa2(archives.resolve("fo4-gnrl-v7-zlib.ba2"));
        assertEquals(7, v7Zlib.getInt(4));
        assertEquals(1, v7Zlib.getInt(12));
        assertTrue(v7Zlib.getInt(24 + 24) > 0, "v7 zlib entry must carry packed bytes");

        ByteBuffer v3RawLz4 = readAndValidateGeneralBa2(archives.resolve("sf-gnrl-v3-m3-raw-lz4.ba2"));
        assertEquals(3, v3RawLz4.getInt(4));
        assertEquals(1L, v3RawLz4.getLong(24));
        assertEquals(3, v3RawLz4.getInt(32));
        assertTrue(v3RawLz4.getInt(36 + 24) > 0, "v3 raw-LZ4 entry must carry packed bytes");

        ByteBuffer v3Mixed = readAndValidateGeneralBa2(archives.resolve("sf-gnrl-v3-m3-mixed.ba2"));
        assertEquals(3, v3Mixed.getInt(4));
        assertEquals(2, v3Mixed.getInt(12));
        assertEquals(3, v3Mixed.getInt(32));
        assertEquals(0, v3Mixed.getInt(36 + 24));
        assertTrue(
                v3Mixed.getInt(36 + 36 + 24) > 0,
                "v3 mixed archive must contain a raw-LZ4 entry after its stored entry");
    }

    /**
     * Verifies the optional 12-archive workstation corpus remains discoverable but uncommittable.
     *
     * @throws IOException if the committed policy files cannot be read
     */
    @Test
    void localCorpusIsDiscoverableAndExcludedFromVersionControl() throws IOException {
        String discovery = Files.readString(reactorRoot().resolve("tests/fixtures/local/README.md"));
        assertTrue(discovery.contains("12 read-only Bethesda Archives"), discovery);
        assertTrue(discovery.contains("corpus/manifest.json"), discovery);
        assertTrue(discovery.contains("oracle/BSArch.exe"), discovery);
        assertTrue(discovery.contains("must never be committed, assembled, or\npublished"), discovery);

        String ignoreRules = Files.readString(reactorRoot().resolve(".gitignore"));
        assertTrue(ignoreRules.contains("/tests/fixtures/local/**"), ignoreRules);
        assertTrue(ignoreRules.contains("!/tests/fixtures/local/README.md"), ignoreRules);
    }

    /**
     * Captures the observable exit status and merged output from the fixture audit command.
     */
    private record AuditResult(int exitCode, String output) {
    }
}
