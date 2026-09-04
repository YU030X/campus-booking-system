#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane E: run ONE concurrency round of deploy/jmeter/booking-concurrency.jmx
    against a strictly local stack and capture classified-later evidence.
.DESCRIPTION
    Default is PLAN MODE. Pass -Execute to actually invoke jmeter. The offline
    contract suite covers plan/safety/report logic only; no real JMeter round has
    run and the three-round report gate remains unchecked.

    Safety and correctness contract (static review only):
      * BaseUrl per round must be loopback (127.0.0.1 / localhost / ::1) with
        scheme http/https, empty path/query/userinfo/fragment - a deeper path or
        query would be silently dropped by the JMX domain/port split and is
        therefore refused.
      * -Execute requires -RoundId; exactly ONE scenario may run per execution:
        runSameSlot/runDistinct are emitted mutually exclusively (never mixed in
        one JTL - contract 3.2).
      * Round id 'vulnerable-baseline' requires BOTH config enabled=true +
        isolatedHistorical=true AND the operator switch -AllowHistoricalBaseline.
      * SAME-SLOT token path: env T13_STUDENT_TOKEN is written to a RANDOMLY
        named SECRET properties file under the system temp directory and loaded
        via a SECOND `-q` argument - the token value NEVER appears in argv, in
        the regular properties file, or in artifacts. File creation, args
        construction, the jmeter invocation and post-run scrub all live inside
        ONE try block whose finally deletes the secret file unconditionally on
        every path (including jmeter-not-found aborts); deletion is verified
        and a failed deletion fails the run with a token-rotation instruction.
      * DISTINCT token path: run.ps1 does NOT require or use T13_STUDENT_TOKEN.
        The round config points at a runtime-generated 100-row CSV
        (token,resourceId,startTime,endTime; 4 columns, NO header line). The
        script validates structure only - exactly 100 non-empty rows, 4 fields,
        non-empty token, numeric distinct resourceIds, formatted times - and
        passes ONLY the CSV path property. Token values are never logged,
        recorded, or written to artifacts. CSV files are runtime artifacts and
        must never be committed.
      * jmeter.properties written per run force XML JTL with response bodies
        saved for offline classification, while request headers/sampler data
        (which contain Authorization) are NOT saved.
      * Pre/post booking/booking_slot row counts (+ scope-window counts) are
        captured through the round's local compose mysql container using
        container-side auth (MYSQL_PWD from the container's own env) - no host
        password is read. Same-slot rounds additionally record slotsPerBooking
        ((end-start)/30, must be a positive 30-minute-aligned integer) so
        summarize.ps1 can assert slot deltas = success * slotsPerBooking.
      * Environment metadata (jmeter/docker/git versions, thread/ramp, redis
        health, seed/history attestations) is recorded per round.
    Exit codes: 0 pass | 1 environment (incl. secret-file cleanup failure) |
    2 refused/invalid config | 3 blocked.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = '',
    [string]$RoundId = '',
    [switch]$Execute,
    [switch]$AllowHistoricalBaseline,
    [string]$JmeterPlan = '',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Unified RunId gate (feeds paths and file names, incl. temp secret file name).
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

if (-not $ConfigPath)  { $ConfigPath  = (Join-Path $PSScriptRoot 'rounds.example.json') }
if (-not $JmeterPlan)  { $JmeterPlan  = (Join-Path $PSScriptRoot 'booking-concurrency.jmx') }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null

function Redact {
    param([AllowEmptyCollection()][string[]]$Secrets, [string]$Text)
    foreach ($s in $Secrets) { if ($s) { $Text = $Text.Replace($s, '***REDACTED***') } }
    return $Text
}

$localHosts = @('127.0.0.1', 'localhost', '::1')

function Assert-LocalBaseUrl {
    # Deep validation: a non-root path, query, userinfo or fragment would be
    # silently dropped when the URL is split into protocol/host/port for the
    # JMX properties - refuse instead of mis-targeting.
    param([string]$Url)
    $u = $null
    try { $u = [uri]$Url } catch {
        Write-Warning 'REFUSED: baseUrl is not a valid absolute URI.'
        exit 2
    }
    if (-not $u.IsAbsoluteUri -or $u.Scheme -notin @('http', 'https')) {
        Write-Warning ("REFUSED: baseUrl scheme '{0}' must be http/https" -f $u.Scheme); exit 2
    }
    if ($localHosts -notcontains $u.Host) {
        Write-Warning ("REFUSED: round baseUrl host '{0}' is not local" -f $u.Host); exit 2
    }
    if ($u.AbsolutePath -ne '/') {
        Write-Warning ("REFUSED: baseUrl must have no path (got '{0}') - it would be silently dropped." -f $u.AbsolutePath); exit 2
    }
    if ($u.Query)      { Write-Warning 'REFUSED: baseUrl must not carry a query string.'; exit 2 }
    if ($u.UserInfo)   { Write-Warning 'REFUSED: baseUrl must not carry userinfo.'; exit 2 }
    if ($u.Fragment)   { Write-Warning 'REFUSED: baseUrl must not carry a fragment.'; exit 2 }
    return $u
}

function Get-ComposeContainerId {
    param([string]$File, [string]$Svc)
    & docker @('compose', '-f', $File, 'ps', '-q', $Svc) | Tee-Object -Variable lines | Out-Null
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($lines | Where-Object { $_ }) | Select-Object -First 1)
}

function Get-CountViaContainer {
    param([string]$File, [string]$Query)
    $cid = Get-ComposeContainerId -File $File -Svc 'mysql'
    if (-not $cid) { throw 'mysql container not running for the round compose file' }
    $out = & docker @('exec', '-e', "T13Q=$Query", $cid,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -uroot --batch --skip-column-names booking_db -e "$T13Q"')
    if ($LASTEXITCODE -ne 0) { throw 'row-count query failed (query redacted)' }
    return (($out -join "`n").Trim())
}

function Get-ScopeCounts {
    # WhereClause is built ONLY from regex-validated numeric ids and formatted
    # timestamps (see fixture gates below) - placeholders can never reach SQL.
    param([string]$File, [string]$WhereClause, [string]$DayFrom, [string]$DayTo)
    $bookingTotal = Get-CountViaContainer -File $File -Query 'SELECT COUNT(*) FROM booking'
    $slotTotal    = Get-CountViaContainer -File $File -Query 'SELECT COUNT(*) FROM booking_slot'
    $window       = Get-CountViaContainer -File $File -Query "SELECT COUNT(*) FROM booking WHERE $WhereClause AND start_time >= '$DayFrom 00:00:00' AND end_time <= '$DayTo 23:59:59'"
    return [ordered]@{ bookingTotal = $bookingTotal; slotTotal = $slotTotal; scopeWindow = $window }
}

function Get-RedisHealth {
    param([string]$File)
    $cid = Get-ComposeContainerId -File $File -Svc 'redis'
    if (-not $cid) { return 'no-container' }
    & docker @('exec', $cid, 'redis-cli', 'ping') | Tee-Object -Variable p | Out-Null
    if ($LASTEXITCODE -ne 0) { return 'cli-error' }
    if (($p | Where-Object { $_ -match 'PONG' })) { return 'healthy' }
    return 'unhealthy'
}

# ---- Load config ---------------------------------------------------------------
if (-not (Test-Path -LiteralPath $ConfigPath)) { Write-Warning "BLOCKED: config not found: $ConfigPath"; exit 3 }
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$rounds = @($config.rounds)
if (-not $Execute) {
    Write-Output 'PLAN MODE - nothing invoked. Rounds available:'
    foreach ($r in $rounds) {
        Write-Output ("  {0}  enabled={1} isolatedHistorical={2} redisExpectedHealthy={3} validSeed={4}" -f `
            $r.id, $r.enabled, $r.isolatedHistorical, $r.redisExpectedHealthy, $r.validSeed)
    }
    Write-Output 'Run with -Execute -RoundId <id> (baseline also needs -AllowHistoricalBaseline and config flags).'
    exit 0
}

if (-not $RoundId) { Write-Warning 'REFUSED: -Execute requires -RoundId (one round per execution).'; exit 2 }
$round = $rounds | Where-Object { $_.id -eq $RoundId } | Select-Object -First 1
if (-not $round) { Write-Warning "REFUSED: round '$RoundId' not in config."; exit 2 }
if (-not $round.enabled) { Write-Warning ("REFUSED: round '{0}' is disabled in config; enable it explicitly first." -f $RoundId); exit 2 }

# Baseline gate: config must declare isolated isolation AND operator must consent.
if ($RoundId -eq 'vulnerable-baseline') {
    if (-not $round.isolatedHistorical -or -not $AllowHistoricalBaseline) {
        Write-Warning 'REFUSED: vulnerable-baseline requires config isolatedHistorical=true AND -AllowHistoricalBaseline.'
        exit 2
    }
}

# Scenario exclusivity: exactly one scenario flag per execution (no mixing).
$scenario = [string]$round.scenario
if ($scenario -ne 'same-slot' -and $scenario -ne 'distinct') {
    Write-Warning "REFUSED: round scenario must be 'same-slot' or 'distinct'."
    exit 2
}
$ss = $round.sameSlot; $ds = $round.distinct
$timePattern = '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$'

if ($scenario -eq 'same-slot') {
    if (-not $ss -or -not $ss.resourceId -or -not $ss.startTime) {
        Write-Warning 'REFUSED: same-slot round lacks fixture values (resourceId/startTime) - expand placeholders first.'
        exit 2
    }
    if ($ss.resourceId -notmatch '^\d+$')     { Write-Warning "REFUSED: sameSlotResourceId must be numeric (got '$($ss.resourceId)') - placeholders/unexpanded fixture."; exit 2 }
    if ($ss.startTime -notmatch $timePattern) { Write-Warning "REFUSED: sameSlotStartTime must match $timePattern."; exit 2 }
    if ($ss.endTime -notmatch $timePattern)   { Write-Warning "REFUSED: sameSlotEndTime must match $timePattern."; exit 2 }

    # slotsPerBooking contract: (end-start)/30 must be a positive 30-min aligned integer.
    $startAt = [datetime]::ParseExact($ss.startTime, 'yyyy-MM-dd HH:mm:ss', $null)
    $endAt   = [datetime]::ParseExact($ss.endTime,   'yyyy-MM-dd HH:mm:ss', $null)
    $minutes = [long]($endAt - $startAt).TotalMinutes
    if (($minutes -le 0) -or (($minutes % 30) -ne 0)) {
        Write-Warning ("REFUSED: same-slot span {0} minutes is not a positive 30-minute-aligned value." -f $minutes)
        exit 2
    }
    $slotsPerBooking = [long]($minutes / 30)

    $scopeWhere = "resource_id = $($ss.resourceId)"
    $scopeFrom  = ($ss.startTime -split ' ')[0]
    $scopeTo    = ($ss.endTime   -split ' ')[0]
} else {
    # DISTINCT: fixture arrives as a runtime-generated 100-row CSV
    # (token,resourceId,startTime,endTime; 4 fields; NO header line).
    if (-not $ds -or -not $ds.csvPath) {
        Write-Warning 'REFUSED: distinct round lacks distinct.csvPath (runtime-generated 100-row CSV).'
        exit 2
    }
    if (-not (Test-Path -LiteralPath $ds.csvPath)) {
        Write-Warning "BLOCKED: distinct CSV not found: $($ds.csvPath)"
        exit 3
    }
    $allLines = @(Get-Content -LiteralPath $ds.csvPath)
    $rows = @($allLines | Where-Object { $_.Trim().Length -gt 0 })
    if ($rows.Count -ne 100) {
        Write-Warning ("REFUSED: distinct CSV must contain exactly 100 non-empty rows (got {0})." -f $rows.Count)
        exit 2
    }
    $idList = New-Object System.Collections.Generic.List[long]
    $seenIds = New-Object System.Collections.Generic.HashSet[long]
    $minDay = $null; $maxDay = $null
    $lineNo = 0
    if ($allLines.Count -ne 100 -or $rows.Count -ne 100) {
        Write-Warning ("REFUSED: distinct CSV must contain exactly 100 data rows with no blank lines (got {0} lines, {1} non-empty)." -f $allLines.Count, $rows.Count)
        exit 2
    }
    foreach ($line in $rows) {
        $lineNo++
        $parts = $line.Split(',')
        if ($parts.Count -ne 4) {
            Write-Warning ("REFUSED: distinct CSV line {0} does not have exactly 4 fields." -f $lineNo); exit 2
        }
        $rowToken = $parts[0].Trim()
        $rid = $parts[1].Trim(); $st = $parts[2].Trim(); $et = $parts[3].Trim()
        if (-not $rowToken)             { Write-Warning ("REFUSED: distinct CSV line {0} has an empty token." -f $lineNo); exit 2 }
        if ($rid -notmatch '^\d+$')     { Write-Warning ("REFUSED: distinct CSV line {0} resourceId must be numeric." -f $lineNo); exit 2 }
        if ($st -notmatch $timePattern) { Write-Warning ("REFUSED: distinct CSV line {0} startTime must match {1}." -f $lineNo, $timePattern); exit 2 }
        if ($et -notmatch $timePattern) { Write-Warning ("REFUSED: distinct CSV line {0} endTime must match {1}." -f $lineNo, $timePattern); exit 2 }
        try {
            $rowStart = [datetime]::ParseExact($st, 'yyyy-MM-dd HH:mm:ss', $null)
            $rowEnd = [datetime]::ParseExact($et, 'yyyy-MM-dd HH:mm:ss', $null)
        } catch {
            Write-Warning ("REFUSED: distinct CSV line {0} contains an invalid timestamp." -f $lineNo); exit 2
        }
        $rowMinutes = [long]($rowEnd - $rowStart).TotalMinutes
        if (($rowMinutes -le 0) -or (($rowMinutes % 30) -ne 0)) {
            Write-Warning ("REFUSED: distinct CSV line {0} span must be positive and 30-minute aligned." -f $lineNo); exit 2
        }
        try { $idVal = [long]$rid } catch {
            Write-Warning ("REFUSED: distinct CSV line {0} resourceId is outside Int64 range." -f $lineNo); exit 2
        }
        if (-not $seenIds.Add($idVal))  { Write-Warning ("REFUSED: distinct CSV line {0} repeats resourceId {1}." -f $lineNo, $rid); exit 2 }
        [void]$idList.Add($idVal)
        $d = ($st -split ' ')[0]
        if (-not $minDay -or $d -lt $minDay) { $minDay = $d }
        $d2 = ($et -split ' ')[0]
        if (-not $maxDay -or $d2 -gt $maxDay) { $maxDay = $d2 }
    }
    # Token values were validated for presence ONLY; they are never stored,
    # logged, or emitted by this script.

    $scopeWhere = "resource_id IN ($($idList -join ','))"
    $scopeFrom  = $minDay
    $scopeTo    = $maxDay
    $slotsPerBooking = $null    # not asserted for the distinct scenario
}

# Local-only endpoint (deep validation).
$uri = Assert-LocalBaseUrl -Url ([string]$round.baseUrl)

# Token requirement: SAME-SLOT only. DISTINCT rows carry their own tokens in
# the CSV; this script never needs or reads T13_STUDENT_TOKEN for them.
$token = $env:T13_STUDENT_TOKEN
if ($scenario -eq 'same-slot' -and -not $token) {
    Write-Warning 'BLOCKED: missing env T13_STUDENT_TOKEN (required for same-slot); nothing was executed.'
    exit 3
}

$composeFile = [string]$round.composeFile
if (-not $composeFile -or -not (Test-Path -LiteralPath $composeFile)) {
    Write-Warning "BLOCKED: round composeFile missing or not found (placeholder?)."
    exit 3
}

# ---- Artifacts -----------------------------------------------------------------
$Artifacts = Join-Path $ArtifactRoot "jmeter-$RoundId-$RunId"
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null
$jtlPath    = Join-Path $Artifacts 'results.xml'
$jmeterLog  = Join-Path $Artifacts 'jmeter.log'
$propsFile  = Join-Path $Artifacts 'jmeter-run.properties'

# Saveservice policy: XML + response bodies for offline classification;
# request headers / sampler data (would carry Authorization) stay OUT.
$propsText = @"
jmeter.save.saveservice.output_format=xml
jmeter.save.saveservice.response_data=true
jmeter.save.saveservice.response_data.on_error=true
jmeter.save.saveservice.request_headers=false
jmeter.save.saveservice.samplerData=false
jmeter.save.saveservice.url=false
jmeter.save.saveservice.assertion_results_failure_message=true
jmeter.save.saveservice.print_field_names=true
jmeter.save.saveservice.timestamp_format=ms
"@
Set-Content -LiteralPath $propsFile -Value $propsText -Encoding ascii

# ---- Environment metadata (executed only under -Execute) ------------------------
$jmeterVersion = (& jmeter --version 2>&1 | Out-String)
$dockerVersion = (& docker --version | Out-String)
$gitHead = (& git rev-parse HEAD | Out-String).Trim()
$redisHealth = Get-RedisHealth -File $composeFile

$metadata = [ordered]@{
    runId = $RunId
    roundId = $RoundId
    scenario = $scenario
    isolatedHistorical = [bool]$round.isolatedHistorical
    historyMirror = $round.historyMirror
    redisExpectedHealthy = [bool]$round.redisExpectedHealthy
    redisObserved = $redisHealth
    validSeedAttested = [bool]$round.validSeed
    threads = 100
    rampSeconds = 1
    loops = 1
    slotsPerBooking = $(if ($scenario -eq 'same-slot') { $slotsPerBooking } else { $null })
    baseUrl = [string]$round.baseUrl
    jmeterVersion = $jmeterVersion.Trim()
    dockerVersion = $dockerVersion.Trim()
    gitHead = $gitHead
    configPath = $ConfigPath
}
$preCounts = $null
try { $preCounts = Get-ScopeCounts -File $composeFile -WhereClause $scopeWhere -DayFrom $scopeFrom -DayTo $scopeTo } catch { }
$metadata.preCounts = $preCounts
# NOTE: secret handling below - metadata never contains token values.
Set-Content -LiteralPath (Join-Path $Artifacts 'run-metadata.json') `
    -Value ($metadata | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM

if ($preCounts) {
    Write-Output ("pre: booking={0} slot={1} window={2}" -f $preCounts.bookingTotal, $preCounts.slotTotal, $preCounts.scopeWindow)
}

# ---- Execute jmeter -------------------------------------------------------------
$scenarioProps = @()
if ($scenario -eq 'same-slot') {
    $scenarioProps += @(
        '-JrunSameSlot=true', '-JrunDistinct=false',
        "-JsameSlotResourceId=$($ss.resourceId)",
        "-JsameSlotStartTime=$($ss.startTime)",
        "-JsameSlotEndTime=$($ss.endTime)"
    )
} else {
    $scenarioProps += @(
        '-JrunSameSlot=false', '-JrunDistinct=true',
        "-JdistinctCsvPath=$($ds.csvPath)"
    )
}

$baseJmeterArgs = @(
    '-n', '-t', $JmeterPlan,
    '-l', $jtlPath,
    '-j', $jmeterLog,
    '-q', $propsFile,
    "-JbaseUrlProtocol=$($uri.Scheme)",
    "-JbaseUrlHost=$($uri.Host)",
    "-JbaseUrlPort=$($uri.Port)"
)

# Secret-file lifecycle: creation happens INSIDE the try so that ANY failure
# (jmeter missing, aborting errors, scrub faults) still reaches the finally,
# which unconditionally deletes the file and re-verifies. The file name is
# system-random - not derived from RunId - and never lands in artifacts.
$secretCleanupFailed = $false
$secretPropsPath = $null
$jmeterExit = $null
$postCounts = $null
try {
    if ($scenario -eq 'same-slot') {
        $secretPropsPath = Join-Path ([System.IO.Path]::GetTempPath()) `
            ([System.IO.Path]::GetRandomFileName() + '.properties')
        Set-Content -LiteralPath $secretPropsPath -Value "T13StudentToken=$token" -Encoding ascii
    }

    $jmeterArgs = $baseJmeterArgs
    if ($secretPropsPath) { $jmeterArgs += @('-q', $secretPropsPath) }
    $jmeterArgs += $scenarioProps

    Write-Output 'Invoking jmeter (same-slot token travels via temp secret properties; never via argv).'
    & jmeter @jmeterArgs
    $jmeterExit = $LASTEXITCODE

    # ---- Post counts + scrub ------------------------------------------------------
    try { $postCounts = Get-ScopeCounts -File $composeFile -WhereClause $scopeWhere -DayFrom $scopeFrom -DayTo $scopeTo } catch { }
    $metadata.postCounts = $postCounts
    $metadata.jmeterExitCode = $jmeterExit
    Set-Content -LiteralPath (Join-Path $Artifacts 'run-metadata.json') `
        -Value ($metadata | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM

    # Defense-in-depth: scrub the same-slot token out of JTL/log bytes if it leaked.
    foreach ($f in @($jtlPath, $jmeterLog)) {
        if ((Test-Path -LiteralPath $f) -and $token) {
            $raw = Get-Content -LiteralPath $f -Raw
            $scrubbed = Redact -Secrets @($token) -Text $raw
            if ($scrubbed -ne $raw) {
                Set-Content -LiteralPath $f -Value $scrubbed -Encoding utf8NoBOM -NoNewline
                Write-Warning ("token bytes were found and scrubbed in {0}" -f (Split-Path $f -Leaf))
            }
        }
    }
}
finally {
    if ($secretPropsPath) {
        if (Test-Path -LiteralPath $secretPropsPath) {
            Remove-Item -LiteralPath $secretPropsPath -Force -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath $secretPropsPath) {
                $secretCleanupFailed = $true
                Write-Warning ("FATAL: secret properties file could not be deleted: {0} - ROTATE the token immediately." -f $secretPropsPath)
            }
        }
    }
}

if ($secretCleanupFailed) { exit 1 }

if ($postCounts) {
    Write-Output ("post: booking={0} slot={1} window={2}" -f $postCounts.bookingTotal, $postCounts.slotTotal, $postCounts.scopeWindow)
}
if ($null -ne $jmeterExit -and $jmeterExit -ne 0) {
    Write-Warning ("jmeter exited {0}; JTL kept for offline classification." -f $jmeterExit)
    exit 1
}
if ($null -eq $jmeterExit) {
    # jmeter never ran to completion (terminating error before $LASTEXITCODE set).
    Write-Warning 'jmeter invocation did not complete; secret file was cleaned up.'
    exit 1
}
Write-Output "RUN COMPLETE - classify with summarize.ps1 against this artifact directory."
exit 0
