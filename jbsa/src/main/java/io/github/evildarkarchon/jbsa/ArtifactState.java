package io.github.evildarkarchon.jbsa;

/** Observable post-operation artifact state; it does not imply operation-wide atomic visibility. */
public enum ArtifactState {
  /** The intended output was published. */
  PUBLISHED,
  /** Existing content was left unchanged. */
  UNCHANGED,
  /** Earlier content was restored by rollback. */
  RESTORED,
  /** The affected artifact is absent. */
  MISSING,
  /** Owned staging remains and cleanup ownership has passed to the caller. */
  RESIDUAL_STAGING
}
