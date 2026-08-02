[CmdletBinding()]
param(
    [string]$CoreVersion = "v26.7.28",
    [switch]$LatestStable,
    [switch]$LatestPrerelease,
    [string]$LocalCore = "",
    [string]$Abis = "arm64-v8a,armeabi-v7a,x86_64",
    [string]$Package = "com.trivox.client",
    [string]$RepoOwner = "",
    [string]$RepoName = "trivox",
    [switch]$Prepare,
    [switch]$PrepareOnly,
    [switch]$Build,
    [switch]$CreateRepo,
    [switch]$Push,
    [switch]$PublicRepo,
    [switch]$PrivateRepo,
    [switch]$NonInteractive,
    [switch]$Clean,
    [switch]$VerboseOutput,
    [switch]$SkipTunHelper
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir

if ($PSBoundParameters.Count -eq 0 -and -not $NonInteractive) {
    Write-Host "Trivox interactive setup: 1) pinned v26.7.28  2) latest stable  3) latest prerelease  4) local official AAR/ZIP"
    $choice = Read-Host "Core source [1]"
    switch ($choice) {
        "2" { $LatestStable = $true }
        "3" { $LatestPrerelease = $true }
        "4" { $LocalCore = Read-Host "Local artifact path" }
        default { }
    }
    $selected = Read-Host "ABIs [$Abis]"
    if ($selected) { $Abis = $selected }
    $buildChoice = Read-Host "Build after preparation? [y/N]"
    if ($buildChoice -match '^(y|yes)$') { $Build = $true }
}

if ($Package -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw "Invalid Android package name." }
$validAbis = @("arm64-v8a", "armeabi-v7a", "x86_64")
$requestedAbis = $Abis.Split(',') | ForEach-Object { $_.Trim() }
if (($requestedAbis | Where-Object { $_ -notin $validAbis }).Count -gt 0) { throw "Invalid ABI list." }
if (-not (Get-Command python -ErrorAction SilentlyContinue) -and -not (Get-Command python3 -ErrorAction SilentlyContinue)) {
    throw "Python 3 is required for safe archive validation."
}
$Python = if (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "python" }

if ($SkipTunHelper) { Write-Host "No external TUN helper is used: Xray v26.7.28 has an Android TUN inbound." }
if ($Clean) {
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $ProjectDir "app/libs/libXray.aar")
}

$headers = @{ Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28" }
if ($env:GITHUB_TOKEN) { $headers.Authorization = "Bearer $($env:GITHUB_TOKEN)" }
if ($LatestStable) {
    $CoreVersion = (Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/XTLS/libXray/releases/latest" -TimeoutSec 45).tag_name
} elseif ($LatestPrerelease) {
    $release = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/XTLS/Xray-core/releases?per_page=30" -TimeoutSec 45 |
        Where-Object { $_.prerelease -and -not $_.draft } | Select-Object -First 1
    if (-not $release) { throw "No Xray prerelease was found." }
    $CoreVersion = $release.tag_name
}

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("trivox-wizard-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    $artifact = $LocalCore
    $sourceUrl = if ($LocalCore) { "local:" + [IO.Path]::GetFileName($LocalCore) } else { "" }
    $expectedSha = ""
    if (-not $artifact) {
        try {
            $release = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/XTLS/libXray/releases/tags/$CoreVersion" -TimeoutSec 45
        } catch {
            throw "libXray has no Android release matching Xray $CoreVersion. Use -LocalCore with an official compatible AAR."
        }
        $asset = $release.assets | Where-Object { $_.name -eq "libxray-android.zip" } | Select-Object -First 1
        if (-not $asset) { throw "Official release has no libxray-android.zip asset." }
        $sourceUrl = $asset.browser_download_url
        $expectedSha = [string]$asset.digest -replace '^sha256:', ''
        $artifact = Join-Path $tempDir "libxray-android.zip"
        Invoke-WebRequest -UseBasicParsing -Uri $sourceUrl -OutFile $artifact -TimeoutSec 300
        if ((Get-Item $artifact).Length -eq 0) { throw "Downloaded core archive is empty." }
    } else {
        $artifact = (Resolve-Path $artifact).Path
    }

    $arguments = @(
        (Join-Path $ScriptDir "generate-core-manifest.py"),
        "--artifact", $artifact,
        "--aar-output", (Join-Path $ProjectDir "app/libs/libXray.aar"),
        "--manifest-output", (Join-Path $ProjectDir "app/src/main/assets/core-manifest.json"),
        "--version", $CoreVersion,
        "--source-url", $sourceUrl,
        "--abis", $Abis
    )
    if ($expectedSha) { $arguments += @("--expected-sha256", $expectedSha) }
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Core preparation failed." }

    $propertiesPath = Join-Path $ProjectDir "gradle.properties"
    $lines = Get-Content $propertiesPath | Where-Object { $_ -notmatch '^(trivoxPackage|trivoxAbis)=' }
    $lines += "trivoxPackage=$Package"
    $lines += "trivoxAbis=$Abis"
    Set-Content -Path $propertiesPath -Value $lines -Encoding utf8

    if ($Build) { & (Join-Path $ProjectDir "build.ps1") all; if ($LASTEXITCODE -ne 0) { throw "Build failed." } }

    if ($CreateRepo -or $Push) {
        if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "Git is required." }
        if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI is required. Install gh, then run: gh auth login" }
        & gh auth status 2>$null; if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run: gh auth login" }
        if (-not $RepoOwner) { $RepoOwner = (& gh api user --jq .login) }
        Push-Location $ProjectDir
        try {
            if (-not (Test-Path ".git")) { & git init }
            & git add .
            & git diff --cached --quiet
            if ($LASTEXITCODE -ne 0) { & git commit -m "Initialize Trivox Android client" }
            if ($CreateRepo) {
                & gh repo view "$RepoOwner/$RepoName" 2>$null
                if ($LASTEXITCODE -ne 0) { $visibility = if ($PublicRepo) { "--public" } else { "--private" }; & gh repo create "$RepoOwner/$RepoName" $visibility --source . --remote origin }
            }
            if ($Push) { & git push -u origin HEAD }
        } finally { Pop-Location }
    }
    Write-Host "Trivox preparation complete. Core: $CoreVersion; ABIs: $Abis"
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tempDir
}
