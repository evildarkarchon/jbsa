# Contributing to JBSA

JBSA accepts external contributions selectively. No contributor license agreement is required.
Maintainer commits may omit sign-off; every accepted external contribution must satisfy the
Developer Certificate of Origin 1.1 and the provenance declarations below.

## Independent authorship

Contributions must be independently authored from documented format facts, observable behavior,
and project-owned synthetic tests. Do not copy, mechanically translate, or preserve distinctive
source structure, comments, or tables from the TES5Edit Reference Snapshot. Do not submit game
assets, local game archives, Conformance Oracle binaries, or output containing protected material.
See [the reference-use policy](docs/reference-use.md) before working from reference evidence.

## DCO sign-off

By adding a sign-off, you certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/).
Sign every commit with:

```text
Signed-off-by: Your Name <your-email@example.com>
```

Git can add the line with `git commit --signoff`. The sign-off must identify the contributor and
must be present on every externally authored commit accepted into the repository.

## Provenance declaration

In the pull request, include a short source and fixture provenance declaration that:

- identifies documentation, standards, observable behavior, or other sources used;
- confirms that no Reference Snapshot code was copied, mechanically translated, or
  structure-preservingly adapted;
- identifies every fixture’s creator or source and redistribution authority; and
- states whether the change introduces dependencies, native bytes, branding, patent, EULA, or
  third-party-rights questions.

Unclear rights or provenance, non-descriptive TES5Edit/BSArch branding, proprietary material,
opaque native bytes, or unresolved substantial-similarity, trademark, patent, or EULA concerns are
stop-before-merge conditions. Keep the material excluded until an explicit project decision and
appropriate qualified review resolve the concern.

## Verification

Run `./mvnw.cmd -B -ntp -C clean verify` on Windows and the focused gate appropriate to the change.
Dependency and native changes must update the compliance inventories before their bytes can enter
release inputs.
