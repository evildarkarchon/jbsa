# FO3 / FNV / Skyrim LE synthetic BSA vectors

These CC0-1.0, project-authored vectors cover stored, zlib, and mixed entries,
each with and without explicit embedded names, for all three game selectors.
The selectors intentionally produce identical bytes for identical options:
they select the same wire family, not three distinct formats. These fixtures
are synthetic interoperability inputs, not proprietary game assets or evidence
of manual game acceptance.

`manifest.json` records source payloads, game/selector assignments, the generator
procedure version, wire digests and hexadecimal-file digests. Regenerate with:

```powershell
gradle :jbsa-test-support:classes
java -cp jbsa-test-support/target/classes/java/main io.github.evildarkarchon.jbsa.fixtures.Bsa068FixtureGenerator --output target/bsa068-fixtures
```

The independent Java recipe imports no JBSA or xEdit code. Zlib fixture bytes
use the JDK provider and make no cross-provider Binary Conformance claim.
The separate scanner validates complete named, contiguous, unshared ASCII
archives. It deliberately rejects noncanonical embedded-name mismatches;
product tests separately establish their tolerated-warning disposition.
`Bsa068ConformanceIT` regenerates and byte-compares every vector and the
manifest. The generator procedure is versioned without a source digest.
