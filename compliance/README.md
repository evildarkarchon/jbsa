# Compliance inventories

The two JSON inventories are the authority for third-party product bytes considered for JBSA. They
record selected candidates even while those candidates are blocked, so absence of approval cannot
be mistaken for missing review work.

## Dependency inventory

`dependency-inventory.json` identifies each exact Maven artifact by group, artifact, packaging,
classifier, version, and SHA-256. Every entry also records its use, whether it contains native
bytes, SPDX license and evidence, required notices, immutable source revision and build provenance,
redistribution evidence, containing release artifacts, and unresolved gates.

An entry with `redistribution.approved: false` must have an empty `releaseArtifacts` list. Changing
that flag is a reviewable promotion: first verify the downloaded artifact checksum, inspect its
complete contents, preserve every applicable license and notice, pass the requirement-specific
conformance/performance/native-loading gates, and name every release artifact that will contain its
bytes. The compliance verifier rejects an external production dependency until the matching exact
entry is approved.

## Native payload inventory

`native-payload-inventory.json` identifies every native file inside a candidate artifact by its
container coordinates and checksum, path inside that container, payload checksum, platform,
component versions, licenses/notices, source/build provenance, and redistribution decision. A
native entry must point to a dependency entry whose exact container checksum agrees and which is
marked as containing native bytes. It also records whether evidence has established that pure Java
cannot satisfy the applicable contract. Approval is rejected until that evidence exists, the
container artifact is itself redistribution-approved, and every containing artifact is an
authorized Windows x64 CLI ZIP rather than the thin `jbsa` library.

No caller-supplied native library path is an inventory source. Release-input inspection hashes each
native file and rejects it unless that exact digest has been approved. Renaming a DLL therefore
does not bypass the gate, and changing any native byte requires a new inventory review.

## Reproducing artifact hashes

Declare the exact candidate in the Gradle version catalog and applicable production configuration
as an isolated review change. After updating the lock and dependency-verification metadata from the
checksum-reviewed Maven Central bytes, resolve the production model and emit its independently
calculated SHA-256:

```powershell
.\gradlew.bat generateResolvedProductionDependencies --no-daemon
Get-Content -Raw target/compliance/resolved-production-dependencies.json
```

The manifest records requested and selected coordinates, classifier, selected variant, filename,
and the SHA-256 computed from the exact Gradle-resolved bytes. Review the relevant record against
the candidate and copy that digest into the licensing inventory; do not approve an unrelated
transitive selected in the same graph.

For native entries, open the JAR as a ZIP and hash the uncompressed entry stream. Do not copy a
checksum from mutable prose or infer a payload’s license from Maven metadata alone; compare the
published bytes, release source, embedded notices, and upstream component provenance.

## Release-input manifests

Every non-empty release-input directory passed to `build/verify-compliance.ps1` needs a JSON
manifest with `schemaVersion: 1` and an `entries` array. Each entry contains `path`, lowercase
`sha256`, `kind`, and `source`. Paths are relative, non-traversing, and unique. Kinds are restricted
to project artifacts, inventoried dependencies/native containers or payloads, and named evidence
classes (license, notice, release notes, SBOM, provenance, checksum, or documentation). Dependency
and native sources must reconcile to their approved inventory identity; evidence sources must name
an existing repository file with exactly the same bytes.

The verifier requires an exact two-way match: every file is manifested, every manifest entry
exists, and every digest matches. It recursively opens JAR/ZIP inputs through a bounded depth and
size, rejects unsafe or duplicate entry names, and applies the proprietary/native checks to nested
bytes before general manifest accounting. The generated SBOM is reconciled in both directions so
an uninventoried transitive runtime component fails even when every direct dependency is approved.
The final `jbsa-dist` verification stages current multi-project outputs and compliance evidence, then
requires `jbsa-dist/target/release-inputs` and `jbsa-dist/target/release-inputs.json` explicitly.
Missing assembly inputs fail rather than skipping this audit. Standalone callers supply both
paths explicitly for any staging location; the root repository audit does not consume stale
staging from an earlier build.

## Gradle compliance model

The Gradle build writes two internal, schema-version-1 contracts below `target/compliance`:
`build-layout.json` names contained generated outputs, while
`resolved-production-dependencies.json` records each production configuration's requested and
resolved coordinates, selected variant, classifier, artifact filename, and exact SHA-256. Their
arrays and object keys are serialized deterministically. These contracts are audit inputs only and
are deliberately absent from the release-input staging list.

`gradlew verifyCompliance` reconciles those contracts with the generated parent-free consumer POM,
the production lockfiles, both strict dependency-verification metadata files, and this maintained
licensing inventory. Gradle resolution proves which bytes were selected; it never grants licensing
or redistribution approval. The task also creates the deterministic CycloneDX 1.6 release SBOM and
the existing notice outputs. The SBOM has no serial number, retains `jbsa-parent` as the logical
root, and excludes tests, build-only projects, and build plugins.
