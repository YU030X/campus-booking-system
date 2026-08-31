#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane D: Redis-outage behavior proof - T07 booking lock MUST fail closed;
    T12 availability Cache Aside MUST fall back to MySQL.
.DESCRIPTION
    STATIC PLAN - never executed yet (tasks.md 5.4 stays unchecked until real run).

    Contract under test (source of truth):
      * T07: booking POST must return HTTP 409 with envelope code 43000 and the
        SYSTEM_BUSY message/category; no DB-only fallback; zero mutation of
        booking/booking_slot rows (counts compared before vs after).
        (BookingLockCoordinator.java:29-65, BookingMessages.java:5)
      * T12: the merged AvailabilityService consumes AvailabilityCachePort. The
        outage GET MUST return HTTP 200/code 0/data, preserve DB state, and record
        latency. Passing still requires this live observation; merged wiring alone
        is not acceptance evidence.
    Safety contract:
      * BaseUrl strictly local: only 127.0.0.1 / localhost / ::1 accepted;
        anything else exits refused WITHOUT any request.
      * Student token arrives via env T13_STUDENT_TOKEN only; every artifact is
        scrubbed through Redact(); token/password never printed.
      * DB access authenticates with the CONTAINER's own MYSQL_USER/MYSQL_PASSWORD
        (the compose-created app account) - host-side DB_* env vars are NOT used.
      * finally guarantees redis restart attempt even on failure paths.
    Exit codes: 0 pass | 1 environment | 2 assertion/refusal | 3 blocked.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1',
    [string]$ComposeFile = '',
    [int]$ResourceId = 1,
    [string]$StartTime = '',
    [string]$EndTime = '',
    [int]$AttendeeCount = 1,
    [string]$Purpose = 'T13 redis-failure probe',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# RunId feeds artifact paths and log lines: strictly bound.
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

if (-not $ComposeFile)  { $ComposeFile  = (Resolve-Path (Join-Path $PSScriptRoot '..\compose.yml')).Path }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
$Artifacts = Join-Path $ArtifactRoot $RunId
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null

# ---- Strict local-endpoint refusal --------------------------------------------
$uri = [uri]$BaseUrl
$localHosts = @('127.0.0.1', 'localhost', '::1')
if ($localHosts -notcontains $uri.Host) {
    Write-Warning ("REFUSED: BaseUrl host '{0}' is not local - lane D runs against loopback only" -f $uri.Host)
    exit 2
}

function Redact {
    param([AllowEmptyCollection()][string[]]$Secrets, [string]$Text)
    foreach ($s in $Secrets) { if ($s) { $Text = $Text.Replace($s, '***REDACTED***') } }
    return $Text
}

# ---- Required runtime-injected credential --------------------------------------
$studentToken = $env:T13_STUDENT_TOKEN
if (-not $studentToken) {
    Write-Warning "BLOCKED: missing required environment T13_STUDENT_TOKEN; nothing was executed"
    exit 3
}

& docker @('compose', '-f', $ComposeFile, 'ps', '-q', 'mysql') | Tee-Object -Variable cidLines | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'compose ps failed' }
$cid = ($cidLines | Where-Object { $_ }) | Select-Object -First 1
if (-not $cid) { Write-Warning 'BLOCKED: local mysql not running'; exit 3 }

# The app account lives INSIDE the container (MYSQL_USER/MYSQL_PASSWORD created
# by the official image first-init). Queries authenticate via container-local
# env inside sh; query text rides an exec-local env var. Host DB_* env vars are
# deliberately unused.
function Invoke-AppMysqlExec {
    param([string]$Query)
    $out = & docker @('exec', '-e', "T13Q=$Query", $cid,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_PASSWORD}" exec mysql -u"${MYSQL_USER}" --batch --skip-column-names booking_db -e "$T13Q"')
    if ($LASTEXITCODE -ne 0) { throw "mutation-check query failed (query redacted)" }
    return ($out -join "`n")
}

function Invoke-ComposeChecked {
    param([string[]]$Args2)
    $fullArgs = @('compose', '-f', $ComposeFile) + $Args2
    & docker @fullArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("compose step '{0}' failed with exit {1}" -f ($Args2 -join ' '), $LASTEXITCODE)
    }
}

if (-not $StartTime) {
    $StartTime = (Get-Date).Date.AddDays(1).AddHours(10).ToString('yyyy-MM-dd HH:mm:ss')
}
if (-not $EndTime) {
    $EndTime = (Get-Date).Date.AddDays(1).AddHours(11).ToString('yyyy-MM-dd HH:mm:ss')
}
$dateOnly = ($StartTime -split ' ')[0]

# SYSTEM_BUSY constant mirrored from booking-api BookingMessages.java:5
$sysBusyMessage = '当前预约请求较多，请稍后重试'

$results = [ordered]@{ runId = $RunId; baseUrl = $BaseUrl; overallPass = $false }
try {
    function Get-MutationSnapshot {
        param([string]$Label)
        $bookingTotal = (Invoke-AppMysqlExec -Query 'SELECT COUNT(*) FROM `booking`').Trim()
        $slotTotal    = (Invoke-AppMysqlExec -Query 'SELECT COUNT(*) FROM `booking_slot`').Trim()
        $window       = (Invoke-AppMysqlExec -Query "SELECT COUNT(*) FROM `booking` WHERE resource_id=$ResourceId AND start_time >= '$dateOnly 00:00:00' AND end_time <= '$dateOnly 23:59:59'").Trim()
        Write-Output "$Label snapshot: booking=$bookingTotal slot=$slotTotal window=$window"
        return [ordered]@{ booking = $bookingTotal; slot = $slotTotal; window = $window }
    }

    $pre = Get-MutationSnapshot -Label 'pre'

    # ---- Stop redis ------------------------------------------------------------
    Invoke-ComposeChecked -Args2 @('stop', 'redis')

    try {
        # ---- T07: booking creation during outage MUST fail closed --------------
        $headers = @{ Authorization = "Bearer $studentToken" }
        $payload = @{
            resourceId    = "$ResourceId"
            startTime     = $StartTime
            endTime       = $EndTime
            purpose       = $Purpose
            attendeeCount = $AttendeeCount
        } | ConvertTo-Json -Compress

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/bookings" -Method Post `
            -Headers $headers -ContentType 'application/json' -Body $payload `
            -SkipHttpErrorCheck -TimeoutSec 30
        $sw.Stop()
        $bodyText = Redact -Secrets @($studentToken) -Text $resp.Content
        Set-Content -LiteralPath (Join-Path $Artifacts 't07-response.txt') `
            -Value "status=$($resp.StatusCode)`nbody=$bodyText" -Encoding utf8NoBOM

        $json = $null
        try { $json = $resp.Content | ConvertFrom-Json } catch { }

        $codeOk   = ($null -ne $json -and [string]$json.code -eq '43000')
        $message  = if ($null -ne $json) { [string]$json.message } else { '' }
        $category = if ($null -ne $json -and $json.PSObject.Properties['category']) { [string]$json.category } else { '' }
        $busyOk   = ($message -eq $sysBusyMessage) -or ($category -match 'SYSTEM_BUSY')
        $statusOk = ([int]$resp.StatusCode -eq 409)

        $post = Get-MutationSnapshot -Label 'post'
        $noMutation = ($post.booking -eq $pre.booking -and $post.slot -eq $pre.slot -and $post.window -eq $pre.window)

        $results.t07 = [ordered]@{
            httpStatus = [int]$resp.StatusCode
            code43000  = [bool]$codeOk
            messageMatchedSystemBusy = [bool]$busyOk
            capturedMessage = $message
            capturedCategory = $category
            noMutation = [bool]$noMutation
            latencyMs  = $sw.ElapsedMilliseconds
        }
        $results.t07Pass = [bool]($statusOk -and $codeOk -and $busyOk -and $noMutation)

        # ---- T12: merged cache-backed read must fall back to MySQL ---------------
        # Strict live assertions: HTTP 200 + envelope code 0 + data + zero DB mutation.
        $preT12 = Get-MutationSnapshot -Label 't12-pre'
        $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
        $availStatus = 0; $availNote = ''; $json2 = $null
        try {
            $r2 = Invoke-WebRequest -Uri "$BaseUrl/api/v1/resources/$ResourceId/available-slots?date=$dateOnly" `
                -Method Get -Headers $headers -SkipHttpErrorCheck -TimeoutSec 30
            $sw2.Stop()
            $availStatus = [int]$r2.StatusCode
            $availNote = Redact -Secrets @($studentToken) -Text ($r2.Content.Substring(0, [Math]::Min(400, $r2.Content.Length)))
            try { $json2 = $r2.Content | ConvertFrom-Json } catch { }
        } catch {
            $sw2.Stop()
            $availStatus = -1
            $availNote = 'request threw (connection-level failure): ' + $_.Exception.Message
        }
        $postT12 = Get-MutationSnapshot -Label 't12-post'
        $noT12Mutation = ($postT12.booking -eq $preT12.booking -and $postT12.slot -eq $preT12.slot -and $postT12.window -eq $preT12.window)
        $envCode = if ($null -ne $json2) { [string]$json2.code } else { '' }
        $dataPresent = ($null -ne $json2 -and $json2.PSObject.Properties['data'] -and $null -ne $json2.data)
        $results.t12 = [ordered]@{
            proofStatus = 'LIVE_FALLBACK_OBSERVATION'
            note = 'Merged AvailabilityService consumes AvailabilityCachePort; strict outage assertions applied.'
            httpStatus = $availStatus
            envelopeCode = $envCode
            dataPresent = [bool]$dataPresent
            noMutation  = [bool]$noT12Mutation
            latencyMs   = $sw2.ElapsedMilliseconds
            consistencyNote = $availNote
        }
        $results.t12Pass = [bool](($availStatus -eq 200) -and ($envCode -eq '0') -and $dataPresent -and $noT12Mutation)
    } finally {
        & docker @('compose', '-f', $ComposeFile, 'start', 'redis')
        if ($LASTEXITCODE -ne 0) { Write-Warning 'redis restart failed - operator action required' }
    }

    # ---- Redis recovery gate -------------------------------------------------------
    Invoke-ComposeChecked -Args2 @('start', 'redis') *> $null   # idempotent re-check
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        & docker @('compose', '-f', $ComposeFile, 'exec', '-T', 'redis', 'redis-cli', 'ping') |
            Tee-Object -Variable pingOut | Out-Null
        if (($pingOut | Where-Object { $_ -match 'PONG' })) { break }
    } while ((Get-Date) -lt $deadline)
    $results.redisRecovered = [bool](($pingOut | Where-Object { $_ -match 'PONG' }))

    $results.overallPass = ($results.t07Pass -and $results.t12Pass -and $results.redisRecovered)
}
catch {
    $errLine = Redact -Secrets @($studentToken) -Text $_.Exception.Message
    Set-Content -LiteralPath (Join-Path $Artifacts 'error.txt') -Value $errLine -Encoding utf8NoBOM
    Write-Warning ("redis-failure-check aborted: {0}" -f $errLine)
    exit 1
}

Set-Content -LiteralPath (Join-Path $Artifacts 'result.json') `
    -Value (Redact -Secrets @($studentToken) -Text ($results | ConvertTo-Json -Depth 6)) -Encoding utf8NoBOM

$verdictLine = ("REDIS-FAILURE-CHECK ({0}): t07={1} t12={2} redisRecovered={3}" -f `
    $RunId, $results.t07Pass, $results.t12.proofStatus, $results.redisRecovered)
Write-Output $verdictLine
if ($results.overallPass) { Write-Output 'PASS'; exit 0 }
if (-not $results.t07Pass) { Write-Warning 'FAIL: T07 fail-closed assertions did not hold.'; exit 2 }
if (-not $results.t12Pass) {
    Write-Warning 'FAIL: T12 live fallback proof failed strict status/code/data/no-mutation assertions.'
    exit 2
}
Write-Warning 'FAIL: redis did not recover within the deadline.'
exit 2
