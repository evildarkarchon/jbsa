<# .SYNOPSIS Collects live Windows qualification preconditions and diagnostic context without changing the machine. #>
[CmdletBinding()]
param([string] $VolumePath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Test-HighPerformancePlan {
    <# .SYNOPSIS Recognizes the stable High performance GUID in one or more powercfg output lines. #>
    param([string[]] $PowerOutput)
    return [bool](($PowerOutput -join ' ') -match '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c')
}
if ($MyInvocation.InvocationName -eq '.') { return }
if (-not $VolumePath) { throw 'VolumePath is required.' }
$drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($VolumePath)))
$os = Get-CimInstance Win32_OperatingSystem
$cpu = @(Get-CimInstance Win32_Processor)
$security = @(Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntivirusProduct)
$activeSecurity = @($security | Where-Object { ($_.productState -band 0x1000) -ne 0 }).Count -gt 0
$power = & powercfg /getactivescheme
$cpuSample = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples.CookedValue | Measure-Object -Average
[ordered]@{
    filesystem = $drive.DriveFormat; free_fraction = $drive.AvailableFreeSpace / $drive.TotalSize
    cpu_percent = $cpuSample.Average; high_performance = (Test-HighPerformancePlan $power)
    security_active = $activeSecurity; boot_time = $os.LastBootUpTime.ToUniversalTime().ToString('O')
    uptime_seconds = ([DateTime]::Now - $os.LastBootUpTime).TotalSeconds
    windows = $os.Caption; windows_build = $os.BuildNumber; memory_bytes = $os.TotalVisibleMemorySize * 1024
    cpu = @($cpu | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    storage = @(Get-CimInstance Win32_DiskDrive | Select-Object Model, FirmwareRevision, InterfaceType, Size)
    firmware = @(Get-CimInstance Win32_BIOS | Select-Object Manufacturer, SMBIOSBIOSVersion)
    power_plan = ($power -join ' '); security_products = @($security.displayName)
} | ConvertTo-Json -Depth 10
