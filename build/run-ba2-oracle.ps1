<# .SYNOPSIS Runs a bounded, digest-pinned local FO4 General BA2 differential observation. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('pack', 'unpack')][string]$Operation,
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [ValidateSet('stored', 'zlib')][string]$Compression = 'stored'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$oracleArguments = @($Operation, $InputPath, $OutputPath, '-mt:no')
if ($Operation -eq 'pack') {
    $oracleArguments += @('-fo4', '-split:0', '-share:no')
    if ($Compression -eq 'zlib') { $oracleArguments += '-z:zlib' }
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
