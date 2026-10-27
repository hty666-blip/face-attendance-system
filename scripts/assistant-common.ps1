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

function Read-AssistantModelConfig {
    param([Parameter(Mandatory = $true)][string]$Path)
    # Treat the private file as data. Never dot-source it or return parser diagnostics.
    $stream = $null
    try {
        $projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
        $localRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.local'))
        $candidate = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $projectRoot $Path }
        $fullPath = [IO.Path]::GetFullPath($candidate)
        if ($fullPath -notmatch '^[A-Za-z]:\\' -or $fullPath.Substring(2).Contains(':') -or
            -not $fullPath.StartsWith(($localRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Invalid path'
        }
        $part = $fullPath
        while ($part) {
            $attributes = [IO.File]::GetAttributes($part)
            if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse point' }
            $next = [IO.Path]::GetDirectoryName($part)
            if ($next -eq $part) { break }
            $part = $next
        }
        if (([IO.File]::GetAttributes($fullPath) -band [IO.FileAttributes]::Directory) -ne 0) { throw 'Not a file' }
        $stream = [IO.File]::Open($fullPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -eq 0 -or $stream.Length -gt 16384) { throw 'Invalid size' }
        $bytes = New-Object byte[] ([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $count = $stream.Read($bytes, $offset, ($bytes.Length - $offset))
            if ($count -le 0) { throw 'Incomplete read' }
            $offset += $count
        }
        $source = [Text.UTF8Encoding]::new($false, $true).GetString($bytes).TrimStart([char]0xFEFF)
        $tokens = $null; $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count -ne 0 -or $ast.ParamBlock -or $ast.BeginBlock -or $ast.ProcessBlock -or
            $ast.DynamicParamBlock -or $ast.UsingStatements.Count -ne 0 -or -not $ast.EndBlock -or
            -not $ast.EndBlock.Unnamed -or $ast.EndBlock.Traps.Count -ne 0) {
            throw 'Invalid script structure'
        }
        $allowed = @('ASSISTANT_ENABLED', 'ASSISTANT_PROVIDER', 'ASSISTANT_BASE_URL', 'ASSISTANT_MODEL',
            'ASSISTANT_API_KEY', 'ASSISTANT_TIMEOUT_SECONDS', 'ASSISTANT_MAX_CONCURRENCY',
            'LANGCHAIN_TRACING_V2', 'LANGSMITH_TRACING')
        $settings = @{}
        foreach ($statement in $ast.EndBlock.Statements) {
            if ($statement -isnot [Management.Automation.Language.AssignmentStatementAst] -or
                $statement.Operator -ne [Management.Automation.Language.TokenKind]::Equals -or
                $statement.Left -isnot [Management.Automation.Language.VariableExpressionAst] -or
                $statement.Left.Splatted -or $statement.Left.VariablePath.UserPath -cnotmatch '^env:([A-Z0-9_]+)$') {
                throw 'Only static environment assignments are allowed'
            }
            $name = $Matches[1]
            if ($allowed -cnotcontains $name -or $settings.ContainsKey($name) -or
                $statement.Right -isnot [Management.Automation.Language.CommandExpressionAst] -or
                $statement.Right.Redirections.Count -ne 0 -or
                $statement.Right.Expression -isnot [Management.Automation.Language.StringConstantExpressionAst] -or
                $statement.Right.Expression.StringConstantType -ne [Management.Automation.Language.StringConstantType]::SingleQuoted) {
                throw 'Invalid assignment'
            }
            $settings[$name] = $statement.Right.Expression.Value
        }
        foreach ($name in $allowed[0..6]) {
            if (-not $settings.ContainsKey($name)) { throw 'Missing setting' }
        }
        if ($settings.ASSISTANT_ENABLED -cnotin @('true', 'false') -or
            $settings.ASSISTANT_PROVIDER -cnotin @('openai-compatible', 'mock') -or
            $settings.ASSISTANT_MODEL -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$' -or
            $settings.ASSISTANT_API_KEY -cnotmatch '^[\x21-\x7E]{16,4096}$' -or
            $settings.ASSISTANT_TIMEOUT_SECONDS -cnotmatch '^([1-9]|[12][0-9]|30)$' -or
            $settings.ASSISTANT_MAX_CONCURRENCY -cnotmatch '^[1-4]$') {
            throw 'Setting out of range'
        }
        $baseUri = $null
        if (-not [Uri]::TryCreate($settings.ASSISTANT_BASE_URL, [UriKind]::Absolute, [ref]$baseUri) -or
            $baseUri.Scheme -cne 'https' -or -not $baseUri.Host -or $baseUri.UserInfo -or
            $baseUri.Query -or $baseUri.Fragment -or -not $baseUri.IsDefaultPort -or
            $settings.ASSISTANT_BASE_URL -match '\s') {
            throw 'Invalid model endpoint'
        }
        foreach ($name in @('LANGCHAIN_TRACING_V2', 'LANGSMITH_TRACING')) {
            if ($settings.ContainsKey($name) -and $settings[$name] -cne 'false') { throw 'Tracing must be disabled' }
            $settings[$name] = 'false'
        }
        return $settings
    } catch {
        throw '模型配置无法安全读取：仅支持本项目 .local 内的普通 UTF-8 文件和允许的单引号静态配置；请检查字段、范围及追踪开关。'
    } finally {
        if ($stream) { $stream.Dispose() }
    }
}

function Invoke-WithAssistantModelConfig {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][scriptblock]$Operation
    )
    $settings = Read-AssistantModelConfig -Path $Path
    $previous = @{}
    foreach ($name in $settings.Keys) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        foreach ($name in $settings.Keys) { [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process') }
        & $Operation
    } finally {
        foreach ($name in $previous.Keys) {
            $value = if ($null -eq $previous[$name]) { [NullString]::Value } else { $previous[$name] }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
