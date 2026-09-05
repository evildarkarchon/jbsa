# Performance v1 Benchmark Corpus

These inputs are independently generated project-owned synthetic material.
They contain no game files, proprietary archives, or Reference Snapshot code.
The versioned generator is [`corpus.py`](../../../build/performance/corpus.py).
Nothing in this directory is a performance result or a passing qualification.

`definitions.json` records the six normative workload counts and logical sizes.
Each `<workload>.json.gz` is a **complete** manifest, including every relative
path, exact byte length, SHA-256, seed, algorithm version, and structural recipe.
Gzip keeps 120,272 file records reviewable without checking in payloads; use the
Python `gzip` module to inspect them. Canonical content is sorted-key ASCII JSON,
without optional whitespace; `manifest_sha256` hashes that form with its own
field omitted. The enclosing gzip has timestamp zero and no filename. Its bytes
are transport, not the corpus identity.

All sources are generated before timing. The random algorithm emits independent
1 MiB SHAKE-256 blocks from `UTF-8(seed + NUL + payload_id + NUL)` followed by a
little-endian 64-bit block counter; the last block is truncated. Structured
sources repeat a 4096-byte record derived from the manifest-pinned JSON template.
These constructions do not invoke, train on, or depend on any archive codec.
Aliases use the same payload identity and generate identical source bytes.

The exact mix is:

| Workload | Structure |
| --- | --- |
| `metadata-100k` | 100,000 nested `.txt` names; 35,456 files of 2685 bytes and 64,544 of 2684 bytes; 256 MiB total |
| `mixed-10k` | 5,000 structured `.txt`, 3,000 pseudorandom `.bin`, 2,000 pseudorandom `.fuz` names marked extension-no-compress; 2 GiB total |
| `bulk-compressible` | Eight distinct structured files, each 256 MiB |
| `bulk-incompressible` | Eight independently seeded SHAKE streams, each 256 MiB |
| `dds-mipmapped` | 256 valid legacy DDS textures, 2 GiB including headers, BC1/BC2/BC3/BC4/BC5, rectangular and odd dimensions, varying mip counts, six-face cubemaps, and all one-through-four chunk counts |
| `shared-content` | Two names per each of 5,000 distinct structured payloads, exactly 1 GiB logical bytes |

DDS mip lengths round each dimension to 4x4 blocks independently. Headers and
payloads follow `JBSA-DDS-001`, `006`, `008`, `009`, and `013`. Two final one-mip
BC1 rectangles fill the remaining **real** payload bytes; there is no padding
or trailing data. The first eight textures preserve the coverage matrix, and
subsequent textures are deterministically enlarged before balancing so no file
exceeds 64 MiB. Every manifest DDS entry states each mip length and contiguous
archive-chunk span. BC payloads are opaque synthetic valid block data, not
pixel-decoded images.

The mixed, metadata, and two bulk workloads also have committed
`<workload>-dds-source.json.gz` specialization manifests. They retain their
workload tokens, exact counts and totals, original relative names, and original
structured/pseudorandom/extension-policy proportions. Every file contains a real
128-byte DDS header and a complete one-mip BC1 surface. Their identity includes
`variant: dds-source` and `variant_version: 1`; the base manifests are unchanged.

Metadata lengths are apportioned in 8-byte BC1 block rows and mixed lengths in
128-byte rows. The bulk rectangle is 56,896 by 9,436 pixels: its 14,224 by 2,359
blocks occupy exactly `256 MiB - 128` bytes, so each complete DDS file is exactly
256 MiB without padding. All dimensions fit the DDS BA2 `u16` fields.

Extensions identify the source mix, while header bytes identify DDS envelopes.
In particular, the `.fuz` names contain generated DDS bytes, not voice data.
**The extension-no-compress source class cannot mean stored DDS archive chunks:**
`JBSA-DX10-006` requires compressing every DDS chunk. General archive cases can
exercise their extension policy; DDS cases preserve the source proportions and
must follow their mandatory codec. Validators must check the selected family and
actual produced structure rather than infer DDS validity or archive storage mode
from the extension. These specialized inputs do not waive Conformance evidence.

The `mixed-10k-medium-1000.json.gz` and
`mixed-10k-dds-source-medium-1000.json.gz` projections supply the version-7/8
medium decode inputs. Each selects parent indices `0, 10, ... 9990`, preserving
500 structured, 300 pseudorandom, and 200 extension-policy entries. Their exact
logical lengths are 214,748,365 and 214,748,416 bytes respectively. The projection
contains the full parent manifest, its canonical SHA-256, and a separate digest
of the full ordered parent recipes. Validation checks the parent and the exact
selected entries; embedded parent records do not count as projected files or
logical bytes. The actual version-7/8 archives must still be oracle-generated,
validated, and digest-bound outside timing.

Inspect definitions (no generation):

```powershell
python build/performance/corpus.py definitions
```

Materialize one explicit workload outside all measured regions. The destination
should be a dedicated empty directory on the benchmark NTFS volume; naming it
with the manifest's `manifest_sha256` gives a content-addressed local cache.
Materializing all workloads consumes 9.25 GiB plus filesystem metadata:

```powershell
python build/performance/corpus.py materialize tests/performance/corpus/mixed-10k.json.gz D:/benchmark-corpus/mixed-10k
python build/performance/corpus.py verify tests/performance/corpus/mixed-10k.json.gz D:/benchmark-corpus/mixed-10k
```

Generation resumes only files already matching the manifest. Byte, length,
digest, missing-path, additional-file, ambiguous Windows-name, and link/junction
mismatches fail; existing mismatches are never repaired silently. Interrupted
new writes are removed, while completed verified files remain resumable.
The directory contains sources only; keep archives and reports elsewhere.

To regenerate a manifest after intentionally reviewing generator/version changes:

```powershell
python build/performance/corpus.py manifest mixed-10k --output D:/benchmark-corpus/mixed-10k.json.gz
python build/performance/corpus.py manifest mixed-10k --variant dds-source --output D:/benchmark-corpus/mixed-10k-dds-source.json.gz
python build/performance/corpus.py projection tests/performance/corpus/mixed-10k-dds-source.json.gz --output D:/benchmark-corpus/mixed-10k-dds-source-medium-1000.json.gz
```

This command streams all source bytes through SHA-256 but stores only the
manifest. It still does substantial CPU work and must finish before timing.
Do not replace a committed corpus identity without the full qualification
required by `JBSA-PERF-003`.

The Python orchestration seam is `read_manifest`,
`validate_manifest(manifest, require_normative=True)`, `materialize`, and
`verify_materialization`. `plan` returns recipes without constructing bytes.
`plan(workload, variant="dds-source")` selects a DDS specialization and
`build_projection(parent_manifest)` selects the fixed medium workload. The same
strict normative validation applies to base, specialized, and projected inputs.
`build_manifest(workload, files=[bounded_recipe, ...])` is available for developer
tests, and always marks the result `scope: smoke`; normative validation rejects
it. Run focused tests with:

```powershell
python -m unittest discover -s build/performance -p test_corpus.py
```
