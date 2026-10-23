package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.Operation;
import io.github.evildarkarchon.jbsa.PackOptions;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link SplitPlanner} from a declarative split cost, the payload cost an Emission implies,
 * and a resolved byte target, with no family adapter. The request's Splitting arm is resolved the
 * way the pipeline resolves it.
 */
final class SplitPlannerTest {
  private static final IoContext CONTEXT =
      IoContext.destination(Path.of("x").toAbsolutePath(), Operation.PACK);

  /** A layout function that admits every part. */
  private static final SplitPlanner.PartLayout<String> ANY_LAYOUT = part -> {};

  /** The three Splitting arms, driven by a declarative split cost. */
  @Test
  void splitArmsFormWholeEntryParts() {
    List<Admitted.Planned<String>> planned =
        List.of(planned("a", 3), planned("b", 3), planned("c", 1));
    assertEquals(List.of(3), sizes(planned, 0, new PackOptions.Splitting.FamilyDefault()));
    assertEquals(List.of(1, 2), sizes(planned, 4, new PackOptions.Splitting.FamilyDefault()));
    assertEquals(List.of(3), sizes(planned, 4, new PackOptions.Splitting.UpToBytes(0)));
    assertEquals(List.of(2, 1), sizes(planned, 0, new PackOptions.Splitting.UpToBytes(6)));
    assertEquals(List.of(1, 1, 1), sizes(planned, 0, new PackOptions.Splitting.LegacyPerEntry()));
  }

  /**
   * D9: a stabilized plan's preflight counts exactly the parts it is certain to publish. Predicted
   * stored sizes split exactly; with an unknown encoded size only "never splits" and "no two
   * entries fit one part" are certain, and any other plan is certain only of its first part.
   */
  @Test
  void knownPartCountCoversOnlyCertainParts() throws Exception {
    var stored = List.of(planned("a", 3), planned("b", 3), planned("c", 1));
    assertEquals(
        2,
        planner(0, 4, new PackOptions.Splitting.FamilyDefault())
            .knownPartCount(stored, false, ANY_LAYOUT));

    var encoded = List.of(encoded("a", 3), encoded("b", 3), encoded("c", 1));
    assertEquals(
        1,
        planner(0, 4, new PackOptions.Splitting.UpToBytes(0))
            .knownPartCount(encoded, false, ANY_LAYOUT));
    // A zero minimum cost lets entries share a part, so only the first part is certain.
    assertEquals(
        1,
        planner(0, 4, new PackOptions.Splitting.LegacyPerEntry())
            .knownPartCount(encoded, false, ANY_LAYOUT));
    assertEquals(
        3,
        planner(200, 0, new PackOptions.Splitting.LegacyPerEntry())
            .knownPartCount(encoded, false, ANY_LAYOUT));
    assertEquals(
        3,
        planner(200, 0, new PackOptions.Splitting.UpToBytes(200))
            .knownPartCount(encoded, false, ANY_LAYOUT));
    assertEquals(
        1,
        planner(200, 0, new PackOptions.Splitting.UpToBytes(201))
            .knownPartCount(encoded, false, ANY_LAYOUT));
  }

  /**
   * An exact prediction admits each predicted part's layout, in output order, before the count is
   * returned, so a wire limit fails before any source is read; the layout's failure is the result.
   */
  @Test
  void exactKnownPartCountAdmitsEveryPredictedLayoutFirst() {
    var stored = List.of(planned("a", 3), planned("b", 3), planned("c", 1));
    var seen = new ArrayList<List<String>>();
    var planner = planner(0, 4, new PackOptions.Splitting.FamilyDefault());

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                planner.knownPartCount(
                    stored,
                    false,
                    part -> {
                      seen.add(part.stream().map(Admitted.Planned::key).toList());
                      if (part.size() > 1)
                        throw CONTEXT.failure(FailureKind.POLICY, "memory.layout-too-large", null);
                    }));

    assertEquals(
        Optional.of("memory.layout-too-large"),
        failure.primaryFailure().diagnosticIdentifier());
    assertEquals(List.of(List.of("a"), List.of("b", "c")), seen);
  }

  /**
   * Returns part sizes for a split under the given family default target, charging the declared
   * size with no fixed or name cost.
   */
  private static List<Integer> sizes(
      List<Admitted.Planned<String>> planned, long familyDefault, PackOptions.Splitting splitting) {
    return planner(0, familyDefault, splitting).split(planned).stream()
        .map(List::size)
        .toList();
  }

  /**
   * A planner charging {@code fixed} per entry plus each entry's declared size, the streamed
   * Emission's payload cost, at the target {@code splitting} resolves to.
   */
  private static SplitPlanner<String> planner(
      long fixed, long familyDefault, PackOptions.Splitting splitting) {
    return new SplitPlanner<>(
        new Admitted.SplitCost<>(fixed, name -> 0),
        SplitPlanner.PayloadCost.DECODED,
        PackPipeline.target(familyDefault, splitting));
  }

  /** A planned stored entry of a given declared size, whose payload is never read. */
  private static Admitted.Planned<String> planned(String name, long size) {
    var entry =
        new PackSources.Entry(
            name,
            name,
            name.getBytes(StandardCharsets.US_ASCII),
            0,
            size,
            (reader, processing) -> {
              throw new AssertionError("Splitting must not read payloads");
            },
            IoContext.generated(name, Operation.PACK));
    return new Admitted.Planned<>(entry, name, PayloadCodec.STORED, new byte[0]);
  }

  /** A planned zlib entry, whose stored size stays unknown until stabilization. */
  private static Admitted.Planned<String> encoded(String name, long size) {
    var stored = planned(name, size);
    return new Admitted.Planned<>(stored.source(), name, PayloadCodec.ZLIB, new byte[0]);
  }
}
