package prototype.algebra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/** Compile-only prototype of a typed operation interpreter and resource ports. */
public final class OperationAlgebraPrototype {
    private OperationAlgebraPrototype() {
    }

    /** Deep module whose single method interprets every supported operation. */
    public interface BethesdaArchives {
        /** Performs one operation on the caller's invoking thread. */
        <R> R perform(Operation<R> operation, RunContext context)
                throws ArchiveOperationException;
    }

    /** Access to the standard implementation without defining a hypothetical SPI. */
    public static final class Archives {
        private Archives() {
        }

        /** Returns the sole supported implementation of the deep module. */
        public static BethesdaArchives standard() {
            throw new UnsupportedOperationException("compile-only interface prototype");
        }
    }

    /** Typed operation algebra. */
    public sealed interface Operation<R> permits Detect, Inspect, Extract, Pack {
    }

    public record Detect(ArchiveInput input) implements Operation<ArchiveDetection> {
    }

    public record Inspect(
            ArchiveInput input,
            InspectionOptions options) implements Operation<ArchiveView> {
    }

    public record Extract(
            ArchiveView archive,
            EntrySelection selection,
            ExtractionTarget target,
            ExtractionOptions options) implements Operation<ExtractionResult> {
    }

    public record Pack(
            PackRecipe recipe,
            ArchiveOutput output) implements Operation<PackingResult> {
    }

    /** Repeatable seekable input whose returned channel becomes library-owned. */
    public interface ArchiveInput {
        /** Opens a fresh channel positioned at zero. */
        SeekableByteChannel open() throws IOException;

        /** Returns the size when it is cheap to know before opening. */
        OptionalLong size();

        /** Returns a diagnostic name hint that cannot control format detection. */
        Optional<String> nameHint();

        /** Adapts a Path to this input seam. */
        static ArchiveInput from(Path path) {
            return new ArchiveInput() {
                @Override
                public SeekableByteChannel open() throws IOException {
                    return Files.newByteChannel(path, StandardOpenOption.READ);
                }

                @Override
                public OptionalLong size() {
                    try {
                        return OptionalLong.of(Files.size(path));
                    } catch (IOException ignored) {
                        // The operation can still discover size after opening the input.
                        return OptionalLong.empty();
                    }
                }

                @Override
                public Optional<String> nameHint() {
                    return Optional.of(path.toString());
                }
            };
        }
    }

    /** Multi-part output port with explicit per-part publication. */
    public interface ArchiveOutput {
        /** Begins one proposed part; the operation closes the returned sink. */
        PartSink begin(ArchivePart proposal) throws IOException;
    }

    /** One output part whose commit and abort semantics are adapter-defined. */
    public interface PartSink extends AutoCloseable {
        /** Returns the bounded-write channel owned by this sink. */
        WritableByteChannel channel();

        /** Publishes the completed part. */
        void commit() throws IOException;

        /** Abandons the incomplete part. */
        void abort() throws IOException;

        /** Releases the channel and any staging resource. */
        @Override
        void close() throws IOException;
    }

    public record ArchivePart(
            int index,
            String suggestedName,
            OptionalLong estimatedSize) {
    }

    /** Owned inspection lifetime with cursored listing and entry capabilities. */
    public interface ArchiveView extends AutoCloseable {
        /** Returns archive-level metadata. */
        ArchiveMetadata metadata();

        /** Opens a caller-owned cursor over matching entry metadata. */
        EntryCursor entries(EntryQuery query) throws ArchiveOperationException;

        /** Finds one entry through Archive Family normalization. */
        Optional<ArchiveEntry> entry(ArchiveName name) throws ArchiveOperationException;

        /** Closes the input and invalidates cursors and entry channels. */
        @Override
        void close();
    }

    /** Streaming metadata cursor for unusually large indexes. */
    public interface EntryCursor extends AutoCloseable {
        /** Advances to the next matching entry. */
        boolean advance() throws ArchiveOperationException;

        /** Returns the current entry after a successful advance. */
        ArchiveEntryMetadata current();

        /** Releases cursor state. */
        @Override
        void close();
    }

    /** Entry capability tied to its parent ArchiveView. */
    public interface ArchiveEntry {
        /** Returns detached logical and stored metadata. */
        ArchiveEntryMetadata metadata();

        /** Opens caller-owned canonical uncompressed bytes. */
        ReadableByteChannel openContent() throws ArchiveOperationException;
    }

    /** Ordered sources, selection, and settings for a pack operation. */
    public record PackRecipe(
            ArchiveEncoding target,
            List<AssetSource> orderedSources,
            EntrySelection selection,
            PackSettings settings) {
        public PackRecipe {
            orderedSources = List.copyOf(orderedSources);
        }
    }

    /** Cursor-producing source port for directories, archives, and generated data. */
    public interface AssetSource {
        /** Opens a caller-configured source whose cursor becomes operation-owned. */
        AssetCursor open(RunContext context) throws ArchiveOperationException;
    }

    /** Streaming cursor over assets to avoid payload materialization. */
    public interface AssetCursor extends AutoCloseable {
        /** Advances to the next asset. */
        boolean advance() throws ArchiveOperationException;

        /** Returns the current asset after a successful advance. */
        Asset current();

        /** Releases source enumeration resources. */
        @Override
        void close();
    }

    /** Logical entry plus a repeatable channel source. */
    public record Asset(
            ArchiveName name,
            ContentSource content,
            FamilyEntryMetadata requestedMetadata) {
    }

    /** Repeatable bounded source of one logical payload. */
    public interface ContentSource {
        /** Returns the logical size without narrowing to int. */
        long uncompressedSize();

        /** Opens a fresh channel that the operation closes. */
        ReadableByteChannel open() throws IOException;
    }

    /** Cross-cutting operation context with no console or process concerns. */
    public record RunContext(
            CancellationToken cancellation,
            ProgressObserver progress,
            ResourceBudget resourceBudget,
            PolicySet policies) {
    }

    /** Supplies cooperative cancellation state. */
    @FunctionalInterface
    public interface CancellationToken {
        /** Returns whether the operation should cancel at a defined checkpoint. */
        boolean cancellationRequested();
    }

    /** Observes immutable progress values. */
    @FunctionalInterface
    public interface ProgressObserver {
        /** Receives the next structured progress event. */
        void onProgress(ProgressEvent event);
    }

    public record ProgressEvent(
            OperationPhase phase,
            long completedUnits,
            OptionalLong totalUnits,
            Optional<ArchiveName> currentEntry) {
    }

    public record ResourceBudget(
            OptionalLong maximumResidentBytes,
            OptionalLong maximumTemporaryBytes) {
    }

    /** Extensible typed policies whose supported keys are library-defined. */
    public interface PolicySet {
        /** Looks up one supported policy value. */
        <T> Optional<T> find(PolicyKey<T> key);
    }

    public record PolicyKey<T>(String name, Class<T> valueType) {
    }

    /** Extensible typed metadata with library-defined keys. */
    public interface Metadata {
        /** Returns available metadata keys. */
        Set<MetadataKey<?>> keys();

        /** Looks up one typed metadata value. */
        <T> Optional<T> find(MetadataKey<T> key);
    }

    public record MetadataKey<T>(String name, Class<T> valueType) {
    }

    public record ArchiveMetadata(
            ArchiveEncoding encoding,
            long encodedSize,
            long entryCount,
            Metadata familyMetadata,
            List<ConformanceDiagnostic> diagnostics) {
        public ArchiveMetadata {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ArchiveEntryMetadata(
            long ordinal,
            ArchiveName name,
            ByteBuffer originalWireName,
            long storedSize,
            long uncompressedSize,
            FamilyEntryMetadata familyMetadata) {
        public ArchiveEntryMetadata {
            originalWireName = originalWireName.asReadOnlyBuffer();
        }
    }

    /** Marker for strongly typed TES3/BSA/General BA2/DDS BA2 value records. */
    public interface FamilyEntryMetadata {
    }

    /** Archive Family remains distinct from version and BA2 subtype. */
    public record ArchiveEncoding(
            ArchiveFamily family,
            OptionalInt wireVersion,
            Optional<Ba2Subtype> ba2Subtype) {
    }

    public record ArchiveName(String displayName) {
        /** Creates a logical name without host Path normalization. */
        public static ArchiveName of(String displayName) {
            return new ArchiveName(displayName);
        }
    }

    @FunctionalInterface
    public interface EntryQuery {
        /** Returns whether one detached entry belongs in this cursor. */
        boolean includes(ArchiveEntryMetadata metadata);
    }

    @FunctionalInterface
    public interface EntrySelection {
        /** Returns whether one detached entry belongs in an operation. */
        boolean includes(ArchiveEntryMetadata metadata);

        /** Selects all entries. */
        static EntrySelection all() {
            return ignored -> true;
        }
    }

    /** Target port whose adapters must enforce containment before writes. */
    public interface ExtractionTarget {
        /** Begins extraction after the library completes logical preflight. */
        ExtractionSink begin(ArchiveMetadata archive) throws IOException;
    }

    /** Operation-owned extraction lifetime. */
    public interface ExtractionSink extends AutoCloseable {
        /** Opens one contained entry destination. */
        WritableByteChannel openEntry(ArchiveEntryMetadata entry) throws IOException;

        /** Publishes completed extraction output. */
        void commit() throws IOException;

        /** Releases staging output; close without commit is not success. */
        @Override
        void close() throws IOException;
    }

    public record InspectionOptions(boolean fullFamilyMetadata) {
    }

    public record ExtractionOptions(boolean replaceExisting) {
    }

    public record PackSettings(
            Compression compression,
            boolean shareIdenticalPayloads,
            OptionalLong splitAtBytes) {
    }

    public record ArchiveDetection(
            DetectionKind kind,
            Optional<ArchiveEncoding> encoding,
            List<ConformanceDiagnostic> diagnostics) {
    }

    public record ExtractionResult(long entryCount, long logicalBytes) {
    }

    public record PackingResult(long entryCount, long logicalBytes) {
    }

    /** Stable machine-facing diagnostic. */
    public record ConformanceDiagnostic(
            String identifier,
            Severity severity,
            String operation,
            Optional<ArchiveName> entry,
            Map<String, String> values) {
    }

    /** Stable checked failure independent of presentation wording. */
    public static final class ArchiveOperationException extends Exception {
        private final String code;
        private final List<ConformanceDiagnostic> diagnostics;

        /** Creates a structured operation failure with an optional I/O cause. */
        public ArchiveOperationException(
                String code,
                List<ConformanceDiagnostic> diagnostics,
                Throwable cause) {
            super(code, cause);
            this.code = code;
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** Returns the machine-comparable failure code. */
        public String code() {
            return code;
        }

        /** Returns immutable diagnostics accumulated before failure. */
        public List<ConformanceDiagnostic> diagnostics() {
            return diagnostics;
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
        DETECTING, INDEXING, PREFLIGHTING, READING, WRITING, VERIFYING
    }

    public enum Severity { INFORMATION, WARNING, ERROR }

    /** Compile-checked example of the operation-algebra calling style. */
    public static void cliLikeUsage(
            Path archivePath,
            ArchiveOutput archiveOutput,
            ExtractionTarget extractionTarget,
            AssetSource patchSource,
            RunContext context) throws ArchiveOperationException, IOException {
        BethesdaArchives library = Archives.standard();

        ArchiveDetection detection = library.perform(
                new Detect(ArchiveInput.from(archivePath)),
                context);

        try (ArchiveView archive = library.perform(
                new Inspect(ArchiveInput.from(archivePath), new InspectionOptions(true)),
                context)) {
            try (EntryCursor entries = archive.entries(ignored -> true)) {
                while (entries.advance()) {
                    System.out.println(entries.current().name().displayName());
                }
            }

            ArchiveEntry entry = archive.entry(ArchiveName.of("textures/hero.dds"))
                    .orElseThrow();
            try (ReadableByteChannel content = entry.openContent()) {
                ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
                content.read(buffer);
            }

            library.perform(
                    new Extract(
                            archive,
                            EntrySelection.all(),
                            extractionTarget,
                            new ExtractionOptions(false)),
                    context);
        }

        PackRecipe recipe = new PackRecipe(
                detection.encoding().orElseThrow(),
                List.of(patchSource),
                EntrySelection.all(),
                new PackSettings(Compression.FAMILY_DEFAULT, true, OptionalLong.empty()));
        library.perform(new Pack(recipe, archiveOutput), context);
    }
}
