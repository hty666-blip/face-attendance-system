param(
    [switch]$BootstrapAdmin,
    [string]$MavenPath = 'mvn',
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'face-common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if ($JavaHome) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome 目录无效' }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
}
if (-not (Get-Command $MavenPath -ErrorAction SilentlyContinue)) {
    throw '未找到 Maven，请使用 -MavenPath 指定 mvn.cmd 的完整路径。'
}
$previousFaceKey = $env:FACE_TEMPLATE_KEY
$previousFaceToken = $env:FACE_INTERNAL_TOKEN
try {
    if ($env:FACE_ENABLED -ne 'false') {
        $requireLocalToken = -not $env:FACE_PROVIDER -or $env:FACE_PROVIDER -eq 'local'
        $faceSecrets = Read-FaceSecrets -RequireTemplateKey -RequireToken:$requireLocalToken
        $env:FACE_TEMPLATE_KEY = $faceSecrets.FACE_TEMPLATE_KEY
        if ($requireLocalToken) { $env:FACE_INTERNAL_TOKEN = $faceSecrets.FACE_INTERNAL_TOKEN }
    }
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
    & $MavenPath -B -ntp -pl admin -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '构建失败' }
    & java -jar admin/target/boot.jar
} finally {
    $env:FACE_TEMPLATE_KEY = $previousFaceKey
    $env:FACE_INTERNAL_TOKEN = $previousFaceToken
    Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BOOTSTRAP_ADMIN_ENABLED -ErrorAction SilentlyContinue
}
