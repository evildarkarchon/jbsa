/**
 * Provides the deep, public Bethesda Archive library seam.
 *
 * <p>Archive Family implementations and third-party providers remain encapsulated behind this
 * module.
 */
module io.github.evildarkarchon.jbsa {
  requires jdk.unsupported;
  requires static org.lwjgl;
  requires static org.lwjgl.lz4;

  exports io.github.evildarkarchon.jbsa;
}
