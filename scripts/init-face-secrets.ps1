$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local'
$secretPath = Join-Path $localRoot 'face-secrets.json'
if (Test-Path -LiteralPath $secretPath) {
    $null = Read-FaceSecrets -RequireTemplateKey -RequireToken
    Write-Host '已存在可用的人脸密钥文件，保持原文件。请将该文件与数据库一起安全备份。'
    return
}
$null = New-Item -ItemType Directory -Path $localRoot -Force
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $keyBytes = New-Object byte[] 32
    $tokenBytes = New-Object byte[] 48
    $random.GetBytes($keyBytes)
    $random.GetBytes($tokenBytes)
} finally {
    $random.Dispose()
}
$settings = [ordered]@{
    version = 1
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    FACE_TEMPLATE_KEY = [Convert]::ToBase64String($keyBytes)
    FACE_INTERNAL_TOKEN = [Convert]::ToBase64String($tokenBytes)
}
$bytes = [System.Text.UTF8Encoding]::new($false).GetBytes(($settings | ConvertTo-Json) + [Environment]::NewLine)
# CreateNew 防止并发执行或已有文件被覆盖，密钥内容不输出到终端。
$stream = [System.IO.File]::Open($secretPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
if ($env:OS -eq 'Windows_NT') {
    $owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [System.Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl', 'Allow'))
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new('S-1-5-18', 'FullControl', 'Allow'))
    Set-Acl -LiteralPath $secretPath -AclObject $acl
}
Write-Host '已创建 .local/face-secrets.json，未输出密钥。请安全备份；丢失模板密钥后需重新录入人脸。'
