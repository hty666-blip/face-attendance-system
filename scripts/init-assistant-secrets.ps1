$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'assistant-common.ps1')
$assistantLocal = Join-Path (Split-Path -Parent $PSScriptRoot) '.local'
$assistantFile = Join-Path $assistantLocal 'assistant-secrets.json'
if (Test-Path -LiteralPath $assistantFile) {
    $null = Read-AssistantSettings -RequireToken
    Write-Host '助手内部令牌已存在，保留原文件。'
    return
}
$null = New-Item -ItemType Directory -Path $assistantLocal -Force
$assistantBytes = New-Object byte[] 48
$assistantRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $assistantRandom.GetBytes($assistantBytes) } finally { $assistantRandom.Dispose() }
$assistantData = [ordered]@{version=1;createdAtUtc=[DateTime]::UtcNow.ToString('o');ASSISTANT_INTERNAL_TOKEN=[Convert]::ToBase64String($assistantBytes)}
$assistantEncoded = [System.Text.UTF8Encoding]::new($false).GetBytes(($assistantData | ConvertTo-Json) + [Environment]::NewLine)
$assistantStream = [System.IO.File]::Open($assistantFile,[System.IO.FileMode]::CreateNew,[System.IO.FileAccess]::Write,[System.IO.FileShare]::None)
try { $assistantStream.Write($assistantEncoded,0,$assistantEncoded.Length) } finally { $assistantStream.Dispose() }
if ($env:OS -eq 'Windows_NT') {
    $assistantOwner = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $assistantAcl = [System.Security.AccessControl.FileSecurity]::new()
    $assistantAcl.SetOwner($assistantOwner); $assistantAcl.SetAccessRuleProtection($true,$false)
    $assistantAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($assistantOwner,'FullControl','Allow'))
    $assistantAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new('S-1-5-18','FullControl','Allow'))
    Set-Acl -LiteralPath $assistantFile -AclObject $assistantAcl
}
Write-Host '已生成 .local/assistant-secrets.json，未显示令牌。此文件不含模型API Key。'
