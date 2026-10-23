package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.util.Objects;

/**
 * The internal LZ4 provider seam: one LZ4 implementation's frame and raw-block codec. {@link
 * NativeLz4} adapts the qualified LWJGL binding and {@link PortableLz4} lz4-java's pure-Java safe
 * provider (JBSA-CODEC-014). Production reaches an adapter only through the provider an {@link
 * AdmittedCodec} resolved, or through {@link Lz4Runtime#preflight} for the qualification-only
 * general frame. Tests choose one the same way, through the module's one test factory, {@link
 * AdmittedCodec#forProvider}, and never call {@link #of} themselves.
 *
 * <p>An adapter is a stateless singleton and keeps every piece of codec state per call or per
 * decoder, so concurrent calls never share mutable state. Every method borrows the caller's lease,
 * which already covers the provider's cost from {@link AdmittedCodec}, and rejects a missing one
 * before any other effect; it never reserves or closes credit itself. Size admission is the
 * caller's: frame sizes are non-negative, and raw sizes are within the raw dispatch limits.
 *
 * <p>This interface must stay free of LWJGL and lz4-java references, so that loading it, or
 * selecting one adapter, never loads the other provider's library.
 */
interface Lz4Adapter {
  /**
   * Returns the adapter of one provider. This is the one place that branches on the provider, so
   * the native and portable code paths never meet anywhere else.
   *
   * <p>Each adapter is reached through its own holder field, which the JVM resolves only when this
   * method returns it, so selecting one never loads the other's class.
   */
  static Lz4Adapter of(Provider provider) {
    Objects.requireNonNull(provider, "provider");
    if (provider == Provider.PORTABLE) return PortableLz4.ADAPTER;
    return NativeLz4.ADAPTER;
  }

  /**
   * Loads whatever this provider still needs before the caller's first side effect. The native
   * adapter has nothing left to load, because {@link Lz4Runtime} pins it only after it has loaded.
   *
   * @throws LinkageError or a runtime exception when the provider's library cannot load
   */
  void admit();

  /**
   * Encodes one LZ4 frame in 64 KiB windows, flushing each window as its own block.
   *
   * @param bsaProfile true for the versioned BSA 0x69 profile (level 12, 4 MiB block maximum,
   *     independent blocks, no checksums or content size); false for the qualification-only general
   *     profile (level 9, 64 KiB block maximum, content checksum and size)
   * @return the encoded frame length
   * @throws NullPointerException when {@code lease} is null
   * @throws IOException a structured codec, source, or sink failure, or the checkpoint's own
   *     cancellation
   */
  long encodeFrame(
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context,
      boolean bsaProfile)
      throws IOException;

  /**
   * Creates an incremental frame decoder that borrows its positional source and the owner's lease,
   * which outlives the decoder. Failures carry this provider's own profile identity.
   *
   * @throws NullPointerException when {@code lease} is null
   * @throws ArchiveException when the decoder's state cannot be created
   */
  Lz4Frame.Decoder frameDecoder(
      ByteSource source,
      long storedSize,
      long decodedSize,
      ResourceBudget.Lease lease,
      IoContext context)
      throws ArchiveException;

  /**
   * Encodes one whole raw HC block without fallback or splitting.
   *
   * @param compressionLevel the HC level, from 1 through 12
   * @return the encoded block length
   * @throws NullPointerException when {@code lease} is null
   * @throws IOException a structured codec, source, or sink failure, or the checkpoint's own
   *     cancellation
   */
  long encodeRaw(
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context,
      int compressionLevel)
      throws IOException;

  /**
   * Decodes one complete raw block, publishing nothing unless it decodes to exactly {@code
   * decodedSize} bytes.
   *
   * @return the decoded length, always {@code decodedSize}
   * @throws NullPointerException when {@code lease} is null
   * @throws IOException a structured codec, source, or sink failure, or the checkpoint's own
   *     cancellation
   */
  long decodeRaw(
      ByteSource source,
      long storedSize,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException;
}
