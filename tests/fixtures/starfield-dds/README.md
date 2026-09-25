# Starfield DDS BA2 wire vectors

These hexadecimal archives are independently authored from the permanent General BA2 and DDS
BA2 requirements. They are project-owned CC0-1.0 data containing one generated 4x4 BC1 texture,
not game content. `manifest.json` records the provenance, exact inputs, and SHA-256 identities.

- `starfield-dds-v2-zlib.hex` frames the mip bytes in an RFC 1950 zlib stream.
- `starfield-dds-v3-raw-lz4.hex` frames the same mip bytes as one complete raw-LZ4 literal block
  and declares compression method `3`.
