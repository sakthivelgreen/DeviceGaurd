<#
.SYNOPSIS
  DeviceGuard MDM - build a release APK and (optionally) publish it as a GitHub Release. Author: Sakthivel J

.DESCRIPTION
  1. Builds the RELEASE APK (not testOnly, signed with the same key as the installed app).
  2. Copies it to dist\DeviceGuard.apk and dist\DeviceGuard-<version>.apk.
  3. With -Upload and $env:GITHUB_TOKEN set: creates the release tag build-<versionCode> and attaches the APK,
     which is what the in-app auto-updater looks for.

  Before each release, raise versionCode (and versionName) in app\build.gradle.kts.

.EXAMPLES
  .\release.ps1                         # build only
  $env:GITHUB_TOKEN = "github_pat_..."  # token with Contents: Read and write on the repo
  .\release.ps1 -Upload -Notes "Screen time, 2FA"
#>
param(
    [switch] $Upload,
    [string] $Notes = "",
    [string] $Repo = "sakthivelgreen/DeviceGaurd"
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
Push-Location $root
try {
    $gradle = Get-Content "app\build.gradle.kts" -Raw
    $code = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    "Building release $name (versionCode $code)…"
    & .\gradlew.bat assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }

    New-Item -ItemType Directory -Force dist | Out-Null
    $apk = "app\build\outputs\apk\release\app-release.apk"
    Copy-Item $apk "dist\DeviceGuard.apk" -Force
    Copy-Item $apk "dist\DeviceGuard-$name.apk" -Force
    "APK: $root\dist\DeviceGuard.apk"

    if (-not $Upload) { "Not uploaded. Run with -Upload (and `$env:GITHUB_TOKEN) to publish build-$code."; return }
    if (-not $env:GITHUB_TOKEN) { throw "Set `$env:GITHUB_TOKEN first (fine-grained token, Contents: Read and write)." }

    $h = @{ Authorization = "Bearer $env:GITHUB_TOKEN"; Accept = "application/vnd.github+json"; "User-Agent" = "DeviceGuard-release" }
    $body = @{ tag_name = "build-$code"; name = "DeviceGuard $name (build $code)"; body = $Notes; target_commitish = "master" } | ConvertTo-Json
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" -Headers $h -Body $body -ContentType "application/json"
    $up = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=DeviceGuard.apk"
    $null = Invoke-RestMethod -Method Post -Uri $up -Headers $h -InFile "dist\DeviceGuard.apk" -ContentType "application/vnd.android.package-archive"
    "Published: $($rel.html_url)"
    "Phones with auto-update on will install it within a day (or Settings → Check for updates now)."
} finally { Pop-Location }
