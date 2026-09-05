# Contributing to JBSA

JBSA accepts external contributions selectively. The maintainer reviews each pull request directly.
No contributor license agreement, DCO sign-off, or automated provenance declaration is required.

## Independent authorship

Contributions must be independently authored from documented format facts, observable behavior,
and project-owned synthetic tests. Do not copy, mechanically translate, or preserve distinctive
source structure, comments, or tables from the TES5Edit Reference Snapshot. Do not submit game
assets, local game archives, Conformance Oracle binaries, or output containing protected material.
See [the reference-use policy](docs/reference-use.md) before working from reference evidence.

## Pull request review

The maintainer may ask for context about sources, fixtures, dependencies, native bytes, branding,
patents, EULAs, or other third-party rights when it is relevant to a change. Unclear rights or
provenance, non-descriptive TES5Edit/BSArch branding, proprietary material, opaque native bytes, or
unresolved substantial-similarity, trademark, patent, or EULA concerns remain stop-before-merge
conditions. Keep the material excluded until an explicit project decision and appropriate qualified
review resolve the concern.

## Verification

Run `./mvnw.cmd -B -ntp -C clean verify` on Windows and the focused gate appropriate to the change.
Dependency and native changes must update the compliance inventories before their bytes can enter
release inputs.
