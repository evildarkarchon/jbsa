# Reference-use and dependency licensing constraints

## Status and scope

This note answers the licensing research question for a Java 25 Bethesda Archive library and its BSArch-compatible example CLI. It examines the read-only Reference Snapshot at TES5Edit commit [`fd1e36020b2b5b6217e553dc0038983146a2e2dd`](https://github.com/TES5Edit/TES5Edit/tree/fd1e36020b2b5b6217e553dc0038983146a2e2dd), the current codec candidate set, and Maven Central publication requirements.

This is engineering research, not legal advice. The conclusions below distinguish obligations stated by primary sources from questions that depend on copyright ownership, jurisdiction, or fact-specific similarity and therefore require qualified counsel.

## Decision summary

The project can preserve freedom to choose its own project license if it implements archive behavior independently and does not copy or translate expressive Reference Snapshot source. Reading the reference, recording facts, and comparing observable behavior do not by themselves make a new Java file MPL-covered; Mozilla says that new files containing no MPL-licensed code are not MPL Modifications. However, a Java file that copies, adapts, or closely translates Reference Snapshot code should be treated as MPL-2.0 Covered Software: retain its notices, license that source file under MPL-2.0, and make the corresponding source available when distributing executable form.

Adopt a source-separated implementation policy:

1. Keep `TES5Edit` read-only and use it as evidence, never as an implementation source directory.
2. Record archive facts, public behavior, test cases, and citations; do not paste or mechanically translate Pascal bodies, comments, tables, or distinctive control flow into Java.
3. Mark any deliberate source adaptation before it is committed. Put adapted code in an explicitly MPL-2.0 file or module, preserve upstream notices, and review its release obligations.
4. Commit golden archives only when generated from project-owned synthetic inputs and verified not to contain Reference Snapshot code, proprietary game assets, or unlicensed third-party fixtures.
5. Treat every shaded JAR, native DLL, and classifier artifact as redistributed content. Carry its exact license and required notices in the applicable binary distribution and in a generated third-party inventory.
6. Run a license/provenance gate against the resolved Maven dependency graph and the actual contents of every release archive before publication. Maven Central validation and POM metadata do not prove license compliance.

This finding deliberately does **not** choose the project's license. That remains the decision in “Choose the project license and reference-use policy.”

## Verified Reference Snapshot licensing

The Reference Snapshot's top-level [`LICENSE.txt`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/LICENSE.txt) is the Mozilla Public License 2.0. The archive-related source files each carry the MPL-2.0 Exhibit A notice, including [`BSArch.dpr`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L1-L9), [`Core/wbBSArchive.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbBSArchive.pas#L1-L9), [`Core/wbCompression.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbCompression.pas#L1-L9), [`Core/wbDDS.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbDDS.pas#L1-L9), and [`Core/wbHash.pas`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/Core/wbHash.pas#L1-L9). The BSArch executable also [displays an MPL notice](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/BSArch.dpr#L355-L369), but Mozilla's FAQ distinguishes source notices from statements displayed by a running program: Section 3.4 protects notices in Source Code Form, while displayed statements may be changed ([MPL FAQ Q19](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q19)).

The reference is not a single-license dependency bundle. Its pinned [`.gitmodules`](https://github.com/TES5Edit/TES5Edit/blob/fd1e36020b2b5b6217e553dc0038983146a2e2dd/.gitmodules) identifies separate compression submodules:

- `External/libdeflate-pas` is pinned at `c044fe1a7b0e2e9930c6a5110c7bd194a4872c91`; that wrapper's [`LICENSE`](https://github.com/ElminsterAU/libdeflate-pas/blob/c044fe1a7b0e2e9930c6a5110c7bd194a4872c91/LICENSE) is MIT and its [`readme.md`](https://github.com/ElminsterAU/libdeflate-pas/blob/c044fe1a7b0e2e9930c6a5110c7bd194a4872c91/readme.md) identifies upstream libdeflate.
- `External/lz4-delphi` is pinned at `6d6244eb768797c1a5aa6346848e6ee68d096e0f`; the wrapper is BSD-2-Clause ([`LICENSE`](https://github.com/ElminsterAU/lz4-delphi/blob/6d6244eb768797c1a5aa6346848e6ee68d096e0f/LICENSE)), and it separately carries BSD-2-Clause notices for [LZ4](https://github.com/ElminsterAU/lz4-delphi/blob/6d6244eb768797c1a5aa6346848e6ee68d096e0f/LICENSE.lz4) and [xxHash](https://github.com/ElminsterAU/lz4-delphi/blob/6d6244eb768797c1a5aa6346848e6ee68d096e0f/LICENSE.xxHash).

Therefore, the top-level MPL notice cannot be used as a shortcut for files or binaries originating in a submodule. Conversely, those permissive submodule licenses do not relicense the MPL-covered archive units. Any copied material must be traced to its own file and upstream origin.

## What MPL 2.0 establishes

The following points are express license terms or Mozilla's official guidance:

- The MPL applies to Source Code Form carrying the notice, executable forms of that source, and Modifications, including a new source file that contains any Covered Software ([MPL 2.0 §§1.4 and 1.10](https://www.mozilla.org/en-US/MPL/2.0/#definitions)).
- Use creates no MPL compliance obligation; obligations arise on distribution outside the organization ([MPL FAQ Q5–Q6](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q5)). A solo developer running the read-only reference or a local oracle is using it, not redistributing it.
- New files containing no MPL-licensed code are not Modifications, even when they form a Larger Work with MPL files ([MPL FAQ Q11](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q11)). This is the license basis for keeping independently authored Java files under a separately chosen project license.
- Distributed MPL Source Code Form, including Modifications, must remain under MPL-2.0; recipients must be told it is MPL-governed and how to obtain the license, and existing substantive notices must remain ([MPL 2.0 §§3.1 and 3.4](https://www.mozilla.org/en-US/MPL/2.0/#responsibilities)).
- When Covered Software is distributed in executable form, the corresponding Source Code Form must be made available by reasonable means in a timely manner, and recipients must be told how to obtain it ([MPL 2.0 §3.2](https://www.mozilla.org/en-US/MPL/2.0/#distribution-of-executable-form); [MPL FAQ Q8–Q10](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q8)).
- Separate non-MPL files may be distributed with MPL files as a Larger Work under terms of the distributor's choice, while the Covered Software remains subject to MPL ([MPL 2.0 §3.3](https://www.mozilla.org/en-US/MPL/2.0/#distribution-of-a-larger-work)).
- The MPL grants no trademark, service-mark, or logo rights except as necessary for notice compliance ([MPL 2.0 §2.3](https://www.mozilla.org/en-US/MPL/2.0/#limitations-on-grant-scope)). Reference attribution therefore does not authorize logos, an implication of endorsement, or branding the Java product as an official TES5Edit/BSArch release.

### Independent behavior reimplementation

**Verified constraint:** Under the MPL's file-level definition and Mozilla's FAQ, a newly authored Java file containing no MPL code is not an MPL Modification. United States copyright law also separates functional subject matter from protected expression: copyright does not extend to an idea, procedure, process, system, or method of operation ([17 U.S.C. §102(b)](https://www.copyright.gov/title17/92chap1.html#102)).

**Engineering rule:** Implement from a written behavior matrix, format facts, independent public specifications, generated tests, and black-box observations. Citations may identify which Reference Snapshot behavior was confirmed, but implementation commits should not carry copied Pascal snippets. Re-derive constants and structures from format semantics or independently published specifications where possible. Preserve a provenance record for unusual constants and algorithms.

**Counsel boundary:** “Independent” is fact-specific. The same developer may read the reference and write Java; this is not a formal clean-room process. Whether substantial similarity, selected constants/tables, structure, sequence, or distinctive comments copy protected expression cannot be decided from the license text alone.

### Adapted or translated Reference Snapshot source

**Verified constraint:** MPL defines a Modification to include a new source file containing Covered Software. U.S. copyright law defines a derivative work to include a translation or another recasting, transformation, or adaptation ([17 U.S.C. §101](https://www.copyright.gov/title17/92chap1.html#101)).

**Project policy:** Treat any mechanical, line-by-line, or recognizably structure-preserving Pascal-to-Java translation as Covered Software. The Java file must carry an MPL-2.0 notice, preserve relevant copyright and attribution notices, identify the source file and pinned commit, and remain available in preferred form for modification. A distributed JAR or native image containing it must point recipients to the exact corresponding source revision. Keep independently authored files separate so the MPL's file-level boundary remains inspectable.

Do not combine copied MPL expression and independently licensed code in the same file merely to avoid a module boundary. If adaptation becomes necessary for conformance, record that choice before implementation and obtain counsel on the exact file and chosen project license.

### Golden outputs and captured oracle results

Running the Reference Snapshot to generate an archive is use, which the MPL does not restrict. The MPL does not state that all program output becomes Covered Software; Covered Software is defined by the source carrying the notice, its executable form, and modifications of that source. Mozilla also says MPL was written for software and generally should not be used for non-software works ([MPL FAQ Q18](https://www.mozilla.org/en-US/MPL/2.0/FAQ/#q18)).

Accordingly, the low-risk committed corpus is:

- synthetic input bytes and names authored for this project;
- archive outputs mechanically produced from those inputs;
- semantic manifests, hashes, and normalized observations; and
- a provenance record naming the generating reference commit, command, and input license.

Do not commit the reference executable, its DLLs, game archives, extracted game assets, or outputs that embed those assets. Avoid capturing the full BSArch banner/license text as a golden CLI output when semantic output assertions suffice. If a generated file contains any Reference Snapshot code or expressive third-party input, its status follows that embedded material rather than the fact that a program generated it.

**Counsel boundary:** Copyrightability and derivative-work status of a particular binary golden file depend on its contents and jurisdiction. Review any golden artifact derived from more than project-owned synthetic inputs before public distribution.

### Fixtures

Every committed fixture needs a machine-readable or Markdown provenance entry recording creator/source, applicable license or permission, generation procedure, and content hash.

- Project-authored synthetic fixtures can be distributed under the project's chosen fixture policy.
- A third-party test vector or sample archive must have an explicit redistribution grant; a public download URL alone is not permission to republish.
- Legally obtained game archives and extracted assets remain ignored local inputs and must never be committed or attached to releases. No general redistribution permission for those assets was established in this research.
- A hash, size, family, and non-sensitive provenance category can describe a local fixture without redistributing its content, but do not publish paths, account data, or other sensitive local metadata.

### Attribution and source availability

For an entirely independent implementation, MPL attribution is not established as a legal condition. It remains good provenance practice to credit TES5Edit/BSArch as behavioral reference, name the pinned commit, link its MPL license, and state that the Java project is independent and unaffiliated.

For copied or adapted MPL material, attribution is an obligation rather than courtesy: retain relevant source notices, include the MPL-2.0 license, and make exact corresponding source available. A practical distribution should include `META-INF/LICENSES/MPL-2.0.txt`, `META-INF/THIRD-PARTY-NOTICES`, and a source URL tied to the released tag. Those filenames are an engineering convention, not language imposed by the MPL; the actual test is whether Sections 3.1–3.4 are satisfied.

## Candidate dependency and native-binary constraints

The codec research ticket makes the technical selection. This table records only licensing/distribution facts for its present shortlist; it is not a dependency recommendation.

| Candidate | Primary license evidence | Distribution consequence |
| --- | --- | --- |
| `com.fulcrumgenomics:jlibdeflate:0.1.0` | Its [Maven POM](https://repo.maven.apache.org/maven2/com/fulcrumgenomics/jlibdeflate/0.1.0/jlibdeflate-0.1.0.pom) and tagged [`LICENSE`](https://github.com/fulcrumgenomics/jlibdeflate/blob/v0.1.0/LICENSE) say MIT. Its tagged [README](https://github.com/fulcrumgenomics/jlibdeflate/blob/v0.1.0/README.md#libdeflate-version) says the [JAR](https://repo.maven.apache.org/maven2/com/fulcrumgenomics/jlibdeflate/0.1.0/jlibdeflate-0.1.0.jar) bundles libdeflate v1.25 and native libraries including Windows x86-64; upstream [libdeflate is MIT](https://github.com/ebiggers/libdeflate#license). | Shipping the dependency or a fat JAR ships both wrapper and native code. Include the Fulcrum Genomics MIT notice and upstream libdeflate MIT notice in the binary distribution. Track the exact embedded libdeflate revision and native build provenance. |
| `org.lwjgl:lwjgl-lz4:3.4.3`, `org.lwjgl:lwjgl:3.4.3`, and `natives-windows` | The exact [`lwjgl-lz4` POM](https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-lz4/3.4.3/lwjgl-lz4-3.4.3.pom) declares BSD-3-Clause; LWJGL's [`LICENSE.md`](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md) contains its conditions. LWJGL identifies its LZ4 binding's [upstream source](https://github.com/LWJGL/lwjgl3/blob/master/modules/generator/src/main/kotlin/org/lwjgl/generator/Modules.kt), whose library is BSD-2-Clause ([LZ4 licensing split](https://github.com/lz4/lz4/blob/dev/LICENSE)). The separate [Windows native classifier](https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-lz4/3.4.3/lwjgl-lz4-3.4.3-natives-windows.jar) contains the native payload. | A CLI bundle containing LWJGL's Java/core/binding JARs and Windows native classifier must reproduce the applicable LWJGL BSD-3-Clause and liblz4 BSD-2-Clause notices in its documentation or other distribution materials. Do not accidentally include LZ4's GPL-licensed CLI/program sources; the upstream license explicitly distinguishes them from `lib/`. |
| `io.airlift:aircompressor-v3:3.7` | Its exact [POM](https://repo.maven.apache.org/maven2/io/airlift/aircompressor-v3/3.7/aircompressor-v3-3.7.pom) declares Apache-2.0 and Java 25. The project documents both pure-Java and bundled-native implementations in its [official repository](https://github.com/airlift/aircompressor), and the distributed [JAR](https://repo.maven.apache.org/maven2/io/airlift/aircompressor-v3/3.7/aircompressor-v3-3.7.jar) contains the payload that must be audited. | Apache-2.0 §4 requires a license copy, modified-file notices where applicable, retention of relevant source notices, and preservation of NOTICE attributions when a NOTICE exists ([official Apache-2.0 text](https://www.apache.org/licenses/LICENSE-2.0)). Because the artifact embeds native libraries for several algorithms/platforms, audit the actual selected JAR contents and every embedded library license, even if only a pure-Java path is invoked on Windows. |
| `org.lz4:lz4-java:1.8.0` | Its exact [Maven POM](https://repo.maven.apache.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.pom) declares Apache-2.0 and describes Java ports/bindings of LZ4 and xxHash. Its distributed [JAR](https://repo.maven.apache.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar) includes native payloads. Upstream LZ4 library code is BSD-2-Clause and [xxHash library files are BSD-2-Clause](https://github.com/Cyan4973/xxHash#license). | A redistributed copy or shaded bundle needs an inventory that preserves Apache-2.0 obligations plus the applicable LZ4/xxHash notices. The POM's single license element is not enough evidence for the embedded native payload. |
| `org.apache.commons:commons-compress:1.28.0` | The [official project page](https://commons.apache.org/proper/commons-compress/) and the [`LICENSE.txt` and `NOTICE.txt` in its JAR](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar) identify Apache-2.0; the project page also documents the origins and optional dependencies of its code. | Preserve `LICENSE.txt` and `NOTICE.txt` when bundling or shading. Its own JAR already carries these files, but verify that the shading process merges rather than overwrites same-named notices. |
| Internal DDS implementation | No dependency license applies to independently written code derived from format facts. Microsoft's DirectXTex is a possible implementation reference and is [MIT-licensed](https://github.com/microsoft/DirectXTex#notices); its [`DDS.h`](https://github.com/microsoft/DirectXTex/blob/main/DirectXTex/DDS.h) and reader/writer source carry Microsoft copyright and MIT notices. | Do not copy DirectXTex headers, tables, or implementation into Java while describing the result as dependency-free. If code is adapted, preserve Microsoft's MIT notice. Independently derive the minimum parser/writer from documented format semantics and project tests. |
| Optional DirectXTex native adapter | DirectXTex source and binaries are MIT-licensed ([official repository notice](https://github.com/microsoft/DirectXTex#notices)). | If later bundled, include Microsoft's copyright and MIT text, record the exact revision/build, and audit any optional components enabled in the native build. Keep the native provider replaceable as already required by the Wayfinder map. |

MIT requires its copyright and permission notice to accompany copies or substantial portions. BSD-2-Clause and BSD-3-Clause require retention in source distributions and reproduction in documentation or other materials with binary distributions; BSD-3-Clause also prohibits endorsement using the licensor/contributor names. Apache-2.0 has the distinct Section 4 obligations summarized above. Use the exact upstream texts, not a hand-written paraphrase, in releases.

Two packaging distinctions matter:

1. A normal Maven library dependency is ordinarily downloaded as its own artifact; a shaded/fat CLI JAR, application ZIP, installer, native image, or copied `lib/` directory redistributes dependency bytes and must carry their notices.
2. A JAR that internally embeds DLLs or other platform binaries redistributes those binaries whenever the JAR is shipped, even when the current machine never loads them. Audit contents, not only the active code path or POM `<licenses>` entry.

Build plugins and test-only dependencies that are not copied into a release are not release payloads. They still belong in the dependency inventory, but the binary-notice set should be derived from actual distributed files. If a test fixture or generated source copies their content, reassess that content separately.

## Maven Central-ready distribution

Sonatype's current Central requirements call for a primary artifact, POM, source JAR, Javadoc JAR, checksums, signatures, and POM metadata including project name/description/URL, license, developers, and SCM ([Central requirements](https://central.sonatype.org/publish/requirements/)). The Central Maven plugin does not generate or enforce every prerequisite, particularly source/Javadoc/signature and metadata requirements ([Central Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/)).

For this project:

- Each published artifact's POM must describe that artifact's own chosen license accurately. If an artifact contains mixed-license source, also document the per-file boundary; multiple POM `<license>` elements alone do not map licenses to files.
- Publish the real preferred Java source. A placeholder source JAR that passes Central validation would not satisfy MPL §3.2 for adapted MPL executable code.
- Include project license text and generated third-party notices in source and binary release assemblies. Configure shading/resource transforms so `META-INF/LICENSE*` and `META-INF/NOTICE*` entries are merged or relocated, not silently discarded.
- Pin dependencies and native revisions. Generate a release inventory with Maven coordinates, classifier, file hash, license identifier, upstream source URL, notice path, and whether bytes are included in each artifact.
- If a self-contained Java runtime or native image is later distributed, audit the selected JDK/runtime and native-image components separately; publishing an ordinary JAR that requires a user-installed Java 25 runtime does not itself bundle that runtime.
- Perform the audit before release. Central states that published components are immutable and generally cannot be removed or modified after publication ([Central immutability policy](https://central.sonatype.org/publish/requirements/immutability/)).

Central's technical acceptance is not a license opinion. The publisher remains responsible for having rights to every source, fixture, generated artifact, and native binary in the publication.

## Required implementation and release gates

Before implementation begins:

- Choose and document the project license and whether deliberate MPL adaptation is allowed at all.
- Add a contributor rule prohibiting unmarked copy/translation from `TES5Edit` and requiring provenance for externally derived code or fixtures.
- Define the fixture manifest fields and keep proprietary local fixtures ignored.

Before accepting a dependency:

- Inspect the exact version's POM, source license, NOTICE file, transitive graph, JAR contents, native classifiers, and embedded binaries.
- Record all licenses represented by the bytes, not only the top-level Maven license value.
- Reject artifacts whose redistribution provenance cannot be established, or obtain clarification from the publisher/counsel before use.

Before each release:

- Diff the resolved dependency/native inventory against the previous release.
- Verify project and third-party license/notice files are present in every JAR, CLI ZIP/installer, source archive, and documentation bundle where required.
- If any MPL-covered executable code is present, verify the exact corresponding source is available and the binary tells recipients where to obtain it.
- Verify the committed fixture corpus contains only approved redistributable content.
- Verify names and packaging do not imply TES5Edit/BSArch endorsement or use contributor logos.
- Inspect the final artifacts after shading, minimization, or native-image construction; source-tree compliance does not prove packaged compliance.

## Questions requiring counsel or rightsholder clarification

The primary sources do not resolve these fact-specific questions:

1. At what point a Java implementation written after studying the Pascal reference is substantially similar enough to contain protected Reference Snapshot expression, despite independently typed source.
2. Whether any copied lookup table, binary layout declaration, hash implementation, DDS conversion table, ordering logic, or test expectation is copyrightable expression rather than an unprotected fact or method in the applicable jurisdiction.
3. Whether a particular golden archive or captured CLI transcript is copyrightable, derivative, or contains protected third-party input.
4. Whether use of “BSArch” in the project/artifact/CLI name presents trademark or passing-off risk. MPL expressly grants no trademark rights.
5. Whether reverse engineering or local oracle execution is restricted by a separately accepted game/tool EULA or local law. MPL permission covers the MPL software; it does not grant rights in Bethesda assets or third-party agreements.
6. Patent exposure for archive, compression, or texture functionality not covered by the limited patent grants of an applicable dependency license. MPL and Apache-2.0 grants come only from their respective contributors; MIT and BSD texts do not contain an express patent grant.
7. Any future distribution of proprietary game data, Xbox conversion tooling, SDK binaries, or Microsoft/Bethesda components beyond the open-source DirectXTex code identified here.

Until those questions are answered, the conservative project rule is: independently implement behavior, keep provenance, distribute only project-owned synthetic fixtures, preserve all third-party notices, and escalate any proposed source translation or proprietary payload before it enters a public artifact.
