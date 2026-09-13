<#
.SYNOPSIS
Builds the canonical Gradle artifacts twice and requires identical SHA-256 hashes.

.PARAMETER ReactorVersion
Effective Gradle candidate version to rebuild and compare. Defaults to gradle.properties.

.NOTES
The consumer POM, library binary, sources, Javadocs, and thin CLI are compared byte for byte. Test
reports and other environmental output are intentionally outside this reproducibility check.
#>
[CmdletBinding()]
param([string] $ReactorVersion)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gradleWrapper = Join-Path $reactorRoot 'gradlew.bat'
if ([string]::IsNullOrWhiteSpace($ReactorVersion)) {
    $versionLine = @(Get-Content -LiteralPath (Join-Path $reactorRoot 'gradle.properties') | Where-Object {
            $_ -match '^version\s*='
        })
    if ($versionLine.Count -ne 1) {
        throw 'gradle.properties must declare exactly one default version.'
    }
    $ReactorVersion = ($versionLine[0] -split '=', 2)[1].Trim()
}
$scratchRoot = [System.IO.Path]::Combine(
    [System.IO.Path]::GetTempPath(),
    "jbsa-reproducibility-$([System.Guid]::NewGuid().ToString('N'))"
)
$artifactPaths = @(
    'jbsa/target/publications/library/pom-default.xml',
    "jbsa/target/libs/jbsa-$reactorVersion.jar",
    "jbsa/target/libs/jbsa-$reactorVersion-sources.jar",
    "jbsa/target/libs/jbsa-$reactorVersion-javadoc.jar",
    "jbsa-cli/target/libs/jbsa-cli-$reactorVersion.jar"
)

try {
    New-Item -ItemType Directory -Path $scratchRoot | Out-Null
    Push-Location $reactorRoot
    try {
        foreach ($pass in 1..2) {
            # Both clean builds use only the exact canonical producers and the candidate under qualification.
            & $gradleWrapper clean ':jbsa:assembleLibraryPublication' ':jbsa-cli:jar' "-Pversion=$ReactorVersion" --no-daemon
            if ($LASTEXITCODE -ne 0) {
                throw "Reproducibility build $pass failed with exit code $LASTEXITCODE."
            }

            $passRoot = Join-Path $scratchRoot "pass-$pass"
            New-Item -ItemType Directory -Path $passRoot | Out-Null
            foreach ($relativePath in $artifactPaths) {
                $source = Join-Path $reactorRoot $relativePath
                if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
                    throw "Reproducibility build $pass did not produce $relativePath."
                }
                $destination = Join-Path $passRoot $relativePath
                New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
                Copy-Item -LiteralPath $source -Destination $destination
            }
        }
    }
    finally {
        Pop-Location
    }

    foreach ($relativePath in $artifactPaths) {
        $first = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $scratchRoot "pass-1/$relativePath")
        $second = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $scratchRoot "pass-2/$relativePath")
        if ($first.Hash -ne $second.Hash) {
            throw "Non-reproducible artifact: $relativePath ($($first.Hash) != $($second.Hash))."
        }
        Write-Output "$relativePath $($first.Hash)"
    }
}
finally {
    if (Test-Path -LiteralPath $scratchRoot) {
        $resolvedScratch = [System.IO.Path]::GetFullPath($scratchRoot)
        $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if (-not $resolvedScratch.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove scratch directory outside the system temp directory: $resolvedScratch"
        }
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
}
