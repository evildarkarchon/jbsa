<#
.SYNOPSIS
Exercises Gradle candidate propagation and exact artifact comparison using an isolated build fixture.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "jbsa-reproducibility-test-$([guid]::NewGuid().ToString('N'))"
try {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'build') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-reproducible-build.ps1') -Destination (Join-Path $fixtureRoot 'build')
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'gradle.properties') -Value 'version=0.1.0-SNAPSHOT'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'gradlew.bat') -Value '@pwsh -NoProfile -File "%~dp0fixture-build.ps1" %*'
    $fixtureBuild = @'
$versionArgument = @($args | Where-Object { $_ -like '-Pversion=*' })
if ($versionArgument.Count -ne 1) { throw 'Expected exactly one effective Gradle version argument.' }
if ($args -notcontains '--no-daemon') { throw 'Reproducibility builds must disable the Gradle daemon.' }
if ($args -notcontains 'clean') { throw 'Each reproducibility pass must be clean.' }
$version = $versionArgument[0].Substring('-Pversion='.Length)
Remove-Item -LiteralPath (Join-Path $PSScriptRoot 'jbsa/target'), (Join-Path $PSScriptRoot 'jbsa-cli/target') -Recurse -Force -ErrorAction SilentlyContinue
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'passes.txt') -Value $version
$pass = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'passes.txt')).Count
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'jbsa/target/libs'), (Join-Path $PSScriptRoot 'jbsa/target/publications/library'), (Join-Path $PSScriptRoot 'jbsa-cli/target/libs') -Force | Out-Null
$paths = @(
    'jbsa/target/publications/library/pom-default.xml',
    "jbsa/target/libs/jbsa-$version.jar",
    "jbsa/target/libs/jbsa-$version-sources.jar",
    "jbsa/target/libs/jbsa-$version-javadoc.jar",
    "jbsa-cli/target/libs/jbsa-cli-$version.jar"
)
foreach ($path in $paths) {
    $content = if ($env:JBSA_REPRODUCIBILITY_TEST_MISMATCH -eq 'true' -and $pass % 2 -eq 0 -and $path -like '*jbsa-cli*') {
        "changed-$version"
    } else {
        "deterministic-$version"
    }
    Set-Content -LiteralPath (Join-Path $PSScriptRoot $path) -Value $content
}
'@
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'fixture-build.ps1') -Value $fixtureBuild
    foreach ($revision in @('2.3.4', '0.1.0-SNAPSHOT')) {
        $arguments = @('-NoProfile', '-File', (Join-Path $fixtureRoot 'build/verify-reproducible-build.ps1'))
        if ($revision -eq '2.3.4') { $arguments += @('-ReactorVersion', $revision) }
        $output = & pwsh @arguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Reproducibility fixture failed: $output" }
        $passes = @(Get-Content -LiteralPath (Join-Path $fixtureRoot 'passes.txt'))
        if ($passes[-1] -cne $revision -or $passes[-2] -cne $revision) {
            throw "Both builds must receive Gradle version $revision."
        }
        if (($output -join "`n") -notmatch "jbsa/target/libs/jbsa-$([regex]::Escape($revision)).jar") {
            throw 'The comparison did not include the requested Gradle artifact version.'
        }
    }
    $env:JBSA_REPRODUCIBILITY_TEST_MISMATCH = 'true'
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'passes.txt')
    $mismatch = & pwsh -NoProfile -File (Join-Path $fixtureRoot 'build/verify-reproducible-build.ps1') 2>&1
    if ($LASTEXITCODE -eq 0 -or ($mismatch -join "`n") -notmatch 'Non-reproducible artifact: jbsa-cli/target/libs/') {
        throw 'The reproducibility workflow did not reject a byte-changed thin CLI artifact.'
    }
    Write-Output 'Gradle reproducibility regression checks passed.'
}
finally {
    Remove-Item Env:JBSA_REPRODUCIBILITY_TEST_MISMATCH -ErrorAction SilentlyContinue
    $resolvedRoot = [System.IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside the temporary directory: $resolvedRoot"
    }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
