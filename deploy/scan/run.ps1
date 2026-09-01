#Requires -Version 7.0
<#
.SYNOPSIS
    T13 local image-scan environment check and offline report validator.
.DESCRIPTION
    Plan is inert. Environment inspects local CLI/cache availability only and
    never runs a scanner or contacts a registry/advisory service. Validate reads
    an operator-produced Trivy/Grype JSON bundle, verifies hashes/image identity/
    DB freshness/counts, and emits a redacted count-only summary.

    This script cannot manufacture CVE evidence. A local SBOM or a successful
    contract test is never treated as a vulnerability scan.

    Exit codes: 0 validated PASS/ready | 1 local environment error |
                2 invalid evidence or findings block release | 3 blocked input.
#>
[CmdletBinding()]
param(
    [ValidateSet('Plan', 'Environment', 'Validate')]
    [string]$Action = 'Plan',
    [string]$ManifestPath = '',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateRange(1, 720)]
    [int]$MaxDatabaseAgeHours = 168
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning "REFUSED: invalid RunId '$RunId'."
    exit 2
}

$deployRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $deployRoot 'artifacts')).Path }
$artifactDir = Join-Path $ArtifactRoot "image-scan-$Action-$RunId"
$resultPath = Join-Path $artifactDir 'result.json'

function Write-Outcome {
    param([int]$ExitCode, [string]$Status, [string]$Reason, $Details = $null)
    New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
    $outcome = [ordered]@{
        runId = $RunId
        action = $Action
        status = $Status
        exitCode = $ExitCode
        reason = $Reason
        generatedAt = (Get-Date).ToString('o')
        localOnly = $true
        scannerInvoked = $false
        registryOrAdvisoryNetworkInvoked = $false
        details = $Details
    }
    Set-Content -LiteralPath $resultPath -Value ($outcome | ConvertTo-Json -Depth 10) -Encoding utf8NoBOM
    if ($ExitCode -eq 0) { Write-Output "$Status - result=$resultPath" } else { Write-Warning "$Status - $Reason" }
    exit $ExitCode
}

function Get-RequiredProperty {
    param($Object, [string]$Name)
    $property = if ($Object) { $Object.PSObject.Properties[$Name] } else { $null }
    if (-not $property -or $null -eq $property.Value) {
        Write-Outcome 3 'BLOCKED_MISSING_INPUT' "missing required field '$Name'"
    }
    return $property.Value
}

function Get-RequiredText {
    param($Object, [string]$Name)
    $text = [string](Get-RequiredProperty $Object $Name)
    if ([string]::IsNullOrWhiteSpace($text) -or $text -match '^<.*>$') {
        Write-Outcome 3 'BLOCKED_PLACEHOLDER_INPUT' "field '$Name' is empty or a placeholder"
    }
    return $text
}

function Get-RequiredBoolean {
    param($Object, [string]$Name)
    $value = Get-RequiredProperty $Object $Name
    if ($value -isnot [bool]) { Write-Outcome 2 'INVALID_EVIDENCE' "field '$Name' must be a JSON boolean" }
    return [bool]$value
}

function Get-RequiredLong {
    param($Object, [string]$Name)
    $raw = Get-RequiredProperty $Object $Name
    [long]$value = 0
    if (-not [long]::TryParse([string]$raw, [ref]$value) -or $value -lt 0) {
        Write-Outcome 2 'INVALID_EVIDENCE' "field '$Name' must be a non-negative integer"
    }
    return $value
}

function Parse-Date {
    param([string]$Text, [string]$Name)
    [datetimeoffset]$value = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($Text, [ref]$value)) {
        Write-Outcome 2 'INVALID_EVIDENCE' "field '$Name' must be an ISO-8601 timestamp"
    }
    return $value.ToUniversalTime()
}

function Resolve-EvidenceFile {
    param([string]$ManifestDirectory, [string]$RelativePath, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        Write-Outcome 2 'INVALID_EVIDENCE_PATH' "$Label must be a non-empty relative path"
    }
    $root = [IO.Path]::GetFullPath($ManifestDirectory).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $candidate = [IO.Path]::GetFullPath((Join-Path $ManifestDirectory $RelativePath))
    if (-not $candidate.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        Write-Outcome 2 'INVALID_EVIDENCE_PATH' "$Label escapes the manifest directory"
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        Write-Outcome 3 'BLOCKED_MISSING_REPORT' "$Label does not exist"
    }
    $current = $ManifestDirectory
    $relative = [IO.Path]::GetRelativePath($ManifestDirectory, $candidate)
    foreach ($segment in @($relative -split '[\\/]')) {
        $current = Join-Path $current $segment
        $item = Get-Item -LiteralPath $current -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Write-Outcome 2 'INVALID_EVIDENCE_PATH' "$Label traverses a symbolic link or junction"
        }
    }
    return $candidate
}

function New-Counts {
    return [ordered]@{ unknown = 0L; negligible = 0L; low = 0L; medium = 0L; high = 0L; critical = 0L }
}

function Add-Severity {
    param($Counts, [string]$Severity)
    $key = ([string]$Severity).Trim().ToLowerInvariant()
    if ($key -notin @('negligible', 'low', 'medium', 'high', 'critical')) { $key = 'unknown' }
    $Counts[$key] = [long]$Counts[$key] + 1
}

function Read-DeclaredCounts {
    param($Object)
    $counts = New-Counts
    foreach ($key in @($counts.Keys)) { $counts[$key] = Get-RequiredLong $Object $key }
    return $counts
}

function Counts-Equal {
    param($Left, $Right)
    foreach ($key in $Left.Keys) { if ([long]$Left[$key] -ne [long]$Right[$key]) { return $false } }
    return $true
}

function Assert-RedactedText {
    param([string]$Text, [string]$Label)
    $credentialPatterns = @(
        '(?i)\bAuthorization\s*:\s*(?:Bearer|Basic)\s+[^\s]+',
        '(?i)\b(?:password|passwd|pwd|token|secret|cookie|set-cookie|api[_-]?key|registry[_-]?auth)\b\s*[:=]\s*(?!<redacted>|REDACTED|\*{3,}|\$\{?[A-Z_][A-Z0-9_]*\}?)[^\s,;]+',
        '(?i)https?://[^/\s:@]+:[^/\s@]+@'
    )
    foreach ($pattern in $credentialPatterns) {
        if ($Text -match $pattern) { Write-Outcome 2 'SENSITIVE_EVIDENCE' "$Label contains credential-like material" }
    }
}

function Parse-TrivyReport {
    param($Report)
    $counts = New-Counts
    $schemaVersion = Get-RequiredLong $Report 'SchemaVersion'
    if ($schemaVersion -lt 2) { Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Trivy SchemaVersion must be at least 2' }
    $artifactName = Get-RequiredText $Report 'ArtifactName'
    [void](Get-RequiredText $Report 'ArtifactType')
    $resultsProperty = $Report.PSObject.Properties['Results']
    if (-not $resultsProperty -or $resultsProperty.Value -isnot [System.Array]) {
        Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Trivy Results must be a JSON array'
    }
    foreach ($result in @($resultsProperty.Value)) {
        if ($null -eq $result -or -not $result.PSObject.Properties['Target'] -or [string]::IsNullOrWhiteSpace([string]$result.Target)) {
            Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'every Trivy result must identify a Target'
        }
        $vulnerabilities = @()
        if ($result.PSObject.Properties['Vulnerabilities'] -and $null -ne $result.Vulnerabilities) {
            if ($result.Vulnerabilities -isnot [System.Array]) {
                Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Trivy Vulnerabilities must be a JSON array when present'
            }
            $vulnerabilities = @($result.Vulnerabilities)
        }
        foreach ($vulnerability in $vulnerabilities) {
            if ($null -eq $vulnerability) { Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Trivy vulnerability entries must be objects' }
            [void](Get-RequiredText $vulnerability 'VulnerabilityID')
            Add-Severity $counts (Get-RequiredText $vulnerability 'Severity')
        }
    }
    $metadata = Get-RequiredProperty $Report 'Metadata'
    $repoDigests = if ($metadata.PSObject.Properties['RepoDigests'] -and $metadata.RepoDigests) { @($metadata.RepoDigests | ForEach-Object { [string]$_ }) } else { @() }
    $repoTags = if ($metadata.PSObject.Properties['RepoTags'] -and $metadata.RepoTags) { @($metadata.RepoTags | ForEach-Object { [string]$_ }) } else { @() }
    return [pscustomobject]@{
        counts = $counts
        imageId = [string](Get-RequiredProperty $metadata 'ImageID')
        repoDigests = $repoDigests
        imageRefs = @($artifactName) + $repoTags
    }
}

function Parse-GrypeReport {
    param($Report)
    $counts = New-Counts
    $descriptor = Get-RequiredProperty $Report 'descriptor'
    if ((Get-RequiredText $descriptor 'name').ToLowerInvariant() -ne 'grype') { Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Grype descriptor.name must equal grype' }
    [void](Get-RequiredText $descriptor 'version')
    $matchesProperty = $Report.PSObject.Properties['matches']
    if (-not $matchesProperty -or $matchesProperty.Value -isnot [System.Array]) {
        Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Grype matches must be a JSON array'
    }
    foreach ($match in @($matchesProperty.Value)) {
        if ($null -eq $match -or -not $match.PSObject.Properties['vulnerability']) { Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'every Grype match must contain vulnerability metadata' }
        $vulnerability = Get-RequiredProperty $match 'vulnerability'
        [void](Get-RequiredText $vulnerability 'id')
        Add-Severity $counts (Get-RequiredText $vulnerability 'severity')
    }
    $source = Get-RequiredProperty $Report 'source'
    if ((Get-RequiredText $source 'type').ToLowerInvariant() -ne 'image') { Write-Outcome 2 'INVALID_REPORT_SCHEMA' 'Grype source.type must equal image' }
    $target = Get-RequiredProperty $source 'target'
    $repoDigests = if ($target.PSObject.Properties['repoDigests'] -and $target.repoDigests) { @($target.repoDigests | ForEach-Object { [string]$_ }) } else { @() }
    $tags = if ($target.PSObject.Properties['tags'] -and $target.tags) { @($target.tags | ForEach-Object { [string]$_ }) } else { @() }
    $userInput = Get-RequiredText $target 'userInput'
    return [pscustomobject]@{
        counts = $counts
        imageId = [string](Get-RequiredProperty $target 'imageID')
        repoDigests = $repoDigests
        imageRefs = @($userInput) + $tags
    }
}

if ($Action -eq 'Plan') {
    Write-Output 'PLAN MODE - no scanner, registry, advisory service, Docker daemon, or report parser invoked.'
    Write-Output 'Environment checks local CLI/cache availability only. Validate requires a local Trivy/Grype manifest bundle.'
    exit 0
}

if ($Action -eq 'Environment') {
    $profileRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    $trivyCommand = Get-Command trivy -ErrorAction SilentlyContinue
    $grypeCommand = Get-Command grype -ErrorAction SilentlyContinue
    $trivyDbCandidates = @(
        (Join-Path $localAppData 'trivy\db\metadata.json'),
        (Join-Path $profileRoot '.cache\trivy\db\metadata.json')
    )
    $grypeDbCandidates = @(
        (Join-Path $profileRoot '.cache\grype\db'),
        (Join-Path $localAppData 'grype\db')
    )
    $trivyDb = @($trivyDbCandidates | Where-Object { Test-Path -LiteralPath $_ })
    $grypeDb = @($grypeDbCandidates | Where-Object { Test-Path -LiteralPath $_ })
    $programFiles = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
    $scoutCandidates = @(
        (Join-Path $profileRoot '.docker\cli-plugins\docker-scout.exe'),
        (Join-Path $programFiles 'Docker\Docker\resources\cli-plugins\docker-scout.exe'),
        (Join-Path $programFiles 'Docker\cli-plugins\docker-scout.exe')
    )
    $scoutPlugin = @($scoutCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1)
    $scoutVersion = ''
    $scoutCommit = ''
    if ($scoutPlugin.Count -gt 0) {
        $versionInfo = (Get-Item -LiteralPath $scoutPlugin[0]).VersionInfo
        $scoutVersion = [string]$versionInfo.ProductVersion
        $scoutCommit = [string]$versionInfo.Comments
    }
    $details = [ordered]@{
        trivy = [ordered]@{ installed = ($null -ne $trivyCommand); dbPaths = $trivyDb }
        grype = [ordered]@{ installed = ($null -ne $grypeCommand); dbPaths = $grypeDb }
        dockerScout = [ordered]@{
            installed = ($scoutPlugin.Count -gt 0)
            version = $scoutVersion
            gitCommit = $scoutCommit
            pluginInvoked = $false
            offlineModeVerified = $false
            advisoryDatabaseFound = $false
            note = 'Plugin file presence only; Scout was not invoked. SBOM cache is not an advisory/CVE database.'
        }
    }
    $ready = (($trivyCommand -and $trivyDb.Count -gt 0) -or ($grypeCommand -and $grypeDb.Count -gt 0))
    if ($ready) { Write-Outcome 0 'READY_FOR_OFFLINE_SCAN' 'local scanner command and candidate database found; freshness still requires report validation' $details }
    Write-Outcome 3 'BLOCKED_NO_OFFLINE_SCANNER_DB' 'no supported scanner plus local advisory database is available; no scan was attempted' $details
}

if (-not $ManifestPath -or -not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    Write-Outcome 3 'BLOCKED_MISSING_MANIFEST' 'Validate requires an existing -ManifestPath'
}

$manifestFull = (Resolve-Path -LiteralPath $ManifestPath).Path
$manifestDirectory = Split-Path -Parent $manifestFull
try { $manifest = Get-Content -LiteralPath $manifestFull -Raw | ConvertFrom-Json } catch {
    Write-Outcome 2 'INVALID_EVIDENCE' 'manifest is not valid JSON'
}

$schemaVersion = Get-RequiredLong $manifest 'schemaVersion'
if ($schemaVersion -ne 1) { Write-Outcome 2 'INVALID_EVIDENCE' 'schemaVersion must equal 1' }
$scanId = Get-RequiredText $manifest 'scanId'
$generatedAt = Parse-Date (Get-RequiredText $manifest 'generatedAt') 'generatedAt'
if ($generatedAt -gt [datetimeoffset]::UtcNow.AddMinutes(5)) { Write-Outcome 2 'INVALID_EVIDENCE' 'generatedAt is unreasonably in the future' }

$scanner = Get-RequiredProperty $manifest 'scanner'
$scannerName = (Get-RequiredText $scanner 'name').ToLowerInvariant()
if ($scannerName -notin @('trivy', 'grype')) { Write-Outcome 2 'UNSUPPORTED_SCANNER_FORMAT' 'only Trivy and Grype JSON reports are accepted' }
$scannerVersion = Get-RequiredText $scanner 'version'
$offline = Get-RequiredBoolean $scanner 'offline'
if (-not $offline) { Write-Outcome 2 'INVALID_LOCAL_EVIDENCE' 'this local validator requires scanner.offline=true' }
$databaseUpdatedAt = Parse-Date (Get-RequiredText $scanner 'databaseUpdatedAt') 'databaseUpdatedAt'
$databaseMetadataRelative = Get-RequiredText $scanner 'databaseMetadata'
$databaseMetadataPath = Resolve-EvidenceFile $manifestDirectory $databaseMetadataRelative 'scanner databaseMetadata'
$databaseChecksum = (Get-RequiredText $scanner 'databaseChecksum').ToLowerInvariant()
if ($databaseChecksum -notmatch '^sha256:[0-9a-f]{64}$') { Write-Outcome 2 'INVALID_EVIDENCE' 'databaseChecksum must be sha256:<64 hex>' }
$actualDatabaseChecksum = 'sha256:' + (Get-FileHash -LiteralPath $databaseMetadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualDatabaseChecksum -ne $databaseChecksum) { Write-Outcome 2 'INVALID_EVIDENCE_HASH' 'scanner database metadata SHA256 mismatch' }
$executionLogRelative = Get-RequiredText $scanner 'executionLog'
$executionLogPath = Resolve-EvidenceFile $manifestDirectory $executionLogRelative 'scanner executionLog'
$executionLogChecksum = (Get-RequiredText $scanner 'executionLogSha256').ToLowerInvariant()
if ($executionLogChecksum -notmatch '^[0-9a-f]{64}$') { Write-Outcome 2 'INVALID_EVIDENCE' 'executionLogSha256 must be 64 hex characters' }
$actualExecutionLogChecksum = (Get-FileHash -LiteralPath $executionLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualExecutionLogChecksum -ne $executionLogChecksum) { Write-Outcome 2 'INVALID_EVIDENCE_HASH' 'scanner execution log SHA256 mismatch' }
$executionLogLength = (Get-Item -LiteralPath $executionLogPath).Length
if ($executionLogLength -eq 0) { Write-Outcome 2 'INVALID_EVIDENCE' 'scanner execution log must not be empty' }
if ($executionLogLength -gt 8MB) { Write-Outcome 3 'BLOCKED_OVERSIZE_LOG' 'scanner execution log exceeds the 8 MiB redaction-scan limit' }
$executionLogText = Get-Content -LiteralPath $executionLogPath -Raw
Assert-RedactedText $executionLogText 'scanner execution log'
$dbAgeHours = ([datetimeoffset]::UtcNow - $databaseUpdatedAt).TotalHours
if ($dbAgeHours -lt -0.1) { Write-Outcome 2 'INVALID_EVIDENCE' 'databaseUpdatedAt is in the future' }
if ($databaseUpdatedAt -gt $generatedAt.AddMinutes(5)) { Write-Outcome 2 'INVALID_EVIDENCE' 'databaseUpdatedAt cannot be later than generatedAt' }
if ($dbAgeHours -gt $MaxDatabaseAgeHours) {
    Write-Outcome 3 'BLOCKED_STALE_DATABASE' ("scanner database age {0:N1}h exceeds {1}h" -f $dbAgeHours, $MaxDatabaseAgeHours)
}

$targets = @(Get-RequiredProperty $manifest 'targets')
if ($targets.Count -ne 2) { Write-Outcome 2 'INVALID_EVIDENCE' 'targets must contain exactly api and edge' }
$targetNames = @($targets | ForEach-Object { [string]$_.name })
if (@($targetNames | Select-Object -Unique).Count -ne 2 -or ((@($targetNames | Sort-Object) -join ',') -ne 'api,edge')) {
    Write-Outcome 2 'INVALID_EVIDENCE' 'targets must be unique api and edge entries'
}

$normalizedTargets = @()
$aggregate = New-Counts
$scannerExitCodes = @()
foreach ($target in $targets) {
    $name = Get-RequiredText $target 'name'
    $imageRef = Get-RequiredText $target 'imageRef'
    $imageId = (Get-RequiredText $target 'imageId').ToLowerInvariant()
    if ($imageId -notmatch '^sha256:[0-9a-f]{64}$') { Write-Outcome 2 'INVALID_EVIDENCE' "$name imageId must be sha256:<64 hex>" }
    $imageDigest = ''
    if ($target.PSObject.Properties['imageDigest'] -and $target.imageDigest) {
        $imageDigest = [string]$target.imageDigest
        if ($imageDigest -notmatch '@sha256:[0-9a-fA-F]{64}$') { Write-Outcome 2 'INVALID_EVIDENCE' "$name imageDigest must end in @sha256:<64 hex>" }
    }
    $reportFormat = (Get-RequiredText $target 'reportFormat').ToLowerInvariant()
    $expectedFormat = if ($scannerName -eq 'trivy') { 'trivy-json' } else { 'grype-json' }
    if ($reportFormat -ne $expectedFormat) { Write-Outcome 2 'INVALID_EVIDENCE' "$name reportFormat does not match scanner" }
    $scanCompleted = Get-RequiredBoolean $target 'scanCompleted'
    if (-not $scanCompleted) { Write-Outcome 3 'BLOCKED_INCOMPLETE_SCAN' "$name scanCompleted must be true" }
    $scannerExitCode = Get-RequiredLong $target 'scannerExitCode'
    $scannerExitCodes += $scannerExitCode
    $relativeReport = Get-RequiredText $target 'rawReport'
    $reportPath = Resolve-EvidenceFile $manifestDirectory $relativeReport "$name rawReport"
    $declaredHash = (Get-RequiredText $target 'rawReportSha256').ToLowerInvariant()
    if ($declaredHash -notmatch '^[0-9a-f]{64}$') { Write-Outcome 2 'INVALID_EVIDENCE' "$name rawReportSha256 must be 64 hex characters" }
    $actualHash = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $declaredHash) { Write-Outcome 2 'INVALID_EVIDENCE_HASH' "$name raw report SHA256 mismatch" }
    try { $rawReport = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json } catch {
        Write-Outcome 2 'INVALID_EVIDENCE' "$name raw report is not valid JSON"
    }
    $parsed = if ($reportFormat -eq 'trivy-json') { Parse-TrivyReport $rawReport } else { Parse-GrypeReport $rawReport }
    if (([string]$parsed.imageId).ToLowerInvariant() -ne $imageId) { Write-Outcome 2 'IMAGE_ID_MISMATCH' "$name report image ID does not match manifest" }
    if (@($parsed.imageRefs | ForEach-Object { ([string]$_).ToLowerInvariant() }) -notcontains $imageRef.ToLowerInvariant()) { Write-Outcome 2 'IMAGE_REFERENCE_MISMATCH' "$name report image references do not contain imageRef" }
    if ($imageDigest -and @($parsed.repoDigests | ForEach-Object { ([string]$_).ToLowerInvariant() }) -notcontains $imageDigest.ToLowerInvariant()) { Write-Outcome 2 'IMAGE_DIGEST_MISMATCH' "$name report repo digests do not contain imageDigest" }
    $declaredCounts = Read-DeclaredCounts (Get-RequiredProperty $target 'declaredCounts')
    if (-not (Counts-Equal $parsed.counts $declaredCounts)) { Write-Outcome 2 'COUNT_MISMATCH' "$name declared severity counts do not match raw report" }
    foreach ($key in @($aggregate.Keys)) { $aggregate[$key] = [long]$aggregate[$key] + [long]$parsed.counts[$key] }
    $normalizedTargets += [ordered]@{
        name = $name
        imageRef = $imageRef
        imageId = $imageId
        imageDigest = $imageDigest
        scannerExitCode = $scannerExitCode
        rawReport = $relativeReport
        rawReportSha256 = $actualHash
        counts = $parsed.counts
    }
}

$decision = Get-RequiredProperty $manifest 'decision'
$decisionStatus = (Get-RequiredText $decision 'status').ToUpperInvariant()
if ($decisionStatus -notin @('PASS', 'FAIL', 'BLOCKED')) { Write-Outcome 2 'INVALID_EVIDENCE' 'decision.status must be PASS, FAIL, or BLOCKED' }
$decisionReason = Get-RequiredText $decision 'reason'
$releaseBlockingFindings = ([long]$aggregate.unknown + [long]$aggregate.high + [long]$aggregate.critical)
$nonZeroScannerExits = @($scannerExitCodes | Where-Object { $_ -ne 0 }).Count
if ($releaseBlockingFindings -gt 0 -and $decisionStatus -eq 'PASS') {
    Write-Outcome 2 'INVALID_PASS_DECISION' 'PASS is forbidden while UNKNOWN/HIGH/CRITICAL findings exist'
}
if ($releaseBlockingFindings -eq 0 -and $nonZeroScannerExits -eq 0 -and $decisionStatus -ne 'PASS') {
    Write-Outcome 2 'INCONSISTENT_DECISION' 'without UNKNOWN/HIGH/CRITICAL findings, this evidence bundle must use PASS or explain findings through a future owner-approved policy change'
}

$details = [ordered]@{
    scanId = $scanId
    manifest = (Split-Path -Leaf $manifestFull)
    scanner = [ordered]@{
        name = $scannerName
        version = $scannerVersion
        offline = $offline
        databaseUpdatedAt = $databaseUpdatedAt.ToString('o')
        databaseMetadata = $databaseMetadataRelative
        databaseChecksum = $databaseChecksum
        executionLog = $executionLogRelative
        executionLogSha256 = $actualExecutionLogChecksum
        databaseAgeHours = [math]::Round($dbAgeHours, 1)
        maxDatabaseAgeHours = $MaxDatabaseAgeHours
    }
    targets = $normalizedTargets
    aggregateCounts = $aggregate
    decision = [ordered]@{ status = $decisionStatus; reason = $decisionReason }
}
if ($decisionStatus -eq 'PASS' -and $nonZeroScannerExits -gt 0) {
    Write-Outcome 2 'INVALID_PASS_DECISION' 'PASS requires scannerExitCode=0 for both targets'
}
if ($decisionStatus -eq 'PASS') { Write-Outcome 0 'VALIDATED_SCAN_PASS' 'both image reports validated with no UNKNOWN/HIGH/CRITICAL findings' $details }
Write-Outcome 2 'VALIDATED_SCAN_BLOCKS_RELEASE' 'scan evidence is structurally valid but its findings block release' $details
