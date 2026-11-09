param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$VisionCache,
    [string]$GradleCache,
    [ValidateSet('Both','Japanese','Chinese')][string]$Language = 'Both',
    [switch]$IncludeEmulator,
    [switch]$SkipLint
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_USER_HOME = Join-Path $repoRoot '.local/android'
if ($GradleCache) { $env:GRADLE_USER_HOME = $GradleCache }
New-Item -ItemType Directory -Path (Join-Path $repoRoot '.local') -Force | Out-Null
$sdkPath = $SdkRoot.Replace('\','/')
[System.IO.File]::WriteAllText((Join-Path $repoRoot 'local.properties'), "sdk.dir=$sdkPath`n")
$key = Join-Path $repoRoot '.local/debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    & (Join-Path $JavaHome 'bin/keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=Translate Offline Development' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local development signing key.' }
}
powershell.exe -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'prepare-offline-assets.ps1') -VisionCache $VisionCache -Language $Language
if ($LASTEXITCODE -ne 0) { throw 'Preparing offline assets failed.' }
$abis = if ($IncludeEmulator) { 'arm64-v8a,x86_64' } else { 'arm64-v8a' }
$languages = if ($Language -eq 'Both') { @('Japanese','Chinese') } else { @($Language) }
$tasks = @(':pipeline:testDebugUnitTest',':ml:testDebugUnitTest',':core:test')
foreach ($sourceLanguage in $languages) {
    $tasks += ":offline:test${sourceLanguage}DebugUnitTest"
    if (!$SkipLint) {
        $tasks += ":offline:lint${sourceLanguage}Debug"
    }
    $tasks += ":offline:assemble${sourceLanguage}Debug"
}
& (Join-Path $JavaHome 'bin/java.exe') -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @tasks "-Pyomu.abis=$abis" --console=plain --no-daemon --max-workers=4
if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." }
foreach ($sourceLanguage in $languages) {
    $flavor = $sourceLanguage.ToLowerInvariant()
    $apk = Join-Path $repoRoot "offline/build/outputs/apk/$flavor/debug/offline-$flavor-debug.apk"
    Get-Item -LiteralPath $apk | Select-Object FullName,Length
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
}
