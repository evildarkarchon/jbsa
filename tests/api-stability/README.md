# Public API stability tests

Run `gradle :jbsa-conformance-tests:apiStabilityTest`. The suite runs the public
behavior contracts, the external named-module source consumers, the module
architecture checks, and a consumer that was compiled before the candidate JAR.
The root `verify` and `automatedAssurance` tasks also run this suite.

The fixed [consumer source](consumer/consumer/apistability/Main.java) explains
which public operations the committed
[`consumer.jar`](../../jbsa-conformance-tests/src/test/resources/api-stability/consumer.jar)
uses. Normal test runs execute that JAR without recompiling it. This catches
binary linkage breaks in the used facade, request, outcome, ownership, and
failure APIs. `PublicModuleConsumerIT` separately compiles its fixed source
against the candidate JAR, so it catches source compatibility breaks. Contract
and architecture tests check behavior and public module boundaries. The suite
allows additive public API changes; it does not compare complete declarations
or hash source files.

When a reviewed interface change intentionally replaces a contract used by the
fixed consumer, update its source and binary JAR with the specification and
affected tests. Build the library first, then compile the consumer with Java 25
and give the JAR entries a fixed timestamp:

```powershell
gradle :jbsa:jar
$jdk25 = 'C:\OpenJDK\jdk-25' # Set this to a local Java 25 JDK.
$libraryJar = (Get-ChildItem jbsa/target/libs -Filter 'jbsa-*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Select-Object -First 1).FullName
$classes = Join-Path (Get-Location) "target/api-stability-$(New-Guid)"
New-Item -ItemType Directory -Path $classes | Out-Null
& "$jdk25/bin/javac.exe" --release 25 --module-path $libraryJar -d $classes `
    tests/api-stability/consumer/module-info.java `
    tests/api-stability/consumer/consumer/apistability/Main.java
& "$jdk25/bin/jar.exe" --create `
    --file jbsa-conformance-tests/src/test/resources/api-stability/consumer.jar `
    --date=2000-01-01T00:00:00Z -C $classes .
```
