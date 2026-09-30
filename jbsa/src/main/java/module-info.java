/**
 * Provides the deep, public Bethesda Archive library seam.
 *
 * <p>Archive Family implementations and third-party providers remain encapsulated behind this
 * module.
 */
// lz4-java publishes only an Automatic-Module-Name, so requiring it is deliberate.
@SuppressWarnings("requires-automatic")
module io.github.evildarkarchon.jbsa {
  requires jdk.unsupported;
  requires static org.lwjgl;
  requires static org.lwjgl.lz4;
  // The always-present portable LZ4 fallback (JBSA-CODEC-014).
  requires org.lz4.java;

  exports io.github.evildarkarchon.jbsa;
}
