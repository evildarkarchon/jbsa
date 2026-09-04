# Synthetic fixture data

Everything below this directory is project-authored fixture data dedicated under CC0-1.0. Java
fixture generators and test code remain Apache-2.0 and live in `jbsa-test-support` and
`jbsa-conformance-tests`.

`manifest.json` is the complete provenance inventory. It records the creator and source, SPDX
license, exact generation procedure and command/options, pinned Reference Snapshot revision,
Conformance Oracle digest when applicable, input and output SHA-256, generation date, and
redistribution class for every generated object. `generator.json` is the deterministic recipe
catalog. A fixture's `input_sha256` is the SHA-256 of its recipe's exact UTF-8 `input` string;
`output.sha256` binds the resulting bytes. Their stable contracts are defined by the three JSON
Schemas in this directory.

Build the generator and materialize a fresh copy into an empty staging directory:

```powershell
mvn -pl jbsa-test-support -am -DskipTests package
java -cp jbsa-test-support/target/classes io.github.evildarkarchon.jbsa.fixtures.FixtureCorpusGenerator --output target/fixture-corpus
```

Audit the committed or staged corpus without modifying it:

```powershell
pwsh -File build/verify-fixture-corpus.ps1 -CorpusRoot tests/fixtures/synthetic
```

The ordinary generator refuses a nonempty destination. Golden objects are immutable and stored at
`goldens/sha256/<sha256>.json`; logical names live separately in `goldens/index.json`. An ordinary
test or failed comparison must never create or replace a golden. A replacement is staged as
untrusted output and becomes committable only with a deliberate record conforming to
`rebaseline-record.schema.json`, including old/new and source hashes, oracle identity, generator
configuration, affected Conformance Cases, rationale, semantic difference, and explicit maintainer
approval.

The compact scenario objects cover structural, boundary, malformed, compression, name-encoding,
ordering, overlay, split, and resource-limit inputs. Large boundary cases such as the 2 GiB split
target remain deterministic virtual recipes; multi-gigabyte materialized data is never committed.
The generated archives include independently authored Fallout 4 General BA2 v7 stored/zlib and
Starfield General BA2 v3 method-3 raw-LZ4/mixed fixtures.

Third-party vectors require an explicit redistribution grant and review. The ignored local corpus
is documented in [`../local/README.md`](../local/README.md); its proprietary bytes and the
Conformance Oracle never enter this corpus or an assembled artifact.
