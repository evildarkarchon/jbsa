# Assemble and verify the self-contained Windows x64 application image

Status: needs-triage
State: open
GitHub issue: #53
Source: https://github.com/evildarkarchon/jbsa/issues/53
Author: evildarkarchon
Created: 2026-09-03T06:54:28Z
Source updated: 2026-09-03T06:54:28Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#52](../issues/52-qualify-jlibdeflate-and-select-the-standard-zlib-dispatch.md), [#29](../issues/29-establish-licensing-and-dependency-provenance-gates.md), [#51](../issues/51-freeze-the-jbsa-1-0-public-archive-interface.md), [#49](../issues/49-complete-the-cross-family-bsarch-compatible-cli.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #52, #51, #49.

## Original issue body

## Objective

Produce the canonical modular library artifacts and self-contained single-process Windows CLI image with its final provider/runtime graph.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DIST-*
- JBSA-BUILD-*
- JBSA-CLI-*
- JBSA-LIC-*

## Acceptance

- Build the final jbsa JAR, flattened consumer POM, sources/Javadoc artifacts, thin CLI, and distribution module from the pinned full JDK build.
- Review jdeps output plus reflection, service, resource, and native needs; create a jlink --compress=2 runtime and jpackage --type app-image --win-console image.
- Apply and verify win.norestart=true, jbsa.exe single-process launch, no child launcher, working-directory behavior, UTF-8 streams, exits, Ctrl+C cleanup, and native loading.
- Assemble jbsa-cli-<version>-windows-x64.zip with checksums, license/notices, SBOM/provenance inputs, and no proprietary or unintended bytes.
- Pass clean-machine smoke coverage for every CLI operation and fail on missing runtime modules, unexplained module-set changes, or unqualified toolchain/provider changes.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
