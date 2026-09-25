# Independent TES3 wire vectors

These project-authored fixtures are dedicated under CC0-1.0. No game data or
Reference Snapshot source code is included. Each `.hex` file is the exact archive
wire byte sequence represented by lowercase hexadecimal with a final LF; decoding
hexadecimal is the only materialization step. This makes every malformed mutation
reviewable without expanding the binary fixture allowlist or changing the pinned
foundation corpus's manifests, generator, catalog or goldens.

`manifest.json` inventories every vector, its decoded size and SHA-256, text-file
SHA-256, author, license, reference revision, exact generator command and generator
identity. The generator remains Apache-2.0. `Tes3ConformanceIT` reproduces and
compares the complete inventory, then exercises the materialized archives only
through the public API. Additional unaccounted files fail its inventory audit.

```powershell
pwsh -NoProfile -File build/generate-tes3-fixtures.ps1 -OutputDirectory target/new-tes3-vectors
```

The generator refuses a nonempty destination. The two-entry canonical vector has
`meshes\a.nif` and `sound\b.wav`; payloads are project-authored bytes. Six variants
isolate truncation, impossible counts, partial overlap, name-offset inconsistency,
stored-hash mismatch and trailing data. The fixed hash and wire vectors do not call
production codec logic. The separate canonical scanner and optional pinned oracle
differentials are described in [TES3 evidence](../../../docs/development/tes3.md).
