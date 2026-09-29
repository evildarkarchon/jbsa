# Fallout 4 DDS BA2 v1 wire vector

This CC0 project-authored archive contains one `textures/checker.dds` entry with
one opaque 4x4 BC1 mip block (`63 0e 87 36 56 a6 ce 50`). Its DX10 record and
version-1 header are serialized directly from `docs/spec/formats/dds-ba2.md`.
The payload uses one complete literal-only RFC 1950 stream, so compression
provider choices cannot change the fixture bytes.

Build `:jbsa-test-support:classes`, then generate the hex vector and manifest
into an empty directory:

```powershell
java -cp jbsa-test-support/target/classes/java/main io.github.evildarkarchon.jbsa.fixtures.Fo4DdsV1FixtureGenerator --output target/fo4-dds-v1
```

`Fallout4V1DdsBa2ConformanceIT` regenerates both files in a temporary directory,
compares them with the committed corpus, and checks public decoding and extraction.
Decode hexadecimal before archive use. The historical FO4 DDS v1 CV1 review
packet under `docs/reviews/issue40-cv1` remains separate frozen evidence.
