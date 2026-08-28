#Requires -Version 7.0
<#
.SYNOPSIS
    T13 verification gate coordinator. Plan/List/Check are safe by default.
.DESCRIPTION
    This file is an orchestration entrypoint, not evidence that a gate passed.
    Plan and Check never start Docker, Maven, npm, JMeter, Chrome, or a public
    deployment. Run requires both -Gate and -Execute, keeps local gates
    loopback-only, and writes the actual exit code under deploy/artifacts.
    External acceptance is deliberately not an executable gate here.

    Gate names:
      static, compose-config, backend, frontend, empty-migration, backup-restore,
      restart-persistence, redis-failure, jmeter, student-browser, approval-browser

    Exit codes: 0 passed executed check | 1 executed failure | 2 refused |
                3 blocked/not run
#>
[CmdletBinding()]
param(
    [ValidateSet('Plan', 'List', 'Check', 'Run')]
    [string]$Mode = 'Plan',
    [ValidateSet('static', 'compose-config', 'backend', 'frontend', 'empty-migration',
        'backup-restore', 'restart-persistence', 'redis-failure', 'jmeter',
        'student-browser', 'approval-browser')]
    [string]$Gate = 'static',
    [switch]$Execute,
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ArtifactRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning "REFUSED: invalid RunId '$RunId'."
    exit 2
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
$artifactDir = Join-Path $ArtifactRoot "verify-$Gate-$RunId"

$gates = [ordered]@{
    static = 'PowerShell parser, Node syntax, JMX XML, ownership/secret path review, git diff check'
    'compose-config' = 'docker compose --env-file deploy/.env -f deploy/compose.yml config'
    backend = 'booking-api: mvn verify (JDK 17)'
    frontend = 'booking-web: npm ci; npm run build (repository lockfile)'
    'empty-migration' = 'deploy/scripts/empty-migration-check.ps1'
    'backup-restore' = 'deploy/scripts/backup-restore-check.ps1'
    'restart-persistence' = 'deploy/scripts/restart-persistence-check.ps1 -Execute'
    'redis-failure' = 'deploy/scripts/redis-failure-check.ps1 -Execute'
    jmeter = 'deploy/jmeter/run.ps1 plus deploy/jmeter/summarize.ps1 per isolated round'
    'student-browser' = 'deploy/e2e/run.ps1 -Execute -Mode StudentBrowser'
    'approval-browser' = 'deploy/e2e/run.ps1 -Execute -Mode ApprovalBrowser'
}

if ($Mode -eq 'List') {
    $gates.GetEnumerator() | ForEach-Object { '{0}: {1}' -f $_.Key, $_.Value }
    exit 0
}

if ($Mode -eq 'Plan') {
    Write-Output 'PLAN MODE - no verification command is invoked.'
    Write-Output "gate=$Gate"
    Write-Output "command=$($gates[$Gate])"
    Write-Output 'External acceptance (DNS/TLS/public host) is not an available gate in this script.'
    Write-Output 'Use -Mode Check for local static checks; use -Mode Run -Execute only after the evidence preconditions are satisfied.'
    exit 0
}

function Write-Result {
    param([int]$ExitCode, [string]$Status, [string]$Detail)
    New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
    $result = [ordered]@{
        runId = $RunId
        gate = $Gate
        mode = $Mode
        status = $Status
        exitCode = $ExitCode
        detail = $Detail
        generatedAt = (Get-Date).ToString('o')
        evidencePolicy = 'This record is valid only for the command actually executed; no command is inferred from a plan.'
    }
    Set-Content -LiteralPath (Join-Path $artifactDir 'gate-result.json') `
        -Value ($result | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
}

if ($Mode -eq 'Check') {
    if ($Gate -ne 'static') {
        Write-Result -ExitCode 3 -Status 'NOT_RUN' -Detail 'Check mode supports only the static gate; runtime gates require -Mode Run -Execute.'
        Write-Warning 'BLOCKED: runtime gate not run. Use explicit Run/Execute only after preconditions are satisfied.'
        exit 3
    }
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $repoRoot 'deploy') -Recurse -File -Filter '*.ps1')) {
        $tokens = $null; $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count -gt 0) { [void]$failures.Add("PowerShell: $($file.FullName)") }
    }
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $repoRoot 'deploy') -Recurse -File -Filter '*.mjs')) {
        & node --check $file.FullName *> $null
        if ($LASTEXITCODE -ne 0) { [void]$failures.Add("Node: $($file.FullName)") }
    }
    $jmx = Join-Path $repoRoot 'deploy\jmeter\booking-concurrency.jmx'
    try { [xml](Get-Content -LiteralPath $jmx -Raw) | Out-Null } catch { [void]$failures.Add("JMX XML: $jmx") }
    Push-Location $repoRoot
    try { git diff --check *> $null; if ($LASTEXITCODE -ne 0) { [void]$failures.Add('git diff --check') } } finally { Pop-Location }
    if ($failures.Count -gt 0) {
        $detail = $failures -join '; '
        Write-Result -ExitCode 1 -Status 'FAIL' -Detail $detail
        Write-Warning "STATIC CHECK FAILED: $detail"
        exit 1
    }
    Write-Result -ExitCode 0 -Status 'STATIC_ONLY_OK' -Detail 'Parser, Node, JMX XML, and git diff checks passed; runtime gates remain unexecuted.'
    Write-Output 'STATIC CHECK OK - this is not runtime evidence.'
    exit 0
}

if (-not $Execute) {
    Write-Result -ExitCode 3 -Status 'NOT_RUN' -Detail 'Run mode requires -Execute.'
    Write-Warning 'BLOCKED: no runtime command was invoked.'
    exit 3
}
if ($Gate -eq 'approval-browser') {
    Write-Result -ExitCode 3 -Status 'BLOCKED_OWNER_APPROVAL' -Detail 'ApprovalBrowser requires deterministic fixture and owner-approved executable; no generic public gate is provided.'
    Write-Warning 'BLOCKED: ApprovalBrowser cannot be authorized by this coordinator alone.'
    exit 3
}
if ($Gate -eq 'static') {
    Write-Warning 'REFUSED: use -Mode Check for the static gate.'
    exit 2
}

# Runtime dispatch is intentionally explicit. No public host/domain/TLS gate is
# represented, and each delegated script owns its own loopback/credential gates.
$scriptPath = $null
$arguments = @()
switch ($Gate) {
    'compose-config' { $scriptPath = $null; $arguments = @('compose-config') }
    backend          { $scriptPath = $null; $arguments = @('backend') }
    frontend         { $scriptPath = $null; $arguments = @('frontend') }
    'empty-migration' { $scriptPath = Join-Path $repoRoot 'deploy\scripts\empty-migration-check.ps1' }
    'backup-restore' { $scriptPath = Join-Path $repoRoot 'deploy\scripts\backup-restore-check.ps1' }
    'restart-persistence' { $scriptPath = Join-Path $repoRoot 'deploy\scripts\restart-persistence-check.ps1'; $arguments = @('-Execute') }
    'redis-failure' { $scriptPath = Join-Path $repoRoot 'deploy\scripts\redis-failure-check.ps1'; $arguments = @('-Execute') }
    jmeter { $scriptPath = Join-Path $repoRoot 'deploy\jmeter\run.ps1' }
    'student-browser' { $scriptPath = Join-Path $repoRoot 'deploy\e2e\run.ps1'; $arguments = @('-Execute', '-Mode', 'StudentBrowser') }
}

if (-not $scriptPath) {
    Write-Result -ExitCode 3 -Status 'BLOCKED' -Detail "No generic dispatch is implemented for gate '$Gate'; invoke its documented command after reviewing preconditions."
    Write-Warning "BLOCKED: coordinator will not guess arguments for '$Gate'."
    exit 3
}
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    Write-Result -ExitCode 3 -Status 'BLOCKED' -Detail "Entrypoint missing: $scriptPath"
    Write-Warning "BLOCKED: entrypoint missing: $scriptPath"
    exit 3
}

New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
$logPath = Join-Path $artifactDir 'command.log'
try {
    & pwsh -NoProfile -File $scriptPath @arguments 2>&1 | Tee-Object -FilePath $logPath | Out-Null
    $exitCode = $LASTEXITCODE
} catch {
    $_ | Out-String | Set-Content -LiteralPath $logPath -Encoding utf8NoBOM
    $exitCode = 1
}
if ($exitCode -eq 0) {
    Write-Result -ExitCode 0 -Status 'EXECUTED_UNCLASSIFIED' -Detail 'Command exited zero; gate-specific evidence and owner conditions still require review.'
    exit 0
}
Write-Result -ExitCode $exitCode -Status 'EXECUTED_FAILED_OR_BLOCKED' -Detail 'Command produced a non-zero exit; inspect redacted command.log and gate artifacts.'
exit $exitCode
