param([string]$PythonCommand = 'python')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Test-Path '.venv/Scripts/python.exe')) {
        & $PythonCommand -m venv .venv
        if ($LASTEXITCODE -ne 0) { throw 'Python virtual environment creation failed.' }
    }
    $pythonPath = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    & $pythonPath -m pip install 'torch==2.9.1' 'torchaudio==2.9.1' --index-url https://download.pytorch.org/whl/cpu
    if ($LASTEXITCODE -ne 0) { throw 'CPU PyTorch installation failed.' }
    & $pythonPath -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Backend dependency installation failed.' }
    & $pythonPath -m pronunciation.prepare
    if ($LASTEXITCODE -ne 0) { throw 'Pinned model preparation failed.' }
} finally { Pop-Location }
