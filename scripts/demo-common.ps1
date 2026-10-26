# 演示入口专用。此文件不会读取开发库、共享密钥或父进程业务环境。
$script:DemoFormat = 'attendance-demo-v1'
$script:DemoProjectLabel = 'com.face-attendance.demo.project'
$script:DemoRunLabel = 'com.face-attendance.demo.run'

function Get-DemoProjectRoot {
    return [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
}

function Assert-DemoNormalPath {
    param([Parameter(Mandatory=$true)][string]$Path)
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute -notmatch '^[A-Za-z]:\\') { throw '演示路径必须位于本机Windows盘符，不能使用网络路径。' }
    $current = $absolute
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw '演示路径不能经过符号链接或重解析目录。' }
        }
        $parent = Split-Path -Parent $current
        if (-not $parent -or $parent -eq $current) { break }
        $current = $parent
    }
    return $absolute
}

function Assert-DemoRunId {
    param([Parameter(Mandatory=$true)][string]$RunId)
    if ($RunId -cnotmatch '^[a-f0-9]{32}$') { throw 'RunId必须是32位小写十六进制编号。' }
}

function Get-DemoManifestPath {
    param([Parameter(Mandatory=$true)][string]$ProjectRoot,[Parameter(Mandatory=$true)][string]$RunId)
    Assert-DemoRunId $RunId
    $root = (Assert-DemoNormalPath $ProjectRoot).TrimEnd('\')
    return Assert-DemoNormalPath (Join-Path $root ('.local/demo/' + $RunId + '/manifest.json'))
}

function New-DemoProtectedDirectory {
    param([Parameter(Mandatory=$true)][string]$ProjectRoot,[Parameter(Mandatory=$true)][string]$RunId)
    $manifestPath = Get-DemoManifestPath $ProjectRoot $RunId
    $runDirectory = Split-Path -Parent $manifestPath
    if (Test-Path -LiteralPath $runDirectory) { throw '本轮运行目录已存在，禁止复用或覆盖。' }
    $parent = Split-Path -Parent $runDirectory
    if (-not (Test-Path -LiteralPath $parent)) { $null = New-Item -ItemType Directory -Path $parent -Force -ErrorAction Stop }
    $null = Assert-DemoNormalPath $parent
    $directory = New-Item -ItemType Directory -Path $runDirectory -ErrorAction Stop
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true,$false)
    $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $propagation = [Security.AccessControl.PropagationFlags]::None
    foreach ($identity in @($owner,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl',$inheritance,$propagation,'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $directory.FullName -AclObject $acl -ErrorAction Stop
    foreach ($name in @('logs','temp','appdata','files','vite-env')) { $null = New-Item -ItemType Directory -Path (Join-Path $runDirectory $name) -ErrorAction Stop }
    return $runDirectory
}

function New-DemoSecret {
    $buffer = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($buffer)
}

function Write-DemoManifest {
    param([Parameter(Mandatory=$true)]$Manifest,[Parameter(Mandatory=$true)][string]$ProjectRoot)
    if (-not [string]::Equals([string]$Manifest.projectRoot,$ProjectRoot,[StringComparison]::OrdinalIgnoreCase)) { throw '运行资料的项目路径不匹配。' }
    $path = Get-DemoManifestPath $ProjectRoot ([string]$Manifest.runId)
    $temporary = Join-Path (Split-Path -Parent $path) ('.manifest.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Manifest | ConvertTo-Json -Depth 15))
    $stream = [IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
    # NTFS同目录替换保持目标始终存在，避免监护读取与停止写入间出现缺文件窗口。
    for ($attempt=0;$attempt -lt 20;$attempt++) {
        try {
            if ([IO.File]::Exists($path)) { [IO.File]::Replace($temporary,$path,[NullString]::Value) }
            else { [IO.File]::Move($temporary,$path) }
            return
        } catch {
            $baseError=$_.Exception.GetBaseException()
            if (($baseError -isnot [IO.IOException] -and $baseError -isnot [UnauthorizedAccessException]) -or $attempt -eq 19) { throw }
            Start-Sleep -Milliseconds 50
        }
    }
}

function Read-DemoManifest {
    param([Parameter(Mandatory=$true)][string]$ProjectRoot,[Parameter(Mandatory=$true)][string]$RunId)
    $path = Get-DemoManifestPath $ProjectRoot $RunId
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw '指定运行的manifest不存在。' }
    $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    if ($manifest.formatVersion -ne $script:DemoFormat -or $manifest.runId -cne $RunId -or
        -not [string]::Equals([string]$manifest.projectRoot,$ProjectRoot,[StringComparison]::OrdinalIgnoreCase)) { throw '运行资料版本、编号或项目路径不匹配。' }
    if ($manifest.containerName -cne ('attendance-demo-' + $RunId)) { throw '运行容器名称不匹配。' }
    if ($manifest.containerId -and [string]$manifest.containerId -cnotmatch '^[a-f0-9]{64}$') { throw '运行容器ID格式不完整，拒绝推断所有权。' }
    if ($manifest.database.name -cne 'attendance_demo' -or $manifest.database.username -cne 'root') { throw '演示数据库契约不匹配。' }
    Assert-DemoPorts @($manifest.ports.database,$manifest.ports.backend,$manifest.ports.assistant,$manifest.ports.frontend) -SkipAvailability
    return $manifest
}

function Request-DemoStop {
    param([string]$ProjectRoot,[string]$RunId)
    $manifest=Read-DemoManifest $ProjectRoot $RunId
    if (-not $manifest.PSObject.Properties['stopRequested']) { $manifest | Add-Member NoteProperty stopRequested $false }
    $manifest.stopRequested=$true
    Write-DemoManifest $manifest $ProjectRoot
    return $manifest
}

function Get-DemoMonitorState {
    param([string]$ProjectRoot,[string]$RunId,[bool]$ComponentExited=$false,[string]$Role='组件')
    $manifest=Read-DemoManifest $ProjectRoot $RunId
    $signal=$manifest.PSObject.Properties['stopRequested']
    if ($signal -and $signal.Value -is [bool] -and $signal.Value) { return 'STOP_REQUESTED' }
    if ($ComponentExited) { throw ('演示组件已退出：' + $Role + '，开始回收本轮资源。') }
    return 'RUNNING'
}

function Assert-DemoPorts {
    param([Parameter(Mandatory=$true)][int[]]$Ports,[switch]$SkipAvailability)
    if ($Ports.Count -ne 4 -or @($Ports | Select-Object -Unique).Count -ne 4) { throw '演示的四个端口必须互不相同。' }
    foreach ($port in $Ports) {
        if ($port -lt 1024 -or $port -gt 65535) { throw '演示端口必须在1024至65535之间。' }
        if (-not $SkipAvailability) {
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
            try { $listener.Start() } catch { throw ('演示端口 ' + $port + ' 已占用或不可用，不会终止占用进程。') }
            finally { $listener.Stop() }
        }
    }
}

function New-DemoEnvironment {
    param([Parameter(Mandatory=$true)][string]$RunDirectory,[string]$DockerPath,[hashtable]$Extra=@{})
    # 从空白构造；绝不继承DB_、SPRING_、JAVA_TOOL_OPTIONS、模型Key或追踪开关。
    $environment = @{
        SystemRoot=$env:SystemRoot; WINDIR=$env:SystemRoot
        TEMP=(Join-Path $RunDirectory 'temp'); TMP=(Join-Path $RunDirectory 'temp')
        USERPROFILE=$RunDirectory; APPDATA=(Join-Path $RunDirectory 'appdata')
        LOCALAPPDATA=(Join-Path $RunDirectory 'appdata'); PYTHONUTF8='1'; PYTHONIOENCODING='utf-8'
        PYTHONDONTWRITEBYTECODE='1'; LANGSMITH_TRACING='false'; LANGCHAIN_TRACING_V2='false'
    }
    $paths = @((Join-Path $env:SystemRoot 'System32'),$env:SystemRoot)
    if ($DockerPath) { $paths += Split-Path -Parent $DockerPath }
    $environment['PATH'] = ($paths | Select-Object -Unique) -join [IO.Path]::PathSeparator
    foreach ($key in $Extra.Keys) { $environment[$key] = [string]$Extra[$key] }
    return $environment
}

function ConvertTo-DemoArgument {
    param([AllowEmptyString()][string]$Value)
    # Windows CommandLineToArgvW规则，兼容.NET Framework无ArgumentList的PS5.1。
    $result = [Text.StringBuilder]::new()
    $null = $result.Append('"'); $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') { $slashes++; continue }
        if ($character -eq '"') { $null = $result.Append(('\' * (2*$slashes+1))); $null = $result.Append('"') }
        else { if ($slashes) { $null = $result.Append(('\' * $slashes)) }; $null = $result.Append($character) }
        $slashes = 0
    }
    if ($slashes) { $null = $result.Append(('\' * (2*$slashes))) }
    $null = $result.Append('"')
    return $result.ToString()
}

function New-DemoProcessInfo {
    param([string]$Executable,[string[]]$Arguments,[string]$WorkingDirectory,[hashtable]$Environment)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable; $info.Arguments=($Arguments | ForEach-Object { ConvertTo-DemoArgument $_ }) -join ' '
    $info.WorkingDirectory=$WorkingDirectory; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.UTF8Encoding]::new($false);$info.StandardErrorEncoding=[Text.UTF8Encoding]::new($false)
    $info.EnvironmentVariables.Clear()
    foreach ($key in $Environment.Keys) { $info.EnvironmentVariables[$key] = [string]$Environment[$key] }
    return $info
}

function Resolve-DemoExecutable {
    param([string]$Path,[string]$Command)
    if (-not $Path) {
        $found = Get-Command $Command -CommandType Application -ErrorAction Stop | Select-Object -First 1
        $Path = $found.Source
    }
    $absolute = Assert-DemoNormalPath $Path
    if (-not (Test-Path -LiteralPath $absolute -PathType Leaf) -or [IO.Path]::GetExtension($absolute) -ine '.exe') { throw ('未找到可直接运行的程序：' + $Command) }
    return $absolute
}

function Invoke-DemoDocker {
    param([string]$DockerPath,[string]$Context,[string[]]$Arguments,[string]$InputText,[int]$TimeoutSeconds=30,[switch]$AllowFailure)
    if ($Context -cnotmatch '^[A-Za-z0-9_.-]{1,100}$') { throw 'Docker context名称格式无效。' }
    # Docker保留本机配置定位所需的最小用户目录，但忽略父DOCKER_HOST/CONTEXT/TLS变量。
    $dockerEnvironment = @{SystemRoot=$env:SystemRoot; WINDIR=$env:SystemRoot; PATH=(Join-Path $env:SystemRoot 'System32'); USERPROFILE=$env:USERPROFILE; APPDATA=$env:APPDATA; LOCALAPPDATA=$env:LOCALAPPDATA; TEMP=$env:TEMP; TMP=$env:TMP}
    $info = New-DemoProcessInfo $DockerPath (@('--context',$Context) + $Arguments) (Get-DemoProjectRoot) $dockerEnvironment
    if ($PSBoundParameters.ContainsKey('InputText')) { $info.RedirectStandardInput=$true }
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        if (-not $process.Start()) { throw '本机Docker命令未启动。' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if ($PSBoundParameters.ContainsKey('InputText')) {
            $inputBytes=[Text.UTF8Encoding]::new($false).GetBytes($InputText)
            $process.StandardInput.BaseStream.Write($inputBytes,0,$inputBytes.Length);$process.StandardInput.Close()
        }
        if (-not $process.WaitForExit($TimeoutSeconds*1000)) { $process.Kill(); $process.WaitForExit(); throw '本机Docker命令超时。' }
        $result = [pscustomobject]@{ExitCode=$process.ExitCode;Output=$stdout.GetAwaiter().GetResult();Error=$stderr.GetAwaiter().GetResult()}
        if ($result.ExitCode -ne 0 -and -not $AllowFailure) { throw '本机Docker操作失败，未输出可能含私密信息的命令结果。' }
        return $result
    } finally { $process.Dispose() }
}

function Assert-DemoLocalDocker {
    param([string]$DockerPath,[string]$Context,[switch]$RequireImage)
    $result = Invoke-DemoDocker $DockerPath $Context @('context','inspect',$Context)
    $data = @($result.Output | ConvertFrom-Json)[0]
    $endpoint = [string]$data.Endpoints.docker.Host
    Assert-DemoDockerEndpoint $endpoint
    $engine = Invoke-DemoDocker $DockerPath $Context @('info','--format','{{.OSType}}')
    if ($engine.Output.Trim() -cne 'linux') { throw '演示需要本机Docker Linux引擎。' }
    if ($RequireImage) { $null = Invoke-DemoDocker $DockerPath $Context @('image','inspect','mysql:8.4') }
}

function Assert-DemoDockerEndpoint {
    param([string]$Endpoint)
    if ($Endpoint -cnotmatch '^npipe:/{4}\./pipe/[A-Za-z0-9_.-]+$') { throw '仅允许本机named pipe Docker context，拒绝远端引擎。' }
}

function Assert-DemoContainerOwnership {
    param($Manifest,$Inspect)
    if ([string]$Manifest.containerId -cnotmatch '^[a-f0-9]{64}$' -or $Inspect.Id -cne $Manifest.containerId -or
        $Inspect.Name -cne ('/' + $Manifest.containerName)) { throw '容器ID或名称与本轮记录不一致，拒绝回收。' }
    if ($Inspect.Config.Labels.($script:DemoProjectLabel) -cne $Manifest.projectRoot -or
        $Inspect.Config.Labels.($script:DemoRunLabel) -cne $Manifest.runId) { throw '容器标签不属于本轮，拒绝回收。' }
    $tmpfs = $Inspect.HostConfig.Tmpfs
    $names = @($tmpfs.PSObject.Properties.Name)
    if ($names.Count -ne 1 -or $names[0] -cne '/var/lib/mysql' -or [string]$tmpfs.'/var/lib/mysql' -cne 'rw,nosuid,nodev,size=512m') { throw '容器临时存储与演示契约不符，拒绝回收。' }
    foreach ($mount in @($Inspect.Mounts)) {
        if ($mount.Type -cne 'tmpfs' -or $mount.Destination -cne '/var/lib/mysql') { throw '容器含非演示挂载，拒绝回收。' }
    }
}

function Get-DemoProcessIdentity {
    param([int]$ProcessId)
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    try {
        $process.Refresh()
        if ($process.HasExited) { return $null }
        $identity=[pscustomobject]@{pid=$process.Id;startTimeUtcTicks=[string]$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.Path}
        if (-not $identity.executablePath -and $process.HasExited) { return $null }
        return $identity
    }
    catch {
        try { if ($process.HasExited) { return $null } } catch { }
        throw '无法核对进程身份，拒绝终止。'
    }
    finally { $process.Dispose() }
}

function Assert-DemoProcessOwnership {
    param($Record,$Identity)
    # 已不存在的PID没有可终止资源；旧版不完整记录不能阻止幂等收尾。
    # 若该PID已复用为存活进程，仍须通过下方全部身份检查。
    if ($null -eq $Identity -and [long]$Record.pid -ge 1) { return }
    if ([long]$Record.pid -lt 1 -or [string]$Record.startTimeUtcTicks -notmatch '^\d{15,20}$' -or -not $Record.executablePath) { throw '进程记录不完整，拒绝终止。' }
    if ($Record.pid -ne $Identity.pid -or [string]$Record.startTimeUtcTicks -cne [string]$Identity.startTimeUtcTicks -or
        -not [string]::Equals([string]$Record.executablePath,[string]$Identity.executablePath,[StringComparison]::OrdinalIgnoreCase)) { throw 'PID已复用或进程身份不一致，拒绝终止。' }
}

function Wait-DemoChildIdentity {
    param($Process,[string]$Executable,[string]$Role,[int]$TimeoutMilliseconds=3000,
        [scriptblock]$IdentityReader={param($childId) Get-DemoProcessIdentity $childId})
    $createdTicks=[string]$Process.StartTime.ToUniversalTime().Ticks
    $deadline=[DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw ('演示组件在身份登记前退出：' + $Role) }
        $identity=$null
        try { $identity=& $IdentityReader ([int]$Process.Id) } catch { }
        if ($identity -and $identity.executablePath) {
            $record=[pscustomobject]@{role=$Role;pid=$Process.Id;startTimeUtcTicks=$createdTicks;executablePath=$Executable}
            Assert-DemoProcessOwnership $record $identity
            return $record
        }
        Start-Sleep -Milliseconds 25
    }
    throw ('演示组件的完整进程身份未就绪，已停止本次启动：' + $Role)
}

function Start-DemoChild {
    param([string]$Role,[string]$Executable,[string[]]$Arguments,[string]$WorkingDirectory,[hashtable]$Environment,[string]$RunDirectory)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-DemoProcessInfo $Executable $Arguments $WorkingDirectory $Environment
    # 禁用FileStream的4KiB托管缓冲，小块启动日志及时可见；仍由CopyToAsync写入，无runspace回调。
    $outFile = [IO.FileStream]::new((Join-Path $RunDirectory ('logs/' + $Role + '.out.log')),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read,1,[IO.FileOptions]::Asynchronous)
    $errFile = [IO.FileStream]::new((Join-Path $RunDirectory ('logs/' + $Role + '.err.log')),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read,1,[IO.FileOptions]::Asynchronous)
    $started=$false
    try {
        if (-not $process.Start()) { throw '演示组件未启动。' }
        $started=$true
        $outTask = $process.StandardOutput.BaseStream.CopyToAsync($outFile)
        $errTask = $process.StandardError.BaseStream.CopyToAsync($errFile)
        $record = Wait-DemoChildIdentity $process $Executable $Role
        return [pscustomobject]@{Process=$process;Record=$record;OutFile=$outFile;ErrFile=$errFile;OutTask=$outTask;ErrTask=$errTask}
    } catch {
        $originalError=$_
        # 只使用此函数亲自启动并持有的Process句柄，不按PID重新查找后终止。
        try { if ($started -and -not $process.HasExited) { $process.Kill();$process.WaitForExit() } } catch { }
        try { if ($outTask) { $null=$outTask.Wait(3000) };if ($errTask) { $null=$errTask.Wait(3000) } } catch { }
        $outFile.Dispose(); $errFile.Dispose(); $process.Dispose()
        throw $originalError
    }
}

function Close-DemoChildHandles {
    param($Child)
    try { $null = $Child.OutTask.Wait(3000); $null = $Child.ErrTask.Wait(3000) } catch { }
    $Child.OutFile.Dispose(); $Child.ErrFile.Dispose(); $Child.Process.Dispose()
}

function Remove-DemoOwnedContainer {
    param($Manifest,[string]$DockerPath,
        [scriptblock]$DockerCommand={param($executable,$context,$arguments) Invoke-DemoDocker $executable $context $arguments})
    $context=[string]$Manifest.dockerContext
    $containerId=[string]$Manifest.containerId
    if ($containerId -cnotmatch '^[a-f0-9]{64}$') { throw '容器ID不完整，拒绝推断所有权。' }
    $lookup=@('container','ls','--all','--quiet','--no-trunc','--filter',('id=' + $containerId))
    $found=& $DockerCommand $DockerPath $context $lookup
    if (-not $found.Output.Trim()) { return }
    try { $result=& $DockerCommand $DockerPath $context @('inspect',$containerId) }
    catch {
        $originalError=$_
        # 并发停止可能在ls与inspect之间删除容器；引擎查询失败不能冒充不存在。
        $remaining=& $DockerCommand $DockerPath $context $lookup
        if (-not $remaining.Output.Trim()) { return }
        throw $originalError
    }
    $inspect=@($result.Output | ConvertFrom-Json)[0]
    Assert-DemoContainerOwnership $Manifest $inspect
    try { $null=& $DockerCommand $DockerPath $context @('rm','--force',$containerId) }
    catch {
        $originalError=$_
        # 只在同一不可变ID被成功查明已消失时承认幂等成功，不吞连接或权限故障。
        $remaining=& $DockerCommand $DockerPath $context $lookup
        if (-not $remaining.Output.Trim()) { return }
        throw $originalError
    }
}

function Stop-DemoOwnedProcess {
    param($Record,
        [scriptblock]$IdentityReader={param($childId) Get-DemoProcessIdentity $childId},
        [scriptblock]$ProcessReader={param($childId) Get-Process -Id $childId -ErrorAction SilentlyContinue})
    $identity=& $IdentityReader ([int]$Record.pid)
    Assert-DemoProcessOwnership $Record $identity
    if ($null -eq $identity) { return }
    $process=& $ProcessReader ([int]$Record.pid)
    # 两次读取之间退出时没有资源可终止，不按名称或端口补找。
    if ($null -eq $process) { return }
    try {
        try {
            $process.Refresh()
            $same=[pscustomobject]@{pid=$process.Id;startTimeUtcTicks=[string]$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.Path}
            if (-not $same.executablePath) { throw '无法读取进程可执行路径。' }
        } catch {
            $originalError=$_
            $latest=& $IdentityReader ([int]$Record.pid)
            if ($null -eq $latest) { return }
            Assert-DemoProcessOwnership $Record $latest
            throw $originalError
        }
        # 此检查放在Kill异常处理外，身份不符不会因后续退出而被掩盖。
        Assert-DemoProcessOwnership $Record $same
        try {
            $process.Kill();$null=$process.WaitForExit(10000)
            if (-not $process.HasExited) { throw '本轮进程未退出。' }
        } catch {
            $originalError=$_
            $latest=& $IdentityReader ([int]$Record.pid)
            if ($null -eq $latest) { return }
            Assert-DemoProcessOwnership $Record $latest
            throw $originalError
        }
    } finally { $process.Dispose() }
}

function Enter-DemoStopLock {
    param([string]$ProjectRoot,[string]$RunId,[ValidateRange(1,60000)][int]$TimeoutMilliseconds=30000)
    $manifestPath=Get-DemoManifestPath $ProjectRoot $RunId
    $lockPath=Assert-DemoNormalPath (Join-Path (Split-Path -Parent $manifestPath) '.stop.lock')
    $deadline=[DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    while ($true) {
        try { return [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
        catch {
            $baseError=$_.Exception.GetBaseException()
            if ($baseError -isnot [IO.IOException] -and $baseError -isnot [UnauthorizedAccessException]) { throw }
            if ([DateTime]::UtcNow -ge $deadline) { throw '本轮停止操作的排他锁未就绪，请稍后重试；没有并行执行资源清理。' }
            Start-Sleep -Milliseconds 25
        }
    }
}

function Stop-DemoResources {
    param($Manifest,[string]$ProjectRoot,[string]$DockerPath)
    $stopLock=$null
    try {
        $stopLock=Enter-DemoStopLock $ProjectRoot ([string]$Manifest.runId)
        return Stop-DemoResourcesLocked $Manifest $ProjectRoot $DockerPath
    } catch {
        return [pscustomobject]@{Success=$false;Failures=@('本轮清理未完成（' + $_.Exception.GetBaseException().GetType().FullName + '），请核对后重试。')}
    } finally { if ($stopLock) { $stopLock.Dispose() } }
}

function Stop-DemoResourcesLocked {
    param($Manifest,[string]$ProjectRoot,[string]$DockerPath)
    $failures = New-Object 'Collections.Generic.List[string]'
    $records = @($Manifest.processes)
    [array]::Reverse($records)
    foreach ($record in $records) {
        try { Stop-DemoOwnedProcess $record }
        catch { $failures.Add(('未回收进程 ' + [string]$record.role + '：' + $_.Exception.Message)) }
    }
    if ($Manifest.containerId) {
        try {
            if ([string]$Manifest.containerId -cnotmatch '^[a-f0-9]{64}$') { throw '容器ID不完整，拒绝推断所有权。' }
            Assert-DemoLocalDocker $DockerPath ([string]$Manifest.dockerContext)
            Remove-DemoOwnedContainer $Manifest $DockerPath
        } catch { $failures.Add(('未回收容器：' + $_.Exception.Message)) }
    }
    $Manifest.ready=$false; $Manifest.ended=($failures.Count -eq 0)
    try {
        # 外部停止与父监护可同时收尾，不以缓存manifest覆盖已经发布的停止信号。
        $latest=Read-DemoManifest $ProjectRoot ([string]$Manifest.runId)
        $signal=$latest.PSObject.Properties['stopRequested']
        if ($signal -and $signal.Value -is [bool] -and $signal.Value) {
            if (-not $Manifest.PSObject.Properties['stopRequested']) { $Manifest | Add-Member NoteProperty stopRequested $false }
            $Manifest.stopRequested=$true
        }
        Write-DemoManifest $Manifest $ProjectRoot
    } catch { $failures.Add(('运行资料无法保存（' + $_.Exception.GetBaseException().GetType().FullName + '）。')) }
    return [pscustomobject]@{Success=($failures.Count -eq 0);Failures=@($failures.ToArray())}
}
