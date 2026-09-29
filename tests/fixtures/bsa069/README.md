# Skyrim SE/AE BSA fixtures

These lowercase-hex vectors are project-authored, redistributable BSA `0x69`
fixtures. `Bsa069FixtureGenerator` constructs them directly from the
written wire specification without invoking JBSA, TES5Edit, or BSArch. The
manifest binds generator inputs, source payloads, wire bytes, and file digests.

```powershell
gradle :jbsa-test-support:classes
java -cp jbsa-test-support/target/classes/java/main io.github.evildarkarchon.jbsa.fixtures.Bsa069FixtureGenerator --output target/bsa069-fixtures
```

`Bsa069ConformanceIT` regenerates and byte-compares the full inventory. Its
independent wire scanner remains a separate check; neither check hashes source code.

Both game labels intentionally encode the same Archive Family. Stored,
LZ4-frame, mixed, and explicit embedded-name combinations are represented. The
LZ4 fixtures use valid independent uncompressed LZ4 blocks inside the exact
level-12 family frame envelope; JBSA's encoder tests separately exercise
provider-compressed blocks.
