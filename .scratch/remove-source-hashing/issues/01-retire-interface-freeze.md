# Remove source hashing and retire Interface Freeze

Status: none
State: closed
Labels: none
Intended owner: agent
Blocked by: none

## Decision

The maintainer requested that all active source hashing stop and that the
Interface Freeze gate no longer block public interface changes. Binary hashing
remains acceptable as evidence identity. This supersedes the Interface Freeze
policy accepted for issue #51.

## Acceptance

- Active assurance and fixture generation no longer hash Java, Python, or
  PowerShell source files or Git source-tree contents.
- The Assurance candidate identity binds built library and CLI JARs. Generator
  and validator procedures use version identities; normative specifications use
  their reviewed version.
- Deviation approval remains tied to the approved rows, profile decision, and
  pinned Oracle observation rather than implementation source bytes.
- The Interface Freeze test and release gate are retired. Compiled consumers,
  architecture checks, and general affected-gate resets remain active.
- Normative release and assurance requirements, the registry, current planning,
  and tests describe the revised policy.
- Historical CV1 and issue #51 records remain intact and clearly historical.

## Comments

### 2026-09-29 — completed

Retired `JBSA-REL-006/007`, removed the active exact-match API freeze test, and
revised specification `0.18.0` to bind tested binary artifacts and procedure
versions without source-code digests. Active deviation approval, fixture
generation, and validator observations no longer rehash source files. Historical
issue #51 and CV1 evidence remain available as records.

Verification: `python -m unittest discover -s tests/assurance` passed 59 tests;
`gradle verify --no-daemon` passed 59 Gradle tasks and produced a passing full
Assurance capsule with 138 of 138 scenarios. `git diff --check` passed.
