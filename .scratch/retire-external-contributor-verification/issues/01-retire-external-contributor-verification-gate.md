# Retire external contributor verification gate

Status: ready-for-agent
State: open
GitHub issue: #62
Source: https://github.com/evildarkarchon/jbsa/issues/62
Author: evildarkarchon
Created: 2026-09-04T00:58:18Z
Source updated: 2026-09-04T00:58:18Z
Migrated: 2026-09-10
Assignees: none
Blocked by: none

## Decision

Retire the external-contribution DCO and provenance-declaration gate. JBSA is small enough that the maintainer will assess external pull requests directly instead of requiring automated author classification, commit sign-off, or checked provenance declarations.

## Scope

- Retire JBSA-LIC-016 and JBSA-LIC-017 without reusing their identifiers.
- Remove the external-contribution verification script and GitHub Actions step.
- Remove DCO/sign-off and mandatory provenance-declaration instructions from contributor-facing documentation and the pull-request template.
- Remove conformance tests and registry evidence dedicated to that gate.
- Keep JBSA-LIC-015 (no CLA), the independent-authorship/reference-use policy, repository/release compliance audits, and maintainer review of licensing or provenance concerns.
- Bump the pre-1.0 normative specification minor version.

## Acceptance criteria

- Pull requests are no longer blocked based on contributor identity, DCO trailers, or checked declaration boxes.
- The normative spec and requirements registry preserve the retired requirements as tombstones with this issue as the retirement decision.
- Existing compliance and build-policy tests pass.

## Comments

No comments at migration time.
