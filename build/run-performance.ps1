<# .SYNOPSIS Runs an explicit full or targeted local performance-v1 qualification. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('full', 'targeted')] [string] $Mode,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $ConfigurationPath,
    [string] $ImpactPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'performance/runner.py'), $Mode, '--output', $OutputDirectory)
if ($ConfigurationPath) { $arguments += @('--configuration', $ConfigurationPath) }
if ($ImpactPath) { $arguments += @('--impact', $ImpactPath) }
& python @arguments
exit $LASTEXITCODE
