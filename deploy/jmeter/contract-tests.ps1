#Requires -Version 7.0
<#
.SYNOPSIS
    Offline contract tests for the T13 JMeter plan, safety gates, and summarizer.
.DESCRIPTION
    Generates synthetic XML JTL and metadata under deploy/artifacts, invokes only
    local PowerShell scripts, and never starts JMeter, Docker, or an HTTP request.
    Passing this harness proves parser/safety contracts only; it is not load-test
    or three-round performance evidence.
#>
[CmdletBinding()]
param(
    [string]$ArtifactRoot = '',
    [string]$RunId = ('contract-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [switch]$KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning "REFUSED: invalid RunId '$RunId'."
    exit 2
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ArtifactRoot) {
    $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path
}
$contractRoot = Join-Path $ArtifactRoot "jmeter-contract-$RunId"
$summarizer = Join-Path $PSScriptRoot 'summarize.ps1'
$runner = Join-Path $PSScriptRoot 'run.ps1'
$jmxPath = Join-Path $PSScriptRoot 'booking-concurrency.jmx'
$roundTemplatePath = Join-Path $PSScriptRoot 'rounds.example.json'
$secret = 'contract-secret-token-never-report'
$priorToken = $env:T13_STUDENT_TOKEN
$assertions = 0

function Assert-Contract {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "CONTRACT FAILED: $Message" }
    $script:assertions++
}

function Write-JsonFile {
    param([string]$Path, $Value)
    Set-Content -LiteralPath $Path -Value ($Value | ConvertTo-Json -Depth 8) -Encoding utf8NoBOM
}

function Invoke-ChildScript {
    param([string]$Path, [string[]]$Arguments)
    $output = @(& pwsh -NoProfile -File $Path @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = ($output -join "`n") }
}

function New-Metadata {
    param(
        [string]$RoundId,
        [ValidateSet('same-slot', 'distinct')][string]$Scenario,
        [long]$PreBooking = 10,
        [long]$PostBooking = 11,
        [long]$PreSlot = 20,
        [long]$PostSlot = 22,
        [long]$PreWindow = 3,
        [long]$PostWindow = 4
    )
    return [ordered]@{
        runId = "synthetic-$RoundId"
        roundId = $RoundId
        scenario = $Scenario
        isolatedHistorical = ($RoundId -eq 'vulnerable-baseline')
        historyMirror = "sha256:contract-$RoundId"
        redisExpectedHealthy = $true
        redisObserved = 'healthy'
        validSeedAttested = $true
        threads = 100
        rampSeconds = 1
        loops = 1
        slotsPerBooking = $(if ($Scenario -eq 'same-slot') { 2 } else { $null })
        baseUrl = 'http://127.0.0.1:18080/'
        jmeterVersion = 'Apache JMeter 5.6.3 synthetic-contract'
        dockerVersion = 'Docker version synthetic-contract'
        gitHead = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
        configPath = 'synthetic-contract.json'
        preCounts = [ordered]@{ bookingTotal = $PreBooking; slotTotal = $PreSlot; scopeWindow = $PreWindow }
        postCounts = [ordered]@{ bookingTotal = $PostBooking; slotTotal = $PostSlot; scopeWindow = $PostWindow }
        jmeterExitCode = 0
    }
}

function New-Sample {
    param(
        [string]$ResponseCode,
        [string]$Body,
        [bool]$Succeeded = $true,
        [int]$ElapsedMs = 10
    )
    $escapedBody = [System.Security.SecurityElement]::Escape($Body)
    $successText = if ($Succeeded) { 'true' } else { 'false' }
    return "  <httpSample t=`"$ElapsedMs`" s=`"$successText`" rc=`"$ResponseCode`"><responseData>$escapedBody</responseData></httpSample>"
}

function New-SyntheticRun {
    param([string]$Name, $Metadata, [string[]]$Samples)
    $dir = Join-Path $contractRoot $Name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $xml = @('<?xml version="1.0" encoding="UTF-8"?>', '<testResults version="1.2">') + $Samples + @('</testResults>')
    Set-Content -LiteralPath (Join-Path $dir 'results.xml') -Value ($xml -join "`n") -Encoding utf8NoBOM
    Set-Content -LiteralPath (Join-Path $dir 'jmeter.log') -Value 'synthetic contract; JMeter was not invoked' -Encoding utf8NoBOM
    Write-JsonFile -Path (Join-Path $dir 'run-metadata.json') -Value $Metadata
    return $dir
}

function Invoke-Summarizer {
    param([string]$RunDir)
    return Invoke-ChildScript -Path $summarizer -Arguments @('-RunDir', $RunDir)
}

function Read-Report {
    param([string]$RunDir)
    return Get-Content -LiteralPath (Join-Path $RunDir 'report.json') -Raw | ConvertFrom-Json
}

function Write-RunnerConfig {
    param([string]$Name, $Round)
    $path = Join-Path $contractRoot "$Name.json"
    Write-JsonFile -Path $path -Value ([ordered]@{ rounds = @($Round) })
    return $path
}

function New-RunnerRound {
    param(
        [string]$Id,
        [string]$Scenario = 'same-slot',
        [bool]$Enabled = $true,
        [bool]$IsolatedHistorical = $false,
        [string]$BaseUrl = 'http://127.0.0.1:18080/',
        [string]$DistinctCsv = ''
    )
    return [ordered]@{
        id = $Id
        enabled = $Enabled
        scenario = $Scenario
        isolatedHistorical = $IsolatedHistorical
        historyMirror = 'sha256:contract-runner'
        redisExpectedHealthy = $true
        validSeed = $true
        baseUrl = $BaseUrl
        composeFile = '<not-reached-by-contract-test>'
        sameSlot = [ordered]@{
            resourceId = '1'
            startTime = '2027-01-01 10:00:00'
            endTime = '2027-01-01 11:00:00'
        }
        distinct = [ordered]@{ csvPath = $DistinctCsv }
    }
}

try {
    New-Item -ItemType Directory -Path $contractRoot -Force | Out-Null
    $env:T13_STUDENT_TOKEN = $secret

    # JMX structure contract: two independent 100/1/1 groups and explicit gates.
    [xml]$jmx = Get-Content -LiteralPath $jmxPath -Raw
    $groups = @($jmx.SelectNodes('//ThreadGroup'))
    Assert-Contract ($groups.Count -eq 2) 'JMX must contain exactly two ThreadGroups'
    foreach ($group in $groups) {
        Assert-Contract ($group.SelectSingleNode("./stringProp[@name='ThreadGroup.num_threads']").InnerText -eq '100') 'each ThreadGroup must use 100 threads'
        Assert-Contract ($group.SelectSingleNode("./stringProp[@name='ThreadGroup.ramp_time']").InnerText -eq '1') 'each ThreadGroup must use a one-second ramp'
        Assert-Contract ($group.SelectSingleNode("./elementProp[@name='ThreadGroup.main_controller']/stringProp[@name='LoopController.loops']").InnerText -eq '1') 'each ThreadGroup must use one loop'
    }
    $conditions = @($jmx.SelectNodes('//IfController/stringProp[@name="IfController.condition"]') | ForEach-Object { $_.InnerText })
    Assert-Contract ($conditions -contains '${__jexl3(${__P(runSameSlot,true)})}') 'same-slot gate must be property-driven'
    Assert-Contract ($conditions -contains '${__jexl3(${__P(runDistinct,false)})}') 'distinct gate must be property-driven'
    $csv = $jmx.SelectSingleNode('//CSVDataSet')
    Assert-Contract ($csv.SelectSingleNode("./stringProp[@name='variableNames']").InnerText -eq 'token,resourceId,startTime,endTime') 'distinct CSV columns must match the fixture contract'
    Assert-Contract ($csv.SelectSingleNode("./boolProp[@name='ignoreFirstLine']").InnerText -eq 'false') 'distinct CSV must not discard a data row as a header'
    $samplers = @($jmx.SelectNodes('//HTTPSamplerProxy'))
    Assert-Contract ($samplers.Count -eq 2) 'JMX must contain one sampler per scenario'
    foreach ($sampler in $samplers) {
        Assert-Contract ($sampler.SelectSingleNode("./stringProp[@name='HTTPSampler.method']").InnerText -eq 'POST') 'each sampler must use POST'
        Assert-Contract ($sampler.SelectSingleNode("./stringProp[@name='HTTPSampler.path']").InnerText -eq '/api/v1/bookings') 'each sampler must target the canonical booking path'
    }

    $roundTemplate = Get-Content -LiteralPath $roundTemplatePath -Raw | ConvertFrom-Json
    $templateRounds = @($roundTemplate.rounds)
    Assert-Contract ($templateRounds.Count -eq 3) 'round template must declare exactly three comparison rounds'
    Assert-Contract ((@($templateRounds.id) -join ',') -eq 'vulnerable-baseline,unique-index-only,unique-index-redisson') 'round template ids must preserve the required comparison order'
    $baselineTemplate = $templateRounds | Where-Object id -eq 'vulnerable-baseline'
    Assert-Contract ($baselineTemplate.enabled -eq $false -and $baselineTemplate.isolatedHistorical -eq $true) 'vulnerable baseline must be disabled by default and isolated'
    Assert-Contract ($baselineTemplate.historyMirror.uniqueIndex -eq 'absent' -and $baselineTemplate.historyMirror.redisson -eq 'absent') 'baseline history mirror must declare both protections absent'
    $indexOnlyTemplate = $templateRounds | Where-Object id -eq 'unique-index-only'
    Assert-Contract ($indexOnlyTemplate.historyMirror.uniqueIndex -eq 'present' -and $indexOnlyTemplate.historyMirror.redisson -eq 'absent') 'index-only history mirror must declare only the database protection'
    $redissonTemplate = $templateRounds | Where-Object id -eq 'unique-index-redisson'
    Assert-Contract ($redissonTemplate.historyMirror.uniqueIndex -eq 'present' -and $redissonTemplate.historyMirror.redisson -eq 'present') 'Redisson history mirror must declare both protections present'

    # Runner safety gates execute before JMeter/Docker and must refuse unsafe input.
    $external = New-RunnerRound -Id 'unique-index-redisson' -BaseUrl 'https://example.invalid/'
    $externalResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $external.id, '-ConfigPath', (Write-RunnerConfig 'external-url' $external), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($externalResult.exitCode -eq 2 -and $externalResult.output -match 'not local') 'runner must refuse non-loopback BaseUrl before execution'

    $disabled = New-RunnerRound -Id 'vulnerable-baseline' -Enabled $false -IsolatedHistorical $true
    $disabledResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $disabled.id, '-AllowHistoricalBaseline', '-ConfigPath', (Write-RunnerConfig 'baseline-disabled' $disabled), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($disabledResult.exitCode -eq 2 -and $disabledResult.output -match 'disabled') 'baseline must require explicit config enablement'

    $notIsolated = New-RunnerRound -Id 'vulnerable-baseline' -IsolatedHistorical $false
    $notIsolatedResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $notIsolated.id, '-AllowHistoricalBaseline', '-ConfigPath', (Write-RunnerConfig 'baseline-not-isolated' $notIsolated), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($notIsolatedResult.exitCode -eq 2 -and $notIsolatedResult.output -match 'isolatedHistorical') 'baseline must require isolatedHistorical=true'

    $noConsent = New-RunnerRound -Id 'vulnerable-baseline' -IsolatedHistorical $true
    $noConsentResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $noConsent.id, '-ConfigPath', (Write-RunnerConfig 'baseline-no-consent' $noConsent), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($noConsentResult.exitCode -eq 2 -and $noConsentResult.output -match 'AllowHistoricalBaseline') 'baseline must require the operator consent switch'

    $badCsvPath = Join-Path $contractRoot 'distinct-99.csv'
    Set-Content -LiteralPath $badCsvPath -Value (@(1..99 | ForEach-Object { "token-$_,$_,2027-01-01 10:00:00,2027-01-01 10:30:00" }) -join "`n") -Encoding utf8NoBOM
    $badDistinct = New-RunnerRound -Id 'distinct-contract' -Scenario 'distinct' -DistinctCsv $badCsvPath
    $badDistinctResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $badDistinct.id, '-ConfigPath', (Write-RunnerConfig 'distinct-bad-csv' $badDistinct), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($badDistinctResult.exitCode -eq 2 -and $badDistinctResult.output -match 'exactly 100') 'distinct scenario must refuse a CSV that is not exactly 100 rows'

    $validCsvPath = Join-Path $contractRoot 'distinct-100.csv'
    Set-Content -LiteralPath $validCsvPath -Value (@(1..100 | ForEach-Object { "token-$_,$_,2027-01-01 10:00:00,2027-01-01 10:30:00" }) -join "`n") -Encoding utf8NoBOM
    $validDistinct = New-RunnerRound -Id 'distinct-contract' -Scenario 'distinct' -DistinctCsv $validCsvPath
    $validDistinctResult = Invoke-ChildScript -Path $runner -Arguments @('-Execute', '-RoundId', $validDistinct.id, '-ConfigPath', (Write-RunnerConfig 'distinct-valid-csv' $validDistinct), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($validDistinctResult.exitCode -eq 3 -and $validDistinctResult.output -match 'composeFile') 'valid 100-row distinct CSV must pass fixture validation and reach the later compose prerequisite'

    $successBody = '{"code":0,"message":"ok","data":{"id":1,"token":"contract-secret-token-never-report"}}'
    $conflictBody = '{"code":43000,"message":"该时段已被占用，请刷新后重试","category":"SLOT_CONFLICT","data":null}'
    $busyBody = '{"code":43000,"message":"当前预约请求较多，请稍后重试","category":"SYSTEM_BUSY","data":null}'

    # Protected 1/99 result: complete metadata, evidence links, row assertions, privacy.
    $protectedSamples = @((New-Sample '201' $successBody  $true 11)) + @(1..99 | ForEach-Object { New-Sample '409' $conflictBody $true (11 + $_) })
    $protectedDir = New-SyntheticRun -Name 'protected-pass' -Metadata (New-Metadata 'unique-index-redisson' 'same-slot') -Samples $protectedSamples
    $protectedResult = Invoke-Summarizer $protectedDir
    Assert-Contract ($protectedResult.exitCode -eq 0) 'protected 1/99 synthetic report must pass'
    $protectedReport = Read-Report $protectedDir
    Assert-Contract ($protectedReport.pass -eq $true -and $protectedReport.classification.success -eq 1 -and $protectedReport.classification.business_conflict -eq 99) 'protected report must classify exactly 1 success and 99 business conflicts'
    Assert-Contract ($protectedReport.execution.threads -eq 100 -and $protectedReport.execution.rampSeconds -eq 1 -and $protectedReport.execution.loops -eq 1) 'report must retain thread/ramp/loop metadata'
    Assert-Contract ($protectedReport.environment.redisObserved -eq 'healthy' -and $protectedReport.environment.jmeterVersion -match '5.6.3') 'report must retain environment and Redis evidence'
    Assert-Contract ($protectedReport.database.preCounts.bookingTotal -eq 10 -and $protectedReport.database.postCounts.slotTotal -eq 22) 'report must retain pre/post database counts'
    Assert-Contract ($protectedReport.evidence.rawJtl -eq 'results.xml' -and $protectedReport.evidence.jmeterLog -eq 'jmeter.log') 'report must link raw JTL and JMeter log'
    $protectedRaw = Get-Content -LiteralPath (Join-Path $protectedDir 'report.json') -Raw
    Assert-Contract (-not $protectedRaw.Contains($secret)) 'report must not copy sensitive response data'

    # SYSTEM_BUSY is an error, never a business conflict, and fails protected scope.
    $busySamples = @((New-Sample '201' $successBody)) + @(1..98 | ForEach-Object { New-Sample '409' $conflictBody }) + @((New-Sample '409' $busyBody))
    $busyDir = New-SyntheticRun -Name 'protected-system-busy' -Metadata (New-Metadata 'unique-index-redisson' 'same-slot') -Samples $busySamples
    $busyResult = Invoke-Summarizer $busyDir
    Assert-Contract ($busyResult.exitCode -eq 2) 'protected report with SYSTEM_BUSY must fail'
    $busyReport = Read-Report $busyDir
    Assert-Contract ($busyReport.classification.system_busy -eq 1 -and $busyReport.classification.business_conflict -eq 98) 'SYSTEM_BUSY must be counted separately'

    # Historical baseline and distinct scope remain report-only.
    $baselineSamples = @(1..2 | ForEach-Object { New-Sample '201' $successBody }) + @(1..98 | ForEach-Object { New-Sample '409' $conflictBody })
    $baselineDir = New-SyntheticRun -Name 'baseline-report-only' -Metadata (New-Metadata 'vulnerable-baseline' 'same-slot' -PostBooking 12 -PostSlot 24 -PostWindow 5) -Samples $baselineSamples
    $baselineResult = Invoke-Summarizer $baselineDir
    $baselineReport = Read-Report $baselineDir
    Assert-Contract ($baselineResult.exitCode -eq 0 -and $baselineReport.verdictScope -eq 'historical-vulnerability-evidence' -and $null -eq $baselineReport.pass) 'baseline duplicates must be historical report-only evidence'

    $distinctSamples = @(1..100 | ForEach-Object { New-Sample '201' $successBody })
    $distinctDir = New-SyntheticRun -Name 'distinct-report-only' -Metadata (New-Metadata 'unique-index-redisson-distinct' 'distinct' -PostBooking 110 -PostSlot 120 -PostWindow 103) -Samples $distinctSamples
    $distinctResult = Invoke-Summarizer $distinctDir
    $distinctReport = Read-Report $distinctDir
    Assert-Contract ($distinctResult.exitCode -eq 0 -and $distinctReport.verdictScope -eq 'lock-granularity-report' -and $null -eq $distinctReport.pass) 'distinct scope must remain report-only'

    # Classification boundary matrix remains mutually exclusive.
    $boundarySamples = @(
        (New-Sample '201' '{malformed-json'),
        (New-Sample '409' '{"code":43000,"message":"wrong conflict text","data":null}'),
        (New-Sample '500' '{"code":50000,"message":"server","data":null}'),
        (New-Sample '' '' $false),
        (New-Sample '401' '{"code":41000,"message":"unauthorized","data":null}')
    )
    $boundaryDir = New-SyntheticRun -Name 'classification-boundaries' -Metadata (New-Metadata 'classification-boundaries' 'distinct' -PostBooking 10 -PostSlot 20 -PostWindow 3) -Samples $boundarySamples
    $boundaryResult = Invoke-Summarizer $boundaryDir
    $boundaryReport = Read-Report $boundaryDir
    Assert-Contract ($boundaryResult.exitCode -eq 0) 'boundary matrix must produce a report-only result'
    Assert-Contract ($boundaryReport.classification.data_error -eq 1 -and $boundaryReport.classification.server_error -eq 1 -and $boundaryReport.classification.connection_error -eq 1 -and $boundaryReport.classification.other -eq 2) 'classification boundary counts must be mutually exclusive'

    # Invalid database deltas fail the protected assertion.
    $badDeltaDir = New-SyntheticRun -Name 'protected-bad-delta' -Metadata (New-Metadata 'unique-index-redisson' 'same-slot' -PostBooking 12) -Samples $protectedSamples
    $badDeltaResult = Invoke-Summarizer $badDeltaDir
    $badDeltaReport = Read-Report $badDeltaDir
    Assert-Contract ($badDeltaResult.exitCode -eq 2 -and $badDeltaReport.assertion.bookingDeltaEqualsSuccess -eq $false) 'protected scope must fail an inconsistent booking delta'

    $nonZeroMeta = New-Metadata 'unique-index-redisson' 'same-slot'
    $nonZeroMeta.jmeterExitCode = 1
    $nonZeroDir = New-SyntheticRun -Name 'nonzero-jmeter-exit' -Metadata $nonZeroMeta -Samples $protectedSamples
    $nonZeroResult = Invoke-Summarizer $nonZeroDir
    $nonZeroReport = Read-Report $nonZeroDir
    Assert-Contract ($nonZeroResult.exitCode -eq 2 -and $nonZeroReport.pass -eq $false -and $nonZeroReport.execution.jmeterExitOk -eq $false) 'non-zero JMeter exit must retain a report but can never pass'

    # Missing environment metadata or a raw log blocks report generation.
    $missingMeta = New-Metadata 'unique-index-redisson' 'same-slot'
    [void]$missingMeta.Remove('dockerVersion')
    $missingMetaDir = New-SyntheticRun -Name 'missing-environment' -Metadata $missingMeta -Samples $protectedSamples
    $missingMetaResult = Invoke-Summarizer $missingMetaDir
    Assert-Contract ($missingMetaResult.exitCode -eq 3 -and $missingMetaResult.output -match 'dockerVersion') 'missing environment metadata must block the report'

    $missingLogDir = New-SyntheticRun -Name 'missing-jmeter-log' -Metadata (New-Metadata 'unique-index-redisson' 'same-slot') -Samples $protectedSamples
    Remove-Item -LiteralPath (Join-Path $missingLogDir 'jmeter.log') -Force
    $missingLogResult = Invoke-Summarizer $missingLogDir
    Assert-Contract ($missingLogResult.exitCode -eq 3 -and $missingLogResult.output -match 'jmeter.log') 'missing JMeter log must block the report'

    Write-Output "JMETER CONTRACT TESTS PASS - assertions=$assertions; no JMeter, Docker, or HTTP request was invoked."
    exit 0
}
catch {
    Write-Warning $_
    exit 1
}
finally {
    $env:T13_STUDENT_TOKEN = $priorToken
    if (-not $KeepArtifacts -and (Test-Path -LiteralPath $contractRoot)) {
        Remove-Item -LiteralPath $contractRoot -Recurse -Force
    }
}
