package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A filesystem artifact's settled state in logical plan order.
 *
 * @param path exact affected filesystem path, including residual staging when present
 * @param ordinal stable logical artifact ordinal
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
