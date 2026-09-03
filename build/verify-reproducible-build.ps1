<#
.SYNOPSIS
Builds the public reactor artifacts twice and requires identical SHA-256 hashes.

.NOTES
Only the library inputs and thin CLI JAR are compared. Test reports and other environmental output
are intentionally outside this reproducibility check.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mavenWrapper = Join-Path $reactorRoot 'mvnw.cmd'
$rootPom = [xml](Get-Content -Raw -LiteralPath (Join-Path $reactorRoot 'pom.xml'))
$pomNamespaces = New-Object System.Xml.XmlNamespaceManager($rootPom.NameTable)
$pomNamespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
$revisionNode = $rootPom.SelectSingleNode('/m:project/m:properties/m:revision', $pomNamespaces)
if ($null -eq $revisionNode -or [string]::IsNullOrWhiteSpace($revisionNode.InnerText)) {
    throw 'The root POM does not declare the reactor revision.'
}
$reactorVersion = $revisionNode.InnerText.Trim()
$scratchRoot = [System.IO.Path]::Combine(
    [System.IO.Path]::GetTempPath(),
    "jbsa-reproducibility-$([System.Guid]::NewGuid().ToString('N'))"
)
$artifactPaths = @(
    'jbsa/.flattened-pom.xml',
    "jbsa/target/jbsa-$reactorVersion.jar",
    "jbsa/target/jbsa-$reactorVersion-sources.jar",
    "jbsa/target/jbsa-$reactorVersion-javadoc.jar",
    "jbsa-cli/target/jbsa-cli-$reactorVersion.jar"
)

try {
    New-Item -ItemType Directory -Path $scratchRoot | Out-Null
    Push-Location $reactorRoot
    try {
        foreach ($pass in 1..2) {
            & $mavenWrapper -B -ntp -C -DskipTests clean package
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
