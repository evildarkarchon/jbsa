<#
.SYNOPSIS
Exercises revision propagation through both reproducibility passes using an isolated build fixture.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "jbsa-revision-test-$([guid]::NewGuid().ToString('N'))"
try {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'build') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-reproducible-build.ps1') -Destination (Join-Path $fixtureRoot 'build')
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'pom.xml') -Value '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><revision>0.1.0-SNAPSHOT</revision></properties></project>'
    # The fixture only produces the requested version, so comparing default-version artifacts cannot pass.
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'mvnw.cmd') -Value '@pwsh -NoProfile -File "%~dp0fixture-build.ps1" %*'
    $fixtureBuild = @'
$revisionArgument = @($args | Where-Object { $_ -like '-Drevision=*' })
if ($revisionArgument.Count -ne 1) { throw 'Expected exactly one effective revision argument.' }
$revision = $revisionArgument[0].Substring('-Drevision='.Length)
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'passes.txt') -Value $revision
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'jbsa/target'), (Join-Path $PSScriptRoot 'jbsa-cli/target') -Force | Out-Null
$paths = @('jbsa/.flattened-pom.xml', "jbsa/target/jbsa-$revision.jar", "jbsa/target/jbsa-$revision-sources.jar", "jbsa/target/jbsa-$revision-javadoc.jar", "jbsa-cli/target/jbsa-cli-$revision.jar")
foreach ($path in $paths) { Set-Content -LiteralPath (Join-Path $PSScriptRoot $path) -Value "deterministic-$revision" }
'@
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'fixture-build.ps1') -Value $fixtureBuild
    foreach ($revision in @('2.3.4', '0.1.0-SNAPSHOT')) {
        $arguments = @('-NoProfile', '-File', (Join-Path $fixtureRoot 'build/verify-reproducible-build.ps1'))
        if ($revision -eq '2.3.4') { $arguments += @('-ReactorVersion', $revision) }
        $output = & pwsh @arguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Reproducibility fixture failed: $output" }
        $passes = @(Get-Content -LiteralPath (Join-Path $fixtureRoot 'passes.txt'))
        if ($passes[-1] -cne $revision -or $passes[-2] -cne $revision) {
            throw "Both builds must receive revision $revision."
        }
        if (($output -join "`n") -notmatch "jbsa/target/jbsa-$([regex]::Escape($revision)).jar") {
            throw 'The comparison did not include the requested artifact version.'
        }
    }
    Write-Output 'Reproducibility revision regression checks passed.'
}
finally {
    $resolvedRoot = [System.IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside the temporary directory: $resolvedRoot"
    }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
