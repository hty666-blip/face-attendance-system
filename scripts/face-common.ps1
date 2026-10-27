function Read-FaceSecrets {
    param(
        [switch]$RequireTemplateKey,
        [switch]$RequireToken
    )
    $secretPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.local/face-secrets.json'
    $settings = $null
    if (Test-Path -LiteralPath $secretPath) {
        try {
            $settings = Get-Content -LiteralPath $secretPath -Raw | ConvertFrom-Json
        } catch {
            throw '本地人脸密钥文件无法读取。请检查 JSON 格式；不要删除已有模板对应的密钥。'
        }
    }
    $templateKey = $env:FACE_TEMPLATE_KEY
    $serviceToken = $env:FACE_INTERNAL_TOKEN
    if (-not $templateKey -and $settings) { $templateKey = [string]$settings.FACE_TEMPLATE_KEY }
    if (-not $serviceToken -and $settings) { $serviceToken = [string]$settings.FACE_INTERNAL_TOKEN }
    if ($RequireTemplateKey) {
        try { $keyBytes = [Convert]::FromBase64String($templateKey) } catch { $keyBytes = @() }
        if ($keyBytes.Length -ne 32) {
            throw '缺少有效的人脸模板密钥。首次运行请执行 scripts/init-face-secrets.ps1；已有模板时应恢复原密钥。'
        }
    }
    if ($RequireToken -and $serviceToken.Length -lt 32) {
        throw '缺少有效的人脸内部服务令牌。请先执行 scripts/init-face-secrets.ps1，并让 Java 与 Python 使用同一份配置。'
    }
    return @{ FACE_TEMPLATE_KEY = $templateKey; FACE_INTERNAL_TOKEN = $serviceToken }
}

function Test-FacePython {
    param([Parameter(Mandatory = $true)][string]$PythonPath)
    & $PythonPath -c "import sys; sys.exit(0 if sys.version_info[:2] in [(3, 11), (3, 12)] else 1)"
    if ($LASTEXITCODE -ne 0) { throw '本地人脸服务需要 Python 3.11 或 3.12，请通过 -PythonPath 指定解释器。' }
}
