# Assurance v2 migration record

The reviewed legacy input is `tests/conformance/catalog.json` at SHA-256
`17a573a623e30ba2287b9cb514e01f7257fbf29d99de90bf4c0cc3efadc9c6ba`.
The deterministic comparison accounts for all 405 cases in the eleven qualified
Archive Families plus the explicitly incomplete Fallout 4 General v7 capability:
399 map to generated Assurance Scenarios, six are retired, and none remain
unmapped. The comparison remains `incomplete`, rather than mapped-equivalent,
because no known producer or shipping archive can qualify General v7.

| Archive Family | Legacy | Mapped | Retired | Unmapped |
| --- | ---: | ---: | ---: | ---: |
| TES3 | 39 | 34 | 5 | 0 |
| BSA 0x67 | 32 | 32 | 0 | 0 |
| BSA 0x68 | 52 | 52 | 0 | 0 |
| BSA 0x69 | 32 | 31 | 1 | 0 |
| Fallout 4 DDS BA2 v7 | 31 | 31 | 0 | 0 |
| Fallout 4 DDS BA2 v8 | 31 | 31 | 0 | 0 |
| Fallout 4 General BA2 v7 (synthetic-only, incomplete) | 31 | 31 | 0 | 0 |
| Fallout 4 General BA2 v8 | 31 | 31 | 0 | 0 |
| Starfield DDS BA2 v2 | 31 | 31 | 0 | 0 |
| Starfield DDS BA2 v3/method 3 | 31 | 31 | 0 | 0 |
| Starfield General BA2 v2 | 31 | 31 | 0 | 0 |
| Starfield General BA2 v3/method 3 | 33 | 33 | 0 | 0 |

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

The comparison refuses any legacy catalog whose exact digest differs from the
reviewed input, preventing later cases from silently inheriting these mappings.
The complete machine-readable comparison is generated at
`target/assurance/legacy-comparison.json` and retained as a CI artifact.
