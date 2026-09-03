#Requires -Version 7.0
<#
.SYNOPSIS
    Offline contract tests for deploy/scan/run.ps1.
.DESCRIPTION
    Generates synthetic Trivy/Grype reports and manifests only. No scanner,
    Docker daemon, registry, advisory service or network request is invoked.
#>
[CmdletBinding()]
param(
    [string]$ArtifactRoot = '',
    [string]$RunId = ('contract-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [switch]$KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') { Write-Warning 'REFUSED: invalid contract RunId.'; exit 2 }
$deployRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $deployRoot 'artifacts')).Path }
$contractRoot = Join-Path $ArtifactRoot "scan-contract-$RunId"
$validator = Join-Path $PSScriptRoot 'run.ps1'
$exampleManifest = Join-Path $PSScriptRoot 'report-manifest.example.json'
$assertions = 0

function Assert-Contract {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "CONTRACT FAILED: $Message" }
    $script:assertions++
}

function Write-JsonFile {
    param([string]$Path, $Value)
    Set-Content -LiteralPath $Path -Value ($Value | ConvertTo-Json -Depth 12) -Encoding utf8NoBOM
}

function Get-Hash {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function New-CountsFromSeverities {
    param([string[]]$Severities)
    $counts = [ordered]@{ unknown = 0; negligible = 0; low = 0; medium = 0; high = 0; critical = 0 }
    foreach ($severity in $Severities) {
        $key = $severity.ToLowerInvariant()
        if ($key -notin @('negligible', 'low', 'medium', 'high', 'critical')) { $key = 'unknown' }
        $counts[$key]++
    }
    return $counts
}

function New-TrivyReport {
    param([string]$ImageId, [string]$ImageRef, [string]$RepoDigest, [string[]]$Severities)
    $vulnerabilities = @($Severities | ForEach-Object {
        [ordered]@{ VulnerabilityID = "SYNTH-$($_)-$([guid]::NewGuid().ToString('N').Substring(0, 6))"; PkgName = 'synthetic'; Severity = $_ }
    })
    return [ordered]@{
        SchemaVersion = 2
        ArtifactName = $ImageRef
        ArtifactType = 'container_image'
        Metadata = [ordered]@{ ImageID = $ImageId; RepoTags = @($ImageRef); RepoDigests = @($RepoDigest) }
        Results = @([ordered]@{ Target = 'synthetic'; Vulnerabilities = $vulnerabilities })
    }
}

function New-GrypeReport {
    param([string]$ImageId, [string]$ImageRef, [string]$RepoDigest, [string[]]$Severities)
    $matches = @($Severities | ForEach-Object {
        [ordered]@{ vulnerability = [ordered]@{ id = "SYNTH-$($_)-$([guid]::NewGuid().ToString('N').Substring(0, 6))"; severity = $_ } }
    })
    return [ordered]@{
        descriptor = [ordered]@{ name = 'grype'; version = 'synthetic' }
        source = [ordered]@{ type = 'image'; target = [ordered]@{ userInput = $ImageRef; imageID = $ImageId; tags = @($ImageRef); repoDigests = @($RepoDigest) } }
        matches = $matches
    }
}

function New-Bundle {
    param(
        [string]$Name,
        [ValidateSet('trivy', 'grype')][string]$Scanner = 'trivy',
        [string[]]$ApiSeverities = @(),
        [string[]]$EdgeSeverities = @(),
        [ValidateSet('PASS', 'FAIL', 'BLOCKED')][string]$Decision = 'PASS',
        [double]$DatabaseAgeHours = 1
    )
    $dir = Join-Path $contractRoot $Name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $dbMetadata = Join-Path $dir 'database-metadata.json'
    $executionLog = Join-Path $dir 'scanner-command.log'
    Set-Content -LiteralPath $dbMetadata -Value '{"synthetic":true,"note":"not an advisory database"}' -Encoding utf8NoBOM
    Set-Content -LiteralPath $executionLog -Value "synthetic offline $Scanner invocation; no scanner was run" -Encoding utf8NoBOM
    $ids = [ordered]@{ api = 'sha256:' + ('a' * 64); edge = 'sha256:' + ('b' * 64) }
    $refs = [ordered]@{ api = 'campus-booking/api:synthetic'; edge = 'campus-booking/edge:synthetic' }
    $digests = [ordered]@{
        api = 'campus-booking/api@sha256:' + ('c' * 64)
        edge = 'campus-booking/edge@sha256:' + ('d' * 64)
    }
    $severityMap = [ordered]@{ api = $ApiSeverities; edge = $EdgeSeverities }
    $targets = @()
    foreach ($targetName in @('api', 'edge')) {
        $reportFile = "$targetName.$Scanner.json"
        $reportPath = Join-Path $dir $reportFile
        $report = if ($Scanner -eq 'trivy') {
            New-TrivyReport $ids[$targetName] $refs[$targetName] $digests[$targetName] $severityMap[$targetName]
        } else {
            New-GrypeReport $ids[$targetName] $refs[$targetName] $digests[$targetName] $severityMap[$targetName]
        }
        Write-JsonFile $reportPath $report
        $targets += [ordered]@{
            name = $targetName
            imageRef = $refs[$targetName]
            imageId = $ids[$targetName]
            imageDigest = $digests[$targetName]
            reportFormat = "${Scanner}-json"
            rawReport = $reportFile
            rawReportSha256 = Get-Hash $reportPath
            scanCompleted = $true
            scannerExitCode = 0
            declaredCounts = New-CountsFromSeverities $severityMap[$targetName]
        }
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        scanId = "synthetic-$Name"
        generatedAt = [datetimeoffset]::UtcNow.ToString('o')
        scanner = [ordered]@{
            name = $Scanner
            version = 'synthetic-1.0'
            offline = $true
            databaseUpdatedAt = [datetimeoffset]::UtcNow.AddHours(-$DatabaseAgeHours).ToString('o')
            databaseMetadata = 'database-metadata.json'
            databaseChecksum = 'sha256:' + (Get-Hash $dbMetadata)
            executionLog = 'scanner-command.log'
            executionLogSha256 = Get-Hash $executionLog
        }
        targets = $targets
        decision = [ordered]@{ status = $Decision; reason = "synthetic $Decision contract case" }
    }
    $manifestPath = Join-Path $dir 'manifest.json'
    Write-JsonFile $manifestPath $manifest
    return [pscustomobject]@{ dir = $dir; manifest = $manifest; manifestPath = $manifestPath }
}

function Save-BundleManifest {
    param($Bundle)
    Write-JsonFile $Bundle.manifestPath $Bundle.manifest
}

function Save-BundleReport {
    param($Bundle, [int]$TargetIndex, $Report)
    $reportPath = Join-Path $Bundle.dir $Bundle.manifest.targets[$TargetIndex].rawReport
    Write-JsonFile $reportPath $Report
    $Bundle.manifest.targets[$TargetIndex].rawReportSha256 = Get-Hash $reportPath
    Save-BundleManifest $Bundle
}

function Invoke-Validate {
    param([string]$Name, [string]$ManifestPath, [int]$MaxAge = 168)
    $childRunId = ('ct-' + ($Name -replace '[^A-Za-z0-9_-]', '-'))
    $output = @(& pwsh -NoProfile -File $validator -Action Validate -ManifestPath $ManifestPath -ArtifactRoot $contractRoot -RunId $childRunId -MaxDatabaseAgeHours $MaxAge 2>&1)
    return [pscustomobject]@{
        exitCode = $LASTEXITCODE
        output = ($output -join "`n")
        resultPath = (Join-Path $contractRoot "image-scan-Validate-$childRunId\result.json")
    }
}

try {
    New-Item -ItemType Directory -Path $contractRoot -Force | Out-Null

    $planOutput = @(& pwsh -NoProfile -File $validator -Action Plan 2>&1)
    Assert-Contract ($LASTEXITCODE -eq 0 -and ($planOutput -join "`n") -match 'no scanner.*invoked') 'Plan must be inert'

    $safeTrivy = New-Bundle 'safe-trivy' -Scanner trivy -ApiSeverities @('MEDIUM') -EdgeSeverities @('LOW')
    $safeTrivyResult = Invoke-Validate 'safe-trivy' $safeTrivy.manifestPath
    Assert-Contract ($safeTrivyResult.exitCode -eq 0 -and $safeTrivyResult.output -match 'VALIDATED_SCAN_PASS') 'fresh safe Trivy bundle must validate PASS'
    $safeSummary = Get-Content -LiteralPath $safeTrivyResult.resultPath -Raw | ConvertFrom-Json
    Assert-Contract ($safeSummary.scannerInvoked -eq $false -and $safeSummary.registryOrAdvisoryNetworkInvoked -eq $false) 'validator result must state no scanner/network invocation'
    Assert-Contract ($safeSummary.details.aggregateCounts.medium -eq 1 -and $safeSummary.details.aggregateCounts.low -eq 1) 'normalized Trivy counts must be retained'

    $safeGrype = New-Bundle 'safe-grype' -Scanner grype -ApiSeverities @('LOW') -EdgeSeverities @()
    $safeGrypeResult = Invoke-Validate 'safe-grype' $safeGrype.manifestPath
    Assert-Contract ($safeGrypeResult.exitCode -eq 0 -and $safeGrypeResult.output -match 'VALIDATED_SCAN_PASS') 'fresh safe Grype bundle must validate PASS'

    $highPass = New-Bundle 'high-pass' -ApiSeverities @('HIGH') -Decision PASS
    $highPassResult = Invoke-Validate 'high-pass' $highPass.manifestPath
    Assert-Contract ($highPassResult.exitCode -eq 2 -and $highPassResult.output -match 'INVALID_PASS_DECISION') 'HIGH finding must never be labeled PASS'

    $highFail = New-Bundle 'high-fail' -ApiSeverities @('CRITICAL') -Decision FAIL
    $highFailResult = Invoke-Validate 'high-fail' $highFail.manifestPath
    Assert-Contract ($highFailResult.exitCode -eq 2 -and $highFailResult.output -match 'VALIDATED_SCAN_BLOCKS_RELEASE') 'valid critical evidence must block release'

    $countMismatch = New-Bundle 'count-mismatch' -ApiSeverities @('MEDIUM')
    $countMismatch.manifest.targets[0].declaredCounts.medium = 0
    Save-BundleManifest $countMismatch
    $countMismatchResult = Invoke-Validate 'count-mismatch' $countMismatch.manifestPath
    Assert-Contract ($countMismatchResult.exitCode -eq 2 -and $countMismatchResult.output -match 'COUNT_MISMATCH') 'declared/raw count mismatch must fail'

    $stale = New-Bundle 'stale-db' -DatabaseAgeHours 200
    $staleResult = Invoke-Validate 'stale-db' $stale.manifestPath -MaxAge 168
    Assert-Contract ($staleResult.exitCode -eq 3 -and $staleResult.output -match 'BLOCKED_STALE_DATABASE') 'stale advisory database must block'

    $rawHash = New-Bundle 'raw-hash'
    $rawHash.manifest.targets[0].rawReportSha256 = '0' * 64
    Save-BundleManifest $rawHash
    $rawHashResult = Invoke-Validate 'raw-hash' $rawHash.manifestPath
    Assert-Contract ($rawHashResult.exitCode -eq 2 -and $rawHashResult.output -match 'INVALID_EVIDENCE_HASH') 'raw report hash mismatch must fail'

    $dbHash = New-Bundle 'db-hash'
    $dbHash.manifest.scanner.databaseChecksum = 'sha256:' + ('0' * 64)
    Save-BundleManifest $dbHash
    $dbHashResult = Invoke-Validate 'db-hash' $dbHash.manifestPath
    Assert-Contract ($dbHashResult.exitCode -eq 2 -and $dbHashResult.output -match 'database metadata SHA256 mismatch') 'database metadata hash mismatch must fail'

    $logHash = New-Bundle 'log-hash'
    $logHash.manifest.scanner.executionLogSha256 = '0' * 64
    Save-BundleManifest $logHash
    $logHashResult = Invoke-Validate 'log-hash' $logHash.manifestPath
    Assert-Contract ($logHashResult.exitCode -eq 2 -and $logHashResult.output -match 'execution log SHA256 mismatch') 'execution log hash mismatch must fail'

    $traversal = New-Bundle 'path-traversal'
    $traversal.manifest.targets[0].rawReport = '..\escape.json'
    Save-BundleManifest $traversal
    $traversalResult = Invoke-Validate 'path-traversal' $traversal.manifestPath
    Assert-Contract ($traversalResult.exitCode -eq 2 -and $traversalResult.output -match 'INVALID_EVIDENCE_PATH') 'path traversal must be refused'

    $missing = New-Bundle 'missing-report'
    Remove-Item -LiteralPath (Join-Path $missing.dir $missing.manifest.targets[0].rawReport) -Force
    $missingResult = Invoke-Validate 'missing-report' $missing.manifestPath
    Assert-Contract ($missingResult.exitCode -eq 3 -and $missingResult.output -match 'BLOCKED_MISSING_REPORT') 'missing raw report must block'

    $duplicate = New-Bundle 'duplicate-target'
    $duplicate.manifest.targets[1].name = 'api'
    Save-BundleManifest $duplicate
    $duplicateResult = Invoke-Validate 'duplicate-target' $duplicate.manifestPath
    Assert-Contract ($duplicateResult.exitCode -eq 2 -and $duplicateResult.output -match 'unique api and edge') 'duplicate target names must fail'

    $imageMismatch = New-Bundle 'image-mismatch'
    $imageMismatch.manifest.targets[0].imageId = 'sha256:' + ('c' * 64)
    Save-BundleManifest $imageMismatch
    $imageMismatchResult = Invoke-Validate 'image-mismatch' $imageMismatch.manifestPath
    Assert-Contract ($imageMismatchResult.exitCode -eq 2 -and $imageMismatchResult.output -match 'IMAGE_ID_MISMATCH') 'report/manifest image mismatch must fail'

    $referenceMismatch = New-Bundle 'reference-mismatch'
    $referenceMismatch.manifest.targets[0].imageRef = 'campus-booking/wrong:synthetic'
    Save-BundleManifest $referenceMismatch
    $referenceMismatchResult = Invoke-Validate 'reference-mismatch' $referenceMismatch.manifestPath
    Assert-Contract ($referenceMismatchResult.exitCode -eq 2 -and $referenceMismatchResult.output -match 'IMAGE_REFERENCE_MISMATCH') 'report/manifest image reference mismatch must fail'

    $digestMismatch = New-Bundle 'digest-mismatch'
    $digestMismatch.manifest.targets[0].imageDigest = 'campus-booking/api@sha256:' + ('e' * 64)
    Save-BundleManifest $digestMismatch
    $digestMismatchResult = Invoke-Validate 'digest-mismatch' $digestMismatch.manifestPath
    Assert-Contract ($digestMismatchResult.exitCode -eq 2 -and $digestMismatchResult.output -match 'IMAGE_DIGEST_MISMATCH') 'provided report/manifest image digest mismatch must fail'

    $badTrivyShape = New-Bundle 'bad-trivy-shape'
    $badTrivyReportPath = Join-Path $badTrivyShape.dir $badTrivyShape.manifest.targets[0].rawReport
    $badTrivyReport = Get-Content -LiteralPath $badTrivyReportPath -Raw | ConvertFrom-Json
    $badTrivyReport.Results = [pscustomobject]@{ Target = 'synthetic'; Vulnerabilities = @() }
    Save-BundleReport $badTrivyShape 0 $badTrivyReport
    $badTrivyShapeResult = Invoke-Validate 'bad-trivy-shape' $badTrivyShape.manifestPath
    Assert-Contract ($badTrivyShapeResult.exitCode -eq 2 -and $badTrivyShapeResult.output -match 'Trivy Results must be a JSON array') 'scalar Trivy Results must not validate as zero findings'

    $badGrypeShape = New-Bundle 'bad-grype-shape' -Scanner grype
    $badGrypeReportPath = Join-Path $badGrypeShape.dir $badGrypeShape.manifest.targets[0].rawReport
    $badGrypeReport = Get-Content -LiteralPath $badGrypeReportPath -Raw | ConvertFrom-Json
    $badGrypeReport.matches = [pscustomobject]@{}
    Save-BundleReport $badGrypeShape 0 $badGrypeReport
    $badGrypeShapeResult = Invoke-Validate 'bad-grype-shape' $badGrypeShape.manifestPath
    Assert-Contract ($badGrypeShapeResult.exitCode -eq 2 -and $badGrypeShapeResult.output -match 'Grype matches must be a JSON array') 'scalar Grype matches must not validate as zero findings'

    $emptyLog = New-Bundle 'empty-log'
    $emptyLogPath = Join-Path $emptyLog.dir $emptyLog.manifest.scanner.executionLog
    [IO.File]::WriteAllBytes($emptyLogPath, [byte[]]@())
    $emptyLog.manifest.scanner.executionLogSha256 = Get-Hash $emptyLogPath
    Save-BundleManifest $emptyLog
    $emptyLogResult = Invoke-Validate 'empty-log' $emptyLog.manifestPath
    Assert-Contract ($emptyLogResult.exitCode -eq 2 -and $emptyLogResult.output -match 'execution log must not be empty') 'empty execution log must fail even with a matching hash'

    $sensitiveLog = New-Bundle 'sensitive-log'
    $sensitiveLogPath = Join-Path $sensitiveLog.dir $sensitiveLog.manifest.scanner.executionLog
    $syntheticCredentialLine = [string]::Concat('Authorization', ': ', 'Bearer', ' ', 'synthetic-rejected-value')
    Set-Content -LiteralPath $sensitiveLogPath -Value $syntheticCredentialLine -Encoding utf8NoBOM
    $sensitiveLog.manifest.scanner.executionLogSha256 = Get-Hash $sensitiveLogPath
    Save-BundleManifest $sensitiveLog
    $sensitiveLogResult = Invoke-Validate 'sensitive-log' $sensitiveLog.manifestPath
    Assert-Contract ($sensitiveLogResult.exitCode -eq 2 -and $sensitiveLogResult.output -match 'SENSITIVE_EVIDENCE') 'credential-like execution log content must fail even with a matching hash'

    $future = New-Bundle 'future-generated-at'
    $future.manifest.generatedAt = [datetimeoffset]::UtcNow.AddHours(1).ToString('o')
    Save-BundleManifest $future
    $futureResult = Invoke-Validate 'future-generated-at' $future.manifestPath
    Assert-Contract ($futureResult.exitCode -eq 2 -and $futureResult.output -match 'generatedAt is unreasonably in the future') 'future scan completion timestamp must fail'

    $incomplete = New-Bundle 'incomplete-scan'
    $incomplete.manifest.targets[0].scanCompleted = $false
    Save-BundleManifest $incomplete
    $incompleteResult = Invoke-Validate 'incomplete-scan' $incomplete.manifestPath
    Assert-Contract ($incompleteResult.exitCode -eq 3 -and $incompleteResult.output -match 'BLOCKED_INCOMPLETE_SCAN') 'incomplete scan flag must block'

    $reparse = New-Bundle 'reparse-path'
    $junctionPath = Join-Path $reparse.dir 'linked-report-dir'
    New-Item -ItemType Junction -Path $junctionPath -Target $safeTrivy.dir | Out-Null
    $reparse.manifest.targets[0].rawReport = 'linked-report-dir\api.trivy.json'
    $reparse.manifest.targets[0].rawReportSha256 = Get-Hash (Join-Path $junctionPath 'api.trivy.json')
    Save-BundleManifest $reparse
    $reparseResult = Invoke-Validate 'reparse-path' $reparse.manifestPath
    Assert-Contract ($reparseResult.exitCode -eq 2 -and $reparseResult.output -match 'symbolic link or junction') 'reparse-point escape from the manifest directory must fail'
    Remove-Item -LiteralPath $junctionPath -Force

    $nonZeroPass = New-Bundle 'nonzero-pass'
    $nonZeroPass.manifest.targets[0].scannerExitCode = 1
    Save-BundleManifest $nonZeroPass
    $nonZeroPassResult = Invoke-Validate 'nonzero-pass' $nonZeroPass.manifestPath
    Assert-Contract ($nonZeroPassResult.exitCode -eq 2 -and $nonZeroPassResult.output -match 'PASS requires scannerExitCode=0') 'PASS requires zero scanner exit codes'

    $nonZeroFail = New-Bundle 'nonzero-fail' -Decision FAIL
    $nonZeroFail.manifest.targets[0].scannerExitCode = 1
    Save-BundleManifest $nonZeroFail
    $nonZeroFailResult = Invoke-Validate 'nonzero-fail' $nonZeroFail.manifestPath
    Assert-Contract ($nonZeroFailResult.exitCode -eq 2 -and $nonZeroFailResult.output -match 'VALIDATED_SCAN_BLOCKS_RELEASE') 'non-zero scanner exit with FAIL must remain valid blocking evidence'

    $exampleResult = Invoke-Validate 'example-placeholder' $exampleManifest
    Assert-Contract ($exampleResult.exitCode -eq 3 -and $exampleResult.output -match 'BLOCKED_PLACEHOLDER_INPUT') 'committed example manifest must remain blocked until expanded'

    Write-Output "SCAN CONTRACT TESTS PASS - assertions=$assertions; no scanner, Docker daemon, registry, advisory service, or network request was invoked."
    exit 0
}
catch {
    Write-Warning $_
    exit 1
}
finally {
    if (-not $KeepArtifacts -and (Test-Path -LiteralPath $contractRoot)) {
        Remove-Item -LiteralPath $contractRoot -Recurse -Force
    }
}
