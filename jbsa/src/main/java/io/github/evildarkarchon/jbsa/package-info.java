/**
 * Public interfaces for reading, assessing, and writing Bethesda Archives.
 *
 * <p>The 1.0 interface provides bounded detection, detached inspection, owned entry content,
 * extraction, and supported Archive Family encoding through one synchronous module. Archive,
 * source, policy, capability, and destination failures use checked outcomes. A breaking change to
 * this interface requires a compatibility assessment and a revised specification.
 *
 * <p>JBSA is independently authored and informed by documented facts and observable behavior from
 * the pinned TES5Edit Reference Snapshot at {@code fd1e36020b2b5b6217e553dc0038983146a2e2dd}. The
 * project is not affiliated with or endorsed by TES5Edit or BSArch.
 */
package io.github.evildarkarchon.jbsa;
