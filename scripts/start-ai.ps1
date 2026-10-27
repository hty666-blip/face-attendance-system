param([string]$ModelConfigPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
. (Join-Path $PSScriptRoot 'assistant-common.ps1')
$startAssistant = {
$assistantRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service'
$assistantPython = Join-Path $assistantRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $assistantPython)) { throw '请先运行 scripts/setup-ai.ps1。' }
Test-FacePython -PythonPath $assistantPython
if ($env:ASSISTANT_ENABLED -ne 'true') { throw '请先显式设置 ASSISTANT_ENABLED=true，并选择真实模型配置或明确的mock演示模式。' }
$assistantSettings = Read-AssistantSettings -RequireToken
$faceSettings = Read-FaceSecrets
$previousAssistantToken = $env:ASSISTANT_INTERNAL_TOKEN
$previousFaceToken = $env:FACE_INTERNAL_TOKEN
$assistantPushedLocation = $false
try {
    $env:ASSISTANT_INTERNAL_TOKEN = $assistantSettings.ASSISTANT_INTERNAL_TOKEN
    $env:FACE_INTERNAL_TOKEN = $faceSettings.FACE_INTERNAL_TOKEN
    Push-Location -LiteralPath $assistantRoot
    $assistantPushedLocation = $true
    Write-Host '本地能力服务：http://127.0.0.1:8010。与start-face共用一个进程，请只启动其中一种。'
    if ($env:ASSISTANT_PROVIDER -eq 'mock') { Write-Host '当前为明确的Mock演示模式，非真实AI；Java业务工具仍会按权限执行。' }
    & $assistantPython -m uvicorn app.main:app --host 127.0.0.1 --port 8010 --workers 1 --limit-concurrency 8 --timeout-keep-alive 5
    if ($LASTEXITCODE -ne 0) { throw '能力服务异常退出，请检查依赖与端口。' }
} finally {
    $env:ASSISTANT_INTERNAL_TOKEN = $previousAssistantToken
    $env:FACE_INTERNAL_TOKEN = $previousFaceToken
    if ($assistantPushedLocation) { Pop-Location }
}
}
if ($ModelConfigPath) {
    Invoke-WithAssistantModelConfig -Path $ModelConfigPath -Operation $startAssistant
} else {
    & $startAssistant
}
