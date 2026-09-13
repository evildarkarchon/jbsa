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
    New-Item -ItemType Directory -Path (Join-Path $repository 'build'), (Join-Path $repository '.scratch/migrate-maven-to-gradle'), (Join-Path $repository 'gradle'), (Join-Path $repository 'jbsa'), (Join-Path $evidence 'resolved-graphs'), (Join-Path $jdkStaging 'bin') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-gradle-qualification.ps1') -Destination (Join-Path $repository 'build')
    [IO.File]::WriteAllText((Join-Path $jdkStaging 'release'), "IMPLEMENTOR_VERSION=`"Temurin-25.0.4+7`"`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path $jdkStaging 'bin/java.exe'), [byte[]](1, 2, 3, 4))
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
    Set-Content -LiteralPath (Join-Path $repository 'jbsa/gradle.lockfile') -Value 'fixture:library:1=fixture'
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
compare = sub.add_parser("compare")
compare.add_argument("--expected", required=True)
compare.add_argument("--actual", required=True)
compare.add_argument("--output", required=True)
args = parser.parse_args()
if args.command == "inspect-build":
    if args.build_tool != "gradle":
        raise SystemExit("qualification must select the Gradle layout")
    pathlib.Path(args.output).write_text(json.dumps({"artifacts": {"library": {"sha256": "gradle-envelope", "entries": [{"path": "A.class", "sha256": "payload"}]}}}), encoding="utf-8")
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
    Set-Content -LiteralPath (Join-Path $repository 'fixture-gradle.ps1') -Value @'
$joined = $args -join ' '
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'fixture-calls.log') -Value $joined
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
$stage = Join-Path $PSScriptRoot 'jbsa-dist/target/release-inputs'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Set-Content -LiteralPath (Join-Path $stage 'jbsa.ps1') -Value 'param([string] $JavaHome, [switch] $ClassPath) Write-Output "jbsa 0.1.0-SNAPSHOT"'
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
    [IO.File]::WriteAllText((Join-Path $evidence 'maven-baseline.json'), (($maven | ConvertTo-Json -Depth 30) + "`n"), [Text.UTF8Encoding]::new($false))

    & (Join-Path $repository 'build/verify-gradle-qualification.ps1') -OutputDirectory $qualification -MavenEvidenceDirectory $evidence -QualificationJdkArchive $jdkArchive -SourceRevision $revision
    if ($LASTEXITCODE -ne 0) { throw 'Gradle qualification fixture failed.' }

    $result = Get-Content -Raw -LiteralPath (Join-Path $qualification 'gradle-qualification.json') | ConvertFrom-Json -Depth 100
    if ($result.source.revision -cne $revision -or -not $result.source.clean) { throw 'Qualification did not bind the clean source revision.' }
    if ($result.qualificationJdk.distributionSha256 -cne $archiveHash -or -not $result.qualificationJdk.matchesMaven) { throw 'Qualification did not bind the exact JDK archive and extracted identity.' }
    if (-not $result.parity.matches -or -not $result.parity.archiveEnvelopeDifferencesIgnored) { throw 'Normalized cross-tool parity did not ignore only archive envelopes.' }
    if (-not $result.reproducibility.matches -or $result.reproducibility.artifactCount -ne 5) { throw 'The complete five-artifact reproducibility workflow was not retained.' }
    if (-not $result.offline.verifiedCacheBuildPassed -or -not $result.offline.strictVerificationFailureObserved) { throw 'Verified-cache offline and strict-verification evidence is incomplete.' }
    if (-not $result.lockfiles.byteConsistent) { throw 'Qualification changed a lockfile.' }
    if (-not $result.configurationCache.compatibleTasks.firstStored -or -not $result.configurationCache.compatibleTasks.secondReused -or -not $result.configurationCache.incompatibleTasks.rejected) { throw 'Configuration-cache proof is incomplete.' }
    if ($result.timings.cold.milliseconds -lt 0 -or $result.timings.warm.milliseconds -lt 0 -or $null -ne $result.timings.speedClaim) { throw 'Timing evidence must contain raw measurements and no speed claim.' }
    if (@($result.gates | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0 -or @($result.cli | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0) { throw 'Gate or CLI parity was not retained.' }
    Write-Output 'Gradle qualification regression checks passed.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolved = [IO.Path]::GetFullPath($fixtureRoot)
        $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing cleanup outside temp: $resolved" }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
