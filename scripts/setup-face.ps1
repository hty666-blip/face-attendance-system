param([string]$PythonPath = 'python')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
$serviceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service'
$venvRoot = Join-Path $serviceRoot '.venv'
$venvPython = Join-Path $venvRoot 'Scripts/python.exe'
Push-Location -LiteralPath $serviceRoot
try {
    if (-not (Test-Path -LiteralPath $venvRoot)) {
        if (-not (Get-Command $PythonPath -ErrorAction SilentlyContinue)) {
            throw '未找到 Python，请通过 -PythonPath 指定 Python 3.11 或 3.12 解释器。'
        }
        Test-FacePython -PythonPath $PythonPath
        & $PythonPath -m venv $venvRoot
        if ($LASTEXITCODE -ne 0) { throw '创建项目独立 Python 环境失败。' }
    }
    if (-not (Test-Path -LiteralPath $venvPython)) {
        throw 'ai-service/.venv 已存在但解释器缺失，请检查该目录；脚本不会覆盖已有环境。'
    }
    Test-FacePython -PythonPath $venvPython
    Write-Host '安装 requirements.txt 中锁定的依赖，仅影响本项目 .venv。'
    & $venvPython -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw '依赖安装失败。' }
    Write-Host '从固定官方版本下载模型并校验大小与 SHA-256。'
    & $venvPython scripts/download_models.py
    if ($LASTEXITCODE -ne 0) { throw '模型准备失败，可检查网络后重新执行本脚本。' }
    Write-Host '本地人脸服务准备完成。运行 scripts/start-face.ps1 启动。'
} finally {
    Pop-Location
}
