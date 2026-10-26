param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')
$demoProject = Get-DemoProjectRoot
$demoManifest = Request-DemoStop $demoProject $RunId
$demoDocker = Resolve-DemoExecutable -Command 'docker.exe'
$demoResult = Stop-DemoResources $demoManifest $demoProject $demoDocker
if (-not $demoResult.Success) {
    foreach ($failure in $demoResult.Failures) { Write-Warning $failure }
    throw '本轮部分资源所有权未通过核对或未退出，已保留；没有清理其他资源。'
}
Write-Host ('演示 ' + $RunId + ' 已停止；本地运行资料与日志保留。')
