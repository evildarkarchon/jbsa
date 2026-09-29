# Starfield General BA2 wire vectors

These hexadecimal bytes are independently authored from `JBSA-GNRL-001` through
`JBSA-GNRL-005`. They are project-owned CC0-1.0 test data and contain no game assets.
The build-only Java generator serializes the BA2 fields directly without calling the
JBSA archive writer. From the repository root, build `:jbsa-test-support:classes`, then run:

```powershell
java -cp jbsa-test-support/target/classes/java/main io.github.evildarkarchon.jbsa.fixtures.StarfieldArchiveFixtureGenerator --general-output <empty-directory>
```

The generator refuses a nonempty destination and reproduces the hexadecimal vector
and manifest. `manifest.json` records the generator identity, exact generation
command, inputs, Reference Snapshot revision, and data digests. Decode the hex file
before archive use.

- `starfield-general-v2-zlib.hex` is a canonical version-2 archive with one RFC 1950
  payload containing byte `07`, followed by the complete `a/b.txt` filename table.

The version-3 method-3 raw-LZ4 and mixed vectors remain in the shared synthetic
fixture corpus, where their generator recipes and SHA-256 digests are recorded.
