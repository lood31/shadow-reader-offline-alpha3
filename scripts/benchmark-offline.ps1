$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $root '.tools/android-sdk/platform-tools/adb.exe'
& $adb get-state
if ($LASTEXITCODE -ne 0) { throw 'Connect one authorized Redmi K80 with USB debugging enabled.' }
& $adb install -r (Join-Path $root 'artifacts/ShadowReader-2.0.0-alpha3-offline-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Application installation failed.' }
& $adb install -r (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Benchmark installation failed.' }
& $adb shell am instrument -w com.shadowreader.app.test/com.shadowreader.app.OfflineBenchmark
$result = & $adb exec-out run-as com.shadowreader.app cat files/offline-benchmark.json
if ($LASTEXITCODE -ne 0) { throw 'Benchmark report unavailable.' }
$result | Set-Content -Encoding utf8 (Join-Path $root 'artifacts/offline-k80-benchmark.json')
if (($result | ConvertFrom-Json).status -ne 'PASS_ENGINE_ONLY') { throw 'Device gate failed; inspect artifacts/offline-k80-benchmark.json.' }
