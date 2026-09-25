<# .SYNOPSIS Runs a bounded, digest-pinned local General BA2 differential observation. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('pack', 'unpack')][string]$Operation,
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [ValidateSet('stored', 'zlib', 'raw-lz4')][string]$Compression = 'stored',
    [ValidateSet('fo4', 'sf1')][string]$Family = 'fo4'
)
$ErrorActionPreference = 'Stop'
if ($Family -eq 'fo4' -and $Compression -eq 'raw-lz4') {
    throw 'Fallout 4 General BA2 does not support raw LZ4.'
}
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$oracleArguments = @($Operation, $InputPath, $OutputPath, '-mt:no')
if ($Operation -eq 'pack') {
    $oracleArguments += @("-$Family", '-split:0', '-share:no')
    if ($Compression -eq 'zlib') { $oracleArguments += '-z:zlib' }
    if ($Compression -eq 'raw-lz4') { $oracleArguments += '-z:lz4' }
}
$result = Invoke-ConformanceOracle -RepositoryRoot $repositoryRoot -Arguments $oracleArguments `
    -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory `
    -Hosted:($env:GITHUB_ACTIONS -eq 'true')
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null
$json = $result | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'observation.json'), $json)
Write-Output $json
if ($result.result -eq 'UNAVAILABLE') { exit 3 }
if ($result.result -ne 'PASS' -or $result.exit_status -ne 0) { exit 1 }
exit 0
