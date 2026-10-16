package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A filesystem artifact's settled state in logical plan order.
 *
 * <p>For extraction, {@code ordinal} is the archive entry ordinal of the selected entry the
 * artifact belongs to, not its position in the selection, so it matches the ordinal of that entry's
 * failures. The destination root and private staging use the first selected entry's ordinal, or 0
 * when nothing is selected. For pack, {@code ordinal} is the zero-based Archive Part index.
 *
 * @param path exact affected filesystem path, including residual staging when present
 * @param ordinal stable logical artifact ordinal: the archive entry ordinal for extraction, the
 *     part index for pack
 * @param state observed post-operation state
 */
public record Artifact(Path path, long ordinal, ArtifactState state) {
  /** Checks required members and rejects a negative ordinal. */
  public Artifact {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(state, "state");
    if (ordinal < 0) {
      throw new IllegalArgumentException("Artifact ordinal must be nonnegative");
    }
  }
}
