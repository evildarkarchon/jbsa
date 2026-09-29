# Qualify jlibdeflate and select the standard zlib dispatch

Status: none
State: closed
GitHub issue: #52
Source: https://github.com/evildarkarchon/jbsa/issues/52
Author: evildarkarchon
Created: 2026-09-03T06:54:26Z
Source updated: 2026-09-03T06:54:26Z
Closed: 2026-09-29
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md), [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-29
Triage rationale: Closed by the Final Profile Gate decision to defer jlibdeflate and keep the unchanged JDK zlib streaming profile.

## Original issue body

## Objective

Use complete conformance and measured evidence to promote or defer the optional jlibdeflate provider and select bounded whole-buffer dispatch without changing an existing immutable profile.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CODEC-*
- JBSA-PERF-*
- JBSA-CONF-*

## Acceptance

- Qualify decode, encode, malformed input, provider fallback, deterministic output, memory credits, native loading, packaging, and notice behavior against the JDK zlib baseline.
- Measure every required codec throughput, memory, size, and relevant binary-repeatability case, accounting explicitly for the libdeflate 1.25 versus reference 1.24 mismatch.
- Select evidence-backed input-size and memory-credit dispatch thresholds or defer promotion if any conformance, performance, memory, native, or compliance gate fails.
- If promoted, create a new digest-identified immutable codec profile and rerun every affected CV1 and PV1 case; never silently mutate the existing profile.
- Keep provider selection and thresholds internal and document the exact decision, evidence, fallback, and requalification triggers.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Assurance v2 readiness — 2026-09-28

The original CV1/PV1 rerun wording is historical after #61. A promoted provider
must rerun the impacted generated Assurance Scenarios and curated Performance
Lanes under the new immutable codec profile, with shared-core or unknown impact
selecting the full conformance tier. The codec behavior, safety, performance,
compliance, and decision requirements remain in force.

## Outcome

The Final Profile Gate deferred jlibdeflate 0.1.0 (libdeflate 1.25, level 12)
and selected the unchanged baseline as the standard zlib dispatch. That
baseline is JDK `Deflater`/`Inflater` streaming at every size, with no
whole-buffer or memory-credit threshold, no native zlib fallback, and the
unchanged `jbsa-lz4-v1` manifest (`f7221b24…732e`). The
[decision record](../../../docs/development/evidence/issue52-jlibdeflate/README.md)
holds the gate table, measurements, fallback rules, and requalification
triggers.

Semantic Decode and Encode Conformance, Failure Kind parity, repeatability,
fallback, and credit admission passed. Native loading failed: each process
maps three DLL copies and leaves three undeletable temporary DLLs, and the
provider honors a caller-supplied `jlibdeflate.library.path`. Packaging and
notices failed: the JAR has no module name, carries uninventoried non-Windows
natives, and has no license text. Level-12 encode ran at 0.18–0.58 times JDK
speed on the repeated, text, and mixed corpora, with up to 3 s uninterruptible
checkpoint gaps;
decode was 1.24–4.82 times faster. libdeflate 1.25 reproduced both pinned
Reference 1.24 oracle streams. Truncated-trailer cases diverge in diagnostic
identifier only.

The qualification-only adapter and the explicit lane live in `:jbsa` test
scope. The candidate is locked only on test classpaths and stays out of every
production lock, the launch policy, and the CLI inputs. No profile changed,
so no Assurance Scenario or Performance Lane rerun is required. Local Windows
`gradle clean verify spotlessCheck --no-daemon` passed; hosted CI for the
closing commit remains to be observed. No provider promotion, Binary
Conformance claim, or release Performance Lane result is claimed.
