param(
    [string]$JavaHome,
    [string]$NodePath,
    [string]$PythonPath,
    [string]$DockerContext='desktop-linux',
    [int]$DatabasePort=13307,
    [int]$BackendPort=19091,
    [int]$AssistantPort=8011,
    [int]$FrontendPort=7001
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')
$demoProject = Get-DemoProjectRoot
$null = Assert-DemoNormalPath $demoProject
Assert-DemoPorts @($DatabasePort,$BackendPort,$AssistantPort,$FrontendPort)
$demoJavaPath = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { '' }
$demoJava = Resolve-DemoExecutable -Path $demoJavaPath -Command 'java.exe'
$demoNode = Resolve-DemoExecutable -Path $NodePath -Command 'node.exe'
if (-not $PythonPath) { $PythonPath = Join-Path $demoProject 'ai-service/.venv/Scripts/python.exe' }
$demoPython = Resolve-DemoExecutable -Path $PythonPath -Command 'python.exe'
$demoDocker = Resolve-DemoExecutable -Command 'docker.exe'
$demoJar = Join-Path $demoProject 'admin/target/boot.jar'
$demoVite = Join-Path $demoProject 'front/node_modules/vite/bin/vite.js'
$demoViteConfig = Join-Path $demoProject 'front/vite.demo.config.js'
$demoSeeder = Join-Path $demoProject 'scripts/demo/seed-data.py'
foreach ($required in @($demoJar,$demoVite,$demoViteConfig,$demoSeeder)) {
    $null = Assert-DemoNormalPath $required
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw '演示依赖尚未准备，请先按README构建和准备；启动器不会安装或构建。' }
}
Assert-DemoLocalDocker $demoDocker $DockerContext -RequireImage
$demoRunId = [Guid]::NewGuid().ToString('N')
$demoDirectory = New-DemoProtectedDirectory $demoProject $demoRunId
$demoManifestPath = Get-DemoManifestPath $demoProject $demoRunId
$demoManifest = [pscustomobject]@{
    formatVersion=$script:DemoFormat;runId=$demoRunId;projectRoot=$demoProject;dockerContext=$DockerContext
    containerId='';containerName=('attendance-demo-' + $demoRunId)
    ports=[pscustomobject]@{database=$DatabasePort;backend=$BackendPort;assistant=$AssistantPort;frontend=$FrontendPort}
    database=[pscustomobject]@{name='attendance_demo';username='root';password=(New-DemoSecret)}
    bootstrap=[pscustomobject]@{username=('demo_admin_' + $demoRunId.Substring(0,12));password=(New-DemoSecret)}
    assistantToken=(New-DemoSecret);jwtSecret=(New-DemoSecret);processes=@();ready=$false;ended=$false;stopRequested=$false
}
$demoChildren = New-Object 'Collections.Generic.List[object]'
$demoCleanup = $null
$demoOriginalFailure = $false
function Save-DemoChild {
    param($Child)
    $demoChildren.Add($Child)
    $demoManifest.processes = @($demoManifest.processes) + @($Child.Record)
    Write-DemoManifest $demoManifest $demoProject
}
function Wait-DemoHttp {
    param([string]$Url,[hashtable]$Headers=@{},[int]$Seconds=90)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($child in $demoChildren) {
            if ($child.Record.role -ne 'seed' -and $child.Process.HasExited) { throw ('演示组件提前退出：' + $child.Record.role + '，请查看本轮日志。') }
        }
        $response=$null
        try {
            $request=[Net.HttpWebRequest]::CreateHttp($Url);$request.Method='GET';$request.Accept='*/*';$request.Proxy=$null;$request.Timeout=2000
            foreach ($key in $Headers.Keys) { $request.Headers.Add($key,[string]$Headers[$key]) }
            $response=$request.GetResponse()
            if ([int]$response.StatusCode -eq 200) { return }
        } catch { } finally { if ($response) { $response.Dispose() } }
        Start-Sleep -Milliseconds 500
    }
    throw '演示组件未按时就绪，请查看本轮日志。'
}
try {
    Write-DemoManifest $demoManifest $demoProject
    $demoMysqlEnv = Join-Path $demoDirectory 'mysql.env'
    $demoMysqlText = 'MYSQL_ROOT_PASSWORD=' + $demoManifest.database.password + "`nMYSQL_ROOT_HOST=%`nMYSQL_DATABASE=attendance_demo`n"
    [IO.File]::WriteAllText($demoMysqlEnv,$demoMysqlText,[Text.UTF8Encoding]::new($false))
    $created = Invoke-DemoDocker $demoDocker $DockerContext @('run','--detach','--pull','never','--name',$demoManifest.containerName,
        '--label',($script:DemoProjectLabel + '=' + $demoProject),'--label',($script:DemoRunLabel + '=' + $demoRunId),
        '--publish',('127.0.0.1:' + $DatabasePort + ':3306'),'--env-file',$demoMysqlEnv,
        '--tmpfs','/var/lib/mysql:rw,nosuid,nodev,size=512m','--memory','768m','--cpus','1','mysql:8.4',
        '--character-set-server=utf8mb4','--collation-server=utf8mb4_0900_ai_ci','--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=64M','--performance-schema=OFF')
    $demoManifest.containerId=$created.Output.Trim()
    if ($demoManifest.containerId -cnotmatch '^[a-f0-9]{64}$') { throw 'Docker没有返回可核对的容器ID。' }
    Write-DemoManifest $demoManifest $demoProject
    $inspected = Invoke-DemoDocker $demoDocker $DockerContext @('inspect',$demoManifest.containerId)
    Assert-DemoContainerOwnership $demoManifest @($inspected.Output | ConvertFrom-Json)[0]
    $mysqlReady=$false; $mysqlDeadline=[DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $mysqlDeadline) {
        $ping = Invoke-DemoDocker $demoDocker $DockerContext @('exec',$demoManifest.containerId,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=attendance_demo --batch --skip-column-names -e "SELECT 1"') -AllowFailure -TimeoutSeconds 5
        if ($ping.ExitCode -eq 0 -and $ping.Output.Trim() -ceq '1') { $mysqlReady=$true;break }
        Start-Sleep -Milliseconds 1000
    }
    if (-not $mysqlReady) { throw '本轮MySQL未按时就绪。' }
    $schema = [IO.File]::ReadAllText((Join-Path $demoProject 'database/schema.sql'),[Text.Encoding]::UTF8)
    $seed = [IO.File]::ReadAllText((Join-Path $demoProject 'database/seed.sql'),[Text.Encoding]::UTF8)
    $null = Invoke-DemoDocker $demoDocker $DockerContext @('exec','-i',$demoManifest.containerId,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot attendance_demo') -InputText ($schema + "`n" + $seed) -TimeoutSeconds 60

    $assistantEnvironment = New-DemoEnvironment $demoDirectory $demoDocker @{
        ASSISTANT_ENABLED='true';ASSISTANT_PROVIDER='mock';ASSISTANT_INTERNAL_TOKEN=$demoManifest.assistantToken
        FACE_INTERNAL_TOKEN='';PYTHONPATH=(Join-Path $demoProject 'ai-service')
    }
    $child = Start-DemoChild 'assistant' $demoPython @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$AssistantPort",'--workers','1','--limit-concurrency','8','--timeout-keep-alive','5') $demoDirectory $assistantEnvironment $demoDirectory
    Save-DemoChild $child
    Wait-DemoHttp ('http://127.0.0.1:' + $AssistantPort + '/assistant/health') @{'X-Assistant-Service-Token'=$demoManifest.assistantToken}

    $backendEnvironment = New-DemoEnvironment $demoDirectory $demoDocker @{
        DB_URL=('jdbc:mysql://127.0.0.1:' + $DatabasePort + '/attendance_demo?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false')
        DB_USERNAME='root';DB_PASSWORD=$demoManifest.database.password;JWT_SECRET=$demoManifest.jwtSecret
        BOOTSTRAP_ADMIN_ENABLED='true';BOOTSTRAP_ADMIN_USERNAME=$demoManifest.bootstrap.username;BOOTSTRAP_ADMIN_PASSWORD=$demoManifest.bootstrap.password
        FACE_ENABLED='false';ASSISTANT_ENABLED='true';ASSISTANT_PROVIDER='mock';ASSISTANT_INTERNAL_TOKEN=$demoManifest.assistantToken
        ASSISTANT_URL=('http://127.0.0.1:' + $AssistantPort);SERVER_ADDRESS='127.0.0.1';SERVER_PORT="$BackendPort"
        FILE_STORAGE_PATH=(Join-Path $demoDirectory 'files');PUBLIC_BASE_URL=('http://127.0.0.1:' + $BackendPort)
        CORS_ALLOWED_ORIGINS=('http://127.0.0.1:' + $FrontendPort)
        REGISTRATION_ENABLED='false';REPORT_SCHEDULING_ENABLED='true';REPORT_MAX_ACTIVE_JOBS='3'
    }
    $child = Start-DemoChild 'backend' $demoJava @('-Xmx512m','-jar',$demoJar,'--spring.config.location=classpath:/application.yml','--server.address=127.0.0.1',('--server.port=' + $BackendPort)) $demoDirectory $backendEnvironment $demoDirectory
    Save-DemoChild $child
    Wait-DemoHttp ('http://127.0.0.1:' + $BackendPort + '/health') -Seconds 120

    # 仅初始化器需要读取本机Docker context；不继承父DOCKER_CONFIG或HOST。
    $seedEnvironment = New-DemoEnvironment $demoDirectory $demoDocker @{DOCKER_CONFIG=(Join-Path $env:USERPROFILE '.docker')}
    $child = Start-DemoChild 'seed' $demoPython @($demoSeeder,'--manifest',$demoManifestPath) $demoDirectory $seedEnvironment $demoDirectory
    Save-DemoChild $child
    if (-not $child.Process.WaitForExit(180000)) { throw '合成数据初始化超时，请查看本轮日志。' }
    if ($child.Process.ExitCode -ne 0) { throw '合成数据初始化失败，请查看本轮日志。' }
    # 初始化器可能补充一次性标记，重新读取后再记录ready。
    $demoManifest = Read-DemoManifest $demoProject $demoRunId
    $demoManifest.processes = @($demoManifest.processes | Where-Object { $_.role -ne 'seed' })
    Write-DemoManifest $demoManifest $demoProject
    $frontendEnvironment = New-DemoEnvironment $demoDirectory $demoDocker @{
        ATTENDANCE_DEMO_ENV_DIR=(Join-Path $demoDirectory 'vite-env');VITE_SERVER_URL=('127.0.0.1:' + $BackendPort);VITE_DEMO_PORT="$FrontendPort"
        VITE_AMAP_KEY='';VITE_AMAP_SECURITY_CODE=''
    }
    $child = Start-DemoChild 'frontend' $demoNode @($demoVite,'--config',$demoViteConfig) $demoDirectory $frontendEnvironment $demoDirectory
    Save-DemoChild $child
    Wait-DemoHttp ('http://127.0.0.1:' + $FrontendPort + '/')
    $demoManifest.ready=$true;Write-DemoManifest $demoManifest $demoProject
    Write-Host ('合成演示已就绪（Mock非真实AI，人脸关闭）：http://127.0.0.1:' + $FrontendPort)
    Write-Host ('RunId：' + $demoRunId)
    Write-Host ('账号密码仅在本地文件：' + (Join-Path $demoDirectory 'accounts.json'))
    Write-Host ('演示月份与样本说明：' + (Join-Path $demoDirectory 'demo-context.json'))
    Write-Host '此终端保持运行；Ctrl+C停止本轮并保留日志，也可在另一终端运行stop-demo.ps1 -RunId。'
    while ($true) {
        if ((Get-DemoMonitorState $demoProject $demoRunId) -eq 'STOP_REQUESTED') { Write-Host '收到本轮停止请求，开始正常收尾。';return }
        foreach ($item in $demoChildren) {
            if ($item.Record.role -ne 'seed' -and $item.Process.HasExited) {
                if ((Get-DemoMonitorState $demoProject $demoRunId -ComponentExited $true -Role $item.Record.role) -eq 'STOP_REQUESTED') { Write-Host '收到本轮停止请求，开始正常收尾。';return }
            }
        }
        Start-Sleep -Milliseconds 500
    }
} catch {
    $demoOriginalFailure=$true
    Write-Host ('演示启动或运行失败：' + $_.Exception.Message)
    throw
} finally {
    try { $demoCleanup = Stop-DemoResources $demoManifest $demoProject $demoDocker }
    catch { $demoCleanup=[pscustomobject]@{Success=$false;Failures=@('本轮资源回收发生错误，已保留运行资料供核对。')} }
    foreach ($item in $demoChildren) { try { Close-DemoChildHandles $item } catch { Write-Warning '本轮日志句柄未能全部关闭。' } }
    if ($demoCleanup.Success) { Write-Host ('本轮资源已停止，日志保留于：' + $demoDirectory) }
    else {
        foreach ($failure in $demoCleanup.Failures) { Write-Warning $failure }
        Write-Warning ('本轮有资源未能安全回收，保留manifest；核对后可重试stop-demo.ps1 -RunId ' + $demoRunId)
        if (-not $demoOriginalFailure) { throw '本轮资源未全部回收，停止操作未成功。' }
    }
}
