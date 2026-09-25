<#
.SYNOPSIS
Downloads and verifies one exact Temurin qualification archive before it can be extracted.

.PARAMETER Platform
Platform-specific JDK identity to provision.

.PARAMETER OutputDirectory
Existing or new directory that will receive the reviewed archive.

.PARAMETER GitHubEnvironmentFile
Optional GitHub Actions environment file that receives `JBSA_JDK_ARCHIVE` after verification.

.OUTPUTS
The absolute path of the checksum-verified archive.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('windows-x64', 'linux-x64')]
    [string] $Platform,

    [Parameter(Mandatory)]
    [string] $OutputDirectory,

    [string] $GitHubEnvironmentFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$distributions = @{
    'windows-x64' = @{
        FileName = 'OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
        Sha256 = '00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283'
    }
    'linux-x64' = @{
        FileName = 'OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz'
        Sha256 = 'dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e'
    }
}

$distribution = $distributions[$Platform]
$releaseBase = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1'
$directory = [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)).FullName
$archive = Join-Path $directory $distribution.FileName

Invoke-WebRequest -Uri "$releaseBase/$($distribution.FileName)" -OutFile $archive
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
if ($actual -cne $distribution.Sha256) {
    throw "Temurin $Platform archive checksum mismatch: expected $($distribution.Sha256), observed $actual."
}

if ($GitHubEnvironmentFile) {
    "JBSA_JDK_ARCHIVE=$archive" | Out-File -FilePath $GitHubEnvironmentFile -Encoding utf8 -Append
}

Write-Output $archive
