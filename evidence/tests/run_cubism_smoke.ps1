<#
.SYNOPSIS
    Builds, installs and runs the P2B-RUNTIME Live2D Cubism smoke test on a connected device.

.DESCRIPTION
    Gradle's connectedAndroidTest task is unusable in this environment: the UTP runner aborts with
    only "严重: Fatal error while executing main with args: --proto_config=..." in utp.0.log, before it
    installs anything. This script reproduces the same verification through the platform path
    (adb install + am instrument), which is sufficient for the runtime gate.

    The Gradle user home is kept inside the workspace because writes to the user profile .gradle
    directory are denied here; the already-populated cache is consumed read-only instead.

    adb writes progress to stderr, so this script runs native tools with ErrorActionPreference
    'Continue' and checks exit codes explicitly. 'Stop' would treat ordinary adb chatter as fatal.

.NOTES
    Verification project: third_party/live2d/sdk-r5/runtime-smoke (official Cubism r.5 sample + Core
    AAR only, no business modules). The SDK and all build output live in ignored directories.
#>
[CmdletBinding()]
param(
    [string]$Workspace = 'D:\AIwatch',
    [string]$Serial = '127.0.0.1:11509',
    [string]$GradleHome = '',
    [string]$ReadOnlyDepCache = "$env:USERPROFILE\.gradle\caches",
    [string]$ReportPath = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'

$smokeProject = Join-Path $Workspace 'third_party\live2d\sdk-r5\runtime-smoke'
$gradleBin    = Join-Path $Workspace '.tools\gradle-8.9\bin\gradle.bat'
$adb          = Join-Path $Workspace '.android-sdk\platform-tools\adb.exe'
if (-not $GradleHome)  { $GradleHome  = Join-Path $Workspace '.tools\gradle-home' }
if (-not $ReportPath)  { $ReportPath  = Join-Path $Workspace 'evidence\reports\cubism-runtime-smoke.txt' }

$apkDir   = Join-Path $smokeProject 'build\outputs\apk'
$mainApk  = Join-Path $apkDir 'debug\CubismRuntimeSmoke-debug.apk'
$testApk  = Join-Path $apkDir 'androidTest\debug\CubismRuntimeSmoke-debug-androidTest.apk'
$runner   = 'com.aiwatch.cubism.smoke.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.aiwatch.smoke.RuntimeSmokeTest'
$captureDir = Join-Path $Workspace '.tools\smoke-captures'

$script:transcript = [System.Collections.Generic.List[string]]::new()
$script:failed = $false
function Redact([string]$Text) {
    # GIT_PRIVACY.md: local account paths must never reach published reports. Gradle emits lines such as
    # "ensure C:\Users\<account>\.android is writable" and the run script itself passes a user-profile
    # cache path, so every captured line is masked before it can be written to evidence.
    $clean = $Text -replace '(?i)[A-Z]:\\Users\\[^\\\s]+', '<USER_HOME>'
    $clean = $clean -replace '(?i)[A-Z]:/Users/[^/\s]+', '<USER_HOME>'
    return $clean
}
function Write-Step([string]$Message) {
    $clean = Redact ([string]$Message)
    Write-Host $clean
    $script:transcript.Add($clean)
}
function Fail([string]$Message) {
    Write-Step "FAIL: $Message"
    $script:failed = $true
}

Write-Step "=== Cubism runtime smoke ==="
Write-Step "utc=$(Get-Date -Format o)"

# ---------- build ----------
if (-not $SkipBuild) {
    Write-Step "--- gradle assemble (workspace gradle home: $GradleHome) ---"
    $env:GRADLE_USER_HOME = $GradleHome
    $env:GRADLE_RO_DEP_CACHE = $ReadOnlyDepCache
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\JDK' }
    # Explicit ':' project paths matter: the bare task names would also run
    # ':cubism-framework:assembleDebugAndroidTest', whose signing step needs a debug keystore lock under
    # the user profile (outside this workspace, therefore denied). Only the app module's APKs are wanted.
    & $gradleBin -p $smokeProject :assembleDebug :assembleDebugAndroidTest --console=plain --offline 2>&1 |
        ForEach-Object { Write-Step $_ }
    if ($LASTEXITCODE -ne 0) { Fail "gradle assemble exited $LASTEXITCODE" }
}

foreach ($apk in @($mainApk, $testApk)) {
    if (-not (Test-Path $apk)) { Fail "missing APK: $apk"; continue }
    $item = Get-Item $apk
    Write-Step ("apk {0} bytes={1} sha256={2}" -f $item.Name, $item.Length, (Get-FileHash $apk -Algorithm SHA256).Hash)
}

# ---------- device ----------
Write-Step "--- adb devices ---"
# The adb server can be restarted between commands in this environment, and a freshly started daemon
# needs a moment before it discovers an already-running emulator. Retry instead of failing on the first
# empty list, which is what a bare one-shot 'adb devices' check would do.
$devices = $null
for ($attempt = 1; $attempt -le 20; $attempt++) {
    $devices = & $adb devices 2>&1
    if ($devices | Select-String -SimpleMatch "$Serial`tdevice") { break }
    Start-Sleep -Seconds 3
}
$devices | ForEach-Object { Write-Step $_ }
if (-not ($devices | Select-String -SimpleMatch "$Serial`tdevice")) { Fail "device $Serial is not in 'device' state after 20 attempts" }
if ($script:failed) { $script:transcript | Set-Content -Path $ReportPath -Encoding utf8; throw "preconditions failed; see $ReportPath" }

$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi 2>&1 | Out-String).Trim()
$sdk = (& $adb -s $Serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
$model = (& $adb -s $Serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
Write-Step "device abi=$abi sdk=$sdk model=$model"

# Page size must be measured, never assumed. Two different numbers exist and they mean different things:
#   * getconf PAGE_SIZE    -> the page size the loader / ELF alignment requirements use. THIS is what
#     "16 KB page size" refers to and what the Android 16 KB compatibility requirement is about.
#   * smaps KernelPageSize -> the kernel's base page size. On an x86_64 16 KB AVD this still reads 4 kB,
#     because 16 KB mode is emulated for compatibility testing, so reporting it as "the page size" is
#     wrong and misleading. Prefer getconf; keep smaps only as a clearly labelled fallback.
Write-Step "--- page size (measured, not assumed) ---"
$pageSize = (& $adb -s $Serial shell getconf PAGE_SIZE 2>&1 | Out-String).Trim()
if ($pageSize -match '^\d+$') {
    Write-Step "pageSize=$pageSize source=getconf (userspace/ELF page size - this is the 16 KB gate metric)"
} else {
    $kernel = (& $adb -s $Serial shell cat /proc/self/smaps 2>&1 | Select-String 'KernelPageSize' | Select-Object -First 1) -replace '\s+', ' '
    Write-Step "pageSize source=smaps kernel=$kernel (getconf unavailable: $pageSize) - kernel base page, NOT the ELF gate metric"
}

# ---------- install ----------
Write-Step "--- install ---"
& $adb -s $Serial install -r -t $mainApk 2>&1 | ForEach-Object { Write-Step $_ }
& $adb -s $Serial install -r -t $testApk 2>&1 | ForEach-Object { Write-Step $_ }

# ---------- run ----------
# Each test method gets its OWN instrumentation invocation, therefore its own app process.
# This is not a style choice: running the whole class in one process is flaky. The official sample
# keeps the Activity in static singletons (LAppPal / LAppDelegate), so once the first test finishes its
# Activity the reference can be stale for the second test and the GL thread then dies with
#   NullPointerException: Activity.getAssets() on a null object reference
#   at LAppPal.loadFileAsBytes -> CubismShaderAndroid.getInstance -> LAppDelegate.onSurfaceCreated
# Observed 2026-09-25: the same whole-class order passed once and crashed the next run. Separate
# processes remove the shared state entirely.
$testMethods = @('twoModelSmoke', 'featureAttribution')
$script:allPassed = $true
foreach ($method in $testMethods) {
    # Start from a stopped package: repeated runs against a long-lived process/emulator leave the GL
    # thread starved, which shows up as "driven=0" traces rather than as an honest failure.
    & $adb -s $Serial shell am force-stop com.aiwatch.cubism.smoke 2>&1 | Out-Null
    System.Threading.Thread::Sleep(500)
    & $adb -s $Serial logcat -c 2>&1 | Out-Null
    Write-Step "--- am instrument ($testClass#$method) ---"
    $instrumentOutput = & $adb -s $Serial shell am instrument -w -r -e class "$testClass#$method" $runner 2>&1
    $instrumentOutput | ForEach-Object { Write-Step $_ }
    $methodPassed = [bool]($instrumentOutput | Select-String -Pattern 'OK \(1 test\)')
    Write-Step "method=$method passed=$methodPassed"
    if (-not $methodPassed) { $script:allPassed = $false }
    Write-Step "--- CubismSmoke markers ($method) ---"
    (& $adb -s $Serial logcat -d -s CubismSmoke:V 2>&1) | ForEach-Object { Write-Step $_ }
}
$passed = $script:allPassed
Write-Step "instrumentation_passed=$passed"

# ---------- captures ----------
Write-Step "--- pull captures ---"
New-Item -ItemType Directory -Force -Path $captureDir | Out-Null
& $adb -s $Serial pull /sdcard/Android/data/com.aiwatch.cubism.smoke/files/ $captureDir 2>&1 |
    ForEach-Object { Write-Step $_ }
Write-Step "captures=$( (Get-ChildItem $captureDir -Recurse -File -Filter *.png -ErrorAction SilentlyContinue).Count ) png in $captureDir"

New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath) | Out-Null
$script:transcript | Set-Content -Path $ReportPath -Encoding utf8
Write-Host "`nReport written to $ReportPath"

if (-not $passed) { Fail "instrumentation did not report success" }
if ($script:failed) { throw "smoke verification FAILED; see $ReportPath" }
Write-Host "smoke verification PASSED"
