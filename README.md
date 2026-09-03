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

See [the build guide](docs/development/build.md) for the Java 25 Maven commands. External
contributions follow [CONTRIBUTING.md](CONTRIBUTING.md), including DCO 1.1 sign-off and provenance
declarations. No contributor license agreement is required.
