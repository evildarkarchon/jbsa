<#
.SYNOPSIS
Exercises the Gradle qualification workflow against a deterministic isolated build fixture.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-gradle-qualification-test-$([guid]::NewGuid().ToString('N'))"
$repository = Join-Path $fixtureRoot 'source'
$evidence = Join-Path $fixtureRoot 'maven-evidence'
$qualification = Join-Path $fixtureRoot 'qualification'
$jdkStaging = Join-Path $fixtureRoot 'jdk-staging/jdk'
$jdkArchive = Join-Path $fixtureRoot 'qualification-jdk.zip'

try {
    New-Item -ItemType Directory -Path (Join-Path $repository 'build'), (Join-Path $repository '.scratch/migrate-maven-to-gradle'), (Join-Path $repository 'gradle/wrapper'), (Join-Path $repository 'jbsa'), (Join-Path $evidence 'resolved-graphs'), (Join-Path $jdkStaging 'bin') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-gradle-qualification.ps1') -Destination (Join-Path $repository 'build')
    [IO.File]::WriteAllText((Join-Path $jdkStaging 'release'), "IMPLEMENTOR_VERSION=`"Temurin-25.0.4+7`"`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path $jdkStaging 'bin/java.exe'), [byte[]](1, 2, 3, 4))
    [IO.File]::WriteAllBytes((Join-Path $jdkStaging 'bin/jar.exe'), [byte[]](5, 6, 7, 8))
    Compress-Archive -Path (Join-Path $fixtureRoot 'jdk-staging/*') -DestinationPath $jdkArchive
    $archiveHash = (Get-FileHash -LiteralPath $jdkArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    $releaseHash = (Get-FileHash -LiteralPath (Join-Path $jdkStaging 'release') -Algorithm SHA256).Hash.ToLowerInvariant()
    $javaHash = (Get-FileHash -LiteralPath (Join-Path $jdkStaging 'bin/java.exe') -Algorithm SHA256).Hash.ToLowerInvariant()

    $protocol = [ordered]@{
        schemaVersion = 1
        migrationBranch = 'initial-implementation'
        candidateVersion = '0.1.0-SNAPSHOT'
        qualificationJdk = [ordered]@{
            implementorVersion = 'Temurin-25.0.4+7'
            distributionSha256 = $archiveHash
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $repository '.scratch/migrate-maven-to-gradle/baseline-protocol.json'),
        (($protocol | ConvertTo-Json -Depth 10) + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
    Set-Content -LiteralPath (Join-Path $repository 'gradle.properties') -Value 'version=0.1.0-SNAPSHOT'
    Set-Content -LiteralPath (Join-Path $repository 'gradle.lockfile') -Value 'fixture:root:1=fixture'
    Set-Content -LiteralPath (Join-Path $repository 'settings-gradle.lockfile') -Value 'fixture:settings:1=fixture'
    Set-Content -LiteralPath (Join-Path $repository 'jbsa/gradle.lockfile') -Value @(
        'fixture:library:1=fixture',
        'com.fasterxml.jackson:jackson-bom:2.22.1=fixture',
        'com.fasterxml.jackson.core:jackson-annotations:2.22=fixture',
        'com.fasterxml.jackson.core:jackson-core:2.22.1=fixture',
        'com.fasterxml.jackson.core:jackson-databind:2.22.1=fixture',
        'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1=fixture',
        'org.yaml:snakeyaml:2.5=fixture'
    )
    [IO.File]::WriteAllText(
        (Join-Path $repository 'gradle/verification-metadata.xml'),
        '<verification-metadata><components><component><artifact><sha256 value="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"/></artifact></component></components></verification-metadata>',
        [Text.UTF8Encoding]::new($false)
    )
    $inspector = @'
import argparse, json, pathlib, sys
parser = argparse.ArgumentParser()
sub = parser.add_subparsers(dest="command", required=True)
inspect = sub.add_parser("inspect-build")
inspect.add_argument("--build-root", required=True)
inspect.add_argument("--java-home", required=True)
inspect.add_argument("--version", required=True)
inspect.add_argument("--build-tool", required=True)
inspect.add_argument("--output", required=True)
inspect.add_argument("--profile-output")
compare = sub.add_parser("compare")
compare.add_argument("--expected", required=True)
compare.add_argument("--actual", required=True)
compare.add_argument("--output", required=True)
args = parser.parse_args()
if args.command == "inspect-build":
    if args.build_tool != "gradle":
        raise SystemExit("qualification must select the Gradle layout")
    pathlib.Path(args.output).write_text(json.dumps({"artifacts": {"library": {"sha256": "gradle-envelope", "entries": [{"path": "A.class", "sha256": "payload"}]}}}), encoding="utf-8")
    if args.profile_output:
        pathlib.Path(args.profile_output).write_text(json.dumps({"schemaVersion": 1, "jdkTools": {"launchCount": 2, "milliseconds": 5, "byTool": {"jar": {"launchCount": 1, "milliseconds": 2}, "javap": {"launchCount": 1, "milliseconds": 3}}}}), encoding="utf-8")
else:
    expected = json.loads(pathlib.Path(args.expected).read_text(encoding="utf-8"))
    actual = json.loads(pathlib.Path(args.actual).read_text(encoding="utf-8"))
    expected["artifacts"]["library"].pop("sha256", None)
    actual["artifacts"]["library"].pop("sha256", None)
    matches = expected == actual
    pathlib.Path(args.output).write_text(json.dumps({"matches": matches, "differences": [] if matches else ["payload"]}), encoding="utf-8")
    raise SystemExit(0 if matches else 1)
'@
    [IO.File]::WriteAllText((Join-Path $repository 'build/parity_artifact_inspector.py'), $inspector, [Text.UTF8Encoding]::new($false))
    Set-Content -LiteralPath (Join-Path $repository 'build/test_parity_artifact_inspector.py') -Value 'print("fixture inspector tests passed")'
    Set-Content -LiteralPath (Join-Path $repository 'build/verify-reproducible-build.ps1') -Value @'
Write-Output 'jbsa/target/publications/library/pom-default.xml reproducible'
Write-Output 'jbsa/target/libs/jbsa-0.1.0-SNAPSHOT.jar reproducible'
Write-Output 'jbsa/target/libs/jbsa-0.1.0-SNAPSHOT-sources.jar reproducible'
Write-Output 'jbsa/target/libs/jbsa-0.1.0-SNAPSHOT-javadoc.jar reproducible'
Write-Output 'jbsa-cli/target/libs/jbsa-cli-0.1.0-SNAPSHOT.jar reproducible'
'@
    Set-Content -LiteralPath (Join-Path $repository 'gradlew.bat') -Value '@pwsh -NoLogo -NoProfile -NonInteractive -File "%~dp0fixture-gradle.ps1" %*'
    [IO.File]::WriteAllBytes((Join-Path $repository 'gradle/wrapper/gradle-wrapper.jar'), [byte[]](9, 10, 11, 12))
    Set-Content -LiteralPath (Join-Path $repository 'gradle/wrapper/gradle-wrapper.properties') -Value 'distributionUrl=fixture'
    $launcherHash = (Get-FileHash -LiteralPath (Join-Path $repository 'gradlew.bat') -Algorithm SHA256).Hash.ToLowerInvariant()
    $wrapperJarHash = (Get-FileHash -LiteralPath (Join-Path $repository 'gradle/wrapper/gradle-wrapper.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    $wrapperPropertiesHash = (Get-FileHash -LiteralPath (Join-Path $repository 'gradle/wrapper/gradle-wrapper.properties') -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $repository 'fixture-gradle.ps1') -Value @'
$joined = $args -join ' '
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'fixture-calls.log') -Value $joined
if ($args -contains 'clean') {
    foreach ($relative in @('target/compliance', 'target/conformance', 'jbsa-dist/target')) {
        $generated = Join-Path $PSScriptRoot $relative
        if (Test-Path -LiteralPath $generated) { Remove-Item -LiteralPath $generated -Recurse -Force }
    }
}
if ((Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'gradle/verification-metadata.xml')) -match '0000000000000000') {
    Write-Output 'Dependency verification failed for deliberately mismatched checksum.'
    exit 1
}
if ($args -contains '--configuration-cache') {
    $incompatible = @('verify', 'verifyCompliance', 'generateResolvedProductionDependencies', ':jbsa-dist:stageReleaseInputs', ':jbsa-conformance-tests:automatedConformance')
    if (@($args | Where-Object { $_ -in $incompatible }).Count -gt 0) {
        Write-Output 'Configuration cache is not supported for evidence, staging, or external-process tasks'
        exit 1
    }
    $marker = Join-Path $env:GRADLE_USER_HOME 'fixture-configuration-cache'
    if (Test-Path -LiteralPath $marker) { Write-Output 'Configuration cache entry reused.' }
    else { Set-Content -LiteralPath $marker -Value 'stored'; Write-Output 'Configuration cache entry stored.' }
}
if ($args -contains ':jbsa-conformance-tests:automatedConformance') {
    New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'target') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target/conformance-exit-code.txt') -Value '1'
}
if ($args -contains 'verify') {
    $compliance = Join-Path $PSScriptRoot 'target/compliance'
    $conformance = Join-Path $PSScriptRoot 'target/conformance'
    $stage = Join-Path $PSScriptRoot 'jbsa-dist/target/release-inputs'
    New-Item -ItemType Directory -Path $compliance, $conformance, $stage -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target/conformance-exit-code.txt') -Value '1'
    Set-Content -LiteralPath (Join-Path $compliance 'resolved-production-dependencies.json') -Value '{"schemaVersion":1}'
    Set-Content -LiteralPath (Join-Path $compliance 'jbsa.cdx.json') -Value '{"bomFormat":"CycloneDX"}'
    Set-Content -LiteralPath (Join-Path $conformance 'matrix.json') -Value '{"contract":"conformance-v1"}'
    Set-Content -LiteralPath (Join-Path $conformance 'report.json') -Value '{"automated_conformance":false}'
    Set-Content -LiteralPath (Join-Path $stage 'jbsa.ps1') -Value 'param([string] $JavaHome, [switch] $ClassPath) Write-Output "jbsa 0.1.0-SNAPSHOT"'
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'jbsa-dist/target/release-inputs.json') -Value '{"schemaVersion":1}'
}
if ($env:JBSA_QUALIFICATION_TEST_FAIL_ONCE -eq 'timing-clean' -and $args -contains 'clean' -and $args -contains '--offline' -and $args -notcontains 'verify') {
    $failureMarker = Join-Path $env:GRADLE_USER_HOME 'fixture-timing-clean-failed-once'
    if (-not (Test-Path -LiteralPath $failureMarker)) {
        Set-Content -LiteralPath $failureMarker -Value 'failed'
        Write-Output 'Injected one-time harness-only timing setup failure.'
        exit 23
    }
}
exit 0
'@

    git -C $repository init -b initial-implementation | Out-Null
    git -C $repository config user.name 'Qualification Fixture'
    git -C $repository config user.email 'fixture@example.invalid'
    git -C $repository add .
    git -C $repository commit -m fixture | Out-Null
    $revision = (git -C $repository rev-parse HEAD).Trim()

    New-Item -ItemType Directory -Path (Join-Path $evidence 'logs') -Force | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $evidence 'resolved-graphs/jbsa.json'),
        '{"groupId":"io.github.evildarkarchon","artifactId":"jbsa","version":"0.1.0-SNAPSHOT","children":[{"groupId":"fixture","artifactId":"library","version":"1"}]}',
        [Text.UTF8Encoding]::new($false)
    )
    Set-Content -LiteralPath (Join-Path $evidence 'logs/cli-module-version.log') -Value 'jbsa 0.1.0-SNAPSHOT'
    Set-Content -LiteralPath (Join-Path $evidence 'logs/cli-classpath-version.log') -Value 'jbsa 0.1.0-SNAPSHOT'
    $maven = [ordered]@{
        schemaVersion = 1
        source = [ordered]@{ revision = $revision; branch = 'initial-implementation'; clean = $true }
        candidateVersion = '0.1.0-SNAPSHOT'
        qualificationJdk = [ordered]@{
            implementorVersion = 'Temurin-25.0.4+7'
            distributionArchive = [IO.Path]::GetFileName($jdkArchive)
            distributionSha256 = $archiveHash
            releaseSha256 = $releaseHash
            javaExecutableSha256 = $javaHash
        }
        build = [ordered]@{
            startedAt = '2026-09-13T00:00:00.0000000+00:00'
            completedAt = '2026-09-13T00:04:25.0000000+00:00'
        }
        artifacts = [ordered]@{ artifacts = [ordered]@{ library = [ordered]@{ sha256 = 'maven-envelope'; entries = @([ordered]@{ path = 'A.class'; sha256 = 'payload' }) } } }
        gates = @('compile', 'unit', 'architecture', 'formatting', 'policy', 'conformance') | ForEach-Object {
            [ordered]@{ name = "gate-$_"; exitCode = 0; outcome = 'PASS' }
        }
        cliObservations = @(
            [ordered]@{ name = 'cli-module-version'; exitCode = 0; outcome = 'PASS'; log = [ordered]@{ path = 'logs/cli-module-version.log' } },
            [ordered]@{ name = 'cli-classpath-version'; exitCode = 0; outcome = 'PASS'; log = [ordered]@{ path = 'logs/cli-classpath-version.log' } }
        )
        resolvedGraphs = @([ordered]@{ project = 'jbsa'; path = 'resolved-graphs/jbsa.json'; sha256 = 'historical' })
        normativeGaps = @()
    }
    $maven.gates += [ordered]@{
        name = 'gate-conformance-observation'
        exitCode = 1
        outcome = 'BLOCKED'
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'maven-baseline.json'), (($maven | ConvertTo-Json -Depth 30) + "`n"), [Text.UTF8Encoding]::new($false))

    $qualificationScript = Join-Path $repository 'build/verify-gradle-qualification.ps1'
    $env:JBSA_QUALIFICATION_TEST_FAIL_ONCE = 'timing-clean'
    $firstDiagnostic = @(& pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision 2>&1)
    if ($LASTEXITCODE -eq 0) { throw 'The injected late harness failure did not stop qualification.' }
    if (-not (Test-Path -LiteralPath "$qualification.pending/checkpoints/verified-cache-offline-build.json" -PathType Leaf)) {
        throw 'The failed run did not retain its full-suite checkpoint.'
    }
    if (-not (Test-Path -LiteralPath "$qualification.pending/logs/timing-clean.log" -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$qualification.pending/checkpoints/cli-module-version.json" -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$qualification.pending/checkpoints/cli-classpath-version.json" -PathType Leaf)) {
        $retainedNames = @(Get-ChildItem -LiteralPath "$qualification.pending/checkpoints" -File | ForEach-Object { $_.Name }) -join ', '
        throw "The injected failure did not occur after CLI checkpointing; retained: $retainedNames; diagnostic: $($firstDiagnostic -join ' | ')"
    }

    $baselineBytes = [IO.File]::ReadAllBytes((Join-Path $evidence 'maven-baseline.json'))
    Add-Content -LiteralPath (Join-Path $evidence 'maven-baseline.json') -Value ' '
    $resumeDiagnostic = @(& pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision -Resume 2>&1)
    if ($LASTEXITCODE -eq 0 -or ($resumeDiagnostic -join "`n") -notmatch 'digest|binding') {
        throw 'Resume accepted evidence whose digest no longer matched the checkpoint binding.'
    }
    [IO.File]::WriteAllBytes((Join-Path $evidence 'maven-baseline.json'), $baselineBytes)

    $retainedState = Get-Content -Raw -LiteralPath "$qualification.pending/qualification-state.json" | ConvertFrom-Json -Depth 20
    $extractedJar = Join-Path ([string] $retainedState.scratchRoot) 'j/jdk/bin/jar.exe'
    $extractedJarBytes = [IO.File]::ReadAllBytes($extractedJar)
    Add-Content -LiteralPath $extractedJar -Value 'tampered'
    $jdkDiagnostic = @(& pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision -Resume 2>&1)
    if ($LASTEXITCODE -eq 0 -or ($jdkDiagnostic -join "`n") -notmatch 'JDK extraction digest') {
        throw 'Resume accepted a changed JDK tool that was outside the prior release/java spot checks.'
    }
    [IO.File]::WriteAllBytes($extractedJar, $extractedJarBytes)

    $checkpointLog = "$qualification.pending/logs/gradle-version.log"
    $checkpointLogBytes = [IO.File]::ReadAllBytes($checkpointLog)
    Add-Content -LiteralPath $checkpointLog -Value 'tampered'
    $logDiagnostic = @(& pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision -Resume 2>&1)
    if ($LASTEXITCODE -eq 0 -or ($logDiagnostic -join "`n") -notmatch 'log digest') {
        throw 'Resume accepted a completed phase whose retained log no longer matched its digest.'
    }
    [IO.File]::WriteAllBytes($checkpointLog, $checkpointLogBytes)

    $checkpointOutput = "$qualification.pending/snapshots/artifact-inspector-profile.json"
    $checkpointOutputBytes = [IO.File]::ReadAllBytes($checkpointOutput)
    Add-Content -LiteralPath $checkpointOutput -Value 'tampered'
    $outputDiagnostic = @(& pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision -Resume 2>&1)
    if ($LASTEXITCODE -eq 0 -or ($outputDiagnostic -join "`n") -notmatch 'output digest') {
        throw 'Resume accepted a completed phase whose retained output no longer matched its digest.'
    }
    [IO.File]::WriteAllBytes($checkpointOutput, $checkpointOutputBytes)

    & pwsh -NoLogo -NoProfile -NonInteractive -File $qualificationScript -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision -Resume
    if ($LASTEXITCODE -ne 0) { throw 'Resumed Gradle qualification fixture failed.' }

    $result = Get-Content -Raw -LiteralPath (Join-Path $qualification 'gradle-qualification.json') | ConvertFrom-Json -Depth 100
    if ($result.source.revision -cne $revision -or -not $result.source.clean) { throw 'Qualification did not bind the clean source revision.' }
    if ($result.qualificationJdk.distributionSha256 -cne $archiveHash -or [string]::IsNullOrWhiteSpace($result.qualificationJdk.extractedSha256) -or -not $result.qualificationJdk.matchesMaven) { throw 'Qualification did not bind the exact JDK archive and complete extracted identity.' }
    if ($result.gradle.launcherSha256 -cne $launcherHash -or $result.gradle.wrapperJarSha256 -cne $wrapperJarHash -or
        $result.gradle.wrapperPropertiesSha256 -cne $wrapperPropertiesHash) {
        throw 'Qualification did not distinguish the Gradle launcher, wrapper JAR, and wrapper properties identities.'
    }
    if (-not $result.parity.matches -or -not $result.parity.archiveEnvelopeDifferencesIgnored) { throw 'Normalized cross-tool parity did not ignore only archive envelopes.' }
    if (-not $result.reproducibility.matches -or $result.reproducibility.artifactCount -ne 5) { throw 'The complete five-artifact reproducibility workflow was not retained.' }
    if (-not $result.offline.verifiedCacheBuildPassed -or -not $result.offline.strictVerificationFailureObserved) { throw 'Verified-cache offline and strict-verification evidence is incomplete.' }
    if (-not $result.lockfiles.byteConsistent) { throw 'Qualification changed a lockfile.' }
    if (-not $result.configurationCache.compatibleTasks.firstStored -or -not $result.configurationCache.compatibleTasks.secondReused -or -not $result.configurationCache.incompatibleTasks.rejected) { throw 'Configuration-cache proof is incomplete.' }
    if ($result.timings.cold.milliseconds -lt 0 -or $result.timings.warm.milliseconds -lt 0 -or $null -ne $result.timings.speedClaim) { throw 'Timing evidence must contain raw measurements and no speed claim.' }
    if (@($result.gates | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0 -or @($result.cli | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0) { throw 'Gate or CLI parity was not retained.' }
    if (-not $result.resume.resumed -or @($result.resume.reusedCommands).Count -eq 0 -or 'verified-cache-offline-build' -notin @($result.resume.reusedCommands)) { throw 'Resume did not report reuse of completed full-suite evidence.' }
    if (@($result.gates | Where-Object { $_.command.reusedFrom -cne 'verified-cache-offline-build' }).Count -ne 0) { throw 'Duplicated gate executions were not derived from the qualifying offline full-suite run.' }
    if ($result.profiling.artifactInspector.jdkTools.launchCount -ne 2 -or $result.profiling.duplicateGateExecutionsAfter -ne 0) { throw 'Qualification profiling did not retain JDK launch and duplicate-gate evidence.' }
    $handoff = $result.releaseQualificationHandoff
    if ($handoff.status -cne 'prepared-not-qualified' -or $handoff.manualQualificationPerformed -or $handoff.fileCount -ne 6) {
        throw 'The report did not preserve the unqualified manual Release Qualification handoff.'
    }
    foreach ($relative in @(
        'release-qualification/release-inputs/jbsa.ps1',
        'release-qualification/release-inputs.json',
        'release-qualification/compliance/resolved-production-dependencies.json',
        'release-qualification/compliance/jbsa.cdx.json',
        'release-qualification/automated-conformance/matrix.json',
        'release-qualification/automated-conformance/report.json'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $qualification $relative) -PathType Leaf)) {
            throw "Qualification omitted retained handoff evidence: $relative"
        }
    }
    Write-Output 'Gradle qualification regression checks passed.'
}
finally {
    Remove-Item Env:JBSA_QUALIFICATION_TEST_FAIL_ONCE -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolved = [IO.Path]::GetFullPath($fixtureRoot)
        $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing cleanup outside temp: $resolved" }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
