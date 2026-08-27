#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane C: prove MySQL volume persistence across container restart/recreate.
.DESCRIPTION
    STATIC PLAN - default behavior is PLAN ONLY. Pass -Execute to actually touch
    the local stack (tasks.md 5.3 stays unchecked until a real executed run).

    Safety contract (verified by static review only):
      * Volume preservation is absolute: this script NEVER emits docker
        `down`, `down -v`, `rm`, or any `-v` flag. Recreate path uses
        `docker compose stop` + `up -d --force-recreate` only.
      * PLAN MODE touches nothing and requires NO secret (credential resolution
        happens only on the -Execute path, and even there the mysql client
        authenticates with the container's OWN MYSQL_ROOT_PASSWORD via
        `sh -c 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" ...'` - no secret on argv).
      * Health gate polls and REQUIRES container health=healthy for mysql/api/
        redis after the action (missing health or non-healthy is a failure);
        readiness timeout fails explicitly.
      * Evidence: pre/post normalized information_schema fingerprints must be
        identical, per-table row counts equal; compose config output and service
        log tails captured under deploy\artifacts\<RunId>\ with native exit-code
        checks.
    Exit codes: 0 pass | 1 environment | 2 assertion/health failure | 3 blocked.
#>
[CmdletBinding()]
param(
    [string]$ComposeFile = '',
    [switch]$Execute,
    [switch]$Recreate,
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$TimeoutSeconds = 300,
    [string[]]$ServicesToCycle = @('api', 'mysql', 'redis')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# RunId feeds artifact paths and plan text: strictly bound.
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

if (-not $ComposeFile)  { $ComposeFile  = (Resolve-Path (Join-Path $PSScriptRoot '..\compose.yml')).Path }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
$Artifacts = Join-Path $ArtifactRoot $RunId
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null

function Redact {
    param([AllowEmptyCollection()][string[]]$Secrets, [string]$Text)
    foreach ($s in $Secrets) { if ($s) { $Text = $Text.Replace($s, '***REDACTED***') } }
    return $Text
}

# Self-audit plan: the exact command verbs this script may run; -v/down/rm are
# forbidden by contract and never constructed below.
$actionPlan = @(
    'PLAN ONLY until -Execute.',
    'readiness+health poll: docker inspect .State.Health for api/mysql/redis',
    "cycle: $(if ($Recreate) { 'compose stop ; compose up -d --force-recreate' } else { 'compose restart api mysql redis' })",
    'post gate: all three containers healthy within timeout',
    'evidence: fingerprints, counts, compose config output, log tails',
    'RULE: commands containing down / -v / volume rm are forbidden in lane C'
)
$results = [ordered]@{ runId = $RunId; mode = ($(if ($Execute) { 'execute' } else { 'plan' })); overallPass = $false }
$results.plan = $actionPlan

Set-Content -LiteralPath (Join-Path $Artifacts 'plan.txt') `
    -Value (($actionPlan -join "`r`n")) -Encoding utf8NoBOM

if (-not $Execute) {
    Write-Output 'PLAN MODE - nothing touched, no credential required. Re-run with -Execute (optionally -Recreate).'
    exit 0
}

function Invoke-Compose {
    param([string[]]$Args2)
    $fullArgs = @('compose', '-f', $ComposeFile) + $Args2
    & docker @fullArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("compose step failed with exit {0}" -f $LASTEXITCODE)
    }
}

& docker @('compose', '-f', $ComposeFile, 'ps', '-q', 'mysql') | Tee-Object -Variable cidLines | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'compose ps failed' }
$cid = ($cidLines | Where-Object { $_ }) | Select-Object -First 1
if (-not $cid) { Write-Warning 'BLOCKED: local stack not running'; exit 3 }

function Invoke-MysqlExecLocal {
    # Authenticates from the container's own MYSQL_ROOT_PASSWORD; the query text
    # rides an exec-local env var. No credential value on any host argv.
    param([string]$ContainerId, [string]$Query)
    $out = & docker @('exec', '-e', "T13Q=$Query", $ContainerId,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -uroot --batch --skip-column-names -e "$T13Q"')
    if ($LASTEXITCODE -ne 0) { throw "mysql exec failed (query redacted)" }
    return ($out -join "`n")
}

function Get-ContainerHealth {
    # Returns 'healthy'/'unhealthy'/'starting'/... or 'absent-no-healthcheck'.
    param([string]$ComposeService)
    & docker @('compose', '-f', $ComposeFile, 'ps', '-q', $ComposeService) |
        Tee-Object -Variable svcIdLines | Out-Null
    if ($LASTEXITCODE -ne 0) { return 'ps-error' }
    $svcCid = ($svcIdLines | Where-Object { $_ }) | Select-Object -First 1
    if (-not $svcCid) { return 'no-container' }
    $fmt = '{{if .State.Health}}{{.State.Health.Status}}{{else}}absent-no-healthcheck{{end}}'
    $h = (& docker @('inspect', '--format', $fmt, $svcCid))
    if ($LASTEXITCODE -ne 0) { return 'inspect-error' }
    return (@($h) | Select-Object -First 1)
}

function Get-Fingerprint {
    param([string]$ContainerId)
    # Plain table-name list for iteration (never reuse the multi-column row as a name).
    $namesQ   = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
    $tblFpQ   = "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
    $colQ     = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, IFNULL(COLUMN_DEFAULT,'NULL') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='booking_db' ORDER BY TABLE_NAME, ORDINAL_POSITION"
    $idxQ     = "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='booking_db' GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME"
    $counts   = @{}
    foreach ($t in ((Invoke-MysqlExecLocal -ContainerId $ContainerId -Query $namesQ) -split "`n" |
                Where-Object { $_ } | ForEach-Object { $_.Trim() })) {
        $counts[$t] = (Invoke-MysqlExecLocal -ContainerId $ContainerId -Query "SELECT COUNT(*) FROM booking_db.$t").Trim()
    }
    $text  = (Invoke-MysqlExecLocal -ContainerId $ContainerId -Query $tblFpQ) + "`n"
    $text += (Invoke-MysqlExecLocal -ContainerId $ContainerId -Query $colQ) + "`n"
    $text += (Invoke-MysqlExecLocal -ContainerId $ContainerId -Query $idxQ)
    return [pscustomobject]@{ Text = $text; Counts = $counts }
}

try {
    # ---- Pre state --------------------------------------------------------------
    $pre = Get-Fingerprint -ContainerId $cid
    Set-Content -LiteralPath (Join-Path $Artifacts 'pre-state.txt') -Value $pre.Text -Encoding utf8NoBOM

    Invoke-Compose -Args2 @('config') |
        Set-Content -LiteralPath (Join-Path $Artifacts 'compose-config.txt') -Encoding utf8NoBOM

    # ---- Restart / recreate (volume-preserving) ---------------------------------
    if ($Recreate) {
        Invoke-Compose -Args2 @('stop')
        Invoke-Compose -Args2 @('up', '-d', '--force-recreate')
    } else {
        Invoke-Compose -Args2 ((@('restart')) + $ServicesToCycle)
    }

    # ---- Health gate: poll EVERY cycled service until healthy within deadline ----
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $healthSnapshot = [ordered]@{}
    foreach ($svc in $ServicesToCycle) { $healthSnapshot[$svc] = 'unknown' }
    $allHealthy = $false
    $cid2 = $null
    do {
        Start-Sleep -Seconds 3
        foreach ($svc in $ServicesToCycle) {
            $healthSnapshot[$svc] = Get-ContainerHealth -ComposeService $svc
        }
        $allHealthy = $true
        foreach ($svc in $ServicesToCycle) {
            if ($healthSnapshot[$svc] -ne 'healthy') { $allHealthy = $false }
        }
        if ($allHealthy) {
            # Healthy implies reachable, but assert a real query too.
            & docker @('compose', '-f', $ComposeFile, 'ps', '-q', 'mysql') | Tee-Object -Variable cidLines2 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'compose ps during health gate failed' }
            $cid2 = ($cidLines2 | Where-Object { $_ }) | Select-Object -First 1
            if (-not $cid2) {
                $allHealthy = $false
            } else {
                try {
                    $null = Invoke-MysqlExecLocal -ContainerId $cid2 -Query 'SELECT 1'
                } catch {
                    $allHealthy = $false
                }
            }
        }
    } while (-not $allHealthy -and ((Get-Date) -lt $deadline))

    if (-not $allHealthy) {
        $states = ($healthSnapshot.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
        Write-Warning ("HEALTH GATE FAILED within {0}s: {1}" -f $TimeoutSeconds, $states)
        $results.healthSnapshot = $healthSnapshot
        $results.overallPass = $false
        Set-Content -LiteralPath (Join-Path $Artifacts 'result.json') `
            -Value ($results | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
        exit 2
    }

    Invoke-Compose -Args2 @('logs', '--no-color', '--tail', '200') |
        Set-Content -LiteralPath (Join-Path $Artifacts 'stack-logs-tail.txt') -Encoding utf8NoBOM

    # ---- Post state and comparison ------------------------------------------------
    $post = Get-Fingerprint -ContainerId $cid2
    Set-Content -LiteralPath (Join-Path $Artifacts 'post-state.txt') -Value $post.Text -Encoding utf8NoBOM

    $fingerprintIdentical = ($pre.Text.Trim() -eq $post.Text.Trim())
    $countsDiffs = [System.Collections.Generic.List[string]]::new()
    foreach ($k in $pre.Counts.Keys) {
        if ($pre.Counts[$k] -ne $post.Counts[$k]) {
            $countsDiffs.Add("$k pre=$($pre.Counts[$k]) post=$($post.Counts[$k])")
        }
    }

    $results.fingerprintIdentical = [bool]$fingerprintIdentical
    $results.countDiffs           = @($countsDiffs)
    $results.healthSnapshot       = $healthSnapshot
    $results.allCycledServicesHealthy = [bool]$allHealthy
    $results.overallPass = ($fingerprintIdentical -and $countsDiffs.Count -eq 0 -and $allHealthy)

    Set-Content -LiteralPath (Join-Path $Artifacts 'result.json') `
        -Value ($results | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
} catch {
    Write-Warning ("restart-persistence-check aborted: {0}" -f $_.Exception.Message)
    exit 1
}

if ($results.overallPass) { Write-Output "RESTART-PERSISTENCE-CHECK PASS ($RunId)"; exit 0 }
Write-Warning "RESTART-PERSISTENCE-CHECK FAIL ($RunId)"
exit 2
