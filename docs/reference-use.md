# Reference Snapshot use

JBSA uses the pinned `TES5Edit` submodule only as a read-only Reference Snapshot. The snapshot at
commit `fd1e36020b2b5b6217e553dc0038983146a2e2dd` provides observable behavior and cited format
evidence; it is not an implementation source tree for adaptation.

## Independent-only implementation rule

Implementation work may use documented format facts, public standards, project-owned synthetic
tests, differential observations, and citations recorded in the normative specification. It must
not copy, mechanically translate, or preserve distinctive Reference Snapshot source structure,
comments, tables, identifiers, or control flow. No MPL adaptation lane is authorized for JBSA.

The `TES5Edit` directory must never be modified. Its contents, license, notices, logos, and identity
remain separate from JBSA’s Apache-2.0 project material. “BSArch-compatible” may be used only as a
descriptive interoperability statement accompanied by an independent and unaffiliated statement.
TES5Edit and BSArch logos must not be used.

## Evidence records

When observable Reference Snapshot behavior informs a change, record the pinned revision, the
observed command or source location, the independently derived conclusion, and the synthetic test
or specification requirement that verifies it. Conformance Oracle outputs may be retained only
when they contain project-owned synthetic material and the fixture record permits redistribution.

## Stop conditions

Stop before merge or release if work raises uncertainty about source similarity or adaptation,
third-party fixture rights, proprietary content, branding or endorsement, native provenance,
trademarks, patents, or an EULA. Exclude the questioned material until an explicit decision and any
appropriate qualified review resolve it. This policy is an engineering control, not legal advice.
