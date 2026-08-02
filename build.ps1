param([ValidateSet("debug", "release", "arm64-v8a", "armeabi-v7a", "x86_64", "universal", "all")][string]$Mode = "debug")
$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $ProjectDir
try {
    $python = if (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "python" }
    & $python tools/generate-core-manifest.py --validate-only --abis "arm64-v8a,armeabi-v7a,x86_64"
    if ($LASTEXITCODE -ne 0) { throw "Core validation failed." }
    New-Item -ItemType Directory -Force dist | Out-Null
    Remove-Item -Force -ErrorAction SilentlyContinue dist/trivox-*.apk, dist/SHA256SUMS.txt
    $abis = if ($Mode -in @("arm64-v8a", "armeabi-v7a", "x86_64")) { $Mode } else { "arm64-v8a,armeabi-v7a,x86_64" }
    $tasks = switch ($Mode) { "debug" { @("assembleDebug") } "all" { @("test", "lint", "assembleDebug", "assembleRelease") } default { @("assembleRelease") } }
    & .\gradlew.bat --no-daemon "-PtrivoxAbis=$abis" @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    $types = if ($Mode -eq "debug") { @("debug") } elseif ($Mode -eq "all") { @("debug", "release") } else { @("release") }
    foreach ($type in $types) {
        Get-ChildItem -Recurse "app/build/outputs/apk/$type" -Filter *.apk | ForEach-Object {
            $abi = if ($_.Name -match "arm64-v8a") { "arm64-v8a" } elseif ($_.Name -match "armeabi-v7a") { "armeabi-v7a" } elseif ($_.Name -match "x86_64") { "x86_64" } else { "universal" }
            Copy-Item -Force $_.FullName "dist/trivox-$abi-$type.apk"
        }
    }
    $sums = Get-ChildItem dist/trivox-*.apk | ForEach-Object { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant(), $_.Name }
    $sums | Sort-Object | Set-Content dist/SHA256SUMS.txt -Encoding ascii
    Get-Content dist/SHA256SUMS.txt
} finally { Pop-Location }
