# Starfield DDS BA2 wire vectors

These hexadecimal archives are independently authored from the permanent General BA2 and DDS
BA2 requirements. They are project-owned CC0-1.0 data containing one generated 4x4 BC1 texture,
not game content. The build-only Java generator writes the BA2 fields, deterministic zlib block,
and raw-LZ4 block directly. From the repository root, build `:jbsa-test-support:classes`, then run:

```powershell
java -cp jbsa-test-support/target/classes/java/main io.github.evildarkarchon.jbsa.fixtures.StarfieldArchiveFixtureGenerator --dds-output <empty-directory>
```

The generator refuses a nonempty destination and reproduces both hexadecimal vectors and the
manifest. `manifest.json` records the generator identity, exact generation command, inputs,
Reference Snapshot revision, and SHA-256 data identities. Decode hex before archive use.

- `starfield-dds-v2-zlib.hex` frames the mip bytes in an RFC 1950 zlib stream.
- `starfield-dds-v3-raw-lz4.hex` frames the same mip bytes as one complete raw-LZ4 literal block
  and declares compression method `3`.
