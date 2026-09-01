#Requires -Version 7.0
<#
.SYNOPSIS
    Offline contract tests for the T13 demo profile, refusal gates, teardown
    scope, secret lifecycle, and Draft evidence template.
.DESCRIPTION
    Uses only Plan/refusal paths that terminate before Docker, SQL, HTTP, E2E or
    browser invocation. Passing this suite proves harness safety contracts only;
    it is not fixture attestation, Demo execution, teardown runtime evidence, or
    acceptance evidence for tasks 6.1-6.4.
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
    Write-Warning "REFUSED: invalid contract RunId '$RunId'."
    exit 2
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
$contractRoot = Join-Path $ArtifactRoot "demo-contract-$RunId"
$runner = Join-Path $PSScriptRoot 'run.ps1'
$profileTemplatePath = Join-Path $PSScriptRoot 'profile.example.json'
$evidenceTemplatePath = Join-Path $PSScriptRoot 'evidence-index.template.md'
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

function Invoke-Runner {
    param([string[]]$Arguments)
    $output = @(& pwsh -NoProfile -File $runner @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = ($output -join "`n") }
}

function New-ValidProfile {
    return [ordered]@{
        publicAccessDenied = $true
        frontendUrl = 'http://127.0.0.1:4173/'
        backendUrl = 'http://127.0.0.1:18080/'
        composeFile = (Join-Path $repoRoot 'deploy\compose.yml')
        t08SeedPath = (Join-Path $repoRoot 'scripts\tests\t08\seed.sql')
        t08HarnessDir = (Join-Path $repoRoot 'scripts\tests\t08')
        e2eRunPath = (Join-Path $repoRoot 'deploy\e2e\run.ps1')
        fixtureOwner = 'T13 ephemeral runtime fixture contract test; not attested'
        namespacePrefix = 't13demo'
        credentials = [ordered]@{ rootPasswordEnv = 'MYSQL_ROOT_PASSWORD' }
        fixtureAttested = $false
        approvalBrowserFixtureAttested = $false
        noShowScanWaitSeconds = 0
    }
}

function New-ValidMap {
    param([string]$MapRunId)
    $prefix = 't13demo_' + ($MapRunId -replace '-', '_')
    return [ordered]@{
        runId = $MapRunId
        userPrefix = $prefix
        purposePrefix = "T13DEMO:$MapRunId`:"
        users = [ordered]@{
            admin = [ordered]@{ username = "${prefix}_admin"; id = '1' }
            student = [ordered]@{ username = "${prefix}_student"; id = '2' }
            intruder = [ordered]@{ username = "${prefix}_intruder"; id = '3' }
        }
        demoResourceId = '10'
        demoResourceName = "T13 DEMO $MapRunId approval room"
        demoCategoryId = '4'
        demoCategoryName = "T13D-$MapRunId"
        pendingBookingId = '5'
        pastConfirmedBookingId = '6'
    }
}

function Write-Profile {
    param([string]$Name, $Profile)
    $path = Join-Path $contractRoot "$Name.json"
    Write-JsonFile -Path $path -Value $Profile
    return $path
}

try {
    New-Item -ItemType Directory -Path $contractRoot -Force | Out-Null

    # Template/profile contract remains local, unapproved, and value-secret-free.
    $templateRaw = Get-Content -LiteralPath $profileTemplatePath -Raw
    $template = $templateRaw | ConvertFrom-Json
    Assert-Contract ([bool]$template.publicAccessDenied) 'demo template must deny public access'
    foreach ($urlName in @('frontendUrl', 'backendUrl')) {
        $uri = [uri]([string]$template.$urlName)
        Assert-Contract ($uri.Host -eq '127.0.0.1') "$urlName must be loopback-only"
    }
    Assert-Contract ($template.fixtureAttested -eq $false) 'fixture template must not self-attest'
    Assert-Contract ($template.approvalBrowserFixtureAttested -eq $false) 'approval fixture template must remain unattested'
    Assert-Contract ([string]$template.namespacePrefix -eq 't13demo') 'namespace template must remain t13demo'
    Assert-Contract ([string]$template.credentials.rootPasswordEnv -eq 'MYSQL_ROOT_PASSWORD') 'profile may record only the root credential environment name'
    Assert-Contract ($templateRaw -notmatch '(?i)"(?:password|token|secret)"\s*:') 'profile must not contain credential value fields'

    # Default Plan mode is inert and explicit about how execution is requested.
    $plan = Invoke-Runner @('-Mode', 'All', '-RunId', 'ct-plan', '-ProfilePath', $profileTemplatePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($plan.exitCode -eq 0 -and $plan.output -match 'PLAN MODE - nothing invoked' -and $plan.output -match 'Run with -Execute') 'Plan mode must be inert and explicit'

    $badRunId = Invoke-Runner @('-Mode', 'All', '-RunId', 'bad!run', '-ProfilePath', $profileTemplatePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($badRunId.exitCode -eq 2 -and $badRunId.output -match 'REFUSED: RunId fails') 'invalid RunId must be refused'

    $missingProfile = Invoke-Runner @('-Mode', 'StudentFlow', '-Execute', '-RunId', 'ct-missing-profile', '-ProfilePath', (Join-Path $contractRoot 'absent.json'), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($missingProfile.exitCode -eq 3 -and $missingProfile.output -match 'BLOCKED: demo profile not found') 'missing profile must block execution'

    $publicProfile = New-ValidProfile; $publicProfile.publicAccessDenied = $false
    $publicResult = Invoke-Runner @('-Mode', 'StudentFlow', '-RunId', 'ct-public', '-ProfilePath', (Write-Profile 'public' $publicProfile), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($publicResult.exitCode -eq 2 -and $publicResult.output -match 'publicAccessDenied must be true') 'publicAccessDenied=false must be refused'

    $externalProfile = New-ValidProfile; $externalProfile.backendUrl = 'https://example.invalid/'
    $externalResult = Invoke-Runner @('-Mode', 'StudentFlow', '-RunId', 'ct-external', '-ProfilePath', (Write-Profile 'external' $externalProfile), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($externalResult.exitCode -eq 2 -and $externalResult.output -match 'host is not loopback') 'non-loopback backend must be refused before execution'

    $ownerlessProfile = New-ValidProfile; $ownerlessProfile.fixtureOwner = '<owner-placeholder>'
    $ownerlessResult = Invoke-Runner @('-Mode', 'StudentFlow', '-RunId', 'ct-ownerless', '-ProfilePath', (Write-Profile 'ownerless' $ownerlessProfile), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($ownerlessResult.exitCode -eq 2 -and $ownerlessResult.output -match 'fixtureOwner') 'missing fixture owner identity must be refused'

    $namespaceProfile = New-ValidProfile; $namespaceProfile.namespacePrefix = 'other'
    $namespaceResult = Invoke-Runner @('-Mode', 'StudentFlow', '-RunId', 'ct-namespace', '-ProfilePath', (Write-Profile 'namespace' $namespaceProfile), '-ArtifactRoot', $contractRoot)
    Assert-Contract ($namespaceResult.exitCode -eq 2 -and $namespaceResult.output -match 'namespacePrefix') 'namespace drift must be refused'

    foreach ($badWait in @('abc', -1, 3601)) {
        $waitProfile = New-ValidProfile; $waitProfile.noShowScanWaitSeconds = $badWait
        $waitResult = Invoke-Runner @('-Mode', 'StudentFlow', '-RunId', "ct-wait-$($badWait -replace '[^A-Za-z0-9]', 'x')", '-ProfilePath', (Write-Profile "wait-$($badWait -replace '[^A-Za-z0-9]', 'x')" $waitProfile), '-ArtifactRoot', $contractRoot)
        Assert-Contract ($waitResult.exitCode -eq 2 -and $waitResult.output -match 'noShowScanWaitSeconds') "invalid no-show wait '$badWait' must be refused"
    }

    # Execute with the committed template stops on unexpanded repo paths, before
    # any Docker/API/E2E call.
    $placeholder = Invoke-Runner @('-Mode', 'StudentFlow', '-Execute', '-RunId', 'ct-placeholder', '-ProfilePath', $profileTemplatePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($placeholder.exitCode -eq 3 -and $placeholder.output -match 'profile path placeholder not expanded') 'template placeholders must block Execute'

    # A fully resolved but unattested profile reaches only the explicit owner gate.
    $validProfilePath = Write-Profile 'valid-unattested' (New-ValidProfile)
    $studentBlocked = Invoke-Runner @('-Mode', 'StudentFlow', '-Execute', '-RunId', 'ct-student-blocked', '-ProfilePath', $validProfilePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($studentBlocked.exitCode -eq 3 -and $studentBlocked.output -match 'requires owner-reviewed fixtureAttested=true') 'StudentFlow must block without owner attestation'
    Assert-Contract (@(Get-ChildItem -LiteralPath $contractRoot -Directory -Filter 'e2e-StudentBrowser-*' -ErrorAction SilentlyContinue).Count -eq 0) 'blocked StudentFlow must not produce browser evidence'

    $teardownMissing = Invoke-Runner @('-Mode', 'Teardown', '-Execute', '-RunId', 'ct-teardown-missing', '-ProfilePath', $validProfilePath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($teardownMissing.exitCode -eq 3 -and $teardownMissing.output -match 'Teardown requires -MapPath') 'Teardown must never guess a fixture map'

    $badNamespaceMap = [ordered]@{ runId = 'ct-map'; userPrefix = 'wrong'; purposePrefix = 'wrong' }
    $badNamespaceMapPath = Join-Path $contractRoot 'bad-namespace-map.json'
    Write-JsonFile $badNamespaceMapPath $badNamespaceMap
    $badNamespaceMapResult = Invoke-Runner @('-Mode', 'Teardown', '-Execute', '-RunId', 'ct-map', '-ProfilePath', $validProfilePath, '-MapPath', $badNamespaceMapPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($badNamespaceMapResult.exitCode -eq 2 -and $badNamespaceMapResult.output -match 'namespace markers are invalid') 'invalid map namespace must be refused before SQL'

    $badNumericMap = New-ValidMap 'ct-map-numeric'
    $badNumericMap.demoResourceId = 'not-numeric'
    $badNumericMapPath = Join-Path $contractRoot 'bad-numeric-map.json'
    Write-JsonFile $badNumericMapPath $badNumericMap
    $badNumericMapResult = Invoke-Runner @('-Mode', 'Teardown', '-Execute', '-RunId', 'ct-map-numeric', '-ProfilePath', $validProfilePath, '-MapPath', $badNumericMapPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($badNumericMapResult.exitCode -eq 2 -and $badNumericMapResult.output -match 'resource id must be numeric') 'non-numeric resource id must be refused before SQL'

    $duplicateBookingMap = New-ValidMap 'ct-map-dup-booking'
    $duplicateBookingMap.pastConfirmedBookingId = $duplicateBookingMap.pendingBookingId
    $duplicateBookingMapPath = Join-Path $contractRoot 'duplicate-booking-map.json'
    Write-JsonFile $duplicateBookingMapPath $duplicateBookingMap
    $duplicateBookingResult = Invoke-Runner @('-Mode', 'Teardown', '-Execute', '-RunId', 'ct-map-dup-booking', '-ProfilePath', $validProfilePath, '-MapPath', $duplicateBookingMapPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($duplicateBookingResult.exitCode -eq 2 -and $duplicateBookingResult.output -match 'booking ids must be distinct') 'duplicate booking ids must be refused before SQL'

    $duplicateUserMap = New-ValidMap 'ct-map-dup-user'
    $duplicateUserMap.users.student.id = $duplicateUserMap.users.admin.id
    $duplicateUserMapPath = Join-Path $contractRoot 'duplicate-user-map.json'
    Write-JsonFile $duplicateUserMapPath $duplicateUserMap
    $duplicateUserResult = Invoke-Runner @('-Mode', 'Teardown', '-Execute', '-RunId', 'ct-map-dup-user', '-ProfilePath', $validProfilePath, '-MapPath', $duplicateUserMapPath, '-ArtifactRoot', $contractRoot)
    Assert-Contract ($duplicateUserResult.exitCode -eq 2 -and $duplicateUserResult.output -match 'user ids must be distinct') 'duplicate user ids must be refused before SQL'

    # Static source contract: cryptographic secret lifecycle and exact teardown.
    $source = Get-Content -LiteralPath $runner -Raw
    Assert-Contract ($source -match 'New-Object byte\[\] 32') 'password generator must use 32 random bytes'
    Assert-Contract ($source -match 'RandomNumberGenerator.*GetBytes') 'password generator must be cryptographic'
    Assert-Contract ($source -match 'GetRandomFileName\(\).*\.json') 'secret file name must be system-random'
    Assert-Contract ($source -match '(?s)finally\s*\{.*Remove-SecretFileOrFail') 'secret cleanup must run from finally'
    Assert-Contract ($source -match 'ROTATE the generated passwords') 'secret cleanup failure must instruct rotation'

    $setupStart = $source.IndexOf('function Invoke-DemoSetup', [StringComparison]::Ordinal)
    $setupEnd = $source.IndexOf('function Invoke-DemoStudentFlow', $setupStart, [StringComparison]::Ordinal)
    Assert-Contract ($setupStart -ge 0 -and $setupEnd -gt $setupStart) 'Setup source block must be locatable'
    $setup = $source.Substring($setupStart, $setupEnd - $setupStart)
    $preflightGateIndex = $setup.IndexOf('demo RunId namespace is not empty', [StringComparison]::Ordinal)
    $recoveryScopeWriteIndex = $setup.IndexOf('Set-Content -LiteralPath $recoveryScopePath', [StringComparison]::Ordinal)
    $firstApiWriteIndex = $setup.IndexOf('Invoke-Api -Method Post -Url "$beUrl/api/v1/auth/register"', [StringComparison]::Ordinal)
    Assert-Contract ($preflightGateIndex -ge 0 -and $firstApiWriteIndex -gt $preflightGateIndex) 'exact namespace preflight must precede the first API mutation'
    Assert-Contract ($recoveryScopeWriteIndex -gt $preflightGateIndex -and $recoveryScopeWriteIndex -lt $firstApiWriteIndex) 'non-secret recovery scope must be written after preflight and before the first mutation'
    Assert-Contract ($setup -match 'WHERE username IN \$expectedUserIn') 'Setup preflight must check exact usernames'
    Assert-Contract ($setup -match 'resource_category WHERE name=''\$categoryName''') 'Setup preflight must check the exact category name'
    Assert-Contract ($setup -match 'resource WHERE name=''\$resourceName''') 'Setup preflight must check the exact resource name'
    Assert-Contract ($setup -match 'booking WHERE purpose IN \$expectedPurposeIn') 'Setup preflight must check the two exact purposes'
    Assert-Contract ($setup -notmatch '(?im)Invoke-RootSql[^\r\n]*\bLIKE\b') 'Setup preflight must not use LIKE scopes'
    $recoveryStart = $setup.IndexOf('$recoveryScope = [ordered]@{', [StringComparison]::Ordinal)
    Assert-Contract ($recoveryStart -ge 0 -and $recoveryScopeWriteIndex -gt $recoveryStart) 'recovery scope source block must be locatable'
    $recoveryBlock = $setup.Substring($recoveryStart, $recoveryScopeWriteIndex - $recoveryStart)
    Assert-Contract ($recoveryBlock -notmatch '(?i)\b(password|token|secret)\b') 'recovery scope must not contain credential fields'

    $mapStart = $source.IndexOf('$map = [ordered]@{', [StringComparison]::Ordinal)
    $mapEnd = $source.IndexOf('Set-Content -LiteralPath $createdMapPath', $mapStart, [StringComparison]::Ordinal)
    Assert-Contract ($mapStart -ge 0 -and $mapEnd -gt $mapStart) 'fixture-map source block must be locatable'
    $mapBlock = $source.Substring($mapStart, $mapEnd - $mapStart)
    Assert-Contract ($mapBlock -notmatch '(?i)\b(password|token|secret)\b') 'fixture-map must not contain secret fields'

    $teardownStart = $source.IndexOf('function Invoke-DemoTeardown', [StringComparison]::Ordinal)
    $teardownEnd = $source.IndexOf('# ---- Orchestration', $teardownStart, [StringComparison]::Ordinal)
    Assert-Contract ($teardownStart -ge 0 -and $teardownEnd -gt $teardownStart) 'teardown source block must be locatable'
    $teardown = $source.Substring($teardownStart, $teardownEnd - $teardownStart)
    Assert-Contract ($teardown -notmatch '(?im)Invoke-RootSql[^\r\n]*\bLIKE\b') 'teardown SQL must not use LIKE scopes'
    Assert-Contract ($teardown -notmatch '(?i)DROP\s+(DATABASE|TABLE)|docker\s+(?:compose\s+)?(?:down|volume)|\s-v\b') 'teardown must not drop schema or volumes'
    Assert-Contract ($teardown -match 'DELETE booking FROM booking WHERE \$scoped') 'booking deletion must use exact recorded booking ids'
    Assert-Contract ($teardown -match '(?s)\$ownedUsers\s*=.*?SELECT COUNT\(\*\) FROM ``user``.*?id=\{0\}.*?username=') 'teardown must verify exact user id+username ownership before deletes'
    Assert-Contract ($teardown -match '(?s)\$ownedResource\s*=.*?JOIN resource_category.*?r\.id=.*?r\.name=.*?c\.id=.*?c\.name=') 'teardown must verify exact resource+category ownership before deletes'
    Assert-Contract ($teardown -match '(?s)\$ownedBookings\s*=.*?purpose=.*?user_id=.*?resource_id=') 'teardown must verify booking purpose, student and resource ownership before deletes'
    $ownershipGateIndex = $teardown.IndexOf('fixture map ownership tuples do not match', [StringComparison]::Ordinal)
    $firstDeleteIndex = $teardown.IndexOf('DELETE violation_record', [StringComparison]::Ordinal)
    Assert-Contract ($ownershipGateIndex -ge 0 -and $ownershipGateIndex -lt $firstDeleteIndex) 'all ownership tuple gates must precede the first delete'
    $categoryValidationIndex = $teardown.IndexOf('fixture map category id must be numeric', [StringComparison]::Ordinal)
    Assert-Contract ($categoryValidationIndex -ge 0 -and $categoryValidationIndex -lt $firstDeleteIndex) 'category id validation must precede the first delete'
    foreach ($gateText in @(
        'fixture map booking ids must be numeric',
        'fixture map booking ids must be distinct',
        'fixture map user ids must be numeric',
        'fixture map user ids must be distinct',
        'fixture map usernames must be distinct',
        'fixture map resource id must be numeric'
    )) {
        $gateIndex = $teardown.IndexOf($gateText, [StringComparison]::Ordinal)
        Assert-Contract ($gateIndex -ge 0 -and $gateIndex -lt $firstDeleteIndex) "'$gateText' gate must precede the first delete"
    }
    $deleteOrder = @('DELETE violation_record', 'DELETE approval_record', 'DELETE booking_slot', 'DELETE booking FROM', 'DELETE notification', 'DELETE blacklist', 'DELETE FROM resource_time_rule', 'DELETE FROM resource_closure', 'DELETE FROM resource WHERE', 'DELETE FROM resource_category', 'DELETE FROM ``user``')
    $previousIndex = -1
    foreach ($needle in $deleteOrder) {
        $currentIndex = $teardown.IndexOf($needle, [StringComparison]::Ordinal)
        Assert-Contract ($currentIndex -gt $previousIndex) "teardown must keep children-first order at '$needle'"
        $previousIndex = $currentIndex
    }

    # Evidence template stays entirely Draft/blocked and never self-promotes.
    $evidence = Get-Content -LiteralPath $evidenceTemplatePath -Raw
    Assert-Contract ($evidence -match 'Every row below is a NOT RUN / DRAFT placeholder') 'evidence template must declare Draft status globally'
    Assert-Contract ($evidence -match 'Approval browser flow.*BLOCKED \(OCR-8\)') 'approval browser row must remain OCR-8 blocked'
    Assert-Contract ($evidence -match 'REQUIRES MANUAL VISUAL PII REVIEW') 'screenshot evidence must require manual PII review'
    Assert-Contract ($evidence -notmatch '(?m)^\|[^\r\n]*\|\s*PASS\s*\|') 'no evidence row may be pre-marked PASS'
    Assert-Contract ($source -match 'Setup may have failed before the complete fixture map; no automatic delete was attempted') 'All-mode finally must surface the pre-map recovery scope without guessing deletes'

    Write-Output "DEMO CONTRACT TESTS PASS - assertions=$assertions; no Docker, SQL, HTTP, E2E, or browser action was invoked."
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
