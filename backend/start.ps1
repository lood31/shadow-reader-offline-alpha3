param([string]$BindAddress = '127.0.0.1', [int]$Port = 8765, [string]$EspeakLibrary = '', [string]$Calibration = '')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ($EspeakLibrary) { $env:PHONEMIZER_ESPEAK_LIBRARY = (Resolve-Path -LiteralPath $EspeakLibrary).Path }
    if ($Calibration) { $env:PRONUNCIATION_CALIBRATION = (Resolve-Path -LiteralPath $Calibration).Path }
    $pythonPath = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    & $pythonPath -m uvicorn pronunciation.api:app --host $BindAddress --port $Port
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error.' }
} finally { Pop-Location }
