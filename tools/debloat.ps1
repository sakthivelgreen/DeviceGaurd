<#
.SYNOPSIS
  DeviceGuard MDM - Bloatware tool (PC side, over ADB). Author: Sakthivel J

.DESCRIPTION
  Uses the same list as the app (app\src\main\assets\bloat.csv).
  Categories: SAFE (safe to remove), CAUTION (lose a feature), KEEP (system-critical, never touched in bulk).
  Works without Device Owner and without root. Every change is reversible with -Action restore.

.EXAMPLES
  .\debloat.ps1 -Action list                          # show every listed app on the phone with its state
  .\debloat.ps1 -Action list -Category SAFE
  .\debloat.ps1 -Action disable -Category SAFE        # disable all SAFE apps (asks to confirm)
  .\debloat.ps1 -Action remove  -Category SAFE        # uninstall for user 0 (asks to confirm)
  .\debloat.ps1 -Action remove  -Package com.vivo.browser
  .\debloat.ps1 -Action restore -Package com.vivo.browser
  .\debloat.ps1 -Action restore -FromLog              # restore everything this script removed or disabled
#>
param(
    [ValidateSet('list', 'disable', 'remove', 'restore')] [string] $Action = 'list',
    [ValidateSet('SAFE', 'CAUTION', 'KEEP', 'ALL')] [string] $Category = 'ALL',
    [string[]] $Package,
    [switch] $FromLog,
    [switch] $Yes,
    [string] $Adb = "$PSScriptRoot\..\platform-tools\adb.exe"   # ADB bundled in MDM_App\platform-tools
)

$ErrorActionPreference = 'Stop'
$csv = Join-Path $PSScriptRoot '..\app\src\main\assets\bloat.csv'
$log = Join-Path $PSScriptRoot 'debloat-log.csv'

if (-not (Test-Path $Adb)) { $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $Adb) { throw "adb.exe not found. Pass -Adb <path>." }

$devices = & $Adb devices | Select-String -Pattern '\tdevice$'
if (-not $devices) { throw "No phone connected. Run: adb connect <ip>:<port>" }

# ---- load list ----
$db = @{}
Get-Content $csv | Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -Skip 1 | ForEach-Object {
    $p = $_.Split(',', 4)
    if ($p.Count -ge 4) { $db[$p[0].Trim()] = [pscustomobject]@{ Package = $p[0].Trim(); Category = $p[1].Trim(); Name = $p[2].Trim(); Note = $p[3].Trim() } }
}

# ---- phone state ----
function Get-Pkgs([string] $flags) {
    (& $Adb shell "pm list packages $flags 2>/dev/null") -replace '^package:', '' | Where-Object { $_ -and -not $_.StartsWith('Error') }
}
$installed = [System.Collections.Generic.HashSet[string]]::new([string[]](Get-Pkgs '--user 0'))
$allSys    = [System.Collections.Generic.HashSet[string]]::new([string[]](Get-Pkgs '-s -u'))
$disabled  = [System.Collections.Generic.HashSet[string]]::new([string[]](Get-Pkgs '-d --user 0'))

function Get-State([string] $pkg) {
    if (-not $allSys.Contains($pkg) -and -not $installed.Contains($pkg)) { return 'NOT ON PHONE' }
    if (-not $installed.Contains($pkg)) { return 'Removed' }
    if ($disabled.Contains($pkg)) { return 'Disabled' }
    return 'Active'
}

# ---- choose targets ----
if ($FromLog) {
    if (-not (Test-Path $log)) { throw "No log found at $log" }
    $targets = Import-Csv $log | Select-Object -ExpandProperty Package -Unique
} elseif ($Package) {
    $targets = $Package
} else {
    $targets = $db.Values | Where-Object { $Category -eq 'ALL' -or $_.Category -eq $Category } | Select-Object -ExpandProperty Package
}

$rows = foreach ($p in $targets) {
    $e = $db[$p]
    [pscustomobject]@{
        Category = if ($e) { $e.Category } else { 'UNKNOWN' }
        State    = Get-State $p
        Package  = $p
        Name     = if ($e) { $e.Name } else { '' }
        Note     = if ($e) { $e.Note } else { 'Not in the list' }
    }
}
$rows = $rows | Where-Object { $_.State -ne 'NOT ON PHONE' } | Sort-Object Category, State, Name

if ($Action -eq 'list') {
    $rows | Format-Table Category, State, Name, Package, Note -AutoSize -Wrap | Out-String -Width 240
    "`nTotal: $($rows.Count)   SAFE: $(($rows | ? Category -eq 'SAFE').Count)   CAUTION: $(($rows | ? Category -eq 'CAUTION').Count)   KEEP: $(($rows | ? Category -eq 'KEEP').Count)"
    return
}

# ---- safety rules ----
if ($Action -in 'disable', 'remove') {
    if (-not $Package -and $Category -in 'KEEP', 'ALL') { throw "Bulk $Action is only allowed for -Category SAFE or CAUTION. Name KEEP packages one by one with -Package." }
    $want = if ($Action -eq 'disable') { 'Active' } else { 'Active', 'Disabled' }
    $rows = $rows | Where-Object { $_.State -in $want }
    $keep = $rows | Where-Object Category -eq 'KEEP'
    if ($keep) { Write-Warning "KEEP (system-critical) packages selected: $($keep.Package -join ', '). This can break calls, SMS, setup or boot." }
} else {
    $rows = $rows | Where-Object { $_.State -in 'Removed', 'Disabled' }
}

if (-not $rows) { "Nothing to do."; return }
$rows | Format-Table Category, State, Name, Package -AutoSize | Out-String -Width 200
if (-not $Yes) {
    $ans = Read-Host "$($Action.ToUpper()) these $($rows.Count) app(s)? Type YES to continue"
    if ($ans -ne 'YES') { "Cancelled."; return }
}

foreach ($r in $rows) {
    $p = $r.Package
    switch ($Action) {
        'disable' { $out = & $Adb shell pm disable-user --user 0 $p }
        'remove'  { $out = & $Adb shell pm uninstall -k --user 0 $p }
        'restore' {
            if ($r.State -eq 'Removed') { $out = & $Adb shell cmd package install-existing $p }
            else { $out = & $Adb shell pm enable $p }
        }
    }
    "{0,-8} {1,-55} {2}" -f $Action, $p, ($out -join ' ')
    if ($Action -ne 'restore') {
        [pscustomobject]@{ Time = (Get-Date -Format s); Action = $Action; Package = $p } | Export-Csv $log -Append -NoTypeInformation
    }
}
"`nDone. Undo any time with:  .\debloat.ps1 -Action restore -FromLog"
