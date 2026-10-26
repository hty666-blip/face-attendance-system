function Read-AssistantSettings {
    param([switch]$RequireToken)
    $assistantToken = $env:ASSISTANT_INTERNAL_TOKEN
    $assistantPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.local/assistant-secrets.json'
    if (-not $assistantToken -and (Test-Path -LiteralPath $assistantPath)) {
        try { $assistantToken = [string](Get-Content -LiteralPath $assistantPath -Raw | ConvertFrom-Json).ASSISTANT_INTERNAL_TOKEN }
        catch { throw '助手内部令牌文件无法读取，请检查格式。' }
    }
    if ($RequireToken -and ($assistantToken.Length -lt 32 -or $assistantToken -notmatch '^[\x21-\x7E]+$')) {
        throw '助手内部令牌缺失或无效，请先运行 scripts/init-assistant-secrets.ps1。'
    }
    return @{ ASSISTANT_INTERNAL_TOKEN = $assistantToken }
}
