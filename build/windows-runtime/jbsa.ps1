<#
.SYNOPSIS
Launches the staged Windows x64 Java 25 qualification bundle with explicit native grants.
.PARAMETER JavaHome
Java 25 installation chosen by the qualification runner or Maven embedder.
.PARAMETER ClassPath
Exercises classpath embedding instead of the default named-module launch.
.PARAMETER CliArguments
Arguments forwarded without shell re-parsing to the JBSA CLI.
.NOTES
This developer qualification launcher is an input to the later application-image gate, not a bundled JRE.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $JavaHome,
    [switch] $ClassPath,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]] $CliArguments = @()
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'This qualification bundle requires Windows x64.'
}
$java = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java executable is missing.' }
$version = & $java -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($version -join "`n") -notmatch 'version "25(?:\.|"|[-+])') { throw 'Java 25 is required.' }
$library = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'jbsa-*.jar' | Where-Object { $_.Name -notmatch '^jbsa-cli-|-(sources|javadoc)\.jar$' })
$cli = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'jbsa-cli-*.jar')
if ($library.Count -ne 1 -or $cli.Count -ne 1) { throw 'Exactly one reactor library and CLI JAR are required.' }
$policy = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'launch-policy.json') | ConvertFrom-Json
$paths = @($library[0].FullName, $cli[0].FullName)
foreach ($artifact in $policy.runtimeArtifacts) {
    $path = Join-Path $PSScriptRoot "lib/$($artifact.file)"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256) {
        throw "Pinned runtime artifact is missing or changed: $($artifact.file)"
    }
    $paths += $path
}
$launch = @('--illegal-native-access=deny', '-Dfile.encoding=UTF-8')
if ($ClassPath) {
    $launch += @('--enable-native-access=ALL-UNNAMED', '--class-path', ($paths -join ';'), $policy.mainClass)
} else {
    # Native-resource modules are not required by Java bindings and must be explicit roots.
    $launch += @('--enable-native-access=io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4', '--add-modules', 'org.lwjgl.natives,org.lwjgl.lz4.natives', '--module-path', ($paths -join ';'), '--module', ($policy.mainModule + '/' + $policy.mainClass))
}
& $java @launch @CliArguments
exit $LASTEXITCODE
