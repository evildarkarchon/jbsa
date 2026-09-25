package io.github.evildarkarchon.jbsa;

/** Upper bound on operation processing workers, excluding the synchronous coordinator. */
public sealed interface WorkerSelection permits WorkerSelection.Automatic, WorkerSelection.UpTo {
  /** Snapshots available processors at operation start, using at least one worker. */
  WorkerSelection AUTOMATIC = new Automatic();

  /** Host-derived worker selection; construction does not snapshot the host. */
  record Automatic() implements WorkerSelection {}

  /** Explicit positive worker ceiling; one requests sequential processing. */
  record UpTo(long workers) implements WorkerSelection {
    /** Creates a worker ceiling; zero or negative values are programmer errors. */
    public UpTo {
      if (workers <= 0) {
        throw new IllegalArgumentException("workers must be positive");
      }
    }
  }
}
