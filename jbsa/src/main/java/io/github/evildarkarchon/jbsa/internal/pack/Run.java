package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.PackRequest;
import io.github.evildarkarchon.jbsa.WorkerSelection;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.OperationSession;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.util.Objects;

/**
 * Operation-owned collaborators every Emission path borrows, plus the scope where it registers the
 * caller-owned resources the operation template closes. The pack body builds it once, right after
 * codec admission, so no Emission path can see a collaborator before it exists. Scratch an emitter
 * opens itself, such as the stabilized spool, belongs to that emitter rather than to the run.
 *
 * @param request the pack request
 * @param operation the operation session whose template runs the pack body
 * @param context the pack's own location, its destination
 * @param budget the pack's resource budget, which the template closes before its epilogue settles
 * @param resources where emitters register caller-owned resources for the template to close
 * @param workers the worker selection snapshot taken right after {@link OperationSession#begin}
 * @param codecs every codec the request admitted, by the requiredCodecs step right after {@code
 *     admit}. Costs, encoders and readback declarations all take their codec from here.
 */
record Run(
    PackRequest request,
    OperationSession operation,
    IoContext context,
    ResourceBudget budget,
    OperationSession.Resources resources,
    WorkerSelection.UpTo workers,
    Codecs codecs) {
  /** Requires every collaborator. */
  Run {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(workers, "workers");
    Objects.requireNonNull(codecs, "codecs");
  }
}
