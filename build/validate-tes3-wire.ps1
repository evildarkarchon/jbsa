<# .SYNOPSIS Independently scans canonical ASCII TES3 stored archives for slice corroboration. #>
param([Parameter(Mandatory)][string]$InputPath)
$ErrorActionPreference = 'Stop'
$bytes = [IO.File]::ReadAllBytes($InputPath)
if ($bytes.Length -lt 12 -or [BitConverter]::ToUInt32($bytes, 0) -ne 256) { throw 'Invalid TES3 header' }
$count = [BitConverter]::ToUInt32($bytes, 8)
$hashStart = 12L + [BitConverter]::ToUInt32($bytes, 4)
$nameStart = 12L + 12L * $count
$dataStart = $hashStart + 8L * $count
if ($count -gt 100000 -or $nameStart -gt $hashStart -or $dataStart -gt $bytes.Length) { throw 'Invalid sections' }
$entries = [Collections.Generic.List[object]]::new()
$namePosition = $nameStart
$payloadPosition = $dataStart
for ($i = 0; $i -lt $count; $i++) {
    $size = [BitConverter]::ToUInt32($bytes, 12 + 8 * $i)
    $offset = [BitConverter]::ToUInt32($bytes, 16 + 8 * $i)
    $nameOffset = [BitConverter]::ToUInt32($bytes, 12 + 8 * $count + 4 * $i)
    if ($nameStart + $nameOffset -ne $namePosition) { throw 'Noncanonical name offset' }
    $end = $namePosition
    while ($end -lt $hashStart -and $bytes[$end] -ne 0) { $end++ }
    if ($end -ge $hashStart -or $end -eq $namePosition) { throw 'Invalid name span' }
    $name = $bytes[$namePosition..($end - 1)]
    if (@($name | Where-Object { $_ -gt 127 -or ($_ -ge 65 -and $_ -le 90) -or $_ -eq 47 }).Count) { throw 'Noncanonical ASCII name' }
    [uint64]$low = 0
    [uint64]$high = 0
    $half = [int][Math]::Floor($name.Count / 2)
    for ($j = 0; $j -lt $half; $j++) { $low = $low -bxor ([uint64]$name[$j] -shl (8 * ($j % 4))) }
    for ($j = $half; $j -lt $name.Count; $j++) {
        [uint64]$word = [uint64]$name[$j] -shl (8 * (($j - $half) % 4))
        $high = $high -bxor $word
        $rotation = $word -band 31
        $high = (($high -shr $rotation) -bor ($high -shl (32 - $rotation))) -band 4294967295
    }
    if ([BitConverter]::ToUInt32($bytes, $hashStart + 8 * $i) -ne $low -or
        [BitConverter]::ToUInt32($bytes, $hashStart + 8 * $i + 4) -ne $high) { throw 'Hash mismatch' }
    if ($dataStart + $offset -ne $payloadPosition -or $payloadPosition + $size -gt $bytes.Length) { throw 'Invalid payload span' }
    $payload = [byte[]]::new($size)
    [Array]::Copy($bytes, $payloadPosition, $payload, 0, $size)
    $entries.Add([ordered]@{
        name = [Text.Encoding]::ASCII.GetString($name)
        name_hash = ('{0:x8}{1:x8}' -f $high, $low)
        size = $size
        payload_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payload)).ToLowerInvariant()
    })
    $namePosition = $end + 1
    $payloadPosition += $size
}
if ($namePosition -ne $hashStart -or $payloadPosition -ne $bytes.Length) { throw 'Noncanonical section boundary' }
@{ projection = [ordered]@{ family = 'tes3'; entries = @($entries.ToArray()) } } | ConvertTo-Json -Depth 10 -Compress
