# Offline synthetic fixtures only. Never reads a user's model credentials.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'assistant-common.ps1')
$script:ConfigSafetyCount = 0
function Assert-ConfigSafety {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw ('FAIL: ' + $Name) }
    $script:ConfigSafetyCount++
}
function Assert-ConfigRejected {
    param([string]$Path, [string]$Name)
    $caught = $false
    try { $null = Read-AssistantModelConfig -Path $Path }
    catch {
        $caught = $true
        Assert-ConfigSafety ($_.Exception.Message -notmatch 'synthetic-config-key|controlled-fixture|Set-Content|malformed-secret') ($Name + ': error hides source')
    }
    Assert-ConfigSafety $caught $Name
}
function New-ConfigFixture {
    param([string]$Name, [hashtable]$Changes = @{}, [string[]]$Omit = @(), [string]$Suffix = '')
    $values = [ordered]@{
        ASSISTANT_ENABLED = 'true'
        ASSISTANT_PROVIDER = 'openai-compatible'
        ASSISTANT_BASE_URL = 'https://api.deepseek.com/beta'
        ASSISTANT_MODEL = 'deepseek-flash'
        ASSISTANT_API_KEY = 'sk-synthetic-config-key-for-tests'
        ASSISTANT_TIMEOUT_SECONDS = '30'
        ASSISTANT_MAX_CONCURRENCY = '1'
        LANGCHAIN_TRACING_V2 = 'false'
        LANGSMITH_TRACING = 'false'
    }
    foreach ($key in $Changes.Keys) { $values[$key] = $Changes[$key] }
    $lines = @('# controlled-fixture; static assignments only')
    foreach ($key in $values.Keys) {
        if ($Omit -cnotcontains $key) { $lines += ('$env:' + $key + " = '" + $values[$key].Replace("'", "''") + "'") }
    }
    $path = Join-Path $testDirectory $Name
    [IO.File]::WriteAllText($path, (($lines -join [Environment]::NewLine) + [Environment]::NewLine + $Suffix), [Text.UTF8Encoding]::new($false))
    return $path
}
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$testDirectory = Join-Path $projectRoot ('.local/assistant-config-tests-' + [Guid]::NewGuid().ToString('N'))
$junctionPath = $null
$outsideFile = Join-Path $projectRoot ('.assistant-config-outside-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$savedEnvironment = @{}
$environmentNames = @('ASSISTANT_ENABLED', 'ASSISTANT_PROVIDER', 'ASSISTANT_BASE_URL', 'ASSISTANT_MODEL', 'ASSISTANT_API_KEY',
    'ASSISTANT_TIMEOUT_SECONDS', 'ASSISTANT_MAX_CONCURRENCY', 'LANGCHAIN_TRACING_V2', 'LANGSMITH_TRACING')
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($relativeScript in @('scripts/start-ai.ps1', 'scripts/assistant-common.ps1', 'scripts/face-common.ps1')) {
        $scriptTokens = $null; $scriptErrors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot $relativeScript), [ref]$scriptTokens, [ref]$scriptErrors)
        Assert-ConfigSafety ($scriptErrors.Count -eq 0) ('parse startup dependency without errors: ' + $relativeScript)
    }
    $null = New-Item -ItemType Directory -Path $testDirectory
    $validPath = New-ConfigFixture 'valid.ps1'
    $settings = Read-AssistantModelConfig -Path $validPath
    Assert-ConfigSafety ($settings.Count -eq 9 -and $settings.ASSISTANT_MODEL -ceq 'deepseek-flash' -and $settings.ASSISTANT_MAX_CONCURRENCY -ceq '1') 'accept complete static configuration'
    Assert-ConfigSafety ($settings.ASSISTANT_API_KEY -ceq 'sk-synthetic-config-key-for-tests') 'retain synthetic key without executing file'
    $relative = $validPath.Substring($projectRoot.Length + 1)
    Assert-ConfigSafety ((Read-AssistantModelConfig -Path $relative).ASSISTANT_PROVIDER -ceq 'openai-compatible') 'resolve relative path against project root'
    $implicitTracing = New-ConfigFixture 'implicit-tracing.ps1' -Omit @('LANGCHAIN_TRACING_V2', 'LANGSMITH_TRACING')
    $settings = Read-AssistantModelConfig -Path $implicitTracing
    Assert-ConfigSafety ($settings.LANGCHAIN_TRACING_V2 -ceq 'false' -and $settings.LANGSMITH_TRACING -ceq 'false') 'force tracing off when omitted'
    $bounds = Read-AssistantModelConfig -Path (New-ConfigFixture 'bounds.ps1' -Changes @{ASSISTANT_TIMEOUT_SECONDS='1';ASSISTANT_MAX_CONCURRENCY='4';ASSISTANT_API_KEY='1234567890abcdef'})
    Assert-ConfigSafety ($bounds.ASSISTANT_TIMEOUT_SECONDS -ceq '1' -and $bounds.ASSISTANT_MAX_CONCURRENCY -ceq '4') 'accept documented minimum and maximum bounds'
    $quoted = Read-AssistantModelConfig -Path (New-ConfigFixture 'quoted.ps1' -Changes @{ASSISTANT_API_KEY="sk-synthetic-config-key'quoted"})
    Assert-ConfigSafety ($quoted.ASSISTANT_API_KEY -ceq "sk-synthetic-config-key'quoted") 'decode escaped quote as data without evaluation'
    foreach ($case in @(
        @{ Name='enabled.ps1'; Changes=@{ASSISTANT_ENABLED='TRUE'} },
        @{ Name='provider.ps1'; Changes=@{ASSISTANT_PROVIDER='unknown'} },
        @{ Name='empty-key.ps1'; Changes=@{ASSISTANT_API_KEY=''} },
        @{ Name='short-key.ps1'; Changes=@{ASSISTANT_API_KEY='short'} },
        @{ Name='space-key.ps1'; Changes=@{ASSISTANT_API_KEY='sk-synthetic-config-key for-tests'} },
        @{ Name='long-key.ps1'; Changes=@{ASSISTANT_API_KEY=('x' * 4097)} },
        @{ Name='model.ps1'; Changes=@{ASSISTANT_MODEL='model with spaces'} },
        @{ Name='empty-model.ps1'; Changes=@{ASSISTANT_MODEL=''} },
        @{ Name='long-model.ps1'; Changes=@{ASSISTANT_MODEL=('a' * 129)} },
        @{ Name='timeout-zero.ps1'; Changes=@{ASSISTANT_TIMEOUT_SECONDS='0'} },
        @{ Name='timeout-large.ps1'; Changes=@{ASSISTANT_TIMEOUT_SECONDS='31'} },
        @{ Name='timeout-fraction.ps1'; Changes=@{ASSISTANT_TIMEOUT_SECONDS='1.5'} },
        @{ Name='concurrency-zero.ps1'; Changes=@{ASSISTANT_MAX_CONCURRENCY='0'} },
        @{ Name='concurrency-large.ps1'; Changes=@{ASSISTANT_MAX_CONCURRENCY='5'} },
        @{ Name='http.ps1'; Changes=@{ASSISTANT_BASE_URL='http://api.deepseek.com/beta'} },
        @{ Name='credentials.ps1'; Changes=@{ASSISTANT_BASE_URL='https://user:synthetic-config-key@api.deepseek.com/beta'} },
        @{ Name='query.ps1'; Changes=@{ASSISTANT_BASE_URL='https://api.deepseek.com/beta?key=synthetic-config-key'} },
        @{ Name='fragment.ps1'; Changes=@{ASSISTANT_BASE_URL='https://api.deepseek.com/beta#fragment'} },
        @{ Name='port.ps1'; Changes=@{ASSISTANT_BASE_URL='https://api.deepseek.com:8443/beta'} },
        @{ Name='unknown.ps1'; Changes=@{OPENAI_API_KEY='sk-synthetic-config-key-for-tests'} },
        @{ Name='trace.ps1'; Changes=@{LANGCHAIN_TRACING_V2='true'} },
        @{ Name='trace-two.ps1'; Changes=@{LANGSMITH_TRACING='true'} },
        @{ Name='token.ps1'; Changes=@{ASSISTANT_INTERNAL_TOKEN='sk-synthetic-config-key-for-tests'} }
    )) {
        $path = New-ConfigFixture $case.Name -Changes $case.Changes
        Assert-ConfigRejected $path ('reject invalid value: ' + $case.Name)
    }
    foreach ($name in $environmentNames[0..6]) {
        Assert-ConfigRejected (New-ConfigFixture ('missing-' + $name + '.ps1') -Omit @($name)) ('require field: ' + $name)
    }
    $marker = Join-Path $testDirectory 'must-not-execute.txt'
    $invalidSyntax = @(
        '$env:ASSISTANT_MODEL = "deepseek-flash"',
        '$env:ASSISTANT_MODEL = (''deepseek-flash'')',
        '$env:ASSISTANT_MODEL += ''extra''',
        '$env:ASSISTANT_MODEL = $env:OTHER_VALUE',
        '$env:ASSISTANT_MODEL = ''a'' + ''b''',
        'param([string]$Value)',
        'function Invoke-Unsafe { ''unused'' }',
        'trap { continue }',
        'if ($true) { $env:ASSISTANT_MODEL = ''other'' }',
        '$global:controlled = ''fixture''',
        ('$env:ASSISTANT_MODEL = $(Set-Content -LiteralPath ''' + $marker + ''' -Value ''controlled-fixture'')'),
        ('Set-Content -LiteralPath ''' + $marker + ''' -Value ''controlled-fixture'''),
        '$env:ASSISTANT_MODEL = ''malformed-secret',
        '$env:ASSISTANT_MODEL = ''duplicate'''
    )
    $syntaxIndex = 0
    foreach ($suffix in $invalidSyntax) {
        $syntaxIndex++
        $omit = if ($suffix.StartsWith('$env:ASSISTANT_MODEL') -and $syntaxIndex -ne $invalidSyntax.Count) { @('ASSISTANT_MODEL') } else { @() }
        Assert-ConfigRejected (New-ConfigFixture ('syntax-' + $syntaxIndex + '.ps1') -Omit $omit -Suffix $suffix) ('reject executable or invalid syntax ' + $syntaxIndex)
    }
    Assert-ConfigSafety (-not (Test-Path -LiteralPath $marker)) 'never execute submitted code'
    $namedPath = Join-Path $testDirectory 'named-block.ps1'
    [IO.File]::WriteAllText($namedPath, ('end {' + [Environment]::NewLine + [IO.File]::ReadAllText($validPath) + [Environment]::NewLine + '}'))
    Assert-ConfigRejected $namedPath 'reject named script block in place of plain assignments'
    [IO.File]::WriteAllText($outsideFile, [IO.File]::ReadAllText($validPath))
    Assert-ConfigRejected $outsideFile 'reject file outside private project directory'
    Assert-ConfigRejected (Join-Path $testDirectory '..\..\README.md') 'reject traversal outside private directory'
    Assert-ConfigRejected '\\localhost\c$\model.ps1' 'reject UNC path'
    Assert-ConfigRejected ($validPath + ':stream') 'reject alternate data stream'
    Assert-ConfigRejected $testDirectory 'reject directory in place of file'
    Assert-ConfigRejected (Join-Path $testDirectory 'missing.ps1') 'reject missing file'
    $emptyPath = Join-Path $testDirectory 'empty.ps1'; [IO.File]::WriteAllText($emptyPath, '')
    Assert-ConfigRejected $emptyPath 'reject empty file'
    $largePath = Join-Path $testDirectory 'large.ps1'; [IO.File]::WriteAllText($largePath, ('#' * 16385))
    Assert-ConfigRejected $largePath 'reject oversized file'
    $invalidUtf8Path = Join-Path $testDirectory 'invalid-utf8.ps1'; [IO.File]::WriteAllBytes($invalidUtf8Path, [byte[]]@(0xFF,0xFE,0xFF))
    Assert-ConfigRejected $invalidUtf8Path 'reject non-UTF8 file'
    $junctionPath = Join-Path $testDirectory 'linked-directory'
    $null = New-Item -ItemType Junction -Path $junctionPath -Target $testDirectory
    Assert-ConfigRejected (Join-Path $junctionPath 'valid.ps1') 'reject linked parent directory'
    [IO.Directory]::Delete($junctionPath); $junctionPath = $null

    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, 'synthetic-parent-value', 'Process') }
    $env:ASSISTANT_MODEL = $null
    Invoke-WithAssistantModelConfig -Path $validPath -Operation {
        Assert-ConfigSafety ($env:ASSISTANT_API_KEY -ceq 'sk-synthetic-config-key-for-tests' -and $env:ASSISTANT_MODEL -ceq 'deepseek-flash') 'inject complete validated configuration only during operation'
        Assert-ConfigSafety ($env:LANGCHAIN_TRACING_V2 -ceq 'false' -and $env:LANGSMITH_TRACING -ceq 'false') 'override inherited tracing only during operation'
    }
    foreach ($name in $environmentNames) {
        $expected = if ($name -ceq 'ASSISTANT_MODEL') { $null } else { 'synthetic-parent-value' }
        Assert-ConfigSafety ([Environment]::GetEnvironmentVariable($name, 'Process') -ceq $expected) ('restore environment after success: ' + $name)
    }
    $failed = $false
    try {
        Invoke-WithAssistantModelConfig -Path $validPath -Operation { throw 'controlled operation failure' }
    } catch { $failed = $true }
    Assert-ConfigSafety $failed 'propagate startup failure'
    foreach ($name in $environmentNames) {
        $expected = if ($name -ceq 'ASSISTANT_MODEL') { $null } else { 'synthetic-parent-value' }
        Assert-ConfigSafety ([Environment]::GetEnvironmentVariable($name, 'Process') -ceq $expected) ('restore environment after failure: ' + $name)
    }
    $script:unexpectedExecution = $false
    try { Invoke-WithAssistantModelConfig -Path $outsideFile -Operation { $script:unexpectedExecution = $true } } catch {}
    Assert-ConfigSafety (-not $script:unexpectedExecution) 'reject whole file before executing operation'
    foreach ($name in $environmentNames) {
        $expected = if ($name -ceq 'ASSISTANT_MODEL') { $null } else { 'synthetic-parent-value' }
        Assert-ConfigSafety ([Environment]::GetEnvironmentVariable($name, 'Process') -ceq $expected) ('leave environment intact on validation failure: ' + $name)
    }
    Write-Host ('Assistant model config safety: ' + $script:ConfigSafetyCount + ' checks passed; offline synthetic fixtures only.')
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        $value = if ($null -eq $savedEnvironment[$name]) { [NullString]::Value } else { $savedEnvironment[$name] }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    if ($junctionPath -and [IO.Directory]::Exists($junctionPath)) { [IO.Directory]::Delete($junctionPath) }
    if ([IO.File]::Exists($outsideFile)) { [IO.File]::Delete($outsideFile) }
    $resolvedTest = [IO.Path]::GetFullPath($testDirectory)
    $expectedParent = [IO.Path]::GetFullPath((Join-Path $projectRoot '.local')).TrimEnd('\') + '\'
    if ($resolvedTest.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedTest) -cmatch '^assistant-config-tests-[a-f0-9]{32}$' -and [IO.Directory]::Exists($resolvedTest)) {
        Remove-Item -LiteralPath $resolvedTest -Recurse -Force
    }
}
