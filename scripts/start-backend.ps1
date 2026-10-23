param([switch]$BootstrapAdmin)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:DB_PASSWORD) {
    $secret = Read-Host 'MySQL 密码（仅用于当前进程）' -AsSecureString
    $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
}
if (-not $env:JWT_SECRET) {
    $buffer = New-Object byte[] 48
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($buffer)
    $random.Dispose()
    $env:JWT_SECRET = [Convert]::ToBase64String($buffer)
    Write-Host '已生成本次运行使用的签名密钥；重启后旧登录失效。'
}
if ($BootstrapAdmin) {
    $env:BOOTSTRAP_ADMIN_ENABLED = 'true'
    if (-not $env:BOOTSTRAP_ADMIN_PASSWORD) {
        $secret = Read-Host '首次管理员密码（8至64位）' -AsSecureString
        $env:BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
    }
}
try {
    & mvn -B -ntp -pl admin -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '构建失败' }
    & java -jar admin/target/boot.jar
} finally {
    Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BOOTSTRAP_ADMIN_ENABLED -ErrorAction SilentlyContinue
}
