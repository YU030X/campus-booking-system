#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane E (offline): classify an XML JTL produced by run.ps1 and emit a
    redacted report.json/report.md.
.DESCRIPTION
    STATIC PLAN - pure offline parsing; never executes jmeter/docker.

    Classification is STRICTLY MUTUALLY EXCLUSIVE, evaluated per sample in this
    order (first match wins):
      0. connection_error : failed transport (rc empty/0/'Non HTTP response')
      1. server_error     : HTTP >= 500
      2. data_error       : an envelope-bearing HTTP response whose body is
                            unparseable or lacks canonical code/message keys,
                            OR HTTP 201 whose code/data do not conform
                            (code != 0 or data missing)
      3. success          : HTTP 201 AND code == 0 AND data present
      4. system_busy      : HTTP 409 AND code == 43000 AND message EXACTLY
                            '当前预约请求较多，请稍后重试' (BookingMessages.java:5)
                            OR category matches SYSTEM_BUSY
      5. business_conflict: HTTP 409 AND code == 43000 AND message EXACTLY
                            '该时段已被占用，请刷新后重试' (BookingMessages.java:4)
                            OR category SLOT_CONFLICT/BOOKING_SLOT_CONFLICT
                            (41000/42000 alone NEVER classify as business)
      6. other            : everything else (401/403/404/422 envelopes, 409 with
                            non-matching 43000 text, etc.)
    A 409 is NEVER automatically labeled a business conflict.

    The 1/99 assertion applies ONLY when ALL hold: scenario == same-slot AND
    round is NOT the historical baseline AND
    redisExpectedHealthy == true AND redisObserved == 'healthy' (strict AND -
    a round that only EXPECTS healthy redis without observing it, or observes
    healthy without expecting it, is NOT protected) AND validSeed attested.
    Assertion set: exactly 1 success + 99 business_conflict + 0 system_busy +
    0 data_error + 0 server/connection/other; booking delta == success count;
    booking_slot delta == success count * slotsPerBooking (run-recorded,
    positive 30-min-aligned integer; missing/invalid => fail).
    Baseline duplicates => historical-vulnerability-evidence (no 1/99).
    Distinct scenario => lock-granularity distribution only (no 1/99).

    PRIVACY: no raw response bodies are emitted. Per class the report carries
    at most the FIRST sample's {httpStatus, code, message, category} with
    message scrubbed through a sensitive key/value regex. The raw JTL stays a
    local git-ignored artifact; publishing it requires a separate redaction and
    secret/PII scan.
    Exit codes: 0 pass | 2 classification/assertion failure | 3 blocked inputs.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RunDir,
    [string]$ReportDir = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $RunDir)) { Write-Warning "BLOCKED: RunDir not found: $RunDir"; exit 3 }

$jtlPath = Join-Path $RunDir 'results.xml'
$metaPath = Join-Path $RunDir 'run-metadata.json'
$jmeterLogPath = Join-Path $RunDir 'jmeter.log'
if (-not (Test-Path -LiteralPath $jtlPath))  { Write-Warning "BLOCKED: results.xml missing (plan artifacts only?)"; exit 3 }
if (-not (Test-Path -LiteralPath $metaPath)) { Write-Warning "BLOCKED: run-metadata.json missing"; exit 3 }
if (-not (Test-Path -LiteralPath $jmeterLogPath)) { Write-Warning "BLOCKED: jmeter.log missing"; exit 3 }

if (-not $ReportDir) { $ReportDir = $RunDir }
if (-not (Test-Path -LiteralPath $ReportDir)) {
    New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
}

$metadata = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
$token = $env:T13_STUDENT_TOKEN

function Stop-Blocked {
    param([string]$Message)
    Write-Warning "BLOCKED: $Message"
    exit 3
}

function Get-RequiredProperty {
    param($Object, [string]$Name)
    $property = if ($Object) { $Object.PSObject.Properties[$Name] } else { $null }
    if (-not $property -or $null -eq $property.Value) {
        Stop-Blocked "run metadata is missing '$Name'"
    }
    return $property.Value
}

function Get-RequiredText {
    param($Object, [string]$Name)
    $value = [string](Get-RequiredProperty -Object $Object -Name $Name)
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^<.*>$') {
        Stop-Blocked "run metadata '$Name' is empty or still a placeholder"
    }
    return $value
}

function Get-RequiredLong {
    param($Object, [string]$Name, [long]$Minimum = 0)
    $raw = Get-RequiredProperty -Object $Object -Name $Name
    [long]$value = 0
    if (-not [long]::TryParse([string]$raw, [ref]$value) -or $value -lt $Minimum) {
        Stop-Blocked "run metadata '$Name' must be an integer >= $Minimum"
    }
    return $value
}

function Get-RequiredBoolean {
    param($Object, [string]$Name)
    $value = Get-RequiredProperty -Object $Object -Name $Name
    if ($value -isnot [bool]) { Stop-Blocked "run metadata '$Name' must be a JSON boolean" }
    return [bool]$value
}

function Get-RequiredCounts {
    param($Object, [string]$Name)
    $counts = Get-RequiredProperty -Object $Object -Name $Name
    return [ordered]@{
        bookingTotal = Get-RequiredLong -Object $counts -Name 'bookingTotal' -Minimum 0
        slotTotal = Get-RequiredLong -Object $counts -Name 'slotTotal' -Minimum 0
        scopeWindow = Get-RequiredLong -Object $counts -Name 'scopeWindow' -Minimum 0
    }
}

$roundId = Get-RequiredText -Object $metadata -Name 'roundId'
$scenario = Get-RequiredText -Object $metadata -Name 'scenario'
if ($scenario -notin @('same-slot', 'distinct')) { Stop-Blocked "scenario must be same-slot or distinct" }
$isolatedHistorical = Get-RequiredBoolean -Object $metadata -Name 'isolatedHistorical'
$historyMirror = Get-RequiredProperty -Object $metadata -Name 'historyMirror'
$historyMirrorText = $historyMirror | ConvertTo-Json -Compress -Depth 6
if ([string]::IsNullOrWhiteSpace($historyMirrorText) -or $historyMirrorText -match '"<[^>]+>"') {
    Stop-Blocked "run metadata 'historyMirror' is empty or still contains a placeholder"
}
$redisExpectedHealthy = Get-RequiredBoolean -Object $metadata -Name 'redisExpectedHealthy'
$redisObserved = Get-RequiredText -Object $metadata -Name 'redisObserved'
$validSeedAttested = Get-RequiredBoolean -Object $metadata -Name 'validSeedAttested'
$threads = Get-RequiredLong -Object $metadata -Name 'threads' -Minimum 1
$rampSeconds = Get-RequiredLong -Object $metadata -Name 'rampSeconds' -Minimum 0
$loops = Get-RequiredLong -Object $metadata -Name 'loops' -Minimum 1
if ($threads -ne 100 -or $rampSeconds -ne 1 -or $loops -ne 1) {
    Stop-Blocked 'thread/ramp/loop contract must be exactly 100/1/1'
}
$baseUrl = Get-RequiredText -Object $metadata -Name 'baseUrl'
try { $baseUri = [uri]$baseUrl } catch { Stop-Blocked 'baseUrl must be an absolute URI' }
if (-not $baseUri.IsAbsoluteUri -or $baseUri.Scheme -notin @('http', 'https') -or
    $baseUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or $baseUri.AbsolutePath -ne '/' -or
    $baseUri.Query -or $baseUri.UserInfo -or $baseUri.Fragment) {
    Stop-Blocked 'baseUrl must be a root loopback http/https URL without query, userinfo, or fragment'
}
$jmeterVersion = Get-RequiredText -Object $metadata -Name 'jmeterVersion'
$dockerVersion = Get-RequiredText -Object $metadata -Name 'dockerVersion'
$gitHead = Get-RequiredText -Object $metadata -Name 'gitHead'
if ($gitHead -notmatch '^[0-9a-fA-F]{7,40}$') { Stop-Blocked 'gitHead must be a 7-40 character hexadecimal revision' }
$jmeterExitCode = Get-RequiredLong -Object $metadata -Name 'jmeterExitCode' -Minimum 0
$preCounts = Get-RequiredCounts -Object $metadata -Name 'preCounts'
$postCounts = Get-RequiredCounts -Object $metadata -Name 'postCounts'

function Redact {
    param([AllowEmptyCollection()][string[]]$Secrets, [string]$Text)
    foreach ($s in $Secrets) { if ($s) { $Text = $Text.Replace($s, '***REDACTED***') } }
    return $Text
}

# Mask sensitive keys/values potentially embedded in server-echoed text.
function Hide-SensitiveValues {
    param([string]$Text)
    $patterns = @(
        '(?i)("(?:password|passwd|token|secret|authorization|api[_-]?key)"\s*:\s*)"[^"]*"',
        '(?i)((?:password|token|secret|authorization)=)[^\s&]+'
    )
    foreach ($p in $patterns) {
        $Text = [regex]::Replace($Text, $p, { param($m) $m.Groups[1].Value + '***' })
    }
    return $Text
}

$sysBusyMessage   = '当前预约请求较多，请稍后重试'   # BookingMessages.java:5
$slotConflictMessage = '该时段已被占用，请刷新后重试' # BookingMessages.java:4

function Classify-Sample {
    param([string]$ResponseCode, [bool]$TransportFailed, [string]$Body)
    $rc = 0
    [void][int]::TryParse(($ResponseCode ?? ''), [ref]$rc)

    # 0. transport-level failure (no HTTP response at all)
    if ($TransportFailed -or $rc -eq 0 -or $ResponseCode -match 'Non HTTP response') {
        return 'connection_error'
    }
    # 1. server-side failure
    if ($rc -ge 500) { return 'server_error' }

    # Envelope parse - the API always answers with the canonical envelope.
    $json = $null
    try { $json = $Body | ConvertFrom-Json } catch { }
    $hasCode    = ($null -ne $json -and $json.PSObject.Properties['code'] -and $null -ne $json.code)
    $hasMessage = ($null -ne $json -and $json.PSObject.Properties['message'])
    $hasData    = ($null -ne $json -and $json.PSObject.Properties['data'] -and $null -ne $json.data)
    $code       = if ($hasCode)    { [string]$json.code }    else { '' }
    $message    = if ($hasMessage) { [string]$json.message } else { '' }
    $category   = if ($null -ne $json -and $json.PSObject.Properties['category'] -and $json.category) { [string]$json.category } else { '' }

    # 2. envelope-bearing response that is unparseable or non-canonical
    if (-not $json -or -not $hasCode -or -not $hasMessage) { return 'data_error' }

    # 3. success needs conforming code AND data
    if ($rc -eq 201) {
        if ($code -eq '0' -and $hasData) { return 'success' }
        return 'data_error'
    }

    # 4/5. conflict family (409 + 43000 + exact message/category only)
    if ($rc -eq 409 -and $code -eq '43000') {
        if ($message -eq $sysBusyMessage -or $category -match 'SYSTEM_BUSY') {
            return 'system_busy'
        }
        if ($message -eq $slotConflictMessage -or $category -match '^(SLOT_CONFLICT|BOOKING_SLOT_CONFLICT)$') {
            return 'business_conflict'
        }
        return 'other'
    }

    # 6. everything else (401/403/404/422, 409 without 43000, ...)
    return 'other'
}

# ---- Parse XML JTL ----------------------------------------------------------------
[xml]$doc = Get-Content -LiteralPath $jtlPath -Raw
$samples = $doc.SelectNodes('//httpSample')
if ($samples.Count -eq 0) { Write-Warning 'BLOCKED: no httpSample nodes in JTL.'; exit 3 }

$classes = [ordered]@{
    success = 0; business_conflict = 0; system_busy = 0
    server_error = 0; connection_error = 0; data_error = 0; other = 0
}
$latencies = New-Object System.Collections.Generic.List[double]
$firstPerClass = @{}
foreach ($s in $samples) {
    $body = ''
    $bodyNode = $s.SelectSingleNode('responseData')
    if ($bodyNode) { $body = [string]$bodyNode.InnerText }
    $transportFailed = ([string]$s.s -eq 'false')
    $class = Classify-Sample -ResponseCode ([string]$s.rc) -TransportFailed $transportFailed -Body $body
    $classes[$class]++
    [double]$ms = 0
    [void][double]::TryParse([string]$s.t, [ref]$ms)
    $latencies.Add($ms)

    # First-sample digest per class: metadata only, NO raw body, NO data field.
    if (-not $firstPerClass[$class]) {
        $j = $null
        try { $j = $body | ConvertFrom-Json } catch { }
        $digest = [ordered]@{
            httpStatus = [string]$s.rc
            code = ''
            message = ''
            category = ''
        }
        if ($null -ne $j) {
            if ($j.PSObject.Properties['code'])    { $digest.code    = [string]$j.code }
            if ($j.PSObject.Properties['message']) { $digest.message = Hide-SensitiveValues -Text ([string]$j.message) }
            if ($j.PSObject.Properties['category']){ $digest.category = [string]$j.category }
        }
        $firstPerClass[$class] = $digest
    }
}

$sorted = $latencies.ToArray(); [Array]::Sort($sorted)
function Get-Pctl {
    param([double[]]$Vals, [double]$P)
    if ($Vals.Count -eq 0) { return 0 }
    $idx = [Math]::Ceiling(($P / 100.0) * $Vals.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    return $Vals[$idx]
}
$stats = [ordered]@{
    count = $latencies.Count
    avgMs = [math]::Round((($latencies | Measure-Object -Average).Average), 1)
    p95Ms = (Get-Pctl -Vals $sorted -P 95)
    p99Ms = (Get-Pctl -Vals $sorted -P 99)
}

# ---- Round semantics ----------------------------------------------------------------
$isBaseline = ($roundId -eq 'vulnerable-baseline')
# STRICT AND: both the expectation AND the observation are required.
$protectedRedis = ($redisExpectedHealthy -and $redisObserved -eq 'healthy')
$validSeed      = $validSeedAttested

$preB = [long]$preCounts.bookingTotal; $postB = [long]$postCounts.bookingTotal
$preS = [long]$preCounts.slotTotal; $postS = [long]$postCounts.slotTotal
$preW = [long]$preCounts.scopeWindow; $postW = [long]$postCounts.scopeWindow
$deltaB = $postB - $preB
$deltaS = $postS - $preS
$deltaW = $postW - $preW

$slotsValue = $null
if ($scenario -eq 'same-slot') {
    $slotsValue = Get-RequiredLong -Object $metadata -Name 'slotsPerBooking' -Minimum 1
}

$results = [ordered]@{
    runDir = $RunDir
    roundId = $roundId
    scenario = $scenario
    execution = [ordered]@{
        threads = $threads
        rampSeconds = $rampSeconds
        loops = $loops
        slotsPerBooking = $slotsValue
        jmeterExitCode = $jmeterExitCode
        jmeterExitOk = ($jmeterExitCode -eq 0)
    }
    environment = [ordered]@{
        baseUrl = $baseUrl
        jmeterVersion = $jmeterVersion
        dockerVersion = $dockerVersion
        gitHead = $gitHead
        historyMirror = $historyMirror
        isolatedHistorical = $isolatedHistorical
        redisExpectedHealthy = $redisExpectedHealthy
        redisObserved = $redisObserved
        validSeedAttested = $validSeedAttested
    }
    classification = $classes
    latency = $stats
    firstSamplePerClass = $firstPerClass
    database = [ordered]@{
        preCounts = $preCounts
        postCounts = $postCounts
        rowDeltas = [ordered]@{ booking = $deltaB; booking_slot = $deltaS; scopeWindow = $deltaW }
    }
    evidence = [ordered]@{
        rawJtl = 'results.xml'
        jmeterLog = 'jmeter.log'
        runMetadata = 'run-metadata.json'
        databasePreCounts = 'run-metadata.json#preCounts'
        databasePostCounts = 'run-metadata.json#postCounts'
    }
}

if ($scenario -eq 'same-slot' -and $isBaseline) {
    $results.verdictScope = 'historical-vulnerability-evidence'
    $results.assertion = 'NONE - duplicates are expected for the pre-unique-index baseline; the 1/99 contract does not apply.'
    $results.pass = $null
} elseif ($scenario -eq 'distinct') {
    $results.verdictScope = 'lock-granularity-report'
    $results.assertion = 'NONE - distinct scenario reports distribution only; no 1/99 contract and no row-delta ratio rule.'
    $results.pass = $null
} elseif ($scenario -eq 'same-slot' -and $protectedRedis -and $validSeed) {
    $slotsOk = ($null -ne $slotsValue -and $slotsValue -gt 0)
    $oneSuccess  = ($classes.success -eq 1)
    $ninetyNine  = ($classes.business_conflict -eq 99)
    $noBusy      = ($classes.system_busy -eq 0)
    $noDataErr   = ($classes.data_error -eq 0)
    $noOtherErr  = ($classes.other -eq 0 -and $classes.server_error -eq 0 -and $classes.connection_error -eq 0)
    $deltaBOk    = ($deltaB -eq $classes.success)
    $deltaSOk    = ($slotsOk -and $deltaS -eq ($classes.success * $slotsValue))
    $results.verdictScope = 'protected-same-slot'
    $results.assertion = [ordered]@{
        exactOneSuccess = [bool]$oneSuccess
        exactly99BusinessConflicts = [bool]$ninetyNine
        zeroSystemBusy = [bool]$noBusy
        zeroDataErrors = [bool]$noDataErr
        zeroServerConnectionOther = [bool]$noOtherErr
        bookingDeltaEqualsSuccess = [bool]$deltaBOk
        slotDeltaEqualsSuccessTimesSlotsPerBooking = [bool]$deltaSOk
        slotsPerBooking = $slotsValue
        slotsPerBookingValid = [bool]$slotsOk
        protectedRedisExpectationAndObservation = [bool]$protectedRedis
    }
    $results.pass = [bool]($oneSuccess -and $ninetyNine -and $noBusy -and $noDataErr -and $noOtherErr -and $deltaBOk -and $deltaSOk)
} else {
    $results.verdictScope = 'unprotected-or-incomplete-metadata'
    $results.assertion = 'NONE - same-slot round without BOTH redisExpectedHealthy=true AND observed healthy, or without validSeed attestation.'
    $results.pass = $null
}

# Preserve an auditable report for an aborted/non-zero JMeter run, but never let
# otherwise-good samples turn that execution into PASS or report-only success.
if ($jmeterExitCode -ne 0) {
    $results.executionFailure = "JMeter exited non-zero ($jmeterExitCode); samples are retained for diagnosis only."
    $results.pass = $false
}

$reportJson = Redact -Secrets @($token) -Text ($results | ConvertTo-Json -Depth 6)
Set-Content -LiteralPath (Join-Path $ReportDir 'report.json') -Value $reportJson -Encoding utf8NoBOM

$md = [System.Text.StringBuilder]::new()
[void]$md.AppendLine("# Concurrency round report - $($metadata.roundId) ($scenario)")
[void]$md.AppendLine()
[void]$md.AppendLine("| class | count |")
[void]$md.AppendLine("|---|---|")
foreach ($k in $classes.Keys) { [void]$md.AppendLine("| $k | $($classes[$k]) |") }
[void]$md.AppendLine()
[void]$md.AppendLine("latency: count=$($stats.count) avg=$($stats.avgMs)ms p95=$($stats.p95Ms)ms p99=$($stats.p99Ms)ms")
[void]$md.AppendLine("execution: threads=$threads rampSeconds=$rampSeconds loops=$loops slotsPerBooking=$slotsValue jmeterExitCode=$jmeterExitCode jmeterExitOk=$($jmeterExitCode -eq 0)")
[void]$md.AppendLine("environment: baseUrl=$baseUrl jmeter=$jmeterVersion docker=$dockerVersion gitHead=$gitHead")
[void]$md.AppendLine("round: historyMirror=$historyMirrorText isolatedHistorical=$isolatedHistorical redisExpectedHealthy=$redisExpectedHealthy redisObserved=$redisObserved validSeedAttested=$validSeedAttested")
[void]$md.AppendLine("pre counts: booking=$preB booking_slot=$preS scopeWindow=$preW")
[void]$md.AppendLine("post counts: booking=$postB booking_slot=$postS scopeWindow=$postW")
[void]$md.AppendLine("row deltas: booking=$deltaB booking_slot=$deltaS scopeWindow=$deltaW")
[void]$md.AppendLine('evidence: results.xml | jmeter.log | run-metadata.json#preCounts | run-metadata.json#postCounts')
[void]$md.AppendLine("verdictScope: $($results.verdictScope); pass: $($results.pass)")
[void]$md.AppendLine("assertion: $($results.assertion | ConvertTo-Json -Compress)")
Set-Content -LiteralPath (Join-Path $ReportDir 'report.md') `
    -Value (Redact -Secrets @($token) -Text ($md.ToString())) -Encoding utf8NoBOM

Write-Output ("report written: {0}" -f (Join-Path $ReportDir 'report.json'))
if ($null -ne $results.pass) {
    if ($results.pass) { Write-Output 'SUMMARIZE PASS'; exit 0 }
    Write-Warning 'SUMMARIZE FAIL'
    exit 2
}
Write-Output 'SUMMARIZE REPORT-ONLY (no 1/99 assertion scope)'
exit 0
