# Assurance v2 migration record

The reviewed legacy input is `tests/conformance/catalog.json` at SHA-256
`17a573a623e30ba2287b9cb514e01f7257fbf29d99de90bf4c0cc3efadc9c6ba`.
The deterministic comparison accounts for all 485 cases in the thirteen qualified
Archive Families plus the explicitly incomplete Fallout 4 General v7 capability:
476 map to generated Assurance Scenarios, nine are retired, and none remain
unmapped. The comparison remains `incomplete`, rather than mapped-equivalent,
because no known producer or shipping archive can qualify General v7.

| Archive Family | Legacy | Mapped | Retired | Unmapped |
| --- | ---: | ---: | ---: | ---: |
| TES3 | 39 | 34 | 5 | 0 |
| BSA 0x67 | 32 | 32 | 0 | 0 |
| BSA 0x68 | 52 | 52 | 0 | 0 |
| BSA 0x69 | 32 | 31 | 1 | 0 |
| Fallout 4 DDS BA2 v1 | 47 | 44 | 3 | 0 |
| Fallout 4 DDS BA2 v7 | 31 | 31 | 0 | 0 |
| Fallout 4 DDS BA2 v8 | 31 | 31 | 0 | 0 |
| Fallout 4 General BA2 v1 | 33 | 33 | 0 | 0 |
| Fallout 4 General BA2 v7 (synthetic-only, incomplete) | 31 | 31 | 0 | 0 |
| Fallout 4 General BA2 v8 | 31 | 31 | 0 | 0 |
| Starfield DDS BA2 v2 | 31 | 31 | 0 | 0 |
| Starfield DDS BA2 v3/method 3 | 31 | 31 | 0 | 0 |
| Starfield General BA2 v2 | 31 | 31 | 0 | 0 |
| Starfield General BA2 v3/method 3 | 33 | 33 | 0 | 0 |

The generated shared-core capability has no historical CV1 Archive Family row;
its scenarios are assessed directly by Assurance v2 and contribute no legacy
case count.

Intentional consolidations replace volatile family/configuration cases with
stable behavioral archetypes:

- accepted decode cases share `decode-entries`;
- stored, zlib, raw-LZ4, LZ4-frame, and mixed encode cases share their applicable
  round-trip scenario;
- all rejected codecs remain explicit under direction-specific unsupported
  codec scenarios;
- malformed inputs and unsafe extraction names consolidate by safety property;
- naming, hashing, flags, compression boundaries, source overlay, sharing,
  splitting, empty/multi-entry, and determinism cases consolidate under
  `behavioral-interactions`, backed by focused owning tests;
- BSA 0x69 retains additional layout, flag, CLI, resource, cancellation,
  ordering, oracle, and performance-checkpoint scenarios because those are
  materially distinct implementation risks.
- Starfield General retains separate v2 and v3/method-3 capabilities plus
  header/method selection, independent wire validation, CLI, native-resource,
  cancellation, oracle, and performance-checkpoint scenarios.
- Starfield DDS retains separate v2 zlib and v3/method-3 raw-LZ4 capabilities,
  independent chunk framing, stored/mixed decode disposition, DDS reconstruction,
  bounded-resource, CLI, independent-validation, bidirectional-oracle, and focused
  performance-checkpoint scenarios.
- Fallout 4 General v1 stored, zlib, and mixed encode/decode cases use their
  applicable round-trip and interaction scenarios, while DDS v1 zlib encode,
  stored/mixed decode disposition, mip reconstruction, target policy, and CLI
  behavior remain distinct. Both families retain malformed/resource, unsupported
  codec, extraction-safety, and local bidirectional-oracle scenarios.
- Fallout 4 v7/v8 retains four decode-only capabilities with explicit encode
  rejection, CLI decode, malformed/resource, independent-validator, oracle,
  interaction, local-corpus, and focused decode-performance scenarios. General v7
  has project-authored vectors corroborated by the Conformance Oracle against Reference Snapshot
  behavior but remains incomplete:
  Creation Kit output is v8, common third-party output is v1, and no known producer
  emits General v7.

The BSA 0x69 retirement is
`CV1-bsa-069.decode.malformed-harmless-trailing-bytes.stored.standard-v1`.
Versioned BSA has no archive-level trailing-byte requirement, so that case was
an inapplicable catalog product rather than a semantic or safety obligation.
Five TES3 decode/codec product cases are also retired: TES3 exposes neither a
decode-time codec selection nor an on-wire compression marker, so treating zlib,
LZ4-frame, raw-LZ4, raw-deflate, or mixed bytes as an unsupported decoder choice
invented an operation that the public interface and Archive Family do not have.
TES3 encode rejection remains an explicit Assurance Scenario.
Three Fallout 4 DDS v1 cases are retired under
[JBSA-SCOPE-009](../../docs/spec/scope.md#jbsa-scope-009): `dds-target-xbox`,
`dds-target-mismatch-xbox`, and `dds-reconstruction-selection` select Xbox encode
or reconstruction behavior deferred beyond 1.0. The PC target's rejection of an
Xbox DDS input remains an active mapped obligation under
[JBSA-DDS-002](../../docs/spec/formats/dds-payload.md#jbsa-dds-002).

The comparison refuses any legacy catalog whose exact digest differs from the
reviewed input, preventing later cases from silently inheriting these mappings.
The complete machine-readable comparison is generated at
`target/assurance/legacy-comparison.json` and retained as a CI artifact.
