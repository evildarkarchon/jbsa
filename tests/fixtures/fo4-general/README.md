# Fallout 4 General BA2 v1 wire vectors

These CC0 project-authored fixtures implement the published General BA2 layout
independently of JBSA and the Reference Snapshot. Run
`python build/generate-ba2-fixtures.py --output <directory>` to reproduce the
hex files and their digest-bound manifest. Decode hexadecimal before archive use.

Stored, zlib and mixed archives contain `meshes/a.nif` (1,024 ASCII `A` bytes)
and `meshes/b.nif` (`00 01 02 ff`) in that order. Mutations cover bounded tolerated
metadata, absent name tables, structural rejection and deferred payload failure.
The build-only `validate-ba2-wire.py` scanner uses a separate CRC implementation
and checks canonical named archives, complete zlib consumption and exact shared
spans without importing the generator or production library.

`Ba2ConformanceIT` reproduces the corpus, consumes the public library API and
validates candidate archives independently. `-Djbsa.ba2.local=true` additionally
runs both directions against the digest-pinned local Conformance Oracle, retaining
observations under `target/ba2-local-evidence`. This is semantic corroboration,
not full CV1, Binary Conformance or game/tool Release Qualification.

`-Djbsa.ba2.performance=true` enables a current-machine development checkpoint.
It reports output size, pack/extract/inspect/prefix-read duration, summed heap-pool
peaks, the configured scratch ceiling and split/sharing scenarios. Measurements
are not formal PV1 qualification or an oracle performance comparison.
