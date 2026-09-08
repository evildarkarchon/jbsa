<# .SYNOPSIS Retains product-independent evidence boundaries for the proposed TES4 cases. #>
. (Join-Path $PSScriptRoot 'conformance-evidence.ps1')
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')

function Add-BsaRejectedFilesystemEvidence {
    <# .SYNOPSIS Attaches complete before/after owned-tree evidence to an observed rejection. #>
    param([Parameter(Mandatory)]$Observation, [AllowEmptyCollection()][object[]]$Before,
        [Parameter(Mandatory)][string]$WorkingDirectory)
    $Observation.filesystem_before = @($Before)
    $Observation.filesystem_after = @(Get-ConformanceFilesystem -Root $WorkingDirectory)
    return $Observation
}

function Get-BsaPackagedProfile {
    <# .SYNOPSIS Reads the selected JAR's exact profile and verifies its sidecar and source identity. #>
    param([Parameter(Mandatory)][string]$Jar, [Parameter(Mandatory)][string]$SourceProfile)
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $profiles = @($archive.Entries | Where-Object FullName -CEQ 'META-INF/jbsa-codec-profile.json')
        $sidecars = @($archive.Entries | Where-Object FullName -CEQ 'META-INF/jbsa-codec-profile.sha256')
        if ($profiles.Count -ne 1 -or $sidecars.Count -ne 1) { throw 'Selected JAR must contain one profile and one sidecar.' }
        $stream = $profiles[0].Open()
        $buffer = [IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() }
        finally { $stream.Dispose(); $buffer.Dispose() }
        $reader = [IO.StreamReader]::new($sidecars[0].Open())
        try { $sidecar = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
        $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
        $sourceDigest = (Get-FileHash -LiteralPath $SourceProfile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($digest -cne $sidecar -or $digest -cne $sourceDigest) { throw 'Selected JAR codec profile differs from its sidecar or source profile.' }
        return @{ sha256 = $digest; profile = ([Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -AsHashtable) }
    } finally { $archive.Dispose() }
}
