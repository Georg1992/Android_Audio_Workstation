#Requires -Version 5.1
<#
.SYNOPSIS
  Audit-only clang-tidy for the audioworkstation native engine (non-failing).

.DESCRIPTION
  Uses compile_commands.json from AGP after a debug native build.
  Writes a text report under build/reports/clang-tidy/.

  Prerequisite:
    ./gradlew :app:assembleDebug
    (or any task that configures app/.cxx/tools/debug/<abi>/)

  Does not modify source files and does not enable -fix.
#>
param(
    [string]$Abi = "arm64-v8a",
    [switch]$IncludeTests
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
$cppRoot = Join-Path $repoRoot "app\src\main\cpp"
$appDir = Join-Path $repoRoot "app"
$reportDir = Join-Path $repoRoot "build\reports\clang-tidy"
$reportFile = Join-Path $reportDir "engine-audit.txt"

function Read-NdkVersionFromGradle {
    $gradle = Join-Path $appDir "build.gradle"
    $text = Get-Content $gradle -Raw
    if ($text -match 'ndkVersion\s*=\s*"([^"]+)"') {
        return $Matches[1]
    }
    throw "Could not read ndkVersion from app/build.gradle"
}

function Read-SdkDir {
    $localProps = Join-Path $repoRoot "local.properties"
    if (-not (Test-Path $localProps)) {
        throw "local.properties not found; set sdk.dir for Android SDK path."
    }
    foreach ($line in Get-Content $localProps) {
        if ($line -match '^\s*sdk\.dir=(.+)$') {
            return $Matches[1].Trim()
        }
    }
    throw "sdk.dir missing in local.properties"
}

$ndkVersion = Read-NdkVersionFromGradle
$sdkDir = Read-SdkDir
$ndkRoot = Join-Path $sdkDir "ndk\$ndkVersion"
$clangTidy = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\clang-tidy.exe"
if (-not (Test-Path $clangTidy)) {
    throw "clang-tidy not found at $clangTidy (NDK $ndkVersion)"
}

$compileDbDir = Join-Path $appDir ".cxx\tools\debug\$Abi"
$compileDb = Join-Path $compileDbDir "compile_commands.json"
if (-not (Test-Path $compileDb)) {
    throw @"
compile_commands.json not found:
  $compileDb
Run first:  ./gradlew :app:assembleDebug
"@
}

$engineSources = @(
    "engine\AudioEngine.cpp",
    "engine\JNI_Bridge.cpp",
    "engine\LocalWavSource.cpp",
    "engine\OboeOutput.cpp"
) | ForEach-Object { Join-Path $cppRoot $_ }

if ($IncludeTests) {
    $engineSources += @(
        "tests\RingBuffer_test.cpp",
        "tests\AudioEngine_MasterPlayback_test.cpp",
        "tests\AudioEngine_TransportRecording_test.cpp"
    ) | ForEach-Object { Join-Path $cppRoot $_ }
}

$configFile = Join-Path $cppRoot ".clang-tidy"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

$header = @(
    "clang-tidy engine audit",
    "=======================",
    "Time:       $(Get-Date -Format o)",
    "NDK:        $ndkVersion",
    "clang-tidy: $clangTidy",
    "Compile DB: $compileDb",
    "Config:     $configFile",
    "Sources:"
) -join "`n"
foreach ($srcPath in $engineSources) {
    $header += "`n  $srcPath"
}
$header += "`n"

Set-Content -Path $reportFile -Value $header -Encoding utf8

$exitIssues = 0
$prevErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
foreach ($source in $engineSources) {
    "`n=== $source ===" | Add-Content -Path $reportFile -Encoding utf8
    $args = @(
        $source,
        "-p", $compileDbDir,
        "--config-file=$configFile",
        "--quiet"
    )
    $output = & $clangTidy @args 2>&1 |
        Where-Object { $_ -notmatch '^\d+ warnings generated\.$' }
    $text = ($output | Out-String).TrimEnd()
    if ($text) {
        $exitIssues = 1
        Add-Content -Path $reportFile -Value $text -Encoding utf8
        Write-Host $text
    } else {
        Add-Content -Path $reportFile -Value "(no issues)" -Encoding utf8
    }
}

$summary = @(
    "",
    "Summary",
    "-------",
    "Files analyzed: $($engineSources.Count)",
    "Report:         $reportFile"
) -join "`n"
Add-Content -Path $reportFile -Value $summary -Encoding utf8
Write-Host $summary

$ErrorActionPreference = $prevErrorActionPreference

if ($exitIssues -ne 0) {
    Write-Host "clang-tidy reported issues (audit mode; exit 0)." -ForegroundColor Yellow
}
exit 0
