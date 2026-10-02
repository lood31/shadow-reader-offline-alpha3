param([switch]$InstallSdk, [switch]$AcceptAndroidLicenses, [switch]$OfflinePronunciation)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (Test-Path '.tools/java-home.txt') {
        $env:JAVA_HOME = (Get-Content '.tools/java-home.txt' -Raw).Trim()
        $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    }
    if (Test-Path '.tools/android-sdk') { $env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk' }
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
    if ($InstallSdk) {
        if (-not $AcceptAndroidLicenses) { throw 'Pass -AcceptAndroidLicenses only after reading and accepting the Android SDK licenses.' }
        $sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
        (1..20 | ForEach-Object { 'y' }) | & $sdkManager "--sdk_root=$env:ANDROID_HOME" --licenses
        if ($LASTEXITCODE -ne 0) { throw "SDK license command failed: $LASTEXITCODE" }
        & $sdkManager "--sdk_root=$env:ANDROID_HOME" 'platforms;android-35' 'build-tools;34.0.0' 'platform-tools'
        if ($LASTEXITCODE -ne 0) { throw "SDK installation failed: $LASTEXITCODE" }
    }
    python (Join-Path $PSScriptRoot 'native-bootstrap.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native toolchain preparation failed.' }
    $gradleCommand = if (Test-Path '.tools/gradle-8.9/bin/gradle.bat') {
        Join-Path $projectRoot '.tools/gradle-8.9/bin/gradle.bat'
    } else { Join-Path $projectRoot 'gradlew.bat' }
    $gradleArguments = @('--no-daemon', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
    if ($OfflinePronunciation) {
        & '.\backend\.venv\Scripts\python.exe' scripts/synthetic-evidence-fixture.py
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic acoustic regression fixtures could not be prepared.' }
        if (Test-Path 'backend/models/offline/model.int8.onnx') {
            & '.\backend\.venv\Scripts\python.exe' scripts/prepare-offline.py
            if ($LASTEXITCODE -ne 0) { throw 'Verified offline model assets are required.' }
        } elseif (-not (Test-Path 'app/src/offline/assets/pronunciation/manifest.json')) {
            throw 'Run python scripts/restore-offline-assets.py <delivered-apk> first.'
        }
        $gradleArguments += '-PofflinePronunciation'
    }
    if (Test-Path '.tools/maven-repo') {
        $gradleArguments += @('--init-script', (Join-Path $PSScriptRoot 'local-cache.init.gradle'))
    }
    & $gradleCommand @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    New-Item -ItemType Directory -Force 'artifacts' | Out-Null
    $appVersion = [regex]::Match((Get-Content 'app/build.gradle.kts' -Raw), 'versionName = "([^"]+)"').Groups[1].Value
    if (-not $appVersion) { throw 'Cannot determine app version.' }
    $variant = if ($OfflinePronunciation) { 'offline-debug' } else { 'debug' }
    $apkPath = "artifacts/ShadowReader-$appVersion-$variant.apk"
    Copy-Item 'app/build/outputs/apk/debug/app-debug.apk' $apkPath -Force
    $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  ShadowReader-$appVersion-$variant.apk" | Set-Content -LiteralPath "$apkPath.sha256" -Encoding ascii
    python scripts/verify-migration.py
    if ($LASTEXITCODE -ne 0) { throw 'Database migration verification failed.' }
} finally { Pop-Location }
