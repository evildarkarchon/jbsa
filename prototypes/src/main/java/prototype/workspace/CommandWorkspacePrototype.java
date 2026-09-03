package prototype.workspace;

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
 * Compile-only prototype of one effective source workspace and command interpreter.
 */
public final class CommandWorkspacePrototype {
    private CommandWorkspacePrototype() {
    }

    /** Minimal operational facade: opening is the only static entry point. */
    public static final class BethesdaArchives {
        private BethesdaArchives() {
        }

        /** Opens an owned, lazy, later-source-wins view over ordered sources. */
        public static Workspace open(SourceStack sources, OpenOptions options) {
            throw new UnsupportedOperationException("compile-only interface prototype");
        }
    }

    /** Owns sources, indexes, temporary resources, and all command execution. */
    public interface Workspace extends AutoCloseable {
        /** Interprets one typed command synchronously while hiding internal scheduling. */
        <R> Outcome<R> run(Command<R> command, Control control);

        /** Releases every input and internal temporary resource. */
        @Override
        void close();
    }

    /** Complete public operation algebra for the workspace. */
    public sealed interface Command<R> permits Inspect, Transfer, Pack {
    }

    /** Detects, summarizes, or lists the effective source view. */
    public record Inspect(
            InspectionDetail detail,
            Selection selection) implements Command<Inspection> {
    }

    /** Transfers logical entry bytes to a safe directory or callback receiver. */
    public record Transfer(
            Selection selection,
            TransferTarget target) implements Command<TransferReport> {
    }

    /** Encodes the effective workspace view as one or more Bethesda Archives. */
    public record Pack(
            Path firstOutput,
            ArchiveEncoding encoding,
            PackRules rules) implements Command<PackReport> {
    }

    /** Ordered sources where later normalized names replace earlier payloads. */
    public record SourceStack(List<Source> sources) {
        public SourceStack {
            sources = List.copyOf(sources);
        }
    }

    /** Kinds of source already required by known consumers and conformance tests. */
    public sealed interface Source
            permits ArchiveFile, DirectoryTree, LooseFile, GeneratedEntries {
    }

    public record ArchiveFile(Path path) implements Source {
    }

    public record DirectoryTree(Path root) implements Source {
    }

    public record LooseFile(Path file, ArchiveName name) implements Source {
    }

    public record GeneratedEntries(List<GeneratedEntry> entries) implements Source {
        public GeneratedEntries {
            entries = List.copyOf(entries);
        }
    }

    /** Generated payload whose channel becomes workspace-owned once opened. */
    public record GeneratedEntry(
            ArchiveName name,
            OptionalLong declaredSize,
            PayloadSource payload) {
    }

    /** Repeatable source of a generated entry's logical bytes. */
    @FunctionalInterface
    public interface PayloadSource {
        /** Opens a fresh channel that the workspace closes after the operation. */
        ReadableByteChannel open() throws IOException;
    }

    /** Selection algebra shared by inspection, transfer, and packing. */
    public sealed interface Selection permits All, Exact, Prefix, GlobSet {
    }

    public record All() implements Selection {
    }

    public record Exact(ArchiveName name) implements Selection {
    }

    public record Prefix(ArchiveName prefix) implements Selection {
    }

    public record GlobSet(List<String> patterns) implements Selection {
        public GlobSet {
            patterns = List.copyOf(patterns);
        }
    }

    /** Target algebra distinguishes contained filesystem extraction from callbacks. */
    public sealed interface TransferTarget permits DirectoryTarget, ReceiverTarget {
    }

    public record DirectoryTarget(Path root) implements TransferTarget {
    }

    public record ReceiverTarget(EntryReceiver receiver) implements TransferTarget {
    }

    /** Callback seam that prevents entry channels from escaping their operation. */
    @FunctionalInterface
    public interface EntryReceiver {
        /**
         * Consumes one entry before returning. The workspace owns the channel and
         * invalidates it when the callback completes.
         */
        void receive(EntryMetadata metadata, ReadableByteChannel data) throws IOException;
    }

    /** Cross-cutting controls defer detailed semantics to later decisions. */
    public record Control(
            CancellationToken cancellation,
            ProgressListener progress,
            ResourceBudget resourceBudget,
            ExecutionPolicy executionPolicy) {
    }

    /** Supplies cooperative cancellation state. */
    @FunctionalInterface
    public interface CancellationToken {
        /** Returns whether execution should cancel at its next defined checkpoint. */
        boolean cancellationRequested();
    }

    /** Observes structured operation progress. */
    @FunctionalInterface
    public interface ProgressListener {
        /** Receives a monotonically advancing progress value. */
        void onProgress(Progress progress);
    }

    public record ResourceBudget(
            OptionalLong maximumResidentBytes,
            OptionalLong maximumTemporaryBytes) {
    }

    public record ExecutionPolicy(String identifier) {
    }

    public record Progress(
            Operation operation,
            Phase phase,
            Optional<ArchiveName> entry,
            long completed,
            OptionalLong total,
            ProgressUnit unit) {
    }

    /** Result algebra makes cancellation and partial artifacts visible. */
    public sealed interface Outcome<R> permits Completed, Failed, Cancelled {
    }

    public record Completed<R>(
            R value,
            List<ConformanceDiagnostic> diagnostics) implements Outcome<R> {
        public Completed {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Failed<R>(
            Optional<R> partial,
            List<ConformanceDiagnostic> diagnostics,
            List<Path> artifacts) implements Outcome<R> {
        public Failed {
            diagnostics = List.copyOf(diagnostics);
            artifacts = List.copyOf(artifacts);
        }
    }

    public record Cancelled<R>(
            Optional<R> partial,
            List<ConformanceDiagnostic> diagnostics,
            List<Path> artifacts) implements Outcome<R> {
        public Cancelled {
            diagnostics = List.copyOf(diagnostics);
            artifacts = List.copyOf(artifacts);
        }
    }

    /** Inspection includes each source and the effective overlaid entry view. */
    public record Inspection(
            List<SourceMetadata> sources,
            List<EntryMetadata> effectiveEntries) {
        public Inspection {
            sources = List.copyOf(sources);
            effectiveEntries = List.copyOf(effectiveEntries);
        }
    }

    public record SourceMetadata(
            int sourceIndex,
            Optional<ArchiveMetadata> detectedArchive,
            long effectiveEntryCount) {
    }

    public record ArchiveMetadata(
            ArchiveEncoding encoding,
            long encodedSize,
            long entryCount,
            List<ConformanceDiagnostic> diagnostics) {
        public ArchiveMetadata {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record EntryMetadata(
            ArchiveName name,
            ByteBuffer originalWireName,
            long storedSize,
            long uncompressedSize,
            int winningSourceIndex,
            FamilyEntryMetadata familyMetadata) {
        public EntryMetadata {
            originalWireName = originalWireName.asReadOnlyBuffer();
        }
    }

    /** Family-specific facts remain values rather than parser implementation types. */
    public sealed interface FamilyEntryMetadata
            permits Tes3Metadata, BsaMetadata, GeneralBa2Metadata, DdsBa2Metadata {
    }

    public record Tes3Metadata(long wireHash, long dataOffset)
            implements FamilyEntryMetadata {
    }

    public record BsaMetadata(long folderHash, long fileHash, long dataOffset)
            implements FamilyEntryMetadata {
    }

    public record GeneralBa2Metadata(List<ChunkMetadata> chunks)
            implements FamilyEntryMetadata {
        public GeneralBa2Metadata {
            chunks = List.copyOf(chunks);
        }
    }

    public record DdsBa2Metadata(
            int width,
            int height,
            int mipCount,
            int dxgiFormat,
            boolean cubemap,
            List<ChunkMetadata> chunks) implements FamilyEntryMetadata {
        public DdsBa2Metadata {
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

    /** Archive Family remains separate from version and BA2 subtype. */
    public record ArchiveEncoding(
            ArchiveFamily family,
            OptionalInt wireVersion,
            Optional<Ba2Subtype> ba2Subtype,
            Compression compression) {
    }

    public record OpenOptions(
            int archiveNameCodePage,
            Optional<String> compatibilityProfile) {
    }

    public record PackRules(
            Selection selection,
            boolean shareIdenticalPayloads,
            OptionalLong splitAtBytes,
            OptionalInt archiveFlags,
            OptionalInt fileFlags) {
    }

    public record TransferReport(
            long entryCount,
            long logicalBytes,
            List<Path> artifacts) {
    }

    public record PackReport(
            long entryCount,
            long logicalBytes,
            List<Path> archives) {
    }

    /** Stable diagnostic shared by the CLI and embedded consumers. */
    public record ConformanceDiagnostic(
            String identifier,
            Severity severity,
            Operation operation,
            Optional<ArchiveName> entry,
            Optional<String> field,
            Map<String, String> values) {
        public ConformanceDiagnostic {
            values = Map.copyOf(values);
        }
    }

    /** Archive name that deliberately does not use host Path normalization. */
    public record ArchiveName(String displayName) {
        public ArchiveName {
            Objects.requireNonNull(displayName);
        }

        /** Creates a logical archive name. */
        public static ArchiveName of(String displayName) {
            return new ArchiveName(displayName);
        }
    }

    public enum InspectionDetail { DETECTION, SUMMARY, ENTRIES, FULL_METADATA }

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

    public enum Operation { INSPECT, TRANSFER, PACK }

    public enum Phase { DISCOVERING, READING, DECODING, WRITING, VERIFYING }

    public enum ProgressUnit { ENTRIES, BYTES }

    public enum Severity { INFORMATION, WARNING, ERROR }

    /** Compile-checked example of one CLI-like command sequence. */
    public static void cliLikeUsage(
            Path archivePath,
            Path outputPath,
            WritableByteChannel callerDestination,
            OpenOptions openOptions,
            Control control) {
        SourceStack sourceStack = new SourceStack(List.of(
                new ArchiveFile(archivePath),
                new DirectoryTree(Path.of("Patch"))));

        try (Workspace workspace = BethesdaArchives.open(sourceStack, openOptions)) {
            Outcome<Inspection> listing = workspace.run(
                    new Inspect(InspectionDetail.ENTRIES, new All()),
                    control);

            Outcome<TransferReport> transfer = workspace.run(
                    new Transfer(
                            new Exact(ArchiveName.of("textures/hero.dds")),
                            new ReceiverTarget((metadata, input) ->
                                    copyInBoundedChunks(input, callerDestination))),
                    control);

            Outcome<PackReport> packed = workspace.run(
                    new Pack(
                            outputPath,
                            new ArchiveEncoding(
                                    ArchiveFamily.STARFIELD_GENERAL_BA2,
                                    OptionalInt.of(2),
                                    Optional.of(Ba2Subtype.GENERAL),
                                    Compression.ZLIB),
                            new PackRules(
                                    new All(),
                                    true,
                                    OptionalLong.empty(),
                                    OptionalInt.empty(),
                                    OptionalInt.empty())),
                    control);

            if (listing instanceof Failed<Inspection>
                    || transfer instanceof Failed<TransferReport>
                    || packed instanceof Failed<PackReport>) {
                // The CLI converts structured outcomes to streams and process exits.
                System.err.println("operation failed");
            }
        }
    }

    /** Copies through a fixed-size buffer without retaining the callback channel. */
    private static void copyInBoundedChunks(
            ReadableByteChannel source,
            WritableByteChannel destination) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        while (source.read(buffer) >= 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                destination.write(buffer);
            }
            buffer.clear();
        }
    }
}
