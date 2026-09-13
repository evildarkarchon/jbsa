<#
.SYNOPSIS
Captures same-revision Gradle parity, reproducibility, offline, cache-policy, and timing evidence.

.PARAMETER OutputDirectory
New directory that will receive the complete qualification record and command logs.

.PARAMETER MavenEvidenceDirectory
Completed Maven parity capture containing maven-baseline.json for the exact source revision.

.PARAMETER QualificationJdkArchive
Pinned qualification JDK archive. Its archive and extracted identities must match the Maven capture.

.PARAMETER SourceRevision
Committed source revision to qualify. Defaults to HEAD.

.PARAMETER Resume
Resume the checkpoint set retained beside OutputDirectory after a harness-only failure.

.NOTES
Cross-tool comparison ignores only whole-archive envelope fields through the reusable artifact
inspector. Two clean Gradle builds retain the complete five-artifact byte comparison separately.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [Parameter(Mandatory = $true)]
    [string] $MavenEvidenceDirectory,
    [Parameter(Mandatory = $true)]
    [string] $QualificationJdkArchive,
    [string] $SourceRevision = 'HEAD',
    [switch] $Resume
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

<#
.SYNOPSIS
Returns the lowercase SHA-256 digest of a UTF-8 string.

.PARAMETER Value
Text whose exact UTF-8 bytes define a checkpoint identity.

.OUTPUTS
Lowercase hexadecimal SHA-256.
#>
function Get-TextSha256 {
    param([Parameter(Mandatory = $true)] [string] $Value)

    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Value)
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

<#
.SYNOPSIS
Normalizes a fresh or JSON-deserialized timestamp for checkpoint hashing.

.PARAMETER Value
ISO text or the DateTime value PowerShell creates while reading JSON.

.OUTPUTS
UTC ISO-8601 text with stable fractional precision.
#>
function Format-CheckpointTimestamp {
    param([Parameter(Mandatory = $true)] [object] $Value)

    return [DateTimeOffset]::Parse([string] $Value).ToUniversalTime().ToString('O')
}

<#
.SYNOPSIS
Returns one deterministic digest for every file beneath a directory.

.PARAMETER Path
Directory whose relative paths, sizes, and file digests are bound.

.OUTPUTS
Lowercase SHA-256 over the ordered inventory.
#>
function Get-DirectoryDigest {
    param([Parameter(Mandatory = $true)] [string] $Path)

    $root = [IO.Path]::GetFullPath($Path)
    $inventory = @(Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\', '/')
        "$relative`t$($_.Length)`t$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    } | Sort-Object)
    return Get-TextSha256 -Value ($inventory -join "`n")
}

$reactorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$protocolPath = Join-Path $reactorRoot '.scratch/migrate-maven-to-gradle/baseline-protocol.json'
$protocol = Get-Content -Raw -LiteralPath $protocolPath | ConvertFrom-Json -Depth 100
$mavenRoot = [IO.Path]::GetFullPath($MavenEvidenceDirectory)
$mavenBaselinePath = Join-Path $mavenRoot 'maven-baseline.json'
if (-not (Test-Path -LiteralPath $mavenBaselinePath -PathType Leaf)) {
    throw "Maven parity baseline is missing: $mavenBaselinePath"
}
$mavenBaseline = Get-Content -Raw -LiteralPath $mavenBaselinePath | ConvertFrom-Json -Depth 100

$branch = (& git -C $reactorRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
    throw 'Gradle qualification requires a named migration branch.'
}
if ($branch -cne [string] $protocol.migrationBranch) {
    throw "Qualification branch '$branch' does not match the migration branch '$($protocol.migrationBranch)'."
}
$status = @(& git -C $reactorRoot status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw 'Gradle qualification requires a clean source worktree so the revision identifies every input.'
}
$revision = (& git -C $reactorRoot rev-parse --verify "$SourceRevision^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or $revision -cnotmatch '^[0-9a-f]{40}$') {
    throw "Source revision does not resolve to one commit: $SourceRevision"
}
if (-not $mavenBaseline.source.clean -or [string] $mavenBaseline.source.revision -cne $revision) {
    throw 'Maven and Gradle qualification must use the same clean committed source revision.'
}
if ([string] $mavenBaseline.source.branch -cne $branch) {
    throw 'Maven and Gradle qualification must use the same migration branch.'
}
$candidateVersion = [string] $protocol.candidateVersion
if ([string] $mavenBaseline.candidateVersion -cne $candidateVersion) {
    throw 'Maven and Gradle qualification must use the same candidate version.'
}

$jdkArchive = [IO.Path]::GetFullPath($QualificationJdkArchive)
if (-not (Test-Path -LiteralPath $jdkArchive -PathType Leaf)) {
    throw "Qualification JDK archive is missing: $jdkArchive"
}
$jdkArchiveHash = (Get-FileHash -LiteralPath $jdkArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jdkArchiveHash -cne [string] $protocol.qualificationJdk.distributionSha256 -or
    $jdkArchiveHash -cne [string] $mavenBaseline.qualificationJdk.distributionSha256) {
    throw "Qualification JDK archive SHA-256 does not match the protocol and Maven evidence: $jdkArchiveHash"
}
if ([IO.Path]::GetFileName($jdkArchive) -cne [string] $mavenBaseline.qualificationJdk.distributionArchive) {
    throw 'Qualification JDK archive filename differs from the Maven evidence identity.'
}

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) {
    throw "Refusing to overwrite existing Gradle qualification evidence: $outputRoot"
}
$tes5EditRoot = [IO.Path]::GetFullPath((Join-Path $reactorRoot 'TES5Edit'))
if ($outputRoot.StartsWith($tes5EditRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Gradle qualification evidence must never be written beneath the read-only Reference Snapshot.'
}
$pendingRoot = "$outputRoot.pending"
if ($Resume) {
    if (-not (Test-Path -LiteralPath $pendingRoot -PathType Container)) {
        throw "No retained qualification checkpoint set exists for resume: $pendingRoot"
    }
} elseif (Test-Path -LiteralPath $pendingRoot) {
    throw "A retained qualification attempt already exists; use -Resume or choose a new output: $pendingRoot"
}

$bindingDocument = [ordered]@{
    schemaVersion = 1
    sourceRevision = $revision
    branch = $branch
    candidateVersion = $candidateVersion
    protocolSha256 = (Get-FileHash -LiteralPath $protocolPath -Algorithm SHA256).Hash.ToLowerInvariant()
    mavenEvidenceSha256 = Get-DirectoryDigest -Path $mavenRoot
    qualificationJdkSha256 = $jdkArchiveHash
    harnessSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    inspectorSha256 = (Get-FileHash -LiteralPath (Join-Path $reactorRoot 'build/parity_artifact_inspector.py') -Algorithm SHA256).Hash.ToLowerInvariant()
}
$bindingSha256 = Get-TextSha256 -Value ($bindingDocument | ConvertTo-Json -Compress -Depth 10)
$script:bindingSha256 = $bindingSha256
$script:resumeReusable = $Resume.IsPresent
$script:resumedCommandNames = [Collections.Generic.List[string]]::new()

<#
.SYNOPSIS
Runs one command, retains its merged stream, and returns timing and outcome evidence.

.PARAMETER Name
Stable identifier used for the retained log filename and evidence record.

.PARAMETER Executable
Exact executable path or command name to invoke without shell reparsing.

.PARAMETER Arguments
Ordered argument array passed to the executable.

.PARAMETER WorkingDirectory
Directory from which the command observes and produces files.

.PARAMETER RequireSuccess
Throws when the command exits nonzero after its complete log has been retained.

.PARAMETER NonResumable
Keeps a timing-sensitive command out of checkpoints so its complete sequence reruns after interruption.

.PARAMETER OutputPaths
Retained evidence files whose path, size, and digest must still match before a phase is reused.

.OUTPUTS
An ordered evidence record containing command, exit, duration, timestamps, and log digest.
#>
function Invoke-QualificationCommand {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Executable,
        [Parameter(Mandatory = $true)] [string[]] $Arguments,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory,
        [ValidateSet('gradle', 'powershell', 'python', 'jdk', 'process')] [string] $Category = 'process',
        [switch] $RequireSuccess,
        [switch] $NonResumable,
        [string[]] $OutputPaths = @()
    )

    $invocationDocument = [ordered]@{
        bindingSha256 = $script:bindingSha256
        name = $Name
        category = $Category
        executable = $Executable
        arguments = $Arguments
        workingDirectory = [IO.Path]::GetFullPath($WorkingDirectory)
        outputPaths = @($OutputPaths | ForEach-Object { [IO.Path]::GetFullPath($_) })
    }
    $invocationSha256 = Get-TextSha256 -Value ($invocationDocument | ConvertTo-Json -Compress -Depth 10)
    $checkpointPath = Join-Path $script:pendingRoot "checkpoints/$Name.json"
    if ($Resume -and -not $NonResumable -and (Test-Path -LiteralPath $checkpointPath -PathType Leaf)) {
        if (-not $script:resumeReusable) {
            throw "Checkpoint sequence contains completed work after an incomplete phase: $Name"
        }
        $checkpointDigestPath = "$checkpointPath.sha256"
        if (-not (Test-Path -LiteralPath $checkpointDigestPath -PathType Leaf) -or
            (Get-Content -Raw -LiteralPath $checkpointDigestPath).Trim() -cne (Get-FileHash -LiteralPath $checkpointPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw "Checkpoint file digest is invalid: $Name"
        }
        $checkpoint = Get-Content -Raw -LiteralPath $checkpointPath | ConvertFrom-Json -Depth 30
        if ([string] $checkpoint.bindingSha256 -cne $script:bindingSha256 -or
            [string] $checkpoint.invocationSha256 -cne $invocationSha256) {
            throw "Checkpoint digest binding does not match the requested qualification phase: $Name"
        }
        $record = $checkpoint.record
        $record.startedAt = Format-CheckpointTimestamp -Value $record.startedAt
        $record.completedAt = Format-CheckpointTimestamp -Value $record.completedAt
        $logPath = Join-Path $script:pendingRoot ([string] $record.log.path)
        if (-not (Test-Path -LiteralPath $logPath -PathType Leaf) -or
            [string] $record.log.sha256 -cne (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw "Checkpoint log digest is invalid: $Name"
        }
        foreach ($output in @($record.outputs)) {
            $outputPath = Join-Path $script:pendingRoot ([string] $output.path)
            if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf) -or
                (Get-Item -LiteralPath $outputPath).Length -ne [long] $output.size -or
                (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string] $output.sha256) {
                throw "Checkpoint output digest is invalid for ${Name}: $($output.path)"
            }
        }
        $record.resumed = $true
        $script:resumedCommandNames.Add($Name)
        Write-Verbose "Reusing digest-bound qualification checkpoint: $Name"
        return $record
    }
    if ($Resume -and -not $NonResumable) { $script:resumeReusable = $false }

    $logPath = Join-Path $script:pendingRoot "logs/$Name.log"
    Push-Location $WorkingDirectory
    try {
        $startedAt = [DateTimeOffset]::UtcNow
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
        $timer.Stop()
        $completedAt = [DateTimeOffset]::UtcNow
    }
    finally {
        Pop-Location
    }
    [IO.File]::WriteAllText($logPath, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $outputEvidence = @($OutputPaths | ForEach-Object {
        $resolvedOutput = [IO.Path]::GetFullPath($_)
        if (-not (Test-Path -LiteralPath $resolvedOutput -PathType Leaf)) {
            throw "Qualification phase $Name omitted retained output: $resolvedOutput"
        }
        [ordered]@{
            path = [IO.Path]::GetRelativePath($script:pendingRoot, $resolvedOutput).Replace('\', '/')
            size = (Get-Item -LiteralPath $resolvedOutput).Length
            sha256 = (Get-FileHash -LiteralPath $resolvedOutput -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
    $record = [ordered]@{
        name = $Name
        command = @($Executable) + $Arguments
        exitCode = $exitCode
        outcome = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
        milliseconds = $timer.ElapsedMilliseconds
        startedAt = $startedAt.ToString('O')
        completedAt = $completedAt.ToString('O')
        category = $Category
        resumed = $false
        reusedFrom = $null
        outputs = $outputEvidence
        log = [ordered]@{
            path = [IO.Path]::GetRelativePath($script:pendingRoot, $logPath).Replace('\', '/')
            sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    if (-not $NonResumable -and ($exitCode -eq 0 -or -not $RequireSuccess)) {
        $checkpointDocument = [ordered]@{
            schemaVersion = 1
            bindingSha256 = $script:bindingSha256
            invocationSha256 = $invocationSha256
            record = $record
        }
        $checkpointTemporary = "$checkpointPath.tmp"
        [IO.File]::WriteAllText($checkpointTemporary, (($checkpointDocument | ConvertTo-Json -Depth 30) + "`n"), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $checkpointTemporary -Destination $checkpointPath
        [IO.File]::WriteAllText("$checkpointPath.sha256", ((Get-FileHash -LiteralPath $checkpointPath -Algorithm SHA256).Hash.ToLowerInvariant() + "`n"), [Text.UTF8Encoding]::new($false))
    }
    if ($RequireSuccess -and $exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode; retained log: $logPath"
    }
    return $record
}

<#
.SYNOPSIS
Creates a named gate record from an earlier command whose task closure already ran that gate.

.PARAMETER Name
Stable gate evidence name.

.PARAMETER Source
Successful command record that executed the exact gate task closure.

.OUTPUTS
A zero-additional-wall-time command record that links back to the source evidence.
#>
function New-ReusedQualificationCommand {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [object] $Source
    )

    if ([int] $Source.exitCode -ne 0) { throw "Cannot derive gate evidence from failed command: $($Source.name)" }
    $record = $Source | ConvertTo-Json -Depth 20 | ConvertFrom-Json -Depth 20
    $record.name = $Name
    $record.command = @($Source.command)
    $record.exitCode = 0
    $record.outcome = 'PASS'
    $record.milliseconds = 0
    $record.startedAt = Format-CheckpointTimestamp -Value $record.startedAt
    $record.completedAt = Format-CheckpointTimestamp -Value $record.completedAt
    $record.reusedFrom = $Source.name
    $record | Add-Member -NotePropertyName sourceMilliseconds -NotePropertyValue $Source.milliseconds
    return $record
}

<#
.SYNOPSIS
Records every tracked Gradle lockfile by path, length, and content hash.

.PARAMETER SourceRoot
Clean detached worktree whose tracked lockfiles are inventoried.

.OUTPUTS
Sorted evidence rows for every tracked `*gradle.lockfile`.

.NOTES
Throws when Git cannot enumerate lockfiles or a tracked lockfile is absent.
#>
function Get-LockfileInventory {
    param([Parameter(Mandatory = $true)] [string] $SourceRoot)

    $paths = @(& git -C $SourceRoot ls-files '*gradle.lockfile')
    if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) {
        throw 'The qualified revision must contain tracked Gradle lockfiles.'
    }
    return @($paths | Sort-Object | ForEach-Object {
        $path = Join-Path $SourceRoot $_
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Tracked lockfile is missing: $_" }
        [ordered]@{
            path = $_.Replace('\', '/')
            size = (Get-Item -LiteralPath $path).Length
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}

<#
.SYNOPSIS
Returns true only when path, length, and hash of every lockfile are byte-consistent.

.PARAMETER Before
Lockfile inventory captured before qualification commands.

.PARAMETER After
Lockfile inventory captured after qualification commands and negative fixtures.

.OUTPUTS
Boolean equality across ordered path, size, and SHA-256 fields.
#>
function Test-InventoryEquality {
    param([object[]] $Before, [object[]] $After)

    if ($Before.Count -ne $After.Count) { return $false }
    for ($index = 0; $index -lt $Before.Count; $index++) {
        if ($Before[$index].path -cne $After[$index].path -or
            $Before[$index].size -ne $After[$index].size -or
            $Before[$index].sha256 -cne $After[$index].sha256) {
            return $false
        }
    }
    return $true
}

<#
.SYNOPSIS
Records every retained Release Qualification handoff file by path, length, and content hash.

.PARAMETER HandoffRoot
Evidence directory whose payload is inventoried. The manifest and its digest sidecar are excluded.

.OUTPUTS
Sorted evidence rows for every retained staged, compliance, and Automated Conformance file.
#>
function Get-ReleaseQualificationInventory {
    param([Parameter(Mandatory = $true)] [string] $HandoffRoot)

    $root = [IO.Path]::GetFullPath($HandoffRoot)
    return @(Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\', '/')
        if ($relative -notin @('manifest.json', 'manifest.json.sha256')) {
            [ordered]@{
                path = $relative
                size = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    } | Sort-Object -Property path)
}

<#
.SYNOPSIS
Preserves the actual staged release inputs and their qualifying generated evidence before clean tasks.

.PARAMETER SourceRoot
Detached candidate worktree containing the successfully verified Gradle outputs.

.PARAMETER PendingRoot
Qualification evidence directory that survives deletion of the detached worktree.

.PARAMETER SourceRevision
Exact committed candidate revision represented by the handoff.

.PARAMETER CandidateVersion
Validated candidate version shared by every staged artifact.

.PARAMETER Resume
Validates and reuses a previously captured digest-bound handoff after a later harness failure.

.OUTPUTS
The parsed or newly generated handoff manifest.
#>
function Save-ReleaseQualificationHandoff {
    param(
        [Parameter(Mandatory = $true)] [string] $SourceRoot,
        [Parameter(Mandatory = $true)] [string] $PendingRoot,
        [Parameter(Mandatory = $true)] [string] $SourceRevision,
        [Parameter(Mandatory = $true)] [string] $CandidateVersion,
        [switch] $Resume
    )

    $handoffRoot = Join-Path $PendingRoot 'release-qualification'
    $manifestPath = Join-Path $handoffRoot 'manifest.json'
    $manifestDigestPath = "$manifestPath.sha256"
    if ($Resume) {
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $manifestDigestPath -PathType Leaf)) {
            throw 'Retained Release Qualification handoff is missing its manifest or digest.'
        }
        $manifestDigest = (Get-Content -Raw -LiteralPath $manifestDigestPath).Trim()
        if ($manifestDigest -cne (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'Retained Release Qualification handoff manifest digest is invalid.'
        }
        $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json -Depth 20
        $actualInventory = @(Get-ReleaseQualificationInventory -HandoffRoot $handoffRoot)
        if ([string] $manifest.sourceRevision -cne $SourceRevision -or
            [string] $manifest.candidateVersion -cne $CandidateVersion -or
            -not (Test-InventoryEquality -Before @($manifest.files) -After $actualInventory)) {
            throw 'Retained Release Qualification handoff no longer matches its qualified inputs or file inventory.'
        }
        return $manifest
    }

    $sources = [ordered]@{
        'release-inputs' = Join-Path $SourceRoot 'jbsa-dist/target/release-inputs'
        'compliance' = Join-Path $SourceRoot 'target/compliance'
        'automated-conformance' = Join-Path $SourceRoot 'target/conformance'
    }
    $releaseManifest = Join-Path $SourceRoot 'jbsa-dist/target/release-inputs.json'
    foreach ($entry in $sources.GetEnumerator()) {
        if (-not (Test-Path -LiteralPath $entry.Value -PathType Container)) {
            throw "Qualification handoff source directory is missing: $($entry.Value)"
        }
    }
    if (-not (Test-Path -LiteralPath $releaseManifest -PathType Leaf)) {
        throw "Qualification handoff release manifest is missing: $releaseManifest"
    }

    New-Item -ItemType Directory -Path $handoffRoot -Force | Out-Null
    foreach ($entry in $sources.GetEnumerator()) {
        Copy-Item -LiteralPath $entry.Value -Destination (Join-Path $handoffRoot $entry.Key) -Recurse
    }
    Copy-Item -LiteralPath $releaseManifest -Destination (Join-Path $handoffRoot 'release-inputs.json')
    $inventory = @(Get-ReleaseQualificationInventory -HandoffRoot $handoffRoot)
    $manifest = [ordered]@{
        schemaVersion = 1
        status = 'prepared-not-qualified'
        manualQualificationPerformed = $false
        sourceRevision = $SourceRevision
        candidateVersion = $CandidateVersion
        files = $inventory
    }
    [IO.File]::WriteAllText($manifestPath, (($manifest | ConvertTo-Json -Depth 20) + "`n"), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText(
        $manifestDigestPath,
        ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
    return $manifest
}

<#
.SYNOPSIS
Explains one measured cross-tool representation difference against the normative Gradle contracts.

.PARAMETER Difference
One normalized leaf difference with path, expected, and actual values.

.PARAMETER AllDifferences
Complete difference set used to ensure raw module or service encodings are accepted only when their
normalized semantic contracts match.

.OUTPUTS
An ordered assessment containing category, requirement, explanation, and acceptance status.
#>
function Get-ParityDifferenceAssessment {
    param(
        [Parameter(Mandatory = $true)] [object] $Difference,
        [Parameter(Mandatory = $true)] [object[]] $AllDifferences
    )

    $path = [string] $Difference.path
    $category = $null
    $requirement = $null
    $explanation = $null
    if ($path -match '^artifacts\.[^.]+\.entries\[path=META-INF/maven(?:/|\])') {
        $category = 'maven-build-provenance'
        $requirement = 'Consumer metadata is compared through the normalized parent-free POM, not Maven-internal JAR provenance.'
        $explanation = 'Gradle omits Maven plugin-generated META-INF/maven entries; their coordinates and dependencies remain covered by the normalized consumer POM.'
    } elseif ($path -match '^artifacts\.[^.]+\.entries\[path=META-INF/MANIFEST\.MF\]\.(sha256|size)$') {
        $category = 'manifest-tool-provenance'
        $requirement = 'JPMS identity, public signatures, and benchmark launch identity remain normative.'
        $explanation = 'Maven and Gradle serialize different build-tool provenance in manifests; normalized module and benchmark contracts are compared independently.'
    } elseif ($path -match '^artifacts\.[^.]+\.entries\[path=.*package-info\.class\]$') {
        $category = 'optional-package-info-bytecode'
        $requirement = 'Java sources, Javadocs, packages, and public signatures must remain equivalent.'
        $explanation = 'Maven emits class files for documentation-only package-info.java sources while direct javac compilation does not; the source and documented package remain present.'
    } elseif ($path -match '^artifacts\.library_javadocs\.entries\[' -and
        @($AllDifferences | Where-Object { $_.path -match '^artifacts\.library\.java_contract\.' }).Count -eq 0 -and
        ($path -match '\.(sha256|size)$' -or
            ($null -eq $Difference.expected -and $null -ne $Difference.actual) -or
            ($null -ne $Difference.expected -and $null -eq $Difference.actual -and
                $path -match '(?:/class-use/|/package-use\.html\]|\[path=src/)'))) {
        $category = 'javadoc-task-presentation'
        $requirement = 'The Javadocs artifact must document the unchanged public API, use the pinned JDK, and reproduce byte-for-byte across clean Gradle builds.'
        $explanation = 'The plugins differ in generated class-use/package-use/source navigation and Gradle-added internal cross-reference or font presentation; missing primary type, package-summary, module-summary, or index documentation is never accepted.'
    } elseif ($path -match '^artifacts\.(library|thin_cli)\.entries\[path=module-info\.class\]\.(sha256|size)$' -and
        @($AllDifferences | Where-Object { $_.path -match '^artifacts\.(library|thin_cli)\.java_contract\.' }).Count -eq 0) {
        $category = 'module-attribute-encoding'
        $requirement = 'JPMS descriptor, complete package set, and public signatures must remain equivalent.'
        $explanation = 'The module-info class encodes tool-specific optional attributes differently; normalized runtime module identity and package membership match.'
    } elseif ($path -match '^artifacts\.benchmark_standalone\.entries\[path=META-INF/services/.+\]\.(sha256|size)$' -and
        @($AllDifferences | Where-Object { $_.path -match '^artifacts\.benchmark_standalone\.benchmark_contract\.' }).Count -eq 0) {
        $category = 'service-file-serialization'
        $requirement = 'The benchmark service-provider set and launcher contract must remain equivalent.'
        $explanation = 'The service descriptor differs only in line serialization; the normalized provider set matches.'
    } elseif ($path -match '^staged_files\.files\[path=(jbsa(?:-cli)?-.+\.jar|jbsa-.+-sources\.jar|jbsa-.+-javadoc\.jar|jbsa-.+\.pom|jbsa\.cdx\.json)\]\.(sha256|size)$' -or
        $path -match '^staging_manifest\.entries\[path=(jbsa(?:-cli)?-.+\.jar|jbsa-.+-sources\.jar|jbsa-.+-javadoc\.jar|jbsa-.+\.pom|jbsa\.cdx\.json)\]\.sha256$') {
        $category = 'derived-staging-byte'
        $requirement = 'The staged set, names, sources, normalized publication/SBOM metadata, and artifact payload contracts must remain equivalent.'
        $explanation = 'The staging hash faithfully records a cross-tool representation difference already assessed at its canonical artifact or metadata seam.'
    }

    return [ordered]@{
        path = $path
        category = if ($null -eq $category) { 'unexplained' } else { $category }
        requirement = $requirement
        explanation = $explanation
        accepted = $null -ne $category
        expected = $Difference.expected
        actual = $Difference.actual
    }
}

<#
.SYNOPSIS
Returns the external module versions recorded recursively by one Maven dependency-tree document.

.PARAMETER Node
Root Maven dependency-tree node produced by the captured dependency-plugin JSON report.

.OUTPUTS
Sorted unique `groupId:artifactId:version` strings, excluding JBSA reactor projects.
#>
function Get-MavenDependencyCoordinates {
    param([Parameter(Mandatory = $true)] [object] $Node)

    $coordinates = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    <# Recursively adds external coordinates while accepting Maven leaf nodes without `children`. #>
    function Add-Node {
        param([Parameter(Mandatory = $true)] [object] $Current)
        if ([string] $Current.groupId -cne 'io.github.evildarkarchon') {
            $null = $coordinates.Add("$($Current.groupId):$($Current.artifactId):$($Current.version)")
        }
        if ($null -ne $Current.PSObject.Properties['children']) {
            foreach ($child in @($Current.children)) { Add-Node -Current $child }
        }
    }
    Add-Node -Current $Node
    return @($coordinates | Sort-Object)
}

<#
.SYNOPSIS
Returns the external module versions retained by one Gradle project lockfile.

.PARAMETER Path
Tracked Gradle lockfile to normalize.

.OUTPUTS
Sorted unique `group:artifact:version` strings; comments and the synthetic `empty` row are omitted.
#>
function Get-GradleLockCoordinates {
    param([Parameter(Mandatory = $true)] [string] $Path)

    return @(Get-Content -LiteralPath $Path | ForEach-Object {
        $candidate = ($_ -split '=', 2)[0]
        if ($candidate -match '^[^:#=]+:[^:#=]+:[^:#=]+$') { $candidate }
    } | Sort-Object -Unique)
}

$statePath = Join-Path $pendingRoot 'qualification-state.json'
if ($Resume) {
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw "Resume state is missing: $statePath" }
    $resumeState = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json -Depth 20
    if ([string] $resumeState.bindingSha256 -cne $bindingSha256) {
        throw 'Resume input digest does not match the retained qualification binding.'
    }
    $scratchRoot = [IO.Path]::GetFullPath([string] $resumeState.scratchRoot)
    $temporaryPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $scratchRoot.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Resume scratch directory is outside system temp: $scratchRoot"
    }
} else {
    $scratchRoot = Join-Path ([IO.Path]::GetTempPath()) "jgq-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
}
# Short child names keep the repository's deepest evidence fixtures beneath Windows MAX_PATH.
$isolatedRoot = Join-Path $scratchRoot 's'
$jdkExtractRoot = Join-Path $scratchRoot 'j'
$gradleUserHome = Join-Path $scratchRoot 'g'
$oldJavaHome = $env:JAVA_HOME
$oldGradleUserHome = $env:GRADLE_USER_HOME
$oldPath = $env:PATH
$worktreeRegistered = $false
$gradleWrapper = $null
$qualificationSucceeded = $false
try {
    if ($Resume) {
        foreach ($requiredDirectory in @($isolatedRoot, $jdkExtractRoot, $gradleUserHome, (Join-Path $pendingRoot 'checkpoints'))) {
            if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
                throw "Resume runtime directory is missing: $requiredDirectory"
            }
        }
    } else {
        New-Item -ItemType Directory -Path (Join-Path $pendingRoot 'logs'), (Join-Path $pendingRoot 'snapshots'), (Join-Path $pendingRoot 'checkpoints'), $jdkExtractRoot, $gradleUserHome -Force | Out-Null
        $resumeState = [ordered]@{
            schemaVersion = 1
            bindingSha256 = $bindingSha256
            binding = $bindingDocument
            scratchRoot = $scratchRoot
            extractedJdkSha256 = $null
        }
        [IO.File]::WriteAllText($statePath, (($resumeState | ConvertTo-Json -Depth 20) + "`n"), [Text.UTF8Encoding]::new($false))
        Expand-Archive -LiteralPath $jdkArchive -DestinationPath $jdkExtractRoot
    }
    $jdkReleases = @(Get-ChildItem -LiteralPath $jdkExtractRoot -Recurse -Filter 'release' -File | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.Directory.FullName 'bin/java.exe') -PathType Leaf
    })
    if ($jdkReleases.Count -ne 1) {
        throw "Qualification archive must contain exactly one JDK root; found $($jdkReleases.Count)."
    }
    $javaHome = $jdkReleases[0].Directory.FullName
    $javaExecutable = Join-Path $javaHome 'bin/java.exe'
    $releaseText = Get-Content -Raw -LiteralPath $jdkReleases[0].FullName
    if ($releaseText -notmatch [regex]::Escape([string] $protocol.qualificationJdk.implementorVersion)) {
        throw "Extracted JDK release identity is not $($protocol.qualificationJdk.implementorVersion)."
    }
    $releaseHash = (Get-FileHash -LiteralPath $jdkReleases[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $javaHash = (Get-FileHash -LiteralPath $javaExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($releaseHash -cne [string] $mavenBaseline.qualificationJdk.releaseSha256 -or
        $javaHash -cne [string] $mavenBaseline.qualificationJdk.javaExecutableSha256) {
        throw 'Extracted qualification JDK identity differs from the Maven evidence.'
    }
    $extractedJdkSha256 = Get-DirectoryDigest -Path $javaHome
    if ($Resume) {
        if ([string] $resumeState.extractedJdkSha256 -cne $extractedJdkSha256) {
            throw 'Resume JDK extraction digest no longer matches the retained exact-JDK identity.'
        }
    } else {
        $resumeState.extractedJdkSha256 = $extractedJdkSha256
        [IO.File]::WriteAllText($statePath, (($resumeState | ConvertTo-Json -Depth 20) + "`n"), [Text.UTF8Encoding]::new($false))
    }

    if ($Resume) {
        $isolatedRevision = (& git -C $isolatedRoot rev-parse --verify 'HEAD^{commit}').Trim()
        $isolatedStatus = @(& git -C $isolatedRoot status --porcelain --untracked-files=no)
        if ($LASTEXITCODE -ne 0 -or $isolatedRevision -cne $revision -or $isolatedStatus.Count -ne 0) {
            throw 'Resume worktree no longer matches the clean qualified revision.'
        }
        $worktreeRegistered = $true
    } else {
        & git -C $reactorRoot worktree add --detach $isolatedRoot $revision
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated Gradle worktree.' }
        $worktreeRegistered = $true
    }
    $env:JAVA_HOME = $javaHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $oldPath
    $gradleWrapper = Join-Path $isolatedRoot 'gradlew.bat'
    $inspector = Join-Path $isolatedRoot 'build/parity_artifact_inspector.py'
    $commonArguments = @('--no-daemon', '--dependency-verification', 'strict', "-Pversion=$candidateVersion")
    $commands = [Collections.Generic.List[object]]::new()

    $gradleIdentity = Invoke-QualificationCommand -Name 'gradle-version' -Executable $gradleWrapper -Arguments @('--no-daemon', '--version') -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($gradleIdentity)
    $inspectorTests = Invoke-QualificationCommand -Name 'artifact-inspector-tests' -Executable 'python' -Arguments @((Join-Path $isolatedRoot 'build/test_parity_artifact_inspector.py')) -WorkingDirectory $isolatedRoot -Category python -RequireSuccess
    $commands.Add($inspectorTests)
    $lockfilesBefore = @(Get-LockfileInventory -SourceRoot $isolatedRoot)
    $allowedGradleOnlyBuildInputs = @(
        # The typed requirement-registry parser is Gradle-only policy-test infrastructure; the
        # Maven oracle intentionally has no authority over its reviewed direct/transitive inputs.
        'com.fasterxml.jackson:jackson-bom:2.22.1',
        'com.fasterxml.jackson.core:jackson-annotations:2.22',
        'com.fasterxml.jackson.core:jackson-core:2.22.1',
        'com.fasterxml.jackson.core:jackson-databind:2.22.1',
        'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1',
        'org.junit.platform:junit-platform-launcher:6.1.3',
        'org.junit:junit-bom:6.1.3',
        'org.openjdk.jmh:jmh-generator-annprocess:1.37',
        'org.yaml:snakeyaml:2.5'
    )
    $dependencyComparisons = @($mavenBaseline.resolvedGraphs | ForEach-Object {
        $projectName = [string] $_.project
        $mavenGraphPath = Join-Path $mavenRoot ([string] $_.path)
        if (-not (Test-Path -LiteralPath $mavenGraphPath -PathType Leaf)) {
            throw "Maven dependency graph is missing: $mavenGraphPath"
        }
        $lockPath = if ($projectName -eq 'jbsa-parent') {
            Join-Path $isolatedRoot 'gradle.lockfile'
        } else {
            Join-Path $isolatedRoot "$projectName/gradle.lockfile"
        }
        $mavenCoordinates = @(Get-MavenDependencyCoordinates -Node (Get-Content -Raw -LiteralPath $mavenGraphPath | ConvertFrom-Json -Depth 100))
        $gradleCoordinates = @(Get-GradleLockCoordinates -Path $lockPath)
        $missing = @($mavenCoordinates | Where-Object { $_ -notin $gradleCoordinates })
        $gradleOnly = @($gradleCoordinates | Where-Object { $_ -notin $mavenCoordinates })
        $unexpected = @($gradleOnly | Where-Object { $_ -notin $allowedGradleOnlyBuildInputs })
        [ordered]@{
            project = $projectName
            mavenCoordinates = $mavenCoordinates
            gradleCoordinates = $gradleCoordinates
            missingFromGradle = $missing
            gradleOnlyBuildInputs = $gradleOnly
            unexpectedGradleInputs = $unexpected
            matches = $missing.Count -eq 0 -and $unexpected.Count -eq 0
            explanation = 'Gradle-only entries are explicit build-only BOM, launcher, or annotation-processor inputs; runtime classifier bytes are compared separately.'
        }
    })
    if (@($dependencyComparisons | Where-Object { -not $_.matches }).Count -ne 0) {
        throw 'Maven dependency graphs and Gradle lockfiles contain unexplained version differences.'
    }

    # These are the sole network-permitted dependency-resolution passes and prime both builds.
    $prime = Invoke-QualificationCommand -Name 'prime-verified-cache' -Executable $gradleWrapper -Arguments ($commonArguments + @('clean', 'verify', 'spotlessCheck')) -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($prime)
    $primeBuildLogic = Invoke-QualificationCommand -Name 'prime-build-logic-cache' -Executable $gradleWrapper -Arguments ($commonArguments + @('-p', 'build-logic', 'test')) -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($primeBuildLogic)
    # Run the policy/architecture selections in the offline closure so later named gate probes are up-to-date.
    $offlineTasks = @('clean', 'verify', 'spotlessCheck', ':jbsa-conformance-tests:architectureTest', ':jbsa-conformance-tests:buildPolicyTest')
    $offline = Invoke-QualificationCommand -Name 'verified-cache-offline-build' -Executable $gradleWrapper -Arguments ($commonArguments + @('--offline') + $offlineTasks) -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($offline)
    $offlineBuildLogic = Invoke-QualificationCommand -Name 'verified-cache-offline-build-logic' -Executable $gradleWrapper -Arguments ($commonArguments + @('--offline', '-p', 'build-logic', 'test')) -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($offlineBuildLogic)
    $conformanceExitSource = Join-Path $isolatedRoot 'target/conformance-exit-code.txt'
    $conformanceExitSnapshot = Join-Path $pendingRoot 'snapshots/offline-conformance-exit-code.txt'
    $conformanceExitDigestPath = "$conformanceExitSnapshot.sha256"
    if ($offline.resumed) {
        if (-not (Test-Path -LiteralPath $conformanceExitSnapshot -PathType Leaf) -or
            -not (Test-Path -LiteralPath $conformanceExitDigestPath -PathType Leaf) -or
            (Get-Content -Raw -LiteralPath $conformanceExitDigestPath).Trim() -cne (Get-FileHash -LiteralPath $conformanceExitSnapshot -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'The retained offline conformance outcome digest is invalid.'
        }
    } else {
        if (-not (Test-Path -LiteralPath $conformanceExitSource -PathType Leaf)) {
            throw 'The strict-offline full suite omitted its conformance outcome.'
        }
        Copy-Item -LiteralPath $conformanceExitSource -Destination $conformanceExitSnapshot
        [IO.File]::WriteAllText($conformanceExitDigestPath, ((Get-FileHash -LiteralPath $conformanceExitSnapshot -Algorithm SHA256).Hash.ToLowerInvariant() + "`n"), [Text.UTF8Encoding]::new($false))
    }

    $gradleSnapshotPath = Join-Path $pendingRoot 'snapshots/gradle-artifacts.json'
    $inspectorProfilePath = Join-Path $pendingRoot 'snapshots/artifact-inspector-profile.json'
    $inspection = Invoke-QualificationCommand -Name 'inspect-gradle-artifacts' -Executable 'python' -Arguments @(
        $inspector, 'inspect-build', '--build-root', $isolatedRoot, '--java-home', $javaHome,
        '--version', $candidateVersion, '--build-tool', 'gradle', '--output', $gradleSnapshotPath,
        '--profile-output', $inspectorProfilePath
    ) -WorkingDirectory $isolatedRoot -Category python -RequireSuccess -OutputPaths @($gradleSnapshotPath, $inspectorProfilePath)
    $commands.Add($inspection)
    $mavenSnapshotPath = Join-Path $pendingRoot 'snapshots/maven-artifacts.json'
    [IO.File]::WriteAllText($mavenSnapshotPath, (($mavenBaseline.artifacts | ConvertTo-Json -Depth 100) + "`n"), [Text.UTF8Encoding]::new($false))
    $parityReportPath = Join-Path $pendingRoot 'parity-report.json'
    $parityCommand = Invoke-QualificationCommand -Name 'compare-normalized-artifacts' -Executable 'python' -Arguments @(
        $inspector, 'compare', '--expected', $mavenSnapshotPath, '--actual', $gradleSnapshotPath, '--output', $parityReportPath
    ) -WorkingDirectory $isolatedRoot -Category python -OutputPaths @($parityReportPath)
    $commands.Add($parityCommand)
    $parityReport = Get-Content -Raw -LiteralPath $parityReportPath | ConvertFrom-Json -Depth 100
    $parityDifferences = @($parityReport.differences)
    $parityAssessments = @($parityDifferences | ForEach-Object {
        Get-ParityDifferenceAssessment -Difference $_ -AllDifferences $parityDifferences
    })
    $unexplainedParity = @($parityAssessments | Where-Object { -not $_.accepted })
    if ($unexplainedParity.Count -ne 0) {
        $unexplainedPaths = @($unexplainedParity | Select-Object -First 10 | ForEach-Object { $_.path }) -join ', '
        throw "Maven-to-Gradle parity has $($unexplainedParity.Count) unexplained normative differences: $unexplainedPaths"
    }

    $reproducibleArtifacts = @(
        'jbsa/target/publications/library/pom-default.xml',
        "jbsa/target/libs/jbsa-$candidateVersion.jar",
        "jbsa/target/libs/jbsa-$candidateVersion-sources.jar",
        "jbsa/target/libs/jbsa-$candidateVersion-javadoc.jar",
        "jbsa-cli/target/libs/jbsa-cli-$candidateVersion.jar"
    )

    # Deliberately corrupt one checksum after parity capture, then restore the exact source bytes.
    $verificationMetadata = Join-Path $isolatedRoot 'gradle/verification-metadata.xml'
    $verificationBytes = [IO.File]::ReadAllBytes($verificationMetadata)
    $verificationText = [Text.Encoding]::UTF8.GetString($verificationBytes)
    $checksumPattern = '(<sha256\s+value=")[0-9a-fA-F]{64}(")'
    if (-not [regex]::IsMatch($verificationText, $checksumPattern)) {
        throw 'Dependency-verification metadata contains no SHA-256 value for the negative proof.'
    }
    $tamperedText = [regex]::Replace(
        $verificationText,
        $checksumPattern,
        { param($match) $match.Groups[1].Value + ('0' * 64) + $match.Groups[2].Value },
        1
    )
    try {
        [IO.File]::WriteAllText($verificationMetadata, $tamperedText, [Text.UTF8Encoding]::new($false))
        $strictFailure = Invoke-QualificationCommand -Name 'strict-verification-negative' -Executable $gradleWrapper -Arguments ($commonArguments + @('--offline', ':jbsa:dependencies', '--configuration', 'runtimeClasspath')) -WorkingDirectory $isolatedRoot -Category gradle
        $commands.Add($strictFailure)
    }
    finally {
        [IO.File]::WriteAllBytes($verificationMetadata, $verificationBytes)
    }
    $strictFailureText = Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $strictFailure.log.path)
    if ($strictFailure.exitCode -eq 0 -or $strictFailureText -notmatch '(?is)(verification.*(failed|checksum)|checksum.*(failed|mismatch))') {
        throw 'The deliberately invalid checksum did not produce an explicit strict-verification failure.'
    }

    $compatibleTasks = @(':jbsa:compileJava', ':jbsa-cli:compileJava', ':jbsa:test', ':jbsa-cli:test', 'spotlessCheck')
    $configurationArguments = $commonArguments + @('--offline', '--configuration-cache', '--configuration-cache-problems=fail') + $compatibleTasks
    $configurationFirst = Invoke-QualificationCommand -Name 'configuration-cache-first' -Executable $gradleWrapper -Arguments $configurationArguments -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($configurationFirst)
    $configurationSecond = Invoke-QualificationCommand -Name 'configuration-cache-second' -Executable $gradleWrapper -Arguments $configurationArguments -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess
    $commands.Add($configurationSecond)
    $configurationFirstText = Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $configurationFirst.log.path)
    $configurationSecondText = Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $configurationSecond.log.path)
    if ($configurationFirstText -notmatch '(?i)configuration cache entry stored' -or
        $configurationSecondText -notmatch '(?i)configuration cache entry reused') {
        throw 'Configuration-cache qualification did not prove one stored and one reused task graph.'
    }

    $incompatibleTasks = @(
        'verify', 'automatedConformance', 'captureAutomatedConformance', 'generateResolvedProductionDependencies',
        'smokeTestBenchmarkLauncher', 'smokeTestThinCli', 'stageReleaseInputs', 'verifyCompliance', 'verifyStagedReleaseInputs'
    )
    $configurationRejected = Invoke-QualificationCommand -Name 'configuration-cache-incompatible' -Executable $gradleWrapper -Arguments ($commonArguments + @('--offline', '--configuration-cache') + $incompatibleTasks) -WorkingDirectory $isolatedRoot -Category gradle
    $commands.Add($configurationRejected)
    $configurationRejectedText = Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $configurationRejected.log.path)
    if ($configurationRejected.exitCode -eq 0 -or
        $configurationRejectedText -notmatch 'Configuration cache is not supported for evidence, staging, or external-process tasks') {
        throw 'Configuration-cache mode did not explicitly reject incompatible evidence, staging, and process tasks.'
    }

    $gateTasks = [ordered]@{
        'gate-compile' = @(':jbsa:classes', ':jbsa-cli:classes', ':jbsa-test-support:classes', ':jbsa-conformance-tests:classes', ':jbsa-benchmarks:classes')
        'gate-unit' = @(':jbsa:test', ':jbsa-cli:test', ':jbsa-test-support:test', ':jbsa-conformance-tests:test', ':jbsa-benchmarks:test')
        'gate-architecture' = @(':jbsa-conformance-tests:architectureTest')
        'gate-formatting' = @('spotlessCheck')
        'gate-policy' = @(':jbsa-conformance-tests:buildPolicyTest')
        'gate-conformance' = @(':jbsa-conformance-tests:conformanceHarnessTest')
        'gate-conformance-observation' = @(':jbsa-conformance-tests:automatedConformance')
    }
    $gateEvidence = @()
    foreach ($mavenGate in @($mavenBaseline.gates)) {
        $gateName = [string] $mavenGate.name
        if (-not $gateTasks.Contains($gateName)) { throw "Unsupported Maven baseline gate seam: $gateName" }
        $gradleGate = New-ReusedQualificationCommand -Name $gateName -Source $offline
        $commands.Add($gradleGate)
        $gradleOutcome = $gradleGate.outcome
        if ($gateName -eq 'gate-conformance-observation' -and $gradleGate.exitCode -eq 0) {
            $gradleOutcome = if ((Get-Content -Raw -LiteralPath $conformanceExitSnapshot).Trim() -eq '1') { 'BLOCKED' } else { 'PASS' }
        }
        $gateEvidence += [ordered]@{
            name = $gateName
            mavenOutcome = [string] $mavenGate.outcome
            gradleOutcome = $gradleOutcome
            matchesMaven = [string] $mavenGate.outcome -ceq $gradleOutcome
            acceptedDifference = ([string] $mavenGate.outcome -eq 'FAIL' -and $gradleOutcome -eq 'PASS')
            explanation = if ([string] $mavenGate.outcome -eq 'FAIL' -and $gradleOutcome -eq 'PASS') {
                'The Maven observation failed, while the normative Gradle gate passed; Maven failures are not promoted to expected replacement-build behavior.'
            } else { $null }
            command = $gradleGate
        }
    }
    if (@($gateEvidence | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0) {
        throw 'Gradle gate outcomes contain an unexplained difference from the same-revision Maven baseline.'
    }

    $stagedRoot = Join-Path $isolatedRoot 'jbsa-dist/target/release-inputs'
    $stagedDigestPath = Join-Path $pendingRoot 'snapshots/staged-inputs.sha256'
    $incompleteCliCheckpoints = @($mavenBaseline.cliObservations | Where-Object {
        $cliCheckpointName = [string] $_.name
        -not (Test-Path -LiteralPath (Join-Path $pendingRoot "checkpoints/$cliCheckpointName.json") -PathType Leaf)
    })
    if (-not $Resume -or $incompleteCliCheckpoints.Count -ne 0) {
        if (-not (Test-Path -LiteralPath $stagedRoot -PathType Container)) {
            throw 'The strict-offline full suite omitted staged CLI inputs.'
        }
        $stagedDigest = Get-DirectoryDigest -Path $stagedRoot
        if ($Resume) {
            if (-not (Test-Path -LiteralPath $stagedDigestPath -PathType Leaf) -or
                (Get-Content -Raw -LiteralPath $stagedDigestPath).Trim() -cne $stagedDigest) {
                $incompleteNames = @($incompleteCliCheckpoints | ForEach-Object { [string] $_.name }) -join ', '
                throw "The retained staged CLI input digest is invalid for incomplete observations: $incompleteNames"
            }
        } else {
            [IO.File]::WriteAllText($stagedDigestPath, ($stagedDigest + "`n"), [Text.UTF8Encoding]::new($false))
        }
    }
    $cliDefinitions = [ordered]@{
        'cli-module-version' = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $stagedRoot 'jbsa.ps1'), '-JavaHome', $javaHome, '--version')
        'cli-classpath-version' = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $stagedRoot 'jbsa.ps1'), '-JavaHome', $javaHome, '-ClassPath', '--version')
    }
    $cliEvidence = @()
    foreach ($mavenCli in @($mavenBaseline.cliObservations)) {
        $cliName = [string] $mavenCli.name
        if (-not $cliDefinitions.Contains($cliName)) { throw "Unsupported Maven CLI seam: $cliName" }
        $gradleCli = Invoke-QualificationCommand -Name $cliName -Executable 'pwsh' -Arguments $cliDefinitions[$cliName] -WorkingDirectory $stagedRoot -Category powershell
        $commands.Add($gradleCli)
        $mavenOutput = (Get-Content -Raw -LiteralPath (Join-Path $mavenRoot $mavenCli.log.path)).Replace("`r`n", "`n").Trim()
        $gradleOutput = (Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $gradleCli.log.path)).Replace("`r`n", "`n").Trim()
        $cliEvidence += [ordered]@{
            name = $cliName
            mavenOutcome = [string] $mavenCli.outcome
            gradleOutcome = $gradleCli.outcome
            outputMatches = $mavenOutput -ceq $gradleOutput
            matchesMaven = ([string] $mavenCli.outcome -ceq $gradleCli.outcome) -and ($mavenOutput -ceq $gradleOutput)
            acceptedDifference = $cliName -eq 'cli-classpath-version' -and
                [string] $mavenCli.outcome -eq 'FAIL' -and $gradleCli.outcome -eq 'FAIL'
            explanation = if ($cliName -eq 'cli-classpath-version' -and
                [string] $mavenCli.outcome -eq 'FAIL' -and $gradleCli.outcome -eq 'FAIL') {
                'The baseline records class-path launch failure as a non-normative observation; absolute process diagnostics may differ while the unchanged failure outcome remains visible.'
            } else { $null }
            command = $gradleCli
        }
    }
    if (@($cliEvidence | Where-Object { -not $_.matchesMaven -and -not $_.acceptedDifference }).Count -ne 0) {
        throw 'Gradle CLI observations contain an unexplained difference from the same-revision Maven baseline.'
    }

    # Later reproducibility and timing phases run clean tasks, so preserve the exact manual handoff
    # only after the complete offline verify closure and CLI observations have accepted these bytes.
    $releaseQualificationManifest = Save-ReleaseQualificationHandoff -SourceRoot $isolatedRoot `
        -PendingRoot $pendingRoot -SourceRevision $revision -CandidateVersion $candidateVersion -Resume:$Resume

    # Reproducibility remains a distinct two-clean-build proof, but it runs after gates and CLI so its
    # cleanup cannot force those already-qualified suites to execute again.
    $reproducibility = Invoke-QualificationCommand -Name 'gradle-reproducibility' -Executable 'pwsh' -Arguments @(
        '-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Join-Path $isolatedRoot 'build/verify-reproducible-build.ps1'),
        '-ReactorVersion', $candidateVersion
    ) -WorkingDirectory $isolatedRoot -Category powershell -RequireSuccess
    $commands.Add($reproducibility)
    $reproducibilityText = Get-Content -Raw -LiteralPath (Join-Path $pendingRoot $reproducibility.log.path)
    foreach ($artifact in $reproducibleArtifacts) {
        if ($reproducibilityText -notmatch [regex]::Escape($artifact)) {
            throw "Reproducibility evidence omitted canonical artifact: $artifact"
        }
    }

    # The measured passes use identical arguments; only the first begins after an explicit clean.
    $timingClean = Invoke-QualificationCommand -Name 'timing-clean' -Executable $gradleWrapper -Arguments ($commonArguments + @('--offline', 'clean')) -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess -NonResumable
    $commands.Add($timingClean)
    $timingTasks = @(
        ':jbsa:assembleLibraryPublication',
        ':jbsa-cli:jar',
        ':jbsa-benchmarks:shadowJar',
        'spotlessCheck'
    )
    $timingArguments = $commonArguments + @('--offline', '--rerun-tasks') + $timingTasks
    $coldTiming = Invoke-QualificationCommand -Name 'timing-cold' -Executable $gradleWrapper -Arguments $timingArguments -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess -NonResumable
    $commands.Add($coldTiming)
    $warmTiming = Invoke-QualificationCommand -Name 'timing-warm' -Executable $gradleWrapper -Arguments $timingArguments -WorkingDirectory $isolatedRoot -Category gradle -RequireSuccess -NonResumable
    $commands.Add($warmTiming)

    $lockfilesAfter = @(Get-LockfileInventory -SourceRoot $isolatedRoot)
    $lockfilesMatch = Test-InventoryEquality -Before $lockfilesBefore -After $lockfilesAfter
    if (-not $lockfilesMatch) { throw 'A qualification command changed tracked Gradle lockfile bytes.' }
    $finalStatus = @(& git -C $isolatedRoot status --porcelain --untracked-files=no)
    if ($LASTEXITCODE -ne 0 -or $finalStatus.Count -ne 0) {
        throw 'Qualification did not restore tracked source bytes after negative testing.'
    }

    if (-not (Test-Path -LiteralPath $inspectorProfilePath -PathType Leaf)) {
        throw 'Artifact inspection omitted its nested JDK-tool profile.'
    }
    $inspectorProfile = Get-Content -Raw -LiteralPath $inspectorProfilePath | ConvertFrom-Json -Depth 20
    $processProfiles = @($commands | Group-Object -Property category | Sort-Object -Property Name | ForEach-Object {
        [ordered]@{
            category = $_.Name
            commandCount = $_.Count
            milliseconds = [long] ((@($_.Group | ForEach-Object { [long] $_.milliseconds }) | Measure-Object -Sum).Sum)
        }
    })
    $mavenBuildMilliseconds = [long] ([DateTimeOffset]::Parse([string] $mavenBaseline.build.completedAt) -
        [DateTimeOffset]::Parse([string] $mavenBaseline.build.startedAt)).TotalMilliseconds

    $report = [ordered]@{
        schemaVersion = 1
        source = [ordered]@{ revision = $revision; branch = $branch; clean = $true; sameRevisionAsMaven = $true }
        candidateVersion = $candidateVersion
        qualificationJdk = [ordered]@{
            implementorVersion = [string] $protocol.qualificationJdk.implementorVersion
            distributionArchive = [IO.Path]::GetFileName($jdkArchive)
            distributionSha256 = $jdkArchiveHash
            extractedSha256 = $extractedJdkSha256
            releaseSha256 = $releaseHash
            javaExecutableSha256 = $javaHash
            matchesMaven = $true
        }
        isolation = [ordered]@{
            strategy = 'detached git worktree with fresh Gradle user home at the Maven revision'
            gradleUserHomeWasInitiallyEmpty = $true
            networkPermittedOnlyForBootstrapAndPrime = $true
        }
        resume = [ordered]@{
            resumed = $Resume.IsPresent
            bindingSha256 = $bindingSha256
            reusedCommands = @($script:resumedCommandNames)
            checkpointDirectory = 'checkpoints'
        }
        profiling = [ordered]@{
            methodology = 'Outer processes use monotonic wall time; the Python inspector separately times every qualification-JDK tool launch.'
            processCategories = $processProfiles
            mavenBuild = [ordered]@{ milliseconds = $mavenBuildMilliseconds; source = 'same-revision Maven baseline timestamps' }
            gradlePrimeBuild = $prime
            gradleOfflineFullSuite = $offline
            conformanceTask = @($gateEvidence | Where-Object { $_.name -eq 'gate-conformance' })[0].command
            powershellProcesses = @($commands | Where-Object { $_.category -eq 'powershell' })
            pythonInspector = $inspection
            artifactInspector = $inspectorProfile
            duplicateGateExecutionsBefore = $gateTasks.Count
            duplicateGateExecutionsAfter = 0
        }
        gradle = [ordered]@{
            wrapperSha256 = (Get-FileHash -LiteralPath $gradleWrapper -Algorithm SHA256).Hash.ToLowerInvariant()
            identity = $gradleIdentity
        }
        artifactInspectorTests = $inspectorTests
        parity = [ordered]@{
            matches = $unexplainedParity.Count -eq 0
            rawSnapshotMatches = [bool] $parityReport.matches
            archiveEnvelopeDifferencesIgnored = $true
            rawDifferenceCount = $parityDifferences.Count
            differenceAssessments = $parityAssessments
            unexplainedDifferences = $unexplainedParity
            normalizedReport = 'parity-report.json'
            coverage = @('artifact entry payloads', 'JPMS and public API', 'consumer POM', 'SBOM', 'notices', 'runtime dependencies', 'staging manifest and bytes')
            resolvedDependencyEvidence = [ordered]@{
                mavenGraphs = @($mavenBaseline.resolvedGraphs)
                gradleManifest = 'target/compliance/resolved-production-dependencies.json'
                projectComparisons = $dependencyComparisons
                comparison = 'Every Maven module version is present in the corresponding Gradle lock; explicit Gradle-only build inputs are bounded, and runtime filename/classifier SHA-256 identities are compared by the artifact inspector.'
            }
        }
        reproducibility = [ordered]@{
            matches = $true
            artifactCount = $reproducibleArtifacts.Count
            artifacts = $reproducibleArtifacts
            command = $reproducibility
        }
        offline = [ordered]@{
            primePassed = $prime.exitCode -eq 0
            includedBuildPrimePassed = $primeBuildLogic.exitCode -eq 0
            verifiedCacheBuildPassed = $offline.exitCode -eq 0 -and $offlineBuildLogic.exitCode -eq 0
            dependencyVerificationMode = 'strict'
            strictVerificationFailureObserved = $strictFailure.exitCode -ne 0
            prime = $prime
            includedBuildPrime = $primeBuildLogic
            build = $offline
            includedBuild = $offlineBuildLogic
            negative = $strictFailure
            conformanceOutcome = [ordered]@{
                path = [IO.Path]::GetRelativePath($pendingRoot, $conformanceExitSnapshot).Replace('\', '/')
                sha256 = (Get-FileHash -LiteralPath $conformanceExitSnapshot -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
        lockfiles = [ordered]@{ byteConsistent = $lockfilesMatch; before = $lockfilesBefore; after = $lockfilesAfter }
        configurationCache = [ordered]@{
            enabledByDefault = $false
            compatibleTasks = [ordered]@{
                tasks = $compatibleTasks
                firstStored = $true
                secondReused = $true
                first = $configurationFirst
                second = $configurationSecond
            }
            incompatibleTasks = [ordered]@{
                tasks = $incompatibleTasks
                rejected = $configurationRejected.exitCode -ne 0
                diagnostic = 'Configuration cache is not supported for evidence, staging, or external-process tasks'
                command = $configurationRejected
            }
        }
        gates = $gateEvidence
        cli = $cliEvidence
        releaseQualificationHandoff = [ordered]@{
            status = [string] $releaseQualificationManifest.status
            manualQualificationPerformed = [bool] $releaseQualificationManifest.manualQualificationPerformed
            root = 'release-qualification'
            manifest = 'release-qualification/manifest.json'
            manifestSha256 = (Get-FileHash -LiteralPath (Join-Path $pendingRoot 'release-qualification/manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
            fileCount = @($releaseQualificationManifest.files).Count
            files = @($releaseQualificationManifest.files)
        }
        timings = [ordered]@{
            machine = [ordered]@{
                operatingSystem = [Environment]::OSVersion.VersionString
                machineName = [Environment]::MachineName
                processorCount = [Environment]::ProcessorCount
            }
            methodology = 'identical offline --rerun-tasks artifact, benchmark, and formatting commands; cold follows explicit clean; warm immediately follows cold; --no-daemon for both; policy and conformance tests are excluded'
            cold = $coldTiming
            warm = $warmTiming
            speedClaim = $null
        }
        normativeGapAssessments = @($mavenBaseline.normativeGaps | ForEach-Object {
            [ordered]@{ id = $_.id; mavenStatus = $_.status; requirement = $_.requirement; gradleEvidence = 'covered by this qualification and referenced command logs' }
        })
        commands = @($commands)
    }
    $reportPath = Join-Path $pendingRoot 'gradle-qualification.json'
    [IO.File]::WriteAllText($reportPath, (($report | ConvertTo-Json -Depth 100).Replace("`r`n", "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $pendingRoot -Destination $outputRoot
    $qualificationSucceeded = $true
    Write-Output "Captured Gradle qualification evidence at $outputRoot"
}
finally {
    if ($null -ne $gradleWrapper -and (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        # TestKit uses a Tooling API daemon even when the outer evidence build requests --no-daemon.
        & $gradleWrapper --stop 2>$null | Out-Null
    }
    if ($qualificationSucceeded -and $worktreeRegistered) {
        & git -C $reactorRoot worktree remove --force $isolatedRoot 2>$null
    }
    if ($qualificationSucceeded -and (Test-Path -LiteralPath $scratchRoot)) {
        $resolvedScratch = [IO.Path]::GetFullPath($scratchRoot)
        $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedScratch.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove scratch directory outside system temp: $resolvedScratch"
        }
        foreach ($attempt in 1..5) {
            try {
                Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
                break
            }
            catch {
                if ($attempt -eq 5) {
                    Write-Warning "Retained locked qualification scratch directory: $resolvedScratch"
                } else {
                    Start-Sleep -Milliseconds 500
                }
            }
        }
    }
    $env:JAVA_HOME = $oldJavaHome
    $env:GRADLE_USER_HOME = $oldGradleUserHome
    $env:PATH = $oldPath
    if (-not $qualificationSucceeded -and (Test-Path -LiteralPath $pendingRoot -PathType Container)) {
        Write-Warning "Qualification state was retained. Resume with the same inputs and -Resume: $pendingRoot"
    }
}
