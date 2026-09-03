<#
.SYNOPSIS
Enforces the DCO 1.1 and provenance declarations required for external pull requests.

.DESCRIPTION
Reads the GitHub pull-request event payload, classifies each commit by its author identity, permits
only maintainer-authored commits to omit sign-off, and requires every external commit plus the
source and fixture provenance confirmations from the pull-request template.

.NOTES
This check is a merge gate, not a determination that the declarations are substantively correct.
Reviewers must still stop when rights, branding, EULA, patent, or provenance evidence is unclear.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
Tests whether a commit author is the maintainer identity allowed to omit DCO sign-off.

.PARAMETER Name
Exact Git author name from the commit object.

.PARAMETER Email
Git author email from the commit object.

.OUTPUTS
True only for the configured maintainer name and verified project/noreply email shapes.
#>
function Test-MaintainerCommitAuthor {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [Parameter(Mandatory = $true)]
        [string] $Email
    )

    return $Name -ceq 'evildarkarchon' -and
        $Email -match '^(?:evildarkarchon@gmail\.com|(?:\d+\+)?evildarkarchon@users\.noreply\.github\.com)$'
}

if ([string]::IsNullOrWhiteSpace($env:GITHUB_EVENT_PATH) -or
    -not (Test-Path -LiteralPath $env:GITHUB_EVENT_PATH -PathType Leaf)) {
    throw 'GITHUB_EVENT_PATH must identify a GitHub pull-request event payload.'
}

$event = Get-Content -Raw -LiteralPath $env:GITHUB_EVENT_PATH | ConvertFrom-Json -Depth 100
if ($null -eq $event.pull_request) {
    Write-Output 'External-contribution verification skipped: the event is not a pull request.'
    exit 0
}

$baseCommit = [string] $event.pull_request.base.sha
$headCommit = [string] $event.pull_request.head.sha
$commitIds = @(& git rev-list "$baseCommit..$headCommit")
if ($LASTEXITCODE -ne 0 -or $commitIds.Count -eq 0) {
    throw "Unable to enumerate external pull-request commits in $baseCommit..$headCommit."
}

$externalCommitCount = 0
foreach ($commitId in $commitIds) {
    # NUL separators preserve multiline commit messages and avoid ambiguous author-field parsing.
    $metadata = & git show -s '--format=%an%x00%ae%x00%B' $commitId
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect external commit $commitId."
    }
    $fields = ([string]::Join("`n", @($metadata))).Split("`0", 3)
    if ($fields.Count -ne 3) {
        throw "External commit metadata is malformed: $commitId."
    }
    $authorName = $fields[0].Trim()
    $authorEmail = $fields[1].Trim()
    if (Test-MaintainerCommitAuthor $authorName $authorEmail) {
        continue
    }

    $externalCommitCount++
    $message = $fields[2].Replace("`r`n", "`n")
    $escapedEmail = [regex]::Escape($authorEmail)
    if ($message -notmatch "(?im)^Signed-off-by:\s+.+\s+<$escapedEmail>\s*$") {
        throw "External commit $commitId lacks a DCO Signed-off-by trailer matching $authorEmail."
    }
}

if ($externalCommitCount -eq 0) {
    Write-Output 'External-contribution verification passed: maintainer commits may omit DCO sign-off.'
    exit 0
}

$pullRequestBody = [string] $event.pull_request.body
$sourceDeclaration = '(?im)^- \[[xX]\] I declare the source provenance used for this change\.$'
$fixtureDeclaration = '(?im)^- \[[xX]\] I declare the fixture provenance or confirm that no fixtures are added\.$'
if ($pullRequestBody -notmatch $sourceDeclaration) {
    throw 'External pull request is missing the checked source-provenance declaration.'
}
if ($pullRequestBody -notmatch $fixtureDeclaration) {
    throw 'External pull request is missing the checked fixture-provenance declaration.'
}

Write-Output 'External-contribution verification passed: DCO sign-offs and provenance declarations are present.'
