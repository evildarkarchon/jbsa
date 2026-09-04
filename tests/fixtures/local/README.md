# Local fixture evidence

This directory is the discovery point for legally obtained, local-only archive evidence. The
project's current workstation corpus contains 12 read-only Bethesda Archives across the eight
Archive Families at `corpus/archives/`, with its uncommitted inventory at `corpus/manifest.json`.
The digest-pinned Conformance Oracle, when provisioned, is
`oracle/BSArch.exe`.

Everything below this directory except this README and `.gitkeep` is ignored. Local archives,
extracted game assets, oracle binaries, oracle outputs containing protected material, and derived
archives containing those assets must remain read-only and must never be committed, assembled, or
published. Hosted tests must use only the redistributable synthetic corpus.

The local inventory is optional. Tooling must report it as unavailable when absent, never download
it, and never turn its absence into a failure of hosted conformance consumers.
