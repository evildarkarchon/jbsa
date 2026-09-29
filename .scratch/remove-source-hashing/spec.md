# Retire the Interface Freeze and source hashing

The maintainer decided on 2026-09-29 that the Interface Freeze gate creates more
cost than value. Active assurance must not hash source code or require a stored
source digest to approve ordinary edits. Built product binaries, fixture bytes,
the Conformance Oracle executable, and release artifacts may retain content
digests for evidence identity and integrity.

Public interface changes remain governed by the normative specifications,
compiled consumers, architecture checks, current conformance evidence, and the
general affected-gate reset policy. The historical Interface Freeze audit and
Assurance v1 evidence remain available as records, without an active freeze gate.

Implementation: [01 — Remove source hashing and retire the gate](issues/01-retire-interface-freeze.md).
