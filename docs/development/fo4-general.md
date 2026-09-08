# Fallout 4 General BA2 v1

Issue #39 adds stored and zlib General BA2 operations through `BethesdaArchives`
and the CLI's `-fo4` selector. The existing immutable metadata types expose the
BTDX envelope, GNRL identity prefix, payload sizes, hashes, extension octets,
and names. The internal zlib profile and operation substrate also serve TES4;
there is no new public provider or family-specific service interface.

```powershell
java -jar jbsa-cli/target/jbsa-cli-0.1.0-SNAPSHOT.jar pack sources output.ba2 -fo4 -z
java -jar jbsa-cli/target/jbsa-cli-0.1.0-SNAPSHOT.jar output.ba2 -dump
```

Omit `-z` for stored output. `PackOptions.entryCompression` permits stored and
zlib entries in the same archive through the library. Names require a folder
component and ASCII-encodable directory, basename, and extension components.
Supplied case survives encoding; wire separators are `/`, display separators
are `\`. Records retain source plan order, including later-source overlay
replacement at the original insertion ordinal. Sharing uses exact content
comparison; splitting is disabled by default and creates independent archives.

The reader retains noncanonical hashes and record fields with diagnostics,
permits exact shared payload spans, and rejects partial overlap and malformed
records. Missing name tables yield diagnosed synthetic display names without
invented wire names or normalized identities. Those entries require explicit
complete names before canonical repacking. Payload validation checks complete
zlib input consumption and exact decoded lengths.

## Requirement trace

The permanent registry directly assigns `JBSA-CODEC-003` and `JBSA-GNRL-001`,
`004`, `006`–`013` to #39. This slice implements their FO4 v1 scope. The FO4 v1
rows of `JBSA-GNRL-002` and the stored/zlib framing of `JBSA-GNRL-005` also
apply. The remaining version/method matrix belongs to later slices.

Cross-cutting requirements include `JBSA-CODEC-001`–`003`, `005`–`006`,
`008`–`010`, `012`–`013`; `JBSA-CLI-004`–`006`, `009`, `012`–`015`;
`JBSA-CONF-010`–`014`; and the shared source, limits, warning policy,
cancellation, ownership, and publication contracts in the library and operation
specifications. General BA2 split planning follows `JBSA-IO-008`.

## Verification

Use Java 25, Maven, PowerShell 7, and Python 3.11 or newer:

```powershell
mvn -B -ntp -C '-Dtest=Ba2ReaderTest,Ba2PackTest,MainTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -ntp -C '-Dit.test=Ba2ConformanceIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.ba2.local=true' verify
mvn -B -ntp -C '-Dit.test=Ba2LocalCorpusIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.ba2.local=true' verify
mvn -B -ntp -C '-Dit.test=Ba2PerformanceCheckpointIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.ba2.performance=true' verify
```

The local differential requires the digest-pinned oracle documented in
[the oracle setup](../reference/bsarch-oracle.md). Synthetic fixtures are
project-authored CC0 material; proprietary game inputs and oracle binaries
remain local. The independent Python validator imports neither the product
nor its fixture generator. Compressed byte equality is not asserted across
providers; semantic observations and decoded bytes are compared.

The optional local corpus test reads the v1 directory under the ignored local
fixture inventory and compares every entry with the pinned oracle. The
2026-09-08 run passed for the available 38-entry archive. Extracted local data
and raw evidence remain under ignored `target/ba2-local-corpus-*` directories.

Development performance measurements are current-machine checkpoints, not
formal PV1 qualification. They do not establish a reference throughput ratio,
an idle-machine attestation, game acceptance, or qualified Binary Conformance.
The immutable CV1 catalog and its approved goldens remain authoritative for
formal case qualification; focused tests must not be described as a complete
CV1 release pass.

The [2026-09-08 checkpoint](fo4-general-checkpoint.csv) records 12 measured
rounds across stored, zlib, mixed, and shared/split scenarios on Windows 11,
Java 25.0.4.1, with 16 logical processors. Each used 18 MiB of synthetic
content and a 64 MiB scratch ceiling. All extracted bytes matched. The
[case mapping](fo4-general-cases.md) connects the 33 applicable catalog
scenarios to development tests and describes the remaining qualification gates.

The separate [10,000-entry metadata checkpoint](fo4-general-metadata-checkpoint.csv)
verifies every entry's order, name, and decoded size. Three measured inspections
took 0.083–0.087 seconds on the same runtime; its single-byte shared payloads
isolate index cost from bulk content work.
