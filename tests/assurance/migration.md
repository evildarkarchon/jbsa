# Assurance v2 migration record

The reviewed legacy input is `tests/conformance/catalog.json` at SHA-256
`17a573a623e30ba2287b9cb514e01f7257fbf29d99de90bf4c0cc3efadc9c6ba`.
The deterministic comparison accounts for all 155 cases in the four qualified
Archive Families: 149 map to generated Assurance Scenarios, six are retired, and
none remain unmapped.

| Archive Family | Legacy | Mapped | Retired | Unmapped |
| --- | ---: | ---: | ---: | ---: |
| TES3 | 39 | 34 | 5 | 0 |
| BSA 0x67 | 32 | 32 | 0 | 0 |
| BSA 0x68 | 52 | 52 | 0 | 0 |
| BSA 0x69 | 32 | 31 | 1 | 0 |

Intentional consolidations replace volatile family/configuration cases with
stable behavioral archetypes:

- accepted decode cases share `decode-entries`;
- stored, zlib, LZ4-frame, and mixed encode cases share their applicable
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
