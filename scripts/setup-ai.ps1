param([string]$PythonPath = 'python')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
$assistantService = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service'
$assistantVenv = Join-Path $assistantService '.venv'
$assistantPython = Join-Path $assistantVenv 'Scripts/python.exe'
if (-not (Test-Path -LiteralPath $assistantVenv)) {
    if (-not (Get-Command $PythonPath -ErrorAction SilentlyContinue)) { throw '请通过 -PythonPath 指定 Python 3.11 或3.12。' }
    Test-FacePython -PythonPath $PythonPath
    & $PythonPath -m venv $assistantVenv
    if ($LASTEXITCODE -ne 0) { throw '创建本项目Python环境失败。' }
}
if (-not (Test-Path -LiteralPath $assistantPython)) { throw '已有.venv缺少解释器，请检查目录；脚本不会覆盖。' }
Test-FacePython -PythonPath $assistantPython
Push-Location -LiteralPath $assistantService
try {
    & $assistantPython -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw '安装锁定依赖失败。' }
    & $assistantPython -m pip check
    if ($LASTEXITCODE -ne 0) { throw '项目Python依赖不一致。' }
} finally { Pop-Location }
Write-Host 'AI能力环境已准备。本步骤没有下载人脸权重、调用模型或启动服务。'
