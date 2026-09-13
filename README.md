# JBSA

JBSA is an independently authored Java 25 library for Bethesda Archives with a thin,
BSArch-compatible command-line consumer. “BSArch-compatible” describes behavioral compatibility;
JBSA is an independent project and is not affiliated with, sponsored by, or endorsed by TES5Edit,
BSArch, Bethesda Softworks, or Microsoft.

The implementation is informed by documented format facts and observable behavior from the pinned
TES5Edit Reference Snapshot at commit `fd1e36020b2b5b6217e553dc0038983146a2e2dd`. The snapshot is a
read-only Git submodule and remains governed by its own licenses. See
[Reference use](docs/reference-use.md) for the clean-room boundary.

## Licensing

Independently authored Java source, project documentation, tests, and fixture generators are
licensed under Apache-2.0. Project-authored synthetic fixture data and normalized observations are
dedicated under CC0-1.0. The top-level license does not relicense the `TES5Edit` submodule,
third-party content, or separately licensed fixture data. Exact texts are in [LICENSES](LICENSES/),
and releaseable third-party material is recorded in the compliance inventories and
[third-party notices](THIRD-PARTY-NOTICES.md). The pinned Reference Snapshot attribution that must
be retained in every public release is recorded in [the release notices](RELEASE-NOTES.md).

## Building and contributing

See [the build guide](docs/development/build.md) for the installed-Java-25 Gradle commands. External
contributions follow [CONTRIBUTING.md](CONTRIBUTING.md) and are reviewed directly by the maintainer.
No contributor license agreement or DCO sign-off is required.

## Public interface milestone

The library is at the **pre-1.0 Interface Candidate** milestone. The interface
remains unfrozen. See
[the Interface Candidate audit](docs/development/interface-candidate.md) for the
four-family consumer checks, post-1.0 Xbox scope and evidence gate status, and
[the interface guide](docs/development/contract-baseline.md) for public JPMS usage, immutable
contracts and archive execution gates. The [TES3 walking slice](docs/development/tes3.md)
provides Morrowind BSA inspection, owned content reads, extraction, packing, and thin CLI commands.
The [TES4 / Oblivion slice](docs/development/tes4.md) adds versioned BSA `0x67`,
stored and zlib content, mixed-entry decoding, flags, and `-tes4` CLI operations.
The [Fallout 3 / New Vegas / Skyrim LE slice](docs/development/bsa-068.md) extends
the common BSA implementation to `0x68`, including explicit embedded names,
mixed-entry packing, and the `-fo3`, `-fnv`, and `-tes5` CLI selectors.
The [Fallout 4 General BA2 slice](docs/development/fo4-general.md) adds `BTDX/GNRL`
version 1 stored/zlib reading and writing, source overlays, sharing, splitting,
and `-fo4` CLI operations.
The [Fallout 4 PC DDS BA2 slice](docs/development/dds.md) adds compressed texture
chunks and canonical PC DDS reconstruction. Xbox DDS support and qualification
are deferred until after 1.0.
