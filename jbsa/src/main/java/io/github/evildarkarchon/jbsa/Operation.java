package io.github.evildarkarchon.jbsa;

/** A synchronous archive operation that can own diagnostics. */
public enum Operation {
  /** Bounded archive recognition. */
  DETECT,
  /** Detached structural inspection. */
  INSPECT,
  /** Opening an owned archive. */
  OPEN,
  /** Streaming one entry's content. */
  READ_CONTENT,
  /** Extracting selected entries. */
  EXTRACT,
  /** Encoding an archive. */
  PACK
}
