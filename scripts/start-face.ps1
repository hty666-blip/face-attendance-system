$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
$serviceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service'
$venvPython = Join-Path $serviceRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    throw '尚未准备项目环境，请先运行 scripts/setup-face.ps1 -PythonPath <Python3.11或3.12解释器>。'
}
Test-FacePython -PythonPath $venvPython
$secrets = Read-FaceSecrets -RequireToken
$previousToken = $env:FACE_INTERNAL_TOKEN
$env:FACE_INTERNAL_TOKEN = $secrets.FACE_INTERNAL_TOKEN
Push-Location -LiteralPath $serviceRoot
try {
    Write-Host '本地人脸服务：http://127.0.0.1:8010，健康检查 /health。按 Ctrl+C 结束。'
    & $venvPython -m uvicorn app.main:app --host 127.0.0.1 --port 8010 --workers 1 --limit-concurrency 8 --timeout-keep-alive 5
    if ($LASTEXITCODE -ne 0) { throw '本地人脸服务已异常退出，请检查依赖、端口和模型文件。' }
} finally {
    $env:FACE_INTERNAL_TOKEN = $previousToken
    Pop-Location
}
