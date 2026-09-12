<#
.SYNOPSIS
Captures the temporary Maven oracle for the Gradle migration in an isolated worktree.

.PARAMETER OutputDirectory
New, previously nonexistent directory that will receive logs, dependency graphs, and baseline JSON.

.PARAMETER QualificationJavaHome
Exact JDK installation required by the migration parity protocol.

.PARAMETER MavenExecutable
Maven launcher to invoke. Defaults to the checked-in wrapper; an absolute Maven 3.9.16 executable
may be supplied only when recording the launcher identity in the resulting evidence.

.PARAMETER SourceRevision
Committed source revision to capture. Defaults to HEAD and must resolve to a full commit.

.NOTES
This script is migration-only and is removed at Maven cutover. The Python artifact inspector and
retained normalized parity reports are reusable after cutover.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [Parameter(Mandatory = $true)]
    [string] $QualificationJavaHome,
    [string] $MavenExecutable,
    [string] $SourceRevision = 'HEAD'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$reactorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$protocolPath = Join-Path $reactorRoot '.scratch/migrate-maven-to-gradle/baseline-protocol.json'
$inspectorPath = Join-Path $reactorRoot 'build/parity_artifact_inspector.py'
$protocol = Get-Content -Raw -LiteralPath $protocolPath | ConvertFrom-Json -Depth 100
$branch = (& git -C $reactorRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
    throw 'Maven parity capture requires a named migration branch.'
}
if ($branch -cne $protocol.migrationBranch) {
    throw "Parity capture branch '$branch' does not match the one migration branch '$($protocol.migrationBranch)'."
}
$status = @(& git -C $reactorRoot status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw 'Parity capture requires a clean worktree so the recorded revision identifies every input.'
}
$revision = (& git -C $reactorRoot rev-parse --verify "$SourceRevision^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or $revision -cnotmatch '^[0-9a-f]{40}$') {
    throw "Source revision does not resolve to one commit: $SourceRevision"
}

$candidateVersion = [string] $protocol.candidateVersion
$javaHome = [IO.Path]::GetFullPath($QualificationJavaHome)
$java = Join-Path $javaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Qualification Java executable is missing: $java"
}
$javaOutput = @(& $java -version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $javaOutput -notmatch [regex]::Escape([string] $protocol.qualificationJdk.implementorVersion)) {
    throw "Qualification JDK must be $($protocol.qualificationJdk.implementorVersion); observed: $javaOutput"
}

$mavenOverride = if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $null
} else {
    [IO.Path]::GetFullPath($MavenExecutable)
}
if ($null -ne $mavenOverride -and -not (Test-Path -LiteralPath $mavenOverride -PathType Leaf)) {
    throw "Maven executable is missing: $mavenOverride"
}
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) {
    throw "Refusing to overwrite existing Maven parity evidence: $outputRoot"
}
$tes5EditRoot = [IO.Path]::GetFullPath((Join-Path $reactorRoot 'TES5Edit'))
if ($outputRoot.StartsWith($tes5EditRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Maven parity evidence must never be written beneath the read-only Reference Snapshot.'
}

<#
.SYNOPSIS
Runs one native command, retains its complete merged stream, and returns a deterministic evidence row.

.PARAMETER Name
Stable evidence name used for the log filename.

.PARAMETER Executable
Exact executable to run.

.PARAMETER Arguments
Arguments passed without shell reparsing.

.PARAMETER WorkingDirectory
Directory in which the command observes and produces files.

.PARAMETER SuccessExitCodes
Exit statuses that represent a valid observed outcome for this command.

.PARAMETER RequireSuccess
Stops capture when the outcome is not successful. Omit this for gates whose failures are evidence.
#>
function Invoke-BaselineCommand {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Executable,
        [Parameter(Mandatory = $true)] [string[]] $Arguments,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory,
        [int[]] $SuccessExitCodes = @(0),
        [switch] $RequireSuccess
    )

    $logPath = Join-Path $script:pendingRoot "logs/$Name.log"
    Push-Location $WorkingDirectory
    try {
        $startedAt = [DateTimeOffset]::UtcNow
        $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
        $completedAt = [DateTimeOffset]::UtcNow
    }
    finally {
        Pop-Location
    }
    [IO.File]::WriteAllText($logPath, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $relativeLog = [IO.Path]::GetRelativePath($script:pendingRoot, $logPath).Replace('\', '/')
    $record = [ordered]@{
        name = $Name
        command = @($Executable) + $Arguments
        exitCode = $exitCode
        outcome = if ($exitCode -in $SuccessExitCodes) { 'PASS' } else { 'FAIL' }
        startedAt = $startedAt.ToString('O')
        completedAt = $completedAt.ToString('O')
        log = [ordered]@{
            path = $relativeLog
            sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    if ($RequireSuccess -and $record.outcome -eq 'FAIL') {
        throw "$Name failed with exit code $exitCode; retained log: $logPath"
    }
    return $record
}

$scratchRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-maven-parity-$([guid]::NewGuid().ToString('N'))"
$isolatedRoot = Join-Path $scratchRoot 'source'
$pendingRoot = "$outputRoot.pending-$([guid]::NewGuid().ToString('N'))"
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$worktreeRegistered = $false
try {
    New-Item -ItemType Directory -Path (Join-Path $pendingRoot 'logs'), (Join-Path $pendingRoot 'resolved-graphs') -Force | Out-Null
    & git -C $reactorRoot worktree add --detach $isolatedRoot $revision
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated Maven worktree.' }
    $worktreeRegistered = $true
    $maven = if ($null -eq $mavenOverride) { Join-Path $isolatedRoot 'mvnw.cmd' } else { $mavenOverride }
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $oldPath
    $mavenIdentity = Invoke-BaselineCommand -Name 'maven-version' -Executable $maven -Arguments @('-version') -WorkingDirectory $isolatedRoot -RequireSuccess
    $build = Invoke-BaselineCommand -Name 'clean-verify' -Executable $maven -Arguments @(
        '-B', '-ntp', '-C', "-Drevision=$candidateVersion", 'clean', 'verify'
    ) -WorkingDirectory $isolatedRoot

    # A failing real gate is part of the baseline. Re-materialize the complete canonical output set
    # without tests so later inventory does not accidentally describe a partially built reactor.
    $materialization = Invoke-BaselineCommand -Name 'materialize-outputs' -Executable $maven -Arguments @(
        '-B', '-ntp', '-C', "-Drevision=$candidateVersion", '-DskipTests', 'clean', 'verify'
    ) -WorkingDirectory $isolatedRoot -RequireSuccess

    $dependencyGraph = Invoke-BaselineCommand -Name 'resolved-dependencies' -Executable $maven -Arguments @(
        '-B', '-ntp', '-C', "-Drevision=$candidateVersion",
        'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree',
        '-DoutputType=json', '-DoutputFile=target/parity-resolved-dependencies.json', '-DappendOutput=false'
    ) -WorkingDirectory $isolatedRoot -RequireSuccess
    $graphRecords = @()
    foreach ($project in @('.', 'jbsa', 'jbsa-cli', 'jbsa-test-support', 'jbsa-conformance-tests', 'jbsa-benchmarks', 'jbsa-dist')) {
        $graph = Join-Path (Join-Path $isolatedRoot $project) 'target/parity-resolved-dependencies.json'
        if (-not (Test-Path -LiteralPath $graph -PathType Leaf)) {
            throw "Resolved dependency graph is missing for project: $project"
        }
        $name = if ($project -eq '.') { 'jbsa-parent.json' } else { "$project.json" }
        $destination = Join-Path $pendingRoot "resolved-graphs/$name"
        Copy-Item -LiteralPath $graph -Destination $destination
        $graphRecords += [ordered]@{
            project = if ($project -eq '.') { 'jbsa-parent' } else { $project }
            path = "resolved-graphs/$name"
            sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }

    $gateArguments = [ordered]@{
        compile = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", '-DskipTests', 'compile')
        unit = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", 'test')
        architecture = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", '-Dgroups=architecture', 'verify')
        formatting = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", 'spotless:check')
        policy = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", '-Dgroups=build-policy', 'verify')
        conformance = @('-B', '-ntp', '-C', "-Drevision=$candidateVersion", '-Dgroups=conformance-harness', 'verify')
    }
    $gates = @()
    foreach ($gate in $gateArguments.Keys) {
        $gates += Invoke-BaselineCommand -Name "gate-$gate" -Executable $maven -Arguments $gateArguments[$gate] -WorkingDirectory $isolatedRoot
    }
    $gates += Invoke-BaselineCommand -Name 'gate-conformance-observation' -Executable 'pwsh' -Arguments @(
        '-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $isolatedRoot 'build/run-conformance.ps1'),
        '-RepositoryRoot', $isolatedRoot, '-OutputDirectory', 'target/conformance'
    ) -WorkingDirectory $isolatedRoot -SuccessExitCodes @(0, 1)

    $inspectionPath = Join-Path $pendingRoot 'artifact-inspection.json'
    & python $inspectorPath inspect-build --build-root $isolatedRoot --java-home $javaHome --version $candidateVersion --output $inspectionPath
    if ($LASTEXITCODE -ne 0) { throw 'Artifact inspection failed.' }

    $stagedRoot = Join-Path $isolatedRoot 'jbsa-dist/target/release-inputs'
    $cliObservations = @(
        Invoke-BaselineCommand -Name 'cli-module-version' -Executable 'pwsh' -Arguments @(
            '-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $stagedRoot 'jbsa.ps1'),
            '-JavaHome', $javaHome, '--version'
        ) -WorkingDirectory $stagedRoot
        Invoke-BaselineCommand -Name 'cli-classpath-version' -Executable 'pwsh' -Arguments @(
            '-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $stagedRoot 'jbsa.ps1'),
            '-JavaHome', $javaHome, '-ClassPath', '--version'
        ) -WorkingDirectory $stagedRoot
    )

    $baseline = [ordered]@{
        schemaVersion = 1
        source = [ordered]@{ revision = $revision; branch = $branch; clean = $true }
        candidateVersion = $candidateVersion
        qualificationJdk = [ordered]@{
            implementorVersion = $protocol.qualificationJdk.implementorVersion
            releaseSha256 = (Get-FileHash -LiteralPath (Join-Path $javaHome 'release') -Algorithm SHA256).Hash.ToLowerInvariant()
            javaExecutableSha256 = (Get-FileHash -LiteralPath $java -Algorithm SHA256).Hash.ToLowerInvariant()
            versionOutput = $javaOutput.Replace("`r`n", "`n")
        }
        maven = [ordered]@{
            executable = $maven
            executableSha256 = (Get-FileHash -LiteralPath $maven -Algorithm SHA256).Hash.ToLowerInvariant()
            wrapperPropertiesSha256 = (Get-FileHash -LiteralPath (Join-Path $reactorRoot '.mvn/wrapper/maven-wrapper.properties') -Algorithm SHA256).Hash.ToLowerInvariant()
            identity = $mavenIdentity
        }
        isolation = [ordered]@{
            strategy = 'detached git worktree per build tool at the same revision'
            generatedOutputsComparedOnlyAfterCapture = $true
            archiveEnvelopeEqualityRequiredAcrossBuildTools = $false
        }
        build = $build
        outputMaterialization = $materialization
        dependencyGraphCommand = $dependencyGraph
        resolvedGraphs = $graphRecords
        gates = $gates
        cliObservations = $cliObservations
        artifacts = Get-Content -Raw -LiteralPath $inspectionPath | ConvertFrom-Json -Depth 100
        normativeGaps = @($protocol.normativeGaps)
    }
    $baselinePath = Join-Path $pendingRoot 'maven-baseline.json'
    [IO.File]::WriteAllText(
        $baselinePath,
        (($baseline | ConvertTo-Json -Depth 100).Replace("`r`n", "`n") + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $pendingRoot -Destination $outputRoot
    Write-Output "Captured Maven parity baseline at $outputRoot"
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
    if ($worktreeRegistered) {
        & git -C $reactorRoot worktree remove --force $isolatedRoot 2>$null
    }
    if (Test-Path -LiteralPath $scratchRoot) {
        $resolvedScratch = [IO.Path]::GetFullPath($scratchRoot)
        $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedScratch.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove scratch directory outside system temp: $resolvedScratch"
        }
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
    # A failed capture keeps its pending directory so the exception's retained-log path remains
    # truthful and a migration reviewer can diagnose the exact command output.
}
