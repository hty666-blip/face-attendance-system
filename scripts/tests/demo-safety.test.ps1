# 不依赖Docker服务、数据库、JAR或测试框架；不运行完整演示。
$ErrorActionPreference='Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'demo-common.ps1')
$script:DemoSafetyCount=0
function Assert-Safety {
    param([bool]$Condition,[string]$Message)
    if (-not $Condition) { throw ('FAIL: ' + $Message) }
    $script:DemoSafetyCount++
}
function Assert-SafetyThrows {
    param([scriptblock]$Operation,[string]$Message)
    $threw=$false
    try { & $Operation } catch { $threw=$true }
    Assert-Safety $threw $Message
}
function Write-SafetyFixtureDiagnostic {
    param($Child,[string]$ErrorPath,[string]$StagePath,[string]$ReleasePath)
    $code=if($Child.Process.HasExited){[string]$Child.Process.ExitCode}else{'still-running'}
    $text=''
    if (Test-Path -LiteralPath $ErrorPath -PathType Leaf) {
        $inputStream=$null;$reader=$null
        try {
            $inputStream=[IO.File]::Open($ErrorPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            $reader=[IO.StreamReader]::new($inputStream,[Text.Encoding]::UTF8)
            $text=$reader.ReadToEnd()
            if($text.Length -gt 2000){$text=$text.Substring(0,2000)}
            $text=$text -replace '[\r\n]+',' '
        } catch { $text='controlled fixture log unavailable' }
        finally { if($reader){$reader.Dispose()}elseif($inputStream){$inputStream.Dispose()} }
    }
    Write-Host ('Controlled lock fixture: exit=' + $code + '; stderr=' + $text)
    $stages='unavailable'
    try {
        if($StagePath -and [IO.File]::Exists($StagePath)){
            $allowed=@('ready-written','sleep-before','sleep-after','release-observed','lock-disposed','stdout-written','stderr-written','returning')
            $known=@([IO.File]::ReadAllLines($StagePath) | Where-Object { $allowed -ccontains $_ })
            $stages=$known -join ','
        }
    }catch{}
    $released=$ReleasePath -and [IO.File]::Exists($ReleasePath)
    Write-Host ('Controlled lock stages=[' + $stages + ']; release=' + $released + '; stdoutTask=' + $Child.OutTask.Status + '; stderrTask=' + $Child.ErrTask.Status)
}
$testRoot=Get-DemoProjectRoot
$testRun=[Guid]::NewGuid().ToString('N')
$testDirectory=$null
$testChild=$null
$previousDb=[Environment]::GetEnvironmentVariable('DB_URL','Process')
$previousJava=[Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS','Process')
$previousKey=[Environment]::GetEnvironmentVariable('ASSISTANT_API_KEY','Process')
$previousDocker=[Environment]::GetEnvironmentVariable('DOCKER_HOST','Process')
try {
    Assert-SafetyThrows { Assert-DemoRunId '../outside' } '拒绝RunId路径穿越'
    Assert-SafetyThrows { Assert-DemoRunId ('A' * 32) } '拒绝非规范RunId'
    Assert-SafetyThrows { Assert-DemoNormalPath '\\remote\share\demo' } '拒绝网络目录'
    Assert-SafetyThrows { Assert-DemoPorts @(13307,13307,8011,7001) -SkipAvailability } '拒绝重复端口'
    Assert-SafetyThrows { Assert-DemoPorts @(80,19091,8011,7001) -SkipAvailability } '拒绝低位端口'
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try {
        $listener.Start();$busyPort=$listener.LocalEndpoint.Port
        $others=@(20001,20002,20003,20004) | Where-Object { $_ -ne $busyPort } | Select-Object -First 3
        Assert-SafetyThrows { Assert-DemoPorts (@($busyPort) + @($others)) } '端口冲突拒绝且不终止占用者'
        Assert-Safety ($listener.Server.IsBound) '冲突检查后占用者仍在'
    } finally { $listener.Stop() }

    Assert-DemoDockerEndpoint 'npipe:////./pipe/dockerDesktopLinuxEngine'
    Assert-Safety $true '接受本机named pipe'
    foreach ($endpoint in @('tcp://127.0.0.1:2375','ssh://server','unix:///var/run/docker.sock','npipe:////server/pipe/docker','https://remote.example')) {
        Assert-SafetyThrows { Assert-DemoDockerEndpoint $endpoint } '拒绝非本机named pipe引擎'
    }

    $testDirectory=New-DemoProtectedDirectory $testRoot $testRun
    Assert-Safety (Test-Path -LiteralPath (Join-Path $testDirectory 'vite-env') -PathType Container) '独立空Vite环境目录'
    Assert-SafetyThrows { New-DemoProtectedDirectory $testRoot $testRun } '拒绝复用运行目录'
    $acl=Get-Acl -LiteralPath $testDirectory
    Assert-Safety ($acl.AreAccessRulesProtected) '运行目录不继承广泛权限'
    $heldLock=Enter-DemoStopLock $testRoot $testRun -TimeoutMilliseconds 100
    try { Assert-SafetyThrows { Enter-DemoStopLock $testRoot $testRun -TimeoutMilliseconds 75 } '同轮排他锁不允许重入并有限退出' }
    finally { $heldLock.Dispose() }
    $releasedLock=Enter-DemoStopLock $testRoot $testRun -TimeoutMilliseconds 100
    Assert-Safety ($releasedLock.CanWrite) '释放锁后可再次获取'
    $releasedLock.Dispose()
    $junction=Join-Path $testDirectory 'junction'
    $keepFile=Join-Path $testDirectory 'files/keep.txt'
    [IO.File]::WriteAllText($keepFile,'synthetic-marker')
    try {
        $null=New-Item -ItemType Junction -Path $junction -Value (Join-Path $testDirectory 'files') -ErrorAction Stop
        Assert-SafetyThrows { Assert-DemoNormalPath (Join-Path $junction 'manifest.json') } '拒绝路径祖先重解析点'
    } finally { if (Test-Path -LiteralPath $junction) { [IO.Directory]::Delete($junction,$false) } }
    Assert-Safety (Test-Path -LiteralPath $keepFile -PathType Leaf) '移除测试重解析点保留真实目标'

    $env:DB_URL='synthetic-parent-db-must-not-be-inherited'
    $env:JAVA_TOOL_OPTIONS='synthetic-parent-options'
    $env:ASSISTANT_API_KEY='synthetic-parent-model-key'
    $env:DOCKER_HOST='tcp://remote.invalid:2375'
    $environment=New-DemoEnvironment $testDirectory '' @{FACE_ENABLED='false';ASSISTANT_PROVIDER='mock'}
    foreach ($key in @('DB_URL','JAVA_TOOL_OPTIONS','ASSISTANT_API_KEY','DOCKER_HOST','SPRING_APPLICATION_JSON','ASSISTANT_BASE_URL','FACE_TEMPLATE_KEY')) {
        Assert-Safety (-not $environment.ContainsKey($key)) ('不继承父环境 ' + $key)
    }
    Assert-Safety ($environment.ASSISTANT_PROVIDER -ceq 'mock' -and $environment.LANGSMITH_TRACING -ceq 'false') 'Mock和追踪策略显式固定'
    $info=New-DemoProcessInfo 'C:\synthetic.exe' @('x') $testDirectory $environment
    Assert-Safety (-not $info.UseShellExecute -and $info.CreateNoWindow -and -not $info.EnvironmentVariables.ContainsKey('DB_URL')) '直接进程使用清空后的环境'
    Assert-Safety ((ConvertTo-DemoArgument '') -ceq '""') '空参数转义'
    Assert-Safety ((ConvertTo-DemoArgument 'a"b') -ceq '"a\"b"') '双引号参数转义'
    Assert-Safety ((ConvertTo-DemoArgument 'C:\folder\') -ceq '"C:\folder\\"') '尾部反斜杠转义'

    $manifest=[pscustomobject]@{formatVersion='attendance-demo-v1';runId=$testRun;projectRoot=$testRoot;dockerContext='desktop-linux';containerId='';containerName=('attendance-demo-' + $testRun);
        database=[pscustomobject]@{name='attendance_demo';username='root';password='synthetic-not-used'};
        ports=[pscustomobject]@{database=13307;backend=19091;assistant=8011;frontend=7001};processes=@();ready=$false;ended=$false}
    Write-DemoManifest $manifest $testRoot
    $loaded=Read-DemoManifest $testRoot $testRun
    Assert-Safety ($loaded.runId -ceq $testRun) '安全manifest可往返'
    Assert-Safety ((Get-DemoMonitorState $testRoot $testRun) -ceq 'RUNNING') '旧manifest无停止字段时继续守护'
    Assert-SafetyThrows { Get-DemoMonitorState $testRoot $testRun -ComponentExited $true -Role 'synthetic' } '非主动组件退出仍报告错误'
    $requested=Request-DemoStop $testRoot $testRun
    Assert-Safety ($requested.stopRequested -and (Get-DemoMonitorState $testRoot $testRun) -ceq 'STOP_REQUESTED') '主动停止信号持久可见'
    Assert-Safety ((Get-DemoMonitorState $testRoot $testRun -ComponentExited $true) -ceq 'STOP_REQUESTED') '主动停止后的组件退出正常收尾'
    $signalCleanup=Stop-DemoResources $manifest $testRoot ''
    $signalPreserved=Read-DemoManifest $testRoot $testRun
    Assert-Safety ($signalCleanup.Success -and $signalPreserved.stopRequested) '并发缓存manifest收尾保留已发布停止信号'
    $badManifest=$manifest | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $badManifest.projectRoot='C:\outside'
    Assert-SafetyThrows { Write-DemoManifest $badManifest $testRoot } '拒绝越界项目manifest写入'
    $loaded.formatVersion='wrong'
    Write-DemoManifest $loaded $testRoot
    Assert-SafetyThrows { Read-DemoManifest $testRoot $testRun } '拒绝错误manifest版本'
    Write-DemoManifest $manifest $testRoot

    $ownerManifest=[pscustomobject]@{runId=$testRun;projectRoot=$testRoot;containerName=('attendance-demo-' + $testRun);containerId=('a' * 64)}
    $container=[pscustomobject]@{Id=('a' * 64);Name=('/attendance-demo-' + $testRun);Config=[pscustomobject]@{Labels=[pscustomobject]@{'com.face-attendance.demo.project'=$testRoot;'com.face-attendance.demo.run'=$testRun}};
        HostConfig=[pscustomobject]@{Tmpfs=[pscustomobject]@{'/var/lib/mysql'='rw,nosuid,nodev,size=512m'}};Mounts=@()}
    Assert-DemoContainerOwnership $ownerManifest $container
    Assert-Safety $true '接受ID名称标签和固定tmpfs一致的容器'
    $container.Mounts=@([pscustomobject]@{Type='tmpfs';Destination='/var/lib/mysql'})
    Assert-DemoContainerOwnership $ownerManifest $container
    Assert-Safety $true '兼容Docker显式tmpfs Mounts'
    $container.Mounts=@([pscustomobject]@{Type='volume';Destination='/var/lib/mysql'})
    Assert-SafetyThrows { Assert-DemoContainerOwnership $ownerManifest $container } '拒绝匿名或命名数据卷'
    $container.Mounts=@();$container.Config.Labels.'com.face-attendance.demo.run'=('b' * 32)
    Assert-SafetyThrows { Assert-DemoContainerOwnership $ownerManifest $container } '拒绝标签不符的其他容器'
    $container.Config.Labels.'com.face-attendance.demo.run'=$testRun;$container.Id=('b' * 64)
    Assert-SafetyThrows { Assert-DemoContainerOwnership $ownerManifest $container } '拒绝不可变ID不符的容器'
    $container.Id=$ownerManifest.containerId
    $script:DemoRaceLookupCount=0
    $script:DemoRaceOutcome='missing'
    $dockerRace={param($executable,$context,$arguments)
        if ($arguments[0] -ceq 'container') {
            if ($arguments[-1] -cne ('id=' + $ownerManifest.containerId)) { throw 'race查询了其他容器ID' }
            $script:DemoRaceLookupCount++
            if ($script:DemoRaceLookupCount -gt 1) {
                if ($script:DemoRaceOutcome -ceq 'engine-error') { throw 'synthetic engine connection error' }
                if ($script:DemoRaceOutcome -ceq 'missing') { return [pscustomobject]@{Output=''} }
            }
            return [pscustomobject]@{Output=$ownerManifest.containerId}
        }
        if ($arguments[0] -ceq 'inspect') { return [pscustomobject]@{Output=($container | ConvertTo-Json -Depth 10)} }
        if ($arguments[0] -ceq 'rm') { throw 'synthetic concurrent rm failure' }
        throw 'unexpected Docker operation'
    }
    Remove-DemoOwnedContainer $ownerManifest 'synthetic.exe' -DockerCommand $dockerRace
    Assert-Safety ($script:DemoRaceLookupCount -eq 2) '删除失败后同一不可变ID确认消失视为幂等成功'
    $script:DemoRaceLookupCount=0;$script:DemoRaceOutcome='present'
    Assert-SafetyThrows { Remove-DemoOwnedContainer $ownerManifest 'synthetic.exe' -DockerCommand $dockerRace } '删除失败且容器仍存在保持失败'
    $script:DemoRaceLookupCount=0;$script:DemoRaceOutcome='engine-error'
    Assert-SafetyThrows { Remove-DemoOwnedContainer $ownerManifest 'synthetic.exe' -DockerCommand $dockerRace } '删除失败后引擎连接错误不能冒充资源不存在'

    $identity=Get-DemoProcessIdentity $PID
    $record=[pscustomobject]@{role='safety';pid=$identity.pid;startTimeUtcTicks=$identity.startTimeUtcTicks;executablePath=$identity.executablePath}
    Assert-DemoProcessOwnership $record $identity
    Assert-Safety $true '进程三项所有权匹配'
    Stop-DemoOwnedProcess $record -IdentityReader {param($childId) return $identity} -ProcessReader {param($childId) return $null}
    Assert-Safety $true '核对身份后获取进程前已退出视为已停止'
    $fakeProcess=[pscustomobject]@{Id=$identity.pid;StartTime=[DateTime]::new([long]$identity.startTimeUtcTicks,[DateTimeKind]::Utc);Path=$identity.executablePath;Disposed=$false}
    $fakeProcess | Add-Member ScriptMethod Refresh { }
    $fakeProcess | Add-Member ScriptMethod Kill { throw 'synthetic concurrent process exit' }
    $fakeProcess | Add-Member ScriptMethod Dispose { $this.Disposed=$true }
    $script:DemoRaceIdentityCount=0
    $exitDuringKill={param($childId) $script:DemoRaceIdentityCount++;if($script:DemoRaceIdentityCount -eq 1){return $identity};return $null}
    Stop-DemoOwnedProcess $record -IdentityReader $exitDuringKill -ProcessReader {param($childId) return $fakeProcess}
    Assert-Safety ($script:DemoRaceIdentityCount -eq 2 -and $fakeProcess.Disposed) '终止间隙退出且再次查明不存在视为成功'
    Assert-SafetyThrows { Stop-DemoOwnedProcess $record -IdentityReader {param($childId) return $identity} -ProcessReader {param($childId) return $fakeProcess} } '终止失败但进程仍存在保持失败'
    $script:DemoRaceIdentityCount=0
    $reusedDuringKill={param($childId)
        $script:DemoRaceIdentityCount++
        if($script:DemoRaceIdentityCount -eq 1){return $identity}
        return [pscustomobject]@{pid=$childId;startTimeUtcTicks=([long]$identity.startTimeUtcTicks+1).ToString();executablePath=$identity.executablePath}
    }
    Assert-SafetyThrows { Stop-DemoOwnedProcess $record -IdentityReader $reusedDuringKill -ProcessReader {param($childId) return $fakeProcess} } '终止竞态中PID复用仍拒绝'
    $record.startTimeUtcTicks=([long]$identity.startTimeUtcTicks-1).ToString()
    Assert-SafetyThrows { Assert-DemoProcessOwnership $record $identity } '拒绝PID复用创建时间不符'
    $record.startTimeUtcTicks=$identity.startTimeUtcTicks;$record.executablePath='C:\different.exe'
    Assert-SafetyThrows { Assert-DemoProcessOwnership $record $identity } '拒绝可执行路径不符'
    $incomplete=[pscustomobject]@{role='seed';pid=123456;startTimeUtcTicks='';executablePath=$null}
    Assert-DemoProcessOwnership $incomplete $null
    Assert-Safety $true '已退出PID的不完整旧记录不阻塞幂等清理'
    Assert-SafetyThrows { Assert-DemoProcessOwnership $incomplete $identity } '存活PID仍拒绝不完整记录'
    $syntheticStart=[DateTime]::UtcNow
    $syntheticProcess=[pscustomobject]@{Id=123456;StartTime=$syntheticStart;HasExited=$false}
    $script:DemoIdentityReads=0
    $eventuallyReady={param($childId)
        $script:DemoIdentityReads++
        $path=if($script:DemoIdentityReads -gt 1){'C:\synthetic.exe'}else{$null}
        return [pscustomobject]@{pid=$childId;startTimeUtcTicks=[string]$syntheticStart.Ticks;executablePath=$path}
    }
    $readyRecord=Wait-DemoChildIdentity $syntheticProcess 'C:\synthetic.exe' 'synthetic' -IdentityReader $eventuallyReady
    Assert-Safety ($script:DemoIdentityReads -ge 2 -and $readyRecord.executablePath -ceq 'C:\synthetic.exe') '启动路径短暂为空时轮询至完整才记录'
    $neverReady={param($childId) return [pscustomobject]@{pid=$childId;startTimeUtcTicks=[string]$syntheticStart.Ticks;executablePath=$null}}
    Assert-SafetyThrows { Wait-DemoChildIdentity $syntheticProcess 'C:\synthetic.exe' 'synthetic' -TimeoutMilliseconds 60 -IdentityReader $neverReady } '持续空路径的启动登记失败'
    $wrongPath={param($childId) return [pscustomobject]@{pid=$childId;startTimeUtcTicks=[string]$syntheticStart.Ticks;executablePath='C:\different.exe'}}
    Assert-SafetyThrows { Wait-DemoChildIdentity $syntheticProcess 'C:\synthetic.exe' 'synthetic' -IdentityReader $wrongPath } '启动进程必须与指定exe一致'
    $manifest.processes=@($record)
    $denied=Stop-DemoResources $manifest $testRoot ''
    Assert-Safety (-not $denied.Success -and -not $manifest.ended) '所有权不符时不谎报清理成功'
    Assert-Safety ($null -ne (Get-Process -Id $PID -ErrorAction SilentlyContinue)) '所有权不符后父测试进程仍存在'
    $manifest.processes=@()
    $first=Stop-DemoResources $manifest $testRoot ''
    $second=Stop-DemoResources $manifest $testRoot ''
    Assert-Safety ($first.Success -and $second.Success -and $manifest.ended) '未启动资源的停止和重复停止幂等'

    # 只启动一个无业务、无子进程的PowerShell夹具，验证实际Windows参数和环境传递。
    $fixture=Join-Path $testDirectory 'argument-fixture.ps1'
    $fixtureText=@'
param([string]$Value,[string]$StopLockPath,[string]$ReadyPath,[string]$ReleasePath,[string]$StagePath)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
if($env:DB_URL){exit 31}
function Write-FixtureStage([string]$Name){if($StagePath){[IO.File]::AppendAllText($StagePath,$Name+[Environment]::NewLine)}}
if($StopLockPath){
    $hold=[IO.File]::Open($StopLockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try{
        [IO.File]::WriteAllText($ReadyPath,'ready')
        Write-FixtureStage 'ready-written'
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        $firstSleep=$true
        while(-not [IO.File]::Exists($ReleasePath)){
            if([DateTime]::UtcNow -ge $deadline){[Console]::Error.Write('synthetic release handshake timeout');exit 32}
            if($firstSleep){Write-FixtureStage 'sleep-before'}
            [Threading.Thread]::Sleep(25)
            if($firstSleep){Write-FixtureStage 'sleep-after';$firstSleep=$false}
        }
        Write-FixtureStage 'release-observed'
    }finally{$hold.Dispose()}
    Write-FixtureStage 'lock-disposed'
}
[Console]::Out.Write([string]$Value)
Write-FixtureStage 'stdout-written'
[Console]::Error.Write('stderr-ok')
Write-FixtureStage 'stderr-written'
Write-FixtureStage 'returning'
'@
    [IO.File]::WriteAllText($fixture,$fixtureText,[Text.UTF8Encoding]::new($false))
    $executable=Resolve-DemoExecutable -Command 'powershell.exe'
    $argumentValue='space "quote" C:\trailing\ 中文'
    $testChild=Start-DemoChild 'safety' $executable @('-NoProfile','-NonInteractive','-File',$fixture,'-Value',$argumentValue) $testDirectory $environment $testDirectory
    Assert-Safety (-not [string]::IsNullOrEmpty($testChild.Record.executablePath) -and [string]::Equals($testChild.Record.executablePath,$executable,[StringComparison]::OrdinalIgnoreCase)) '真实启动不记录空或不匹配进程路径'
    Assert-Safety ($testChild.Process.WaitForExit(20000) -and $testChild.Process.ExitCode -eq 0) '实际子进程不会继承父业务环境'
    $null=$testChild.OutTask.Wait(3000);$null=$testChild.ErrTask.Wait(3000)
    $testChild.OutFile.Flush();$testChild.ErrFile.Flush()
    Close-DemoChildHandles $testChild;$testChild=$null
    $actual=[IO.File]::ReadAllText((Join-Path $testDirectory 'logs/safety.out.log'),[Text.Encoding]::UTF8)
    Assert-Safety ($actual -ceq $argumentValue) '实际Windows中文空格引号参数原样往返'
    $errorText=[IO.File]::ReadAllText((Join-Path $testDirectory 'logs/safety.err.log'),[Text.Encoding]::UTF8)
    Assert-Safety ($errorText -ceq 'stderr-ok') 'stdout/stderr异步日志均完成'
    $lockReady=Join-Path $testDirectory 'lock-ready.txt'
    $lockRelease=Join-Path $testDirectory 'lock-release.txt'
    $lockStages=Join-Path $testDirectory 'lock-stages.txt'
    $lockErrorPath=Join-Path $testDirectory 'logs/safety-lock.err.log'
    $testChild=Start-DemoChild 'safety-lock' $executable @('-NoProfile','-NonInteractive','-File',$fixture,'-Value','synthetic-lock','-StopLockPath',(Join-Path $testDirectory '.stop.lock'),'-ReadyPath',$lockReady,'-ReleasePath',$lockRelease,'-StagePath',$lockStages) $testDirectory $environment $testDirectory
    $lockDeadline=[DateTime]::UtcNow.AddSeconds(25)
    while (-not (Test-Path -LiteralPath $lockReady) -and -not $testChild.Process.HasExited -and [DateTime]::UtcNow -lt $lockDeadline) { Start-Sleep -Milliseconds 25 }
    if(-not (Test-Path -LiteralPath $lockReady)){Write-SafetyFixtureDiagnostic $testChild $lockErrorPath $lockStages $lockRelease}
    Assert-Safety (Test-Path -LiteralPath $lockReady -PathType Leaf) '受控子进程已持有同轮停止锁'
    Assert-SafetyThrows { Enter-DemoStopLock $testRoot $testRun -TimeoutMilliseconds 100 } '跨进程锁阻止并行清理'
    [IO.File]::WriteAllText($lockRelease,'release')
    $lockExited=$testChild.Process.WaitForExit(25000)
    if(-not $lockExited -or $testChild.Process.ExitCode -ne 0){Write-SafetyFixtureDiagnostic $testChild $lockErrorPath $lockStages $lockRelease}
    Assert-Safety $lockExited '释放信号后持锁子进程按时退出'
    Assert-Safety ($testChild.Process.ExitCode -eq 0) '持锁子进程正常退出码'
    Close-DemoChildHandles $testChild;$testChild=$null
    $afterChildLock=Enter-DemoStopLock $testRoot $testRun -TimeoutMilliseconds 100
    Assert-Safety ($afterChildLock.CanWrite) '跨进程释放后可重入清理'
    $afterChildLock.Dispose()
    Write-Host ('PASS: ' + $script:DemoSafetyCount + ' demo safety checks; no Docker/database/full demo was started.')
} catch {
    Write-Output ('Safety test stopped after ' + $script:DemoSafetyCount + ' checks.')
    throw
} finally {
    [Environment]::SetEnvironmentVariable('DB_URL',$previousDb,'Process')
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS',$previousJava,'Process')
    [Environment]::SetEnvironmentVariable('ASSISTANT_API_KEY',$previousKey,'Process')
    [Environment]::SetEnvironmentVariable('DOCKER_HOST',$previousDocker,'Process')
    if ($testChild) {
        if (-not $testChild.Process.HasExited) { $testChild.Process.Kill();$testChild.Process.WaitForExit() }
        Close-DemoChildHandles $testChild
    }
    if ($testDirectory -and (Test-Path -LiteralPath $testDirectory)) {
        # 仅删除本测试明确创建的文件和空目录，无递归删除。
        foreach ($relative in @('manifest.json','argument-fixture.ps1','logs/safety.out.log','logs/safety.err.log','logs/safety-lock.out.log','logs/safety-lock.err.log','lock-ready.txt','lock-release.txt','lock-stages.txt','.stop.lock','files/keep.txt')) {
            $testFile=Join-Path $testDirectory $relative
            if (Test-Path -LiteralPath $testFile) { Remove-Item -LiteralPath $testFile -Force }
        }
        foreach ($relative in @('logs','temp','appdata','files','vite-env')) {
            $emptyDirectory=Assert-DemoNormalPath (Join-Path $testDirectory $relative)
            try { [IO.Directory]::Delete($emptyDirectory,$false) } catch { }
        }
        try { [IO.Directory]::Delete((Assert-DemoNormalPath $testDirectory),$false) } catch { }
    }
}
