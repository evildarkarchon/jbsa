# TES4 / Oblivion slice vectors

These project-authored CC0-1.0 text vectors implement the written 0x67 wire
contract independently of JBSA and xEdit. `manifest.json` inventories every
vector's decoded length and SHA-256. Regenerate with:

```powershell
python build/generate-bsa-fixtures.py --output target/tes4-regenerated
```

The three positive vectors contain `meshes/a.nif` (1024 ASCII A bytes) and
`meshes/b.nif` (`00 01 02 ff`), covering stored, zlib, and mixed framing. The zlib
vector deliberately compresses the small entry too, exercising valid decode
independently of compression savings. The malformed variants truncate a
payload or change the declared decoded size. Compressed vector bytes are Python
zlib evidence; they do not assert cross-provider compressed Binary Conformance.

`build/validate-bsa-wire.py` independently scans the named, contiguous, unshared
ASCII subset, checks name hashes and full zlib consumption, and emits semantic
payload digests. The scanner imports neither the generator nor product code.
`BsaConformanceIT` reproduces this corpus and checks the public library seam.
These tests do not create or replace immutable CV1 goldens.
