# BSArch Conformance Oracle

This document attests the local executable selected as the Conformance Oracle for the pinned Reference Snapshot. The executable is deliberately excluded from version control and must be provisioned separately on each development machine.

## Identity

| Property | Value |
| --- | --- |
| Local path | `tests/fixtures/local/oracle/BSArch.exe` |
| SHA-256 | `4C34FE4173A2BD04BA52D5A6357348256EE424573785085FDAFAAB524CF7B0C2` |
| Size | 5,319,168 bytes |
| Banner | `BSArch v1.0 x64 by zilav, ElminsterAU, Sheson` |
| PE format | COFF x86-64, Windows console subsystem |
| PE timestamp | `2026-06-15T07:17:17Z` |
| Authenticode | Unsigned |
| Pinned source | `TES5Edit` commit `fd1e36020b2b5b6217e553dc0038983146a2e2dd` |
| Pinned source version | `cBSArchVersion = '1.0'` in `Core/wbBSArchive.pas` |

The user attests that this executable is the matching build retained from the Reference Snapshot. Its banner matches the pinned source declaration. Because the file is unsigned and no reproducible build or signed release digest is available, the correspondence cannot be proven cryptographically; the SHA-256 digest above is the canonical local identity for all subsequent tests.

## Runtime dependencies

Static PE inspection found imports only from Windows system libraries: `kernel32.dll`, `ole32.dll`, `user32.dll`, `oleaut32.dll`, `msvcrt.dll`, `advapi32.dll`, and `api-ms-win-crt-string-l1-1-0.dll`. The help and smoke-test paths ran without co-located codec or runtime DLLs.

## Behavioral attestation

All probes used redirected standard streams and a finite timeout.

| Probe | Result |
| --- | --- |
| No arguments | Exit `0`; help and banner on stdout; stderr empty |
| Invalid first argument | Exit `1`; `EInvalidArguments` on stdout; stderr empty |
| TES3 pack with `-mt:no` | Exit `0`; archive created |
| Archive information | Exit `0` |
| `-list` | Exit `0` |
| `-dump` | Exit `0` |
| Unpack with `-mt:no` | Exit `0` |
| Pack/unpack byte round-trip | Extracted file SHA-256 matched the source SHA-256 |

This is a smoke-level attestation, not the conformance corpus. Format-family coverage, malformed inputs, deterministic archive bytes, compression variants, DDS behavior, and performance remain governed by their Wayfinder tickets and the local corpus manifest.

## Provisioning rule

Never commit the executable. A machine is oracle-ready only when the local file exists at the documented path and its SHA-256 matches this attestation. Any replacement requires a new digest, a repeated behavioral attestation, and an explicit update to the conformance deviation record.
