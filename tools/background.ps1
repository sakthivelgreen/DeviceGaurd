<#
.SYNOPSIS
  DeviceGuard MDM - Android "Restricted" background mode over ADB. Author: Sakthivel J

.DESCRIPTION
  Android's real background restriction (Settings > Battery > Restricted) can only be set by the
  system or ADB. This applies it to the apps on DeviceGuard's "Background apps" list (read from the
  phone), or to -Package. Restricted apps can't run jobs, alarms, sync or services in the background.

.EXAMPLES
  .\background.ps1 -Action status                      # state of every app on the DeviceGuard list
  .\background.ps1 -Action apply                       # restrict all listed apps
  .\background.ps1 -Action undo                        # back to normal
  .\background.ps1 -Action apply -Package org.videolan.vlc
#>
param(
    [ValidateSet('status', 'apply', 'undo')] [string] $Action = 'status',
    [string[]] $Package,
    [string] $Adb = "$PSScriptRoot\..\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) { $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $Adb) { throw "adb.exe not found. Pass -Adb <path>." }
if (-not (& $Adb devices | Select-String -Pattern '\tdevice$')) { throw "No phone connected." }

if (-not $Package) {
    # DeviceGuard's list (debug build allows run-as)
    $xml = (& $Adb shell "run-as com.devguard.mdm cat shared_prefs/policy.xml") -join "`n"
    $m = [regex]::Match($xml, '<set name="noBackgroundPkgs">(.*?)</set>', 'Singleline')
    $Package = if ($m.Success) { [regex]::Matches($m.Groups[1].Value, '<string>([^<]+)</string>') | ForEach-Object { $_.Groups[1].Value } } else { @() }
    if (-not $Package) { "DeviceGuard's Background apps list is empty. Add apps in the app, or pass -Package."; return }
}

foreach ($p in $Package | Sort-Object) {
    switch ($Action) {
        'apply' {
            & $Adb shell cmd appops set $p RUN_ANY_IN_BACKGROUND ignore | Out-Null
            & $Adb shell am set-standby-bucket $p restricted | Out-Null
        }
        'undo' {
            & $Adb shell cmd appops set $p RUN_ANY_IN_BACKGROUND allow | Out-Null
            & $Adb shell am set-standby-bucket $p active | Out-Null
        }
    }
    $op = (& $Adb shell cmd appops get $p RUN_ANY_IN_BACKGROUND) -join ' '
    $bucket = (& $Adb shell am get-standby-bucket $p) -join ''
    $bucketName = switch ($bucket.Trim()) { '5' { 'exempted' } '10' { 'active' } '20' { 'working_set' } '30' { 'frequent' } '40' { 'rare' } '45' { 'RESTRICTED' } default { $bucket } }
    $restricted = if ($op -match 'ignore') { 'RESTRICTED' } else { 'normal' }
    "{0,-45} background: {1,-10} bucket: {2}" -f $p, $restricted, $bucketName
}
if ($Action -eq 'apply') { "`nDone. Undo any time with:  .\background.ps1 -Action undo" }
