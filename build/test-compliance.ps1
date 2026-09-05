<#
.SYNOPSIS
Exercises compliance decisions against owned fixture bytes and index-only reference gitlinks.
.NOTES
Loads the verifier definitions without its command entry point. Never creates a TES5Edit directory.
Throws when any expected rejection is absent or an approved input is rejected.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$verifier = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'verify-compliance.ps1')
$entryPoint = $verifier.IndexOf('$dependencyInventory = Read-ComplianceInventory')
$definitions = $verifier.Substring(0, $entryPoint).Replace('$PSScriptRoot', ("'" + $PSScriptRoot.Replace("'", "''") + "'"))
. ([scriptblock]::Create($definitions))
$originalRoot = $reactorRoot
$candidate = (Get-Content -Raw (Join-Path $originalRoot 'compliance/dependency-inventory.json') |
    ConvertFrom-Json).entries[1]
$ownedRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('jbsa-compliance-cases-' + [guid]::NewGuid())
New-Item -ItemType Directory $ownedRoot | Out-Null
$failures = [System.Collections.Generic.List[string]]::new()
$caseCount = 0

<# .SYNOPSIS Creates an owned minimal repository with an index-only pinned reference. #>
function New-PolicyFixture {
    $script:reactorRoot = Join-Path $ownedRoot ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory $reactorRoot | Out-Null
    & git -C $reactorRoot init --quiet
    & git -C $reactorRoot update-index --add --cacheinfo '160000,fd1e36020b2b5b6217e553dc0038983146a2e2dd,TES5Edit'
    Copy-Item (Join-Path $originalRoot '.gitmodules') (Join-Path $reactorRoot '.gitmodules')
    New-Item -ItemType Directory (Join-Path $reactorRoot 'jbsa'), (Join-Path $reactorRoot 'jbsa-cli') | Out-Null
    Set-Content (Join-Path $reactorRoot 'pom.xml') '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><revision>1.0</revision></properties></project>'
    Set-Content (Join-Path $reactorRoot 'jbsa/pom.xml') '<project xmlns="http://maven.apache.org/POM/4.0.0"/>'
    Set-Content (Join-Path $reactorRoot 'jbsa-cli/pom.xml') '<project xmlns="http://maven.apache.org/POM/4.0.0"/>'
    $script:ReactorVersion = '1.0'
}

<# .SYNOPSIS Runs one real policy operation and checks its success or diagnostic category. #>
function Test-PolicyCase {
    param([string] $Name, [scriptblock] $Action, [string] $Rejection = '')
    $script:caseCount++
    try {
        New-PolicyFixture
        & $Action | Out-Null
        if ($Rejection) { throw "Expected rejection matching: $Rejection" }
        Write-Output "PASS $Name"
    } catch {
        if ($Rejection -and $_.Exception.Message -notlike 'Expected rejection*' -and
            $_.Exception.Message -match $Rejection) {
            Write-Output "PASS $Name"
        } else {
            $failures.Add("${Name}: $($_.Exception.Message)")
            Write-Output "FAIL ${Name}: $($_.Exception.Message)"
        }
    }
}

<# .SYNOPSIS Returns independent mutable provenance with cleared promotion blockers. #>
function New-ApprovedDependency {
    $entry = $candidate | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $entry.redistribution.approved = $true
    $entry.redistribution.releaseArtifacts = @('jbsa-cli-1.0-windows-x64.zip')
    $entry.blockedBy = @()
    return $entry
}

<# .SYNOPSIS Writes a one-entry ZIP of exact owned bytes without extracting any paths. #>
function Write-TestZip {
    param([string] $Path, [string] $EntryName, [byte[]] $Bytes)
    $zip = [System.IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $stream = $zip.CreateEntry($EntryName).Open()
        try { $stream.Write($Bytes) } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
}

<# .SYNOPSIS Writes a synthetic CycloneDX component carrying a caller-selected artifact hash. #>
function Write-TestSbom {
    param([object] $Entry, [string] $Hash)
    $component = @{
        group = $Entry.groupId; name = $Entry.artifactId; version = $Entry.version
        purl = "pkg:maven/$($Entry.groupId)/$($Entry.artifactId)@$($Entry.version)?type=jar"
        hashes = @(@{ alg = 'SHA-256'; content = $Hash })
    }
    $path = Join-Path $reactorRoot 'sbom.json'
    @{ bomFormat = 'CycloneDX'; specVersion = '1.6'; components = @($component) } |
        ConvertTo-Json -Depth 10 | Set-Content $path
    return $path
}

try {
    Test-PolicyCase 'approved dependency clears blockers' {
        Get-ValidatedDependencyLookup ([pscustomobject]@{ entries = @((New-ApprovedDependency)) })
    }
    Test-PolicyCase 'approved dependency rejects unresolved blockers' {
        $entry = New-ApprovedDependency
        $entry.blockedBy = @('still unresolved')
        Get-ValidatedDependencyLookup ([pscustomobject]@{ entries = @($entry) })
    } 'blockedBy'
    foreach ($invalid in @('[null]', '[{}]', '"jbsa-cli-1.0-windows-x64.zip"', '["unknown.zip"]',
            '["jbsa-cli-1.0-windows-x64.zip","jbsa-cli-1.0-windows-x64.zip"]')) {
        Test-PolicyCase "invalid release identities $invalid" {
            $entry = New-ApprovedDependency
            $entry.redistribution.releaseArtifacts = ConvertFrom-Json -NoEnumerate $invalid
            Assert-ProvenanceRecord $entry 'candidate'
        } 'releaseArtifacts|containing artifact'
    }
    foreach ($scope in @('test', 'provided')) {
        Test-PolicyCase "non-release $scope dependency" {
            Set-Content (Join-Path $reactorRoot 'jbsa/pom.xml') "<project xmlns='http://maven.apache.org/POM/4.0.0'><dependencies><dependency><groupId>unreviewed</groupId><artifactId>tool</artifactId><version>1.0</version><scope>$scope</scope></dependency></dependencies></project>"
            Test-ProductDependencies @{}
        }
    }
    Test-PolicyCase 'same-group dependency cannot impersonate reactor output' {
        Set-Content (Join-Path $reactorRoot 'jbsa/pom.xml') '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency><groupId>io.github.evildarkarchon</groupId><artifactId>unreviewed-code</artifactId><version>1.0</version></dependency></dependencies></project>'
        Test-ProductDependencies @{}
    } 'absent from the approved inventory'
    Test-PolicyCase 'SBOM same-group artifact is not implicitly approved' {
        $entry = New-ApprovedDependency
        $entry.groupId = 'io.github.evildarkarchon'
        $entry.artifactId = 'unreviewed-code'
        Test-GeneratedSbom ([pscustomobject]@{ entries = @() }) (Write-TestSbom $entry $entry.sha256)
    } 'uninventoried external component'
    Test-PolicyCase 'SBOM reactor exemption requires expected packaging' {
        $entry = New-ApprovedDependency
        $entry.groupId = 'io.github.evildarkarchon'
        $entry.artifactId = 'jbsa-parent'
        $entry.version = '1.0'
        Test-GeneratedSbom ([pscustomobject]@{ entries = @() }) (Write-TestSbom $entry $entry.sha256)
    } 'uninventoried external component'
    Test-PolicyCase 'SBOM current reactor library is accepted' {
        $entry = New-ApprovedDependency
        $entry.groupId = 'io.github.evildarkarchon'
        $entry.artifactId = 'jbsa'
        $entry.version = '1.0'
        Test-GeneratedSbom ([pscustomobject]@{ entries = @() }) (Write-TestSbom $entry $entry.sha256)
    }
    Test-PolicyCase 'SBOM rejects altered dependency digest' {
        $entry = New-ApprovedDependency
        Test-GeneratedSbom ([pscustomobject]@{ entries = @($entry) }) (Write-TestSbom $entry ('0' * 64))
    } 'SHA-256|digest|checksum'
    Test-PolicyCase 'SBOM accepts approved dependency digest' {
        $entry = New-ApprovedDependency
        Test-GeneratedSbom ([pscustomobject]@{ entries = @($entry) }) (Write-TestSbom $entry $entry.sha256)
    }
    foreach ($kind in @('license', 'notice', 'release-notes', 'sbom', 'provenance', 'checksum', 'documentation')) {
        Test-PolicyCase "evidence $kind must match source bytes" {
            $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
            Set-Content (Join-Path $reactorRoot 'LICENSE') 'authentic source'
            $payload = Join-Path $inputs.FullName 'evidence.txt'
            Set-Content $payload 'forged staged bytes'
            $manifest = Join-Path $reactorRoot 'manifest.json'
            @{schemaVersion=1; entries=@(@{path='evidence.txt';sha256=(Get-LowercaseSha256 $payload);kind=$kind;source='LICENSE'})} |
                ConvertTo-Json -Depth 10 | Set-Content $manifest
            Test-ReleaseInputs $inputs.FullName $manifest @{} @{} @{} '1.0'
        } 'does not match.*source'
    }
    Test-PolicyCase 'empty release tree rejects manifested missing files' {
        $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
        $source = Join-Path $reactorRoot 'LICENSE'
        Set-Content $source 'authentic source'
        $manifest = Join-Path $reactorRoot 'manifest.json'
        @{schemaVersion=1; entries=@(@{path='LICENSE';sha256=(Get-LowercaseSha256 $source);kind='license';source='LICENSE'})} |
            ConvertTo-Json -Depth 10 | Set-Content $manifest
        Test-ReleaseInputs $inputs.FullName $manifest @{} @{} @{} '1.0'
    } 'manifest names a missing file'
    Test-PolicyCase 'empty release tree rejects absent explicitly requested manifest' {
        $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
        Test-ReleaseInputs $inputs.FullName (Join-Path $reactorRoot 'missing-manifest.json') @{} @{} @{} '1.0'
    } 'Missing compliance inventory'
    Test-PolicyCase 'empty optional release tree remains allowed without manifest' {
        $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
        Test-ReleaseInputs $inputs.FullName '' @{} @{} @{} '1.0'
    }
    Test-PolicyCase 'hidden release input cannot evade inventory' {
        $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
        $payload = Join-Path $inputs.FullName '.hidden.dll'
        Set-Content $payload 'unapproved native'
        if ($IsWindows) { (Get-Item -Force $payload).Attributes = 'Hidden' }
        Test-ReleaseInputs $inputs.FullName '' @{} @{} @{} '1.0'
    } 'Unapproved native payload'
    foreach ($magic in @('00010000', '42534100', '42544458', '44445320', '54455334', '54455333')) {
        foreach ($nested in @($false, $true)) {
            Test-PolicyCase "renamed proprietary magic $magic nested=$nested" {
                $inputs = New-Item -ItemType Directory (Join-Path $reactorRoot 'inputs')
                $bytes = [Convert]::FromHexString($magic + '000000000000000000000000')
                if ($nested) { Write-TestZip (Join-Path $inputs.FullName 'container.zip') 'harmless.bin' $bytes }
                else { [System.IO.File]::WriteAllBytes((Join-Path $inputs.FullName 'harmless.bin'), $bytes) }
                Test-ReleaseInputs $inputs.FullName '' @{} @{} @{} '1.0'
            } 'Proprietary or local fixture material'
        }
    }
    Test-PolicyCase 'tracked non-ASCII filename is audited losslessly' {
        $name = ([char]0xe9).ToString() + '.dll'
        Set-Content (Join-Path $reactorRoot $name) 'unapproved native'
        & git -C $reactorRoot add -- $name
        Test-TrackedRepositoryBytes @{} @{}
    } 'Unapproved native payload'
    Test-PolicyCase 'tracked ZIP recursively audits payload' {
        Write-TestZip (Join-Path $reactorRoot 'container.zip') 'native.dll' ([byte[]]@(1,2,3))
        & git -C $reactorRoot add -- container.zip
        Test-TrackedRepositoryBytes @{} @{}
    } 'Unapproved native payload'
    Test-PolicyCase 'reference pin is accepted without checkout' { Test-TrackedRepositoryBytes @{} @{} }
    Test-PolicyCase 'reference revision drift is rejected' {
        & git -C $reactorRoot update-index --cacheinfo '160000,1111111111111111111111111111111111111111,TES5Edit'
        Test-TrackedRepositoryBytes @{} @{}
    } 'pinned.*gitlink|gitlink.*pin'
    Test-PolicyCase 'reference ordinary file index entry is rejected' {
        $objectId = 'ordinary reference' | & git -C $reactorRoot hash-object -w --stdin
        & git -C $reactorRoot update-index --cacheinfo "100644,$objectId,TES5Edit"
        Test-TrackedRepositoryBytes @{} @{}
    } 'pinned.*gitlink|gitlink.*pin'
    Test-PolicyCase 'reference URL drift is rejected' {
        & git config --file (Join-Path $reactorRoot '.gitmodules') submodule.TES5Edit.url 'https://example.com/replaced.git'
        Test-TrackedRepositoryBytes @{} @{}
    } 'reference.*(path|URL)|TES5Edit.*(path|URL)'
} finally {
    $script:reactorRoot = $originalRoot
    # Only this script's freshly created temp subtree is eligible for recursive cleanup.
    $resolvedOwnedRoot = [System.IO.Path]::GetFullPath($ownedRoot)
    if (-not $resolvedOwnedRoot.StartsWith([System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the owned temporary directory.'
    }
    Remove-Item -LiteralPath $resolvedOwnedRoot -Recurse -Force
}
if ($failures.Count) { throw "$($failures.Count) / $caseCount compliance regressions failed.`n$($failures -join "`n")" }
Write-Output "All $caseCount compliance regression cases passed."
