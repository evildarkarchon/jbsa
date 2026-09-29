# Archive fixture generation and verification

The build-only Java generators create the project-authored archive corpora in
tests/fixtures. They serialize wire bytes independently of the JBSA archive
writer. Each generator has a versioned recipe and writes only to an absent or
empty directory.

| Corpus | Archive versions represented |
| --- | --- |
| tes3 | TES3 BSA |
| tes4, bsa067 | TES4 BSA 0x67 |
| bsa068, bsa069 | BSA 0x68 and 0x69 |
| fo4-general | Fallout 4 General BA2 v1 |
| fo4-dds-v1 | Fallout 4 DDS BA2 v1 |
| synthetic | Fallout 4 General and DDS BA2 v7/v8; Starfield General BA2 v3 method 3 |
| starfield-general | Starfield General BA2 v2 |
| starfield-dds | Starfield DDS BA2 v2 and v3 method 3 |

Generate a review copy from the repository root:

~~~powershell
.\gradlew.bat :jbsa-test-support:generateArchiveFixtures -ParchiveFixtureOutput=target/archive-fixtures-review
~~~

The task creates one subdirectory per corpus. The default output, when the
property is omitted, is target/archive-fixtures-generated. Choose a new empty
path for each run; generation refuses to replace existing files. Compare any
proposed fixture or manifest change with the committed corpus before copying
reviewed files into tests/fixtures.

Verify the committed corpora with JUnit:

~~~powershell
.\gradlew.bat :jbsa-conformance-tests:archiveFixtureTest
~~~

The test task regenerates fixtures in temporary directories, checks the exact
relative file inventory and bytes against the committed copies, and exercises
the generator's empty-destination guard. The Archive Family conformance tasks
also check independent wire properties, decoded payloads, and malformed-input
behavior through the public library. A test failure identifies the changed
corpus and file without rebinding a source-code digest.

Manifest SHA-256 values bind fixture wire bytes, text files, and, where
recorded, recipe inputs. They are data-integrity evidence, not hashes of Java,
Python, or PowerShell source. Generator identity is the recorded procedure
version. Existing independent validators remain available for their separate
wire checks. The optional historical CV1 Automated Conformance runner retains
its frozen catalog bindings and is separate from this fixture workflow.
