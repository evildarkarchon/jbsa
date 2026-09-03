package prototype.facade;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Compile-only prototype of a Path-first deep module with an owned read handle.
 * The nested types make this alternative independent of the other prototypes.
 */
public final class PathFirstFacadePrototype {
    private PathFirstFacadePrototype() {
    }

    /** Conventional facade used directly by the thin CLI and embedded callers. */
    public static final class BethesdaArchives {
        private BethesdaArchives() {
        }

        /** Recognizes bytes without claiming full Decode Conformance. */
        public static ArchiveDetection detect(Path archive) throws IOException {
            throw prototypeOnly();
        }

        /** Returns detached metadata and closes its internal archive lifetime. */
        public static ArchiveInspection inspect(Path archive) throws IOException {
            throw prototypeOnly();
        }

        /** Opens a caller-owned lifetime for repeated inspection and entry access. */
        public static OpenArchive open(Path archive, OpenOptions options) throws IOException {
            throw prototypeOnly();
        }

        /** Extracts selected entries after destination-containment preflight. */
        public static ExtractionResult extract(
                ExtractRequest request,
                OperationControl control) throws IOException {
            throw prototypeOnly();
        }

        /** Packs ordered sources; a later normalized entry name wins. */
        public static PackingResult pack(
                PackRequest request,
                OperationControl control) throws IOException {
            throw prototypeOnly();
        }

        private static UnsupportedOperationException prototypeOnly() {
            return new UnsupportedOperationException("compile-only interface prototype");
        }
    }

    /** Owned Bethesda Archive read lifetime. */
    public interface OpenArchive extends AutoCloseable {
        /** Returns the detached summary and entry-metadata snapshot. */
        ArchiveInspection inspection();

        /** Finds one entry by the Archive Family's normalized lookup rules. */
        Optional<ArchiveEntry> entry(ArchiveName name) throws IOException;

        /** Extracts through the same safe implementation used by the facade. */
        ExtractionResult extractTo(
                Path destination,
                ExtractOptions options,
                OperationControl control) throws IOException;

        /** Closes the archive input and invalidates any outstanding entry channels. */
        @Override
        void close() throws IOException;
    }

    /** Entry capability tied to its parent {@link OpenArchive}. */
    public interface ArchiveEntry {
        /** Returns immutable logical and stored metadata. */
        ArchiveEntryMetadata metadata();

        /**
         * Opens canonical uncompressed bytes. The caller closes the channel while
         * keeping the parent archive open.
         */
        ReadableByteChannel openChannel() throws IOException;
    }

    /** Ordered request for creating one or more Bethesda Archives. */
    public record PackRequest(
            Path destination,
            ArchiveEncoding encoding,
            List<PackSource> orderedSources,
            PackOptions options) {
        public PackRequest {
            Objects.requireNonNull(destination);
            Objects.requireNonNull(encoding);
            orderedSources = List.copyOf(orderedSources);
            Objects.requireNonNull(options);
        }

        /** Creates an empty request whose source order can be built immutably. */
        public static PackRequest to(
                Path destination,
                ArchiveEncoding encoding,
                PackOptions options) {
            return new PackRequest(destination, encoding, List.of(), options);
        }

        /** Appends the highest-precedence source and returns a new request. */
        public PackRequest addSource(PackSource source) {
            var copy = new java.util.ArrayList<>(orderedSources);
            copy.add(source);
            return new PackRequest(destination, encoding, copy, options);
        }
    }

    /** Sources known to have distinct real callers in the initial module. */
    public sealed interface PackSource permits DetectedPath, NamedFile, GeneratedEntry {
        /** Lets the implementation detect a directory, file, or Bethesda Archive. */
        static PackSource path(Path path) {
            return new DetectedPath(path);
        }

        /** Maps one loose file to an explicit archive name. */
        static PackSource namedFile(Path file, ArchiveName name) {
            return new NamedFile(file, name);
        }

        /** Supplies repeatable generated content without exposing a storage port. */
        static PackSource generated(
                ArchiveName name,
                OptionalLong size,
                PayloadSource payload) {
            return new GeneratedEntry(name, size, payload);
        }
    }

    /** Path whose loose/archive role is detected by the implementation. */
    public record DetectedPath(Path path) implements PackSource {
    }

    /** One loose file with an explicit logical archive name. */
    public record NamedFile(Path file, ArchiveName name) implements PackSource {
    }

    /** Caller-generated logical entry whose channel is library-owned once opened. */
    public record GeneratedEntry(
            ArchiveName name,
            OptionalLong declaredSize,
            PayloadSource payload) implements PackSource {
    }

    /** Repeatable factory for a generated entry's bytes. */
    @FunctionalInterface
    public interface PayloadSource {
        /**
         * Opens a fresh channel. JBSA closes it and may open it more than once for
         * hashing, retry, or packing.
         */
        ReadableByteChannel open() throws IOException;
    }

    /** Request for a safe filesystem extraction. */
    public record ExtractRequest(
            Path archive,
            Path destination,
            EntrySelection selection,
            OpenOptions openOptions,
            ExtractOptions extractOptions) {
    }

    /** Cross-cutting hooks whose exact dispatch semantics remain a later decision. */
    public record OperationControl(
            ProgressListener progress,
            CancellationToken cancellation) {
        /** Returns no-op progress and never-cancelled controls. */
        public static OperationControl defaults() {
            return new OperationControl(ignored -> { }, () -> false);
        }
    }

    /** Observes immutable operation progress without receiving console concerns. */
    @FunctionalInterface
    public interface ProgressListener {
        /** Receives a monotonically sequenced snapshot. */
        void onProgress(ProgressSnapshot progress);
    }

    /** Supplies cooperative cancellation state. */
    @FunctionalInterface
    public interface CancellationToken {
        /** Returns whether the current operation should cancel at its next checkpoint. */
        boolean cancellationRequested();
    }

    /** Progress units use long counters for very large payloads. */
    public record ProgressSnapshot(
            long sequence,
            OperationPhase phase,
            long completedEntries,
            OptionalLong totalEntries,
            long completedBytes,
            OptionalLong totalBytes) {
    }

    /** Detached result of format recognition. */
    public record ArchiveDetection(
            DetectionKind kind,
            Optional<ArchiveEncoding> encoding,
            List<ConformanceDiagnostic> diagnostics) {
        public ArchiveDetection {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Detached metadata suitable for listing without an open payload lifetime. */
    public record ArchiveInspection(
            ArchiveMetadata metadata,
            List<ArchiveEntryMetadata> entries,
            List<ConformanceDiagnostic> diagnostics) {
        public ArchiveInspection {
            entries = List.copyOf(entries);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Archive Family remains distinct from the wire version and BA2 subtype. */
    public record ArchiveEncoding(
            ArchiveFamily family,
            OptionalInt wireVersion,
            Optional<Ba2Subtype> ba2Subtype) {
    }

    /** Summary shared by every Archive Family. */
    public record ArchiveMetadata(
            ArchiveEncoding encoding,
            long encodedSize,
            long entryCount,
            OptionalInt archiveFlags,
            OptionalInt fileFlags) {
    }

    /** Logical entry metadata plus family-specific stored facts. */
    public record ArchiveEntryMetadata(
            ArchiveName name,
            ByteBuffer originalWireName,
            long storedSize,
            long uncompressedSize,
            boolean compressed,
            StoredEntryMetadata storedMetadata) {
        public ArchiveEntryMetadata {
            originalWireName = originalWireName.asReadOnlyBuffer();
        }
    }

    /** Preserves family-specific metadata without leaking parser implementations. */
    public sealed interface StoredEntryMetadata
            permits Tes3EntryMetadata, BsaEntryMetadata,
                    GeneralBa2EntryMetadata, DdsBa2EntryMetadata {
    }

    public record Tes3EntryMetadata(long wireHash, long dataOffset)
            implements StoredEntryMetadata {
    }

    public record BsaEntryMetadata(long folderHash, long fileHash, long dataOffset)
            implements StoredEntryMetadata {
    }

    public record GeneralBa2EntryMetadata(
            long directoryHash,
            long basenameHash,
            String extension,
            List<ChunkMetadata> chunks) implements StoredEntryMetadata {
        public GeneralBa2EntryMetadata {
            chunks = List.copyOf(chunks);
        }
    }

    public record DdsBa2EntryMetadata(
            int width,
            int height,
            int mipCount,
            int dxgiFormat,
            boolean cubemap,
            int tileMode,
            List<ChunkMetadata> chunks) implements StoredEntryMetadata {
        public DdsBa2EntryMetadata {
            chunks = List.copyOf(chunks);
        }
    }

    public record ChunkMetadata(
            long dataOffset,
            long storedSize,
            long uncompressedSize,
            OptionalInt firstMip,
            OptionalInt lastMip) {
    }

    /** Stable machine-facing diagnostic; human wording is presentation-owned. */
    public record ConformanceDiagnostic(
            String identifier,
            DiagnosticSeverity severity,
            String operation,
            Optional<ArchiveName> entry,
            Optional<String> field,
            Map<String, String> values) {
        public ConformanceDiagnostic {
            values = Map.copyOf(values);
        }
    }

    /** Logical archive name; host Path normalization is intentionally absent. */
    public record ArchiveName(String displayName) {
        public ArchiveName {
            Objects.requireNonNull(displayName);
        }

        /** Creates a logical name whose normalization remains implementation-owned. */
        public static ArchiveName of(String displayName) {
            return new ArchiveName(displayName);
        }
    }

    /** Entry selection is intentionally small in the interface-shape prototype. */
    @FunctionalInterface
    public interface EntrySelection {
        /** Returns whether detached metadata belongs in the operation. */
        boolean includes(ArchiveEntryMetadata entry);

        /** Selects every entry. */
        static EntrySelection all() {
            return ignored -> true;
        }
    }

    public record OpenOptions(
            int archiveNameCodePage,
            Optional<String> compatibilityProfile) {
    }

    public record ExtractOptions(boolean replaceExisting) {
    }

    public record PackOptions(
            Compression compression,
            OptionalLong splitAtBytes,
            boolean shareIdenticalPayloads,
            OptionalLong maximumResidentBytes) {
    }

    public record ExtractionResult(
            long entryCount,
            long logicalBytes,
            List<Path> artifacts,
            List<ConformanceDiagnostic> diagnostics) {
    }

    public record PackingResult(
            long entryCount,
            long logicalBytes,
            List<Path> archives,
            List<ConformanceDiagnostic> diagnostics) {
    }

    public enum ArchiveFamily {
        TES3_BSA,
        OBLIVION_BSA,
        FO3_FNV_SKYRIM_LE_BSA,
        SKYRIM_SE_BSA,
        FALLOUT4_GENERAL_BA2,
        FALLOUT4_DDS_BA2,
        STARFIELD_GENERAL_BA2,
        STARFIELD_DDS_BA2
    }

    public enum Ba2Subtype { GENERAL, DDS }

    public enum Compression { STORED, FAMILY_DEFAULT, ZLIB, LZ4_FRAME, RAW_LZ4 }

    public enum DetectionKind {
        SUPPORTED_ARCHIVE_FAMILY,
        RECOGNIZED_BUT_UNSUPPORTED,
        NOT_A_BETHESDA_ARCHIVE
    }

    public enum DiagnosticSeverity { INFORMATION, WARNING, ERROR }

    public enum OperationPhase {
        DETECTING,
        INDEXING,
        PREFLIGHTING,
        READING,
        COMPRESSING,
        WRITING,
        VERIFYING
    }

    /** Compile-checked example of the thin CLI crossing only this public seam. */
    public static void cliLikeUsage(
            Path archivePath,
            Path outputPath,
            WritableByteChannel callerOwnedDestination,
            OpenOptions openOptions,
            PackOptions packOptions,
            OperationControl control) throws IOException {
        ArchiveInspection listing = BethesdaArchives.inspect(archivePath);
        long entryCount = listing.metadata().entryCount();

        try (OpenArchive archive = BethesdaArchives.open(archivePath, openOptions)) {
            ArchiveEntry entry = archive.entry(ArchiveName.of("textures/hero.dds"))
                    .orElseThrow();
            try (ReadableByteChannel bytes = entry.openChannel()) {
                ByteBuffer chunk = ByteBuffer.allocate(256 * 1024);
                while (bytes.read(chunk) >= 0) {
                    chunk.flip();
                    while (chunk.hasRemaining()) {
                        callerOwnedDestination.write(chunk);
                    }
                    chunk.clear();
                }
            }
        }

        PackRequest request = PackRequest.to(
                        outputPath,
                        new ArchiveEncoding(
                                ArchiveFamily.STARFIELD_GENERAL_BA2,
                                OptionalInt.of(2),
                                Optional.of(Ba2Subtype.GENERAL)),
                        packOptions)
                .addSource(PackSource.path(archivePath))
                .addSource(PackSource.path(Path.of("Patch")));
        PackingResult result = BethesdaArchives.pack(request, control);

        if (entryCount != result.entryCount()) {
            // The example deliberately observes semantic results through the interface.
            System.out.println("overlay changed the effective entry count");
        }
    }
}
