#Requires -Version 7.0
<#
.SYNOPSIS
    Offline contract tests for the T13 ApprovalBrowser owner-output boundary.
.DESCRIPTION
    Creates a synthetic local file-writing runner under ignored deploy/artifacts.
    It never starts Chrome, Docker, SQL, HTTP, Maven, npm, or a T01-T12 harness.
    Passing proves refusal (attestation/command form/owner-root containment/
    reparse points/executable type/traversal paths/stale output), freshness,
    strict-boolean manifest gates (cleanup/refresh), malformed-manifest
    degradation, unscanned-binary redaction fail-closed, screenshot-marker and
    redaction contracts only; ApprovalBrowser remains EXECUTED_UNPROVEN/OCR-8.
#>
[CmdletBinding()]
param(
    [string]$RunId = ('approval-contract-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ArtifactRoot = '',
    [switch]$KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning "REFUSED: invalid contract RunId '$RunId'."
    exit 2
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
$contractRoot = Join-Path $ArtifactRoot "approval-contract-$RunId"
$runner = Join-Path $PSScriptRoot 'run.ps1'
$templatePath = Join-Path $PSScriptRoot 'profile.example.json'
$assertions = 0

function Assert-Contract {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "APPROVAL CONTRACT FAILED: $Message" }
    $script:assertions++
}

function Write-JsonFile {
    param([string]$Path, $Value)
    Set-Content -LiteralPath $Path -Value ($Value | ConvertTo-Json -Depth 10) -Encoding utf8NoBOM
}

function Invoke-E2eRunner {
    param([string[]]$Arguments)
    $output = @(& pwsh -NoProfile -File $runner @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = ($output -join "`n") }
}

function New-ApprovalProfile {
    param([string]$OwnerRoot, [string]$OwnerRunner)
    return [ordered]@{
        publicAccessDenied = $true
        frontendUrl = 'http://127.0.0.1:4173/'
        backendUrl = 'http://127.0.0.1:18080/'
        bookingApiDir = (Join-Path $repoRoot 'booking-api')
        t08HarnessDir = (Join-Path $repoRoot 'scripts\tests\t08')
        composeFile = (Join-Path $repoRoot 'deploy\compose.yml')
        credentials = [ordered]@{
            dbUsernameEnv = 'T13_E2E_DB_USERNAME'
            dbPasswordEnv = 'T13_E2E_DB_PASSWORD'
            jwtSecretEnv = 'T13_E2E_JWT_SECRET'
            redisPasswordEnv = 'T13_E2E_REDIS_PASSWORD'
        }
        fixtureAttested = $false
        approvalBrowserFixtureAttested = $true
        approvalBrowserOwnerRoot = $OwnerRoot
        approvalBrowserCommand = @(
            $OwnerRunner,
            '{T13_RUN_ID}',
            '{T13_ARTIFACT_ROOT}'
        )
    }
}

try {
    New-Item -ItemType Directory -Path $contractRoot -Force | Out-Null
    $ownerRoot = Join-Path $contractRoot 'synthetic-owner-root'
    New-Item -ItemType Directory -Path $ownerRoot -Force | Out-Null
    $ownerRunner = Join-Path $ownerRoot 'synthetic-approval-runner.ps1'
    $ownerRunnerSource = @'
#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$ArtifactRoot,
    [string]$OmitCase = '',
    [int]$ExitCode = 0,
    [string]$EvilMode = ''
)
$ErrorActionPreference = 'Stop'
$caseIds = @(
    'admin-login-refresh',
    'pending-list-refresh',
    'approve-refresh',
    'reject-refresh',
    'student-approved-detail-refresh',
    'student-rejected-detail-refresh'
) | Where-Object { $_ -cne $OmitCase }
$screens = Join-Path $ArtifactRoot 'screenshots'
$network = Join-Path $ArtifactRoot 'network'
New-Item -ItemType Directory -Path $screens, $network -Force | Out-Null
$pngBytes = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
if ($EvilMode -eq 'traversal') {
    # Prove containment, not absence: the traversal target really exists one
    # level ABOVE the owner output root before T13 resolves it.
    $escapeDir = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($ArtifactRoot))
    [IO.File]::WriteAllBytes((Join-Path $escapeDir 'escape.png'), $pngBytes)
}
if ($EvilMode -eq 'binary-file') {
    # NUL bytes => unscannable binary under an unlisted extension.
    [IO.File]::WriteAllBytes((Join-Path $ArtifactRoot 'blob.dat'), [byte[]](0x00, 0x01, 0x02, 0x03, 0xFF))
}
$cases = @()
$first = $true
foreach ($id in $caseIds) {
    $shotRel = "screenshots/$id.png"
    if ($EvilMode -eq 'traversal') { $shotRel = '../../escape.png' }
    $networkRel = "network/$id.jsonl"
    if ($EvilMode -ne 'traversal') {
        [IO.File]::WriteAllBytes((Join-Path $ArtifactRoot $shotRel), $pngBytes)
    }
    Set-Content -LiteralPath (Join-Path $ArtifactRoot $networkRel) -Value '{"header":"Authorization: Bearer abcdefghijklmnop","password":"synthetic-only"}' -Encoding utf8NoBOM
    $caseEntry = [ordered]@{
        id = $id
        status = 'PASS'
    }
    if ($EvilMode -ne 'missing-case-field' -or -not $first) {
        $caseEntry.refreshObserved = $true
        $caseEntry.apiReloadObserved = $true
    }
    $caseEntry.routeAfterRefresh = '/admin/approvals'
    $caseEntry.screenshot = $shotRel
    $caseEntry.networkEvidence = $networkRel
    $cases += $caseEntry
    $first = $false
}
if ($EvilMode -eq 'malformed-manifest') {
    $manifest = [ordered]@{ schemaVersion = 1; runId = $RunId }
} else {
    $cleanupPerformed = $true
    if ($EvilMode -eq 'cleanup-false') { $cleanupPerformed = $false }
    if ($EvilMode -eq 'cleanup-string') { $cleanupPerformed = 'false' }
    $manifest = [ordered]@{
        schemaVersion = 1
        runId = $RunId
        cleanup = [ordered]@{ performed = $cleanupPerformed; status = 'PASS' }
        cases = $cases
    }
}
Set-Content -LiteralPath (Join-Path $ArtifactRoot 'approval-evidence.json') -Value ($manifest | ConvertTo-Json -Depth 8) -Encoding utf8NoBOM
Write-Output 'Authorization: Bearer abcdefghijklmnop'
exit $ExitCode
'@
    Set-Content -LiteralPath $ownerRunner -Value $ownerRunnerSource -Encoding utf8NoBOM

    $templateRaw = Get-Content -LiteralPath $templatePath -Raw
    $template = $templateRaw | ConvertFrom-Json
    Assert-Contract (-not [bool]$template.approvalBrowserFixtureAttested) 'template must not self-attest ApprovalBrowser'
    Assert-Contract ($null -eq $template.approvalBrowserCommand) 'template must not provide an executable command'
    Assert-Contract ([string]$template.approvalBrowserContract.runIdPlaceholder -ceq '{T13_RUN_ID}') 'template must document the run-id argv placeholder'
    Assert-Contract ([string]$template.approvalBrowserContract.artifactRootPlaceholder -ceq '{T13_ARTIFACT_ROOT}') 'template must document the artifact-root argv placeholder'
    Assert-Contract (@($template.approvalBrowserContract.requiredCaseIds).Count -eq 6) 'template must list six required refresh cases'

    $profile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $profilePath = Join-Path $contractRoot 'valid-profile.json'
    Write-JsonFile $profilePath $profile

    $unattested = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $unattested.approvalBrowserFixtureAttested = $false
    $unattestedPath = Join-Path $contractRoot 'unattested-profile.json'
    Write-JsonFile $unattestedPath $unattested
    $unattestedResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', 'approval-unattested', '-ProfilePath', $unattestedPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($unattestedResult.exitCode -eq 3 -and $unattestedResult.output -match 'requires approvalBrowserFixtureAttested=true') 'unattested fixture must block before owner command execution'

    $stringCommand = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $stringCommand.approvalBrowserCommand = $ownerRunner
    $stringCommandPath = Join-Path $contractRoot 'string-command-profile.json'
    Write-JsonFile $stringCommandPath $stringCommand
    $stringCommandResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', 'approval-string-command', '-ProfilePath', $stringCommandPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($stringCommandResult.exitCode -eq 2 -and $stringCommandResult.output -match 'must be a JSON array') 'shell/string command forms must be refused'

    $missingPlaceholder = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $missingPlaceholder.approvalBrowserCommand = @($ownerRunner, '{T13_RUN_ID}')
    $missingPlaceholderPath = Join-Path $contractRoot 'missing-placeholder-profile.json'
    Write-JsonFile $missingPlaceholderPath $missingPlaceholder
    $missingPlaceholderResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', 'approval-missing-placeholder', '-ProfilePath', $missingPlaceholderPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($missingPlaceholderResult.exitCode -eq 2 -and $missingPlaceholderResult.output -match 'argv\[1:2\].*T13_ARTIFACT_ROOT') 'both positional argv placeholders must be present exactly once'

    $outsideRoot = New-ApprovalProfile -OwnerRoot (Join-Path $contractRoot 'other-owner-root') -OwnerRunner $ownerRunner
    New-Item -ItemType Directory -Path $outsideRoot.approvalBrowserOwnerRoot -Force | Out-Null
    $outsideRootPath = Join-Path $contractRoot 'outside-root-profile.json'
    Write-JsonFile $outsideRootPath $outsideRoot
    $outsideRootResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', 'approval-outside-root', '-ProfilePath', $outsideRootPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($outsideRootResult.exitCode -eq 2 -and $outsideRootResult.output -match 'inside the repository-local owner-approved harness root') 'executable outside the owner root must be refused'

    $validRunId = 'approval-valid-contract'
    $validResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $validRunId, '-ProfilePath', $profilePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($validResult.exitCode -eq 2 -and $validResult.output -match 'remains UNPROVEN.*contractComplete=True') 'complete synthetic evidence must remain non-pass/EXECUTED_UNPROVEN'
    $validArtifacts = Join-Path $contractRoot "e2e-ApprovalBrowser-$validRunId"
    $status = Get-Content -LiteralPath (Join-Path $validArtifacts 'approval-browser-status.json') -Raw | ConvertFrom-Json
    Assert-Contract ([bool]$status.contractComplete -and [string]$status.status -ceq 'EXECUTED_UNPROVEN') 'status must distinguish contract completeness from acceptance'
    $redaction = Get-Content -LiteralPath (Join-Path $validArtifacts 'redaction-manifest.json') -Raw | ConvertFrom-Json
    Assert-Contract (@($redaction.residual).Count -eq 0 -and @($redaction.unsafeLinks).Count -eq 0 -and @($redaction.oversizeUnredacted).Count -eq 0) 'synthetic owner output and command log must pass fail-closed redaction'
    $networkText = Get-Content -LiteralPath (Join-Path $validArtifacts 'approval-owner-output\network\approve-refresh.jsonl') -Raw
    Assert-Contract ($networkText -notmatch 'abcdefghijklmnop|synthetic-only' -and $networkText -match 'REDACTED|"\*\*\*"') 'network evidence secrets must be redacted in place'
    Assert-Contract (Test-Path -LiteralPath (Join-Path $validArtifacts 'approval-owner-output\REQUIRES-MANUAL-VISUAL-PII-REVIEW.txt')) 'referenced PNGs must receive a manual visual review marker'

    foreach ($missingCase in @($template.approvalBrowserContract.requiredCaseIds)) {
        $caseRunId = 'approval-missing-' + ([string]$missingCase -replace '[^A-Za-z0-9_-]', '-')
        $caseProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
        $caseProfile.approvalBrowserCommand += @([string]$missingCase)
        $caseProfilePath = Join-Path $contractRoot "$caseRunId-profile.json"
        Write-JsonFile $caseProfilePath $caseProfile
        $caseResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $caseRunId, '-ProfilePath', $caseProfilePath, '-ArtifactRoot', $contractRoot)
        $caseStatusPath = Join-Path $contractRoot "e2e-ApprovalBrowser-$caseRunId\approval-browser-status.json"
        $caseStatus = Get-Content -LiteralPath $caseStatusPath -Raw | ConvertFrom-Json
        Assert-Contract ($caseResult.exitCode -eq 2 -and -not [bool]$caseStatus.contractComplete -and (@($caseStatus.errors) -join ';') -match [regex]::Escape([string]$missingCase)) "missing refresh case '$missingCase' must fail the evidence contract"
    }

    $nonzeroRunId = 'approval-owner-nonzero'
    $nonzeroProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $nonzeroProfile.approvalBrowserCommand += @('', '9')
    $nonzeroProfilePath = Join-Path $contractRoot 'nonzero-profile.json'
    Write-JsonFile $nonzeroProfilePath $nonzeroProfile
    $nonzeroResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $nonzeroRunId, '-ProfilePath', $nonzeroProfilePath, '-ArtifactRoot', $contractRoot)
    $nonzeroStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$nonzeroRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($nonzeroResult.exitCode -eq 2 -and -not [bool]$nonzeroStatus.contractComplete -and [int]$nonzeroStatus.commandExit -eq 9) 'nonzero owner runner must remain invalid and non-pass'

    # Negative: a screenshot path escaping the owner output via ../ must be
    # refused even though the target file really exists outside the root.
    $traversalRunId = 'approval-traversal-path'
    $traversalProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $traversalProfile.approvalBrowserCommand += @('', '0', 'traversal')
    $traversalProfilePath = Join-Path $contractRoot 'traversal-profile.json'
    Write-JsonFile $traversalProfilePath $traversalProfile
    $traversalResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $traversalRunId, '-ProfilePath', $traversalProfilePath, '-ArtifactRoot', $contractRoot)
    $traversalStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$traversalRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($traversalResult.exitCode -eq 2 -and -not [bool]$traversalStatus.contractComplete) 'traversal screenshot paths must keep the run unproven'
    Assert-Contract ((@($traversalStatus.errors) -join ';') -match 'screenshot missing/unsafe') 'traversal screenshot paths must be reported as unsafe, never resolved'
    Assert-Contract (Test-Path -LiteralPath (Join-Path $contractRoot 'escape.png')) 'the traversal target must exist outside the owner output so refusal proves containment, not absence'

    # Negative: cleanup.performed=false must fail the evidence contract.
    $cleanupFalseRunId = 'approval-cleanup-false'
    $cleanupFalseProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $cleanupFalseProfile.approvalBrowserCommand += @('', '0', 'cleanup-false')
    $cleanupFalseProfilePath = Join-Path $contractRoot 'cleanup-false-profile.json'
    Write-JsonFile $cleanupFalseProfilePath $cleanupFalseProfile
    $cleanupFalseResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $cleanupFalseRunId, '-ProfilePath', $cleanupFalseProfilePath, '-ArtifactRoot', $contractRoot)
    $cleanupFalseStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$cleanupFalseRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($cleanupFalseResult.exitCode -eq 2 -and -not [bool]$cleanupFalseStatus.contractComplete -and (@($cleanupFalseStatus.errors) -join ';') -match 'manifest cleanup must be performed/PASS') 'cleanup.performed=false must fail the evidence contract'

    # Negative: the STRING "false" must not satisfy the boolean cleanup gate.
    $cleanupStringRunId = 'approval-cleanup-string'
    $cleanupStringProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $cleanupStringProfile.approvalBrowserCommand += @('', '0', 'cleanup-string')
    $cleanupStringProfilePath = Join-Path $contractRoot 'cleanup-string-profile.json'
    Write-JsonFile $cleanupStringProfilePath $cleanupStringProfile
    $cleanupStringResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $cleanupStringRunId, '-ProfilePath', $cleanupStringProfilePath, '-ArtifactRoot', $contractRoot)
    $cleanupStringStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$cleanupStringRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($cleanupStringResult.exitCode -eq 2 -and -not [bool]$cleanupStringStatus.contractComplete -and (@($cleanupStringStatus.errors) -join ';') -match 'manifest cleanup must be performed/PASS') 'a string "false" must not satisfy the boolean cleanup gate'

    # Negative: unscannable binary content under an unlisted extension must
    # fail the redaction pass (never skip-then-pass).
    $binaryRunId = 'approval-unscanned-binary'
    $binaryProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $binaryProfile.approvalBrowserCommand += @('', '0', 'binary-file')
    $binaryProfilePath = Join-Path $contractRoot 'binary-profile.json'
    Write-JsonFile $binaryProfilePath $binaryProfile
    $binaryResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $binaryRunId, '-ProfilePath', $binaryProfilePath, '-ArtifactRoot', $contractRoot)
    $binaryStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$binaryRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($binaryResult.exitCode -eq 2) 'unscannable binary owner output must keep the run unproven'
    Assert-Contract ([int]$binaryStatus.redactionExit -eq 2 -and -not [bool]$binaryStatus.contractComplete) 'unscannable binary owner output must fail the redaction gate'

    # Negative: a manifest without a cases array must degrade to contract
    # errors with a status file, never an abort without evidence.
    $malformedRunId = 'approval-malformed-manifest'
    $malformedProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $malformedProfile.approvalBrowserCommand += @('', '0', 'malformed-manifest')
    $malformedProfilePath = Join-Path $contractRoot 'malformed-profile.json'
    Write-JsonFile $malformedProfilePath $malformedProfile
    $malformedResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $malformedRunId, '-ProfilePath', $malformedProfilePath, '-ArtifactRoot', $contractRoot)
    $malformedStatusPath = Join-Path $contractRoot "e2e-ApprovalBrowser-$malformedRunId\approval-browser-status.json"
    Assert-Contract (Test-Path -LiteralPath $malformedStatusPath) 'a malformed manifest must still produce a status file'
    $malformedStatus = Get-Content -LiteralPath $malformedStatusPath -Raw | ConvertFrom-Json
    Assert-Contract ($malformedResult.exitCode -eq 2 -and -not [bool]$malformedStatus.contractComplete -and (@($malformedStatus.errors) -join ';') -match 'required case missing/duplicated') 'a manifest without cases must fail the required-case contract instead of aborting'

    # Negative: a case object missing boolean refresh fields must report the
    # incomplete case contract instead of crashing on a missing property.
    $fieldRunId = 'approval-missing-field'
    $fieldProfile = New-ApprovalProfile -OwnerRoot $ownerRoot -OwnerRunner $ownerRunner
    $fieldProfile.approvalBrowserCommand += @('', '0', 'missing-case-field')
    $fieldProfilePath = Join-Path $contractRoot 'missing-field-profile.json'
    Write-JsonFile $fieldProfilePath $fieldProfile
    $fieldResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $fieldRunId, '-ProfilePath', $fieldProfilePath, '-ArtifactRoot', $contractRoot)
    $fieldStatus = Get-Content -LiteralPath (Join-Path $contractRoot "e2e-ApprovalBrowser-$fieldRunId\approval-browser-status.json") -Raw | ConvertFrom-Json
    Assert-Contract ($fieldResult.exitCode -eq 2 -and -not [bool]$fieldStatus.contractComplete -and (@($fieldStatus.errors) -join ';') -match 'case contract incomplete: admin-login-refresh') 'a case missing refreshObserved must fail the case contract instead of aborting'

    $staleResult = Invoke-E2eRunner @('-Mode', 'ApprovalBrowser', '-Execute', '-RunId', $validRunId, '-ProfilePath', $profilePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($staleResult.exitCode -eq 2 -and $staleResult.output -match 'output directory is not empty; stale evidence cannot be reused') 'reusing an existing run-id output must fail closed before command execution'

    Write-Output "APPROVAL CONTRACT TESTS PASS - assertions=$assertions; no Chrome, Docker, SQL, HTTP, Maven, npm, or T01-T12 harness was invoked."
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
