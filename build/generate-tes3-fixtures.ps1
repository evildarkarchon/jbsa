<# .SYNOPSIS Materializes independent CC0 TES3 wire vectors as reviewable hexadecimal text. #>
param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if ((Test-Path -LiteralPath $OutputDirectory) -and @(Get-ChildItem -LiteralPath $OutputDirectory -Force).Count) {
    throw 'Fixture generation requires an empty destination; existing evidence is never replaced.'
}
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
# These wire bytes and hash constants were independently worked from JBSA-TES3-002/003.
# No production encoder, parser, hashing helper, or Reference Snapshot source is used.
$canonical = [Convert]::FromHexString(
    '00010000310000000200000004000000000000000500000004000000000000000d000000' +
    '6d65736865735c612e6e696600736f756e645c622e77617600' +
    '081673683271b754176f756ed04597bb4e49460000010203ff')
$vectors = [ordered]@{ stored = $canonical }
$vectors['truncated-payload'] = [byte[]]$canonical[0..($canonical.Length - 2)]
$impossible = [byte[]]$canonical.Clone()
[Array]::Copy([BitConverter]::GetBytes([uint32]::MaxValue), 0, $impossible, 8, 4)
$vectors['impossible-count'] = $impossible
$overlap = [byte[]]$canonical.Clone()
$overlap[24] = 3
$vectors['partial-overlap'] = $overlap
$offset = [byte[]]$canonical.Clone()
$offset[32] = 1
$vectors['name-offset'] = $offset
$hash = [byte[]]$canonical.Clone()
$hash[61] = $hash[61] -bxor 1
$vectors['stored-hash'] = $hash
$vectors['trailing-data'] = [byte[]]($canonical + [byte]0)
$records = [Collections.Generic.List[object]]::new()
foreach ($entry in $vectors.GetEnumerator()) {
    $name = 'tes3-' + $entry.Key + '.hex'
    $wire = [byte[]]$entry.Value
    $text = [Convert]::ToHexString($wire).ToLowerInvariant() + "`n"
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $name), $text, [Text.UTF8Encoding]::new($false))
    $records.Add([ordered]@{
        id = 'tes3-' + $entry.Key
        path = $name
        representation = 'lowercase hexadecimal followed by LF; decode before archive use'
        wire_size = $wire.Length
        wire_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($wire)).ToLowerInvariant()
        file_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
        source = 'independently authored JBSA-TES3-002/003 wire vector; one-field mutation ' + $entry.Key
        oracle_sha256 = $null
    })
}
$manifest = [ordered]@{
    schema_version = 1
    corpus_id = 'jbsa-tes3-wire-vectors-v1'
    creator = 'JBSA project contributors'
    spdx_license = 'CC0-1.0'
    redistribution_class = 'project-authored-redistributable'
    generated_on = '2026-09-08'
    reference_snapshot_revision = 'fd1e36020b2b5b6217e553dc0038983146a2e2dd'
    generator = [ordered]@{
        path = 'build/generate-tes3-fixtures.ps1'
        version = '1'
        spdx_license = 'Apache-2.0'
        sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        command = 'pwsh -NoProfile -File build/generate-tes3-fixtures.ps1 -OutputDirectory <empty-directory>'
    }
    fixtures = @($records.ToArray())
}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'manifest.json'),
    ($manifest | ConvertTo-Json -Depth 10).Replace("`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
