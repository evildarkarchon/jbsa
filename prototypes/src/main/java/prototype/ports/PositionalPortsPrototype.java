package prototype.ports;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Compile-only prototype whose external seam exposes positional leases and
 * transactional multi-part destinations.
 */
public final class PositionalPortsPrototype {
    private PositionalPortsPrototype() {
    }

    /** Deep archive module built over caller-selected storage adapters. */
    public interface BethesdaArchives {
        /** Performs bounded header recognition through a source lease. */
        ArchiveDetection detect(ArchiveSource source, Control control)
                throws IOException, ArchiveFailure;

        /** Opens an owned archive lifetime over one source lease. */
        OpenArchive open(ArchiveSource source, OpenOptions options, Control control)
                throws IOException, ArchiveFailure;

        /** Packs ordered semantic layers into one destination transaction. */
        PackingResult pack(
                PackRequest request,
                ArchiveDestination destination,
                Control control) throws IOException, ArchiveFailure;
    }

    /** Access to the sole supported module implementation. */
    public static final class Archives {
        private Archives() {
        }

        /** Returns the standard implementation. */
        public static BethesdaArchives standard() {
            throw new UnsupportedOperationException("compile-only interface prototype");
        }
    }

    /** Factory for a library-owned positional read lease. */
    @FunctionalInterface
    public interface ArchiveSource {
        /** Opens one fresh lease positioned conceptually at byte zero. */
        ReadLease open() throws IOException;
    }

    /** Absolute-long read capability that never requires an int-sized payload. */
    public interface ReadLease extends AutoCloseable {
        /** Returns the encoded source size. */
        long size() throws IOException;

        /** Reads at an absolute position into the caller-provided bounded buffer. */
        int read(long position, ByteBuffer destination) throws IOException;

        /** Releases the underlying source according to its ownership adapter. */
        @Override
        void close() throws IOException;
    }

    /** Factory for one atomic-or-best-effort multi-part destination transaction. */
    public interface ArchiveDestination {
        /** Begins a library-owned destination lease. */
        DestinationLease begin() throws IOException;
    }

    /** Multi-part output transaction. */
    public interface DestinationLease extends AutoCloseable {
        /** Opens one proposed split part for bounded positional writes. */
        PartSink openPart(ArchivePart part) throws IOException;

        /** Publishes every completed part. */
        void commit() throws IOException;

        /** Aborts unpublished output; adapter cleanup capabilities may vary. */
        void abort() throws IOException;

        /** Releases staging resources; close without commit is not success. */
        @Override
        void close() throws IOException;
    }

    /** Positional sink for one archive part. */
    public interface PartSink extends AutoCloseable {
        /** Writes bounded bytes at an absolute position. */
        int write(long position, ByteBuffer source) throws IOException;

        /** Sets the final encoded length without narrowing to int. */
        void setLength(long length) throws IOException;

        /** Releases this part's writable resource. */
        @Override
        void close() throws IOException;
    }

    /** Owned read view over archive metadata and entry capabilities. */
    public interface OpenArchive extends AutoCloseable {
        /** Returns archive-level metadata. */
        ArchiveMetadata inspect();

        /** Opens a caller-owned cursor over stored-order entry metadata. */
        EntryCursor list() throws ArchiveFailure;

        /** Finds one entry through Archive Family normalization. */
        Optional<ArchiveEntryMetadata> find(ArchiveName name) throws ArchiveFailure;

        /** Opens caller-owned positional logical bytes for one entry. */
        EntryReader openEntry(long entryId, Control control)
                throws IOException, ArchiveFailure;

        /** Extracts selected entries through a target transaction. */
        ExtractionResult extract(
                EntrySelection selection,
                ExtractionTarget target,
                Control control) throws IOException, ArchiveFailure;

        /** Closes the source lease and invalidates cursors and entry readers. */
        @Override
        void close() throws IOException;
    }

    /** Cursor that avoids requiring one materialized metadata list. */
    public interface EntryCursor extends AutoCloseable {
        /** Advances to the next stored-order entry. */
        boolean advance() throws ArchiveFailure;

        /** Returns the current metadata value. */
        ArchiveEntryMetadata current();

        /** Releases cursor state. */
        @Override
        void close();
    }

    /** Positional logical-entry reader tied to its parent OpenArchive. */
    public interface EntryReader extends AutoCloseable {
        /** Returns the uncompressed logical size. */
        long size();

        /** Reads canonical bytes at an absolute logical position. */
        int read(long position, ByteBuffer destination)
                throws IOException, ArchiveFailure;

        /** Releases decompression and read state. */
        @Override
        void close() throws IOException;
    }

    /** Transactional extraction target with NTFS and recording adapters. */
    public interface ExtractionTarget {
        /** Begins output only after logical name-containment preflight succeeds. */
        ExtractionLease begin(ArchiveMetadata archive) throws IOException;
    }

    /** Extraction transaction. */
    public interface ExtractionLease extends AutoCloseable {
        /** Opens one already-preflighted logical entry destination. */
        EntrySink openEntry(ArchiveEntryMetadata entry) throws IOException;

        /** Publishes all completed entries. */
        void commit() throws IOException;

        /** Aborts unpublished extraction output. */
        void abort() throws IOException;

        /** Releases the transaction; close without commit is not success. */
        @Override
        void close() throws IOException;
    }

    /** Sequential bounded sink for one extracted logical entry. */
    public interface EntrySink extends AutoCloseable {
        /** Writes the remaining bytes from a bounded source buffer. */
        int write(ByteBuffer source) throws IOException;

        /** Releases the entry destination. */
        @Override
        void close() throws IOException;
    }

    /** Semantic packing layer for directories, archives, or generated entries. */
    public interface EntryLayer {
        /** Opens a library-owned cursor over this layer. */
        LayerCursor open() throws IOException, ArchiveFailure;
    }

    /** Streaming cursor over one packing layer. */
    public interface LayerCursor extends AutoCloseable {
        /** Advances to the next logical entry. */
        boolean advance() throws IOException, ArchiveFailure;

        /** Returns the current entry after a successful advance. */
        PackEntry current();

        /** Releases the layer enumeration. */
        @Override
        void close() throws IOException;
    }

    /** Logical packing entry whose payload is itself a repeatable source lease. */
    public record PackEntry(
            ArchiveName name,
            ArchiveSource payload,
            FamilyEntryMetadata requestedMetadata) {
    }

    /** Ordered layers and format policy for one pack transaction. */
    public record PackRequest(
            ArchiveEncoding encoding,
            List<EntryLayer> orderedLayers,
            PackOptions options) {
        public PackRequest {
            orderedLayers = List.copyOf(orderedLayers);
        }
    }

    public record PackOptions(
            Compression compression,
            OptionalLong splitAtBytes,
            boolean shareIdenticalPayloads,
            int archiveNameCodePage,
            Optional<String> compatibilityProfile) {
    }

    public record OpenOptions(
            int archiveNameCodePage,
            Optional<String> compatibilityProfile) {
    }

    /** Cross-cutting hooks; later decisions define exact scheduling semantics. */
    public record Control(
            CancellationToken cancellation,
            ProgressListener progress,
            OptionalInt maximumWorkers,
            OptionalLong maximumResidentBytes) {
    }

    /** Supplies cooperative cancellation state. */
    @FunctionalInterface
    public interface CancellationToken {
        /** Returns whether execution should cancel at the next checkpoint. */
        boolean cancellationRequested();
    }

    /** Observes structured operation progress. */
    @FunctionalInterface
    public interface ProgressListener {
        /** Receives one immutable progress event. */
        void onProgress(ProgressEvent event);
    }

    public record ProgressEvent(
            OperationPhase phase,
            long completed,
            OptionalLong total,
            OptionalLong entryId) {
    }

    public record ArchiveMetadata(
            ArchiveEncoding encoding,
            long encodedSize,
            long entryCount,
            FamilyArchiveMetadata familyMetadata,
            List<ConformanceDiagnostic> diagnostics) {
    }

    public record ArchiveEntryMetadata(
            long entryId,
            ArchiveName name,
            ByteBuffer originalWireName,
            long storedSize,
            long uncompressedSize,
            FamilyEntryMetadata familyMetadata) {
        public ArchiveEntryMetadata {
            originalWireName = originalWireName.asReadOnlyBuffer();
        }
    }

    /** Marker for strongly typed Archive Family metadata values. */
    public interface FamilyArchiveMetadata {
    }

    /** Marker for strongly typed entry metadata values. */
    public interface FamilyEntryMetadata {
    }

    /** Archive Family remains separate from version and BA2 subtype. */
    public record ArchiveEncoding(
            ArchiveFamily family,
            OptionalInt wireVersion,
            Optional<Ba2Subtype> ba2Subtype) {
    }

    public record ArchivePart(int ordinal, String suggestedSuffix) {
    }

    public record ArchiveName(String displayName) {
        /** Creates a logical name without host Path normalization. */
        public static ArchiveName of(String displayName) {
            return new ArchiveName(displayName);
        }
    }

    @FunctionalInterface
    public interface EntrySelection {
        /** Returns whether one entry belongs in the operation. */
        boolean includes(ArchiveEntryMetadata entry);

        /** Selects every entry. */
        static EntrySelection all() {
            return ignored -> true;
        }
    }

    public record ArchiveDetection(
            DetectionKind kind,
            Optional<ArchiveEncoding> encoding,
            List<ConformanceDiagnostic> diagnostics) {
    }

    public record ExtractionResult(long entryCount, long logicalBytes) {
    }

    public record PackingResult(
            long entryCount,
            long logicalBytes,
            List<WrittenArchive> archives) {
    }

    public record WrittenArchive(ArchivePart part, long encodedBytes) {
    }

    /** Stable machine-facing diagnostic. */
    public record ConformanceDiagnostic(
            String identifier,
            Severity severity,
            String operation,
            Optional<ArchiveName> entry,
            Map<String, String> values) {
    }

    /** Checked failure retaining a stable code and optional I/O cause. */
    public static final class ArchiveFailure extends Exception {
        private final ErrorCode code;
        private final List<ConformanceDiagnostic> diagnostics;

        /** Creates a structured failure for callers and the CLI adapter. */
        public ArchiveFailure(
                ErrorCode code,
                List<ConformanceDiagnostic> diagnostics,
                Throwable cause) {
            super(code.name(), cause);
            this.code = code;
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** Returns the machine-comparable error code. */
        public ErrorCode code() {
            return code;
        }

        /** Returns immutable diagnostics accumulated before failure. */
        public List<ConformanceDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    /** Built-in adapters; their implementations remain inside the module. */
    public static final class IO {
        private IO() {
        }

        /** Creates an owned Path source. */
        public static ArchiveSource pathSource(Path path) {
            throw prototypeOnly();
        }

        /** Borrows a caller-owned channel that remains open after lease close. */
        public static ArchiveSource borrowedSource(FileChannel channel) {
            throw prototypeOnly();
        }

        /** Transfers one caller channel's lifetime to the returned source lease. */
        public static ArchiveSource ownedSource(FileChannel channel) {
            throw prototypeOnly();
        }

        /** Creates a staged multi-part Path destination. */
        public static ArchiveDestination pathDestination(Path path) {
            throw prototypeOnly();
        }

        /** Creates a directory-tree packing layer. */
        public static EntryLayer directoryLayer(Path directory) {
            throw prototypeOnly();
        }

        /** Creates a packing layer from an existing Bethesda Archive. */
        public static EntryLayer archiveLayer(ArchiveSource archive) {
            throw prototypeOnly();
        }

        /** Creates a containment-safe directory extraction target. */
        public static ExtractionTarget directoryTarget(Path directory) {
            throw prototypeOnly();
        }

        private static UnsupportedOperationException prototypeOnly() {
            return new UnsupportedOperationException("compile-only interface prototype");
        }
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

    public enum OperationPhase {
        DETECTING, INDEXING, PREFLIGHTING, READING, WRITING, FINALIZING
    }

    public enum Severity { INFORMATION, WARNING, ERROR }

    public enum ErrorCode {
        UNSUPPORTED_ARCHIVE_FAMILY,
        UNSUPPORTED_CODEC,
        REJECTED_ARCHIVE,
        DUPLICATE_NORMALIZED_NAME,
        UNSAFE_EXTRACTION_NAME,
        RESOURCE_LIMIT_EXCEEDED,
        DOWNSTREAM_IO_FAILED,
        CANCELLED
    }

    /** Compile-checked example of the lease-heavy calling style. */
    public static void cliLikeUsage(
            Path archivePath,
            Path outputPath,
            OpenOptions openOptions,
            PackOptions packOptions,
            Control control) throws IOException, ArchiveFailure {
        BethesdaArchives library = Archives.standard();
        ArchiveSource source = IO.pathSource(archivePath);

        ArchiveDetection detection = library.detect(source, control);
        try (OpenArchive archive = library.open(source, openOptions, control)) {
            try (EntryCursor entries = archive.list()) {
                while (entries.advance()) {
                    System.out.println(entries.current().name().displayName());
                }
            }

            ArchiveEntryMetadata entry = archive.find(ArchiveName.of("textures/hero.dds"))
                    .orElseThrow();
            try (EntryReader reader = archive.openEntry(entry.entryId(), control)) {
                ByteBuffer chunk = ByteBuffer.allocate(256 * 1024);
                reader.read(3L * 1024 * 1024 * 1024, chunk);
            }

            archive.extract(
                    EntrySelection.all(),
                    IO.directoryTarget(Path.of("unpacked")),
                    control);
        }

        PackRequest request = new PackRequest(
                detection.encoding().orElseThrow(),
                List.of(
                        IO.archiveLayer(source),
                        IO.directoryLayer(Path.of("Patch"))),
                packOptions);
        library.pack(request, IO.pathDestination(outputPath), control);
    }
}
