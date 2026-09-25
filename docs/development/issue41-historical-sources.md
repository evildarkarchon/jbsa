# Historical Interface Candidate source copies

The exact approved packet remains bound to review digest
`a2e9702750796da6cad78a0e36458475c14bbdaf9b2d3da69c16825710b93c3c`.
Its nine duplicate binary files are not committed: repository compliance permits
these project-authored archive bytes only in the original synthetic corpus.

For historical packet inspection, reconstruct those exact bytes with
`python build/materialize-interface-historical-sources.py`. The command verifies
each original source digest before copying into the narrowly ignored historical
artifact paths. It creates no golden observations or approvals.

The active catalog uses the original tracked synthetic source paths through a
separate manifest with no goldens. Sixteen unqualified case identities changed to
bind that corrected provenance; all 109 admitted case objects, governing
specification bindings and approved golden bytes remain exactly unchanged.
The before/after catalog digests and equality proof are recorded in
`evidence/issue41-source-repair.json`. `build/repair-interface-synthetic-provenance.py`
prepares this narrow correction without publishing the active catalog.
