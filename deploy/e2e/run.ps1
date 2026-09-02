#Requires -Version 7.0
<#
.SYNOPSIS
    T13 slice 4: integration / E2E execution plan over EXISTING repo test assets.
.DESCRIPTION
    Default is PLAN MODE. -Execute actually runs the selected mode. ApiIntegration
    and StudentBrowser have recorded local evidence; ApprovalBrowser remains
    owner-blocked and can never self-promote from an offline/output contract.

    Modes:
      ApiIntegration   - narrow, EXPLICITLY LISTED set of existing booking-api
                         test classes covering: auth + password non-disclosure,
                         resources, availability, direct + pending booking,
                         approval/reject, cancel, check-in, no-show/violation,
                         slot release, idempotency, concurrency, boundaries.
                         The list is hardcoded below AND mirrored in
                         deploy/e2e/inventory.md. Every class file is verified
                         to exist BEFORE mvn runs; ANY missing class BLOCKS the
                         run (exit 3) - coverage is never silently claimed.
      StudentBrowser   - reuses the repo's ONLY browser harness
                         scripts/tests/t08/run.ps1 -Action Run (Chrome
                         --headless=new over raw CDP). Requires profile
                         fixtureAttested=true; sets T08_QA_FRONTEND/T08_QA_BACKEND
                         to the profile loopback URLs. The T08 harness is NEVER
                         copied or modified.
      ApprovalBrowser  - deterministic approval browser flow. If profile
                         approvalBrowserFixtureAttested is false => BLOCKED
                         (exit 3). Even when true, execution happens ONLY if
                         a reparse-free, repository-local owner root plus an
                         approved .exe/.ps1 command exist (.ps1 runs in a
                         separate pwsh child process; .bat/.cmd refused).
                         Positional RunId/output-root placeholders, fresh
                         non-reparse output dir, strict-boolean manifest gates,
                         six refresh cases, distinct evidence files, cleanup,
                         safe paths and T13 redaction are fail-closed. This
                         mode NEVER reports pass: executed => exit 2
                         (EXECUTED_UNPROVEN).
      All              - Api -> Student -> Approval, in order; any blocked or
                         failing child makes the overall run non-zero.

    Safety contract:
      * Deep URL validation: scheme http/https, loopback host, empty
        path/query/userinfo/fragment. publicAccessDenied must be true.
      * Credentials: env VARIABLE NAMES only in the profile; values must exist
        in the host environment at Execute time and are never printed.
      * Artifacts per run-id under deploy/artifacts/e2e-<mode>-<runid>/; text
        artifacts are redacted via deploy/e2e/redact-artifacts.mjs; screenshots
        are marked "requires manual visual PII review" and never auto-claimed
        redacted.
    Exit codes: 0 pass | 1 environment failure | 2 refused/unproven |
    3 blocked (missing class/fixture/env).
#>
[CmdletBinding()]
param(
    [ValidateSet('ApiIntegration', 'StudentBrowser', 'ApprovalBrowser', 'All')]
    [string]$Mode = 'All',
    [switch]$Execute,
    [string]$ProfilePath = '',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ProfilePath)  { $ProfilePath  = (Join-Path $PSScriptRoot 'profile.example.json') }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null

# ---- THE explicit ApiIntegration class set (mirrored in inventory.md) ----------
$script:ApiClasses = @(
    'auth/AuthServiceTest',
    'auth/AuthControllerMockMvcTest',
    'auth/RequestValidationTest',
    'auth/security/JwtSecurityTest',
    'resource/ResourceApiTest',
    'availability/AvailabilityApiTest',
    'availability/AvailabilityServiceTest',
    'booking/BookingControllerMockMvcTest',
    'booking/BookingCreatorTest',
    'booking/BookingCreationGuardTest',
    'booking/BookingIntervalValidatorTest',
    'booking/BookingSlotSplitterTest',
    'booking/DefaultBookingActionsTest',
    'approval/ApprovalServiceTest',
    'approval/ApprovalRequestTest',
    'approval/ApprovalControllerMockMvcTest',
    'checkin/CheckInServiceTest',
    'checkin/CheckInControllerMockMvcTest',
    'violation/ViolationServiceTest',
    'violation/DefaultViolationPortTest',
    'violation/ViolationControllerMockMvcTest',
    'task/NoShowScanTaskTest',
    'task/NoShowItemProcessorTest',
    'booking/BookingActionsMysqlIntegrationTest',
    'booking/BookingMysqlIntegrationTest',
    'booking/BookingConcurrencyIntegrationTest',
    'booking/BookingRedisLockIntegrationTest',
    'approval/ApprovalMysqlIntegrationTest',
    'approval/ApprovalApiRealIntegrationTest',
    'violation/NoShowMysqlIntegrationTest',
    'violation/ViolationPortLateCancelMysqlIntegrationTest',
    'user/UserMysqlIntegrationTest',
    'common/config/SecurityContextIntegrationTest',
    'resource/ResourceMysqlIntegrationTest',
    'availability/AvailabilityMysqlIntegrationTest',
    'user/UserCreditMysqlIntegrationTest',
    'common/config/RedisRealIntegrationTest'
)
$script:ApiClasses = @($script:ApiClasses | Select-Object -Unique)
$script:ApprovalRequiredCases = @(
    'admin-login-refresh',
    'pending-list-refresh',
    'approve-refresh',
    'reject-refresh',
    'student-approved-detail-refresh',
    'student-rejected-detail-refresh'
)

# Env contract: DB_URL + REDIS_HOST are hard-required; RESOURCE_MYSQL_URL /
# USER_CREDIT_MYSQL_URL are DERIVED from DB_URL when absent; REDIS_PORT
# defaults to 6379; credential values arrive via the profile-declared ENV
# NAMES (never via this file).
$script:HardRequiredEnv = @('DB_URL', 'REDIS_HOST')
$script:DerivedFromDbUrl = @('RESOURCE_MYSQL_URL', 'USER_CREDIT_MYSQL_URL')

$localHosts = @('127.0.0.1', 'localhost', '::1')

function Assert-LocalUrl {
    param([string]$Url, [string]$Label)
    try { $u = [uri]$Url } catch {
        Write-Warning ("REFUSED: {0} is not a valid absolute URL." -f $Label)
        exit 2
    }
    if (-not $u.IsAbsoluteUri -or $u.Scheme -notin @('http', 'https')) {
        Write-Warning ("REFUSED: {0} scheme '{1}' must be http/https" -f $Label, $u.Scheme); exit 2
    }
    if ($localHosts -notcontains $u.Host) {
        Write-Warning ("REFUSED: {0} host '{1}' is not loopback - public/prod denied." -f $Label, $u.Host); exit 2
    }
    if ($u.AbsolutePath -ne '/') { Write-Warning ("REFUSED: {0} must not carry a path." -f $Label); exit 2 }
    if ($u.Query)                { Write-Warning ("REFUSED: {0} must not carry a query." -f $Label); exit 2 }
    if ($u.UserInfo)             { Write-Warning ("REFUSED: {0} must not carry userinfo." -f $Label); exit 2 }
    if ($u.Fragment)             { Write-Warning ("REFUSED: {0} must not carry a fragment." -f $Label); exit 2 }
    return $u
}

function Test-ReparseFreeAncestry {
    # True only when every component from StartPath up to (and including)
    # StopPrefix exists and carries no reparse-point attribute. Blocks
    # junction/symlink redirection that a string-prefix containment check
    # cannot see.
    param([Parameter(Mandatory)][string]$StartPath, [Parameter(Mandatory)][string]$StopPrefix)
    $current = [System.IO.Path]::GetFullPath($StartPath).TrimEnd('\', '/')
    $stopFull = [System.IO.Path]::GetFullPath($StopPrefix).TrimEnd('\', '/')
    while ($true) {
        if (-not (Test-Path -LiteralPath $current)) { return $false }
        $item = Get-Item -LiteralPath $current -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
        if ($current -ieq $stopFull) { return $true }
        $parent = [System.IO.Path]::GetDirectoryName($current)
        if (-not $parent -or $parent -eq $current) { return $false }
        $current = $parent
    }
}

function Get-JsonValue {
    # Missing JSON properties must yield $null (contract error), never a
    # StrictMode PropertyNotFoundException that aborts without a status file.
    param($Object, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Test-StrictTrue {
    # Only a real JSON boolean true passes. PowerShell casts the string "false"
    # to [bool]$true, so attestation/cleanup/refresh gates must not use [bool].
    param($Value)
    return ($null -ne $Value -and $Value -is [bool] -and $Value)
}

function Resolve-ApprovalEvidenceFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string[]]$AllowedExtensions
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [System.IO.Path]::IsPathRooted($RelativePath)) {
        return $null
    }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $Root $RelativePath))
    if (-not $candidate.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $candidate -PathType Leaf) -or
        $AllowedExtensions -notcontains [System.IO.Path]::GetExtension($candidate).ToLowerInvariant()) {
        return $null
    }
    $rootItem = Get-Item -LiteralPath $Root -Force
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $null }
    $currentPath = $candidate
    while ($currentPath.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        $currentItem = Get-Item -LiteralPath $currentPath -Force
        if (($currentItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $null }
        $currentPath = [System.IO.Path]::GetDirectoryName($currentPath)
    }
    return $candidate
}

# ---- Load profile ---------------------------------------------------------------
if (-not (Test-Path -LiteralPath $ProfilePath)) { Write-Warning "BLOCKED: profile not found: $ProfilePath"; exit 3 }
$profile0 = Get-Content -LiteralPath $ProfilePath -Raw | ConvertFrom-Json
if (-not (Test-StrictTrue (Get-JsonValue $profile0 'publicAccessDenied'))) {
    Write-Warning 'REFUSED: profile.publicAccessDenied must be boolean true (public/prod denied by default).'
    exit 2
}
$null = Assert-LocalUrl -Url ([string](Get-JsonValue $profile0 'frontendUrl')) -Label 'frontendUrl'
$null = Assert-LocalUrl -Url ([string](Get-JsonValue $profile0 'backendUrl'))  -Label 'backendUrl'

$plan = [ordered]@{
    runId = $RunId
    mode = $Mode
    executed = [bool]$Execute
    frontendUrl = [string](Get-JsonValue $profile0 'frontendUrl')
    backendUrl = [string](Get-JsonValue $profile0 'backendUrl')
    fixtureAttested = (Test-StrictTrue (Get-JsonValue $profile0 'fixtureAttested'))
    approvalBrowserFixtureAttested = (Test-StrictTrue (Get-JsonValue $profile0 'approvalBrowserFixtureAttested'))
    approvalBrowserCommandPresent = ($null -ne (Get-JsonValue $profile0 'approvalBrowserCommand'))
    apiClassCount = @($script:ApiClasses).Count
}

if (-not $Execute) {
    Write-Output 'PLAN MODE - nothing invoked.'
    Write-Output ("mode={0} frontend={1} backend={2} fixtureAttested={3} approvalFixtureAttested={4}" -f `
        $Mode, (Get-JsonValue $profile0 'frontendUrl'), (Get-JsonValue $profile0 'backendUrl'), `
        (Test-StrictTrue (Get-JsonValue $profile0 'fixtureAttested')), (Test-StrictTrue (Get-JsonValue $profile0 'approvalBrowserFixtureAttested')))
    Write-Output ("ApiIntegration narrow set: {0} classes (see deploy/e2e/inventory.md)." -f @($script:ApiClasses).Count)
    Write-Output 'Run with -Execute -Mode <ApiIntegration|StudentBrowser|ApprovalBrowser|All>.'
    exit 0
}

# ---- Execute-mode preconditions -------------------------------------------------
$bookingApiDir = [string](Get-JsonValue $profile0 'bookingApiDir')
$t08Dir = [string](Get-JsonValue $profile0 't08HarnessDir')
foreach ($p in @($bookingApiDir, $t08Dir)) {
    if (-not $p -or -not (Test-Path -LiteralPath $p -PathType Container)) {
        Write-Warning "BLOCKED: profile path placeholder not expanded to a directory: '$p'"
        exit 3
    }
}
# The profile may select a fixture, but it may not redirect this lane to an
# arbitrary browser harness or a different source tree.
$expectedApiDir = (Resolve-Path (Join-Path $repoRoot 'booking-api')).Path
$expectedT08Dir = (Resolve-Path (Join-Path $repoRoot 'scripts\tests\t08')).Path
if ((Resolve-Path -LiteralPath $bookingApiDir).Path -ne $expectedApiDir) {
    Write-Warning 'REFUSED: bookingApiDir must be the repository booking-api directory.'
    exit 2
}
if ((Resolve-Path -LiteralPath $t08Dir).Path -ne $expectedT08Dir) {
    Write-Warning 'REFUSED: t08HarnessDir must be the repository scripts/tests/t08 directory.'
    exit 2
}
$Artifacts = Join-Path $ArtifactRoot "e2e-$Mode-$RunId"
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null
$artifactsItem = Get-Item -LiteralPath $Artifacts -Force
if (($artifactsItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    Write-Warning 'REFUSED: artifacts run directory is a reparse point; evidence containment cannot be guaranteed.'
    exit 2
}

function Invoke-ApiIntegration {
    # Verify every listed class file exists - a missing class BLOCKS; we never
    # claim coverage that the class set does not provide.
    $testRoot = Join-Path $bookingApiDir 'src\test\java\com\yu030x\booking'
    $missing = @()
    foreach ($rel in $script:ApiClasses) {
        if (-not (Test-Path -LiteralPath (Join-Path $testRoot ($rel + '.java')))) {
            $missing += $rel
        }
    }
    if ($missing.Count -gt 0) {
        Write-Warning ("BLOCKED: missing test classes: {0}" -f ($missing -join ', '))
        return 3
    }

    # Env contract: hard-required host vars; derived URLs; credential values
    # read from the PROFILE-DECLARED env NAMES. Values are never printed.
    $envMissing = @($script:HardRequiredEnv | Where-Object { -not [Environment]::GetEnvironmentVariable($_) })
    if ($envMissing.Count -gt 0) {
        Write-Warning ("BLOCKED: missing required environment variables: {0}" -f ($envMissing -join ', '))
        return 3
    }
    $cred = Get-JsonValue $profile0 'credentials'
    $dbUser   = [Environment]::GetEnvironmentVariable([string](Get-JsonValue $cred 'dbUsernameEnv'))
    $dbPass   = [Environment]::GetEnvironmentVariable([string](Get-JsonValue $cred 'dbPasswordEnv'))
    $jwtValue = [Environment]::GetEnvironmentVariable([string](Get-JsonValue $cred 'jwtSecretEnv'))
    $credMissing = @()
    if (-not $dbUser)   { $credMissing += [string](Get-JsonValue $cred 'dbUsernameEnv') }
    if (-not $dbPass)   { $credMissing += [string](Get-JsonValue $cred 'dbPasswordEnv') }
    if (-not $jwtValue) { $credMissing += [string](Get-JsonValue $cred 'jwtSecretEnv') }
    if ($credMissing.Count -gt 0) {
        Write-Warning ("BLOCKED: missing credential environment variables: {0}" -f ($credMissing -join ', '))
        return 3
    }

    # Temporary child-process env mapping; EVERY touched var is precisely
    # restored in finally (removed again if it was absent before).
    $dbUrl = [Environment]::GetEnvironmentVariable('DB_URL')
    $mappings = [ordered]@{
        'DB_USERNAME'               = $dbUser
        'DB_PASSWORD'               = $dbPass
        'JWT_SECRET'                = $jwtValue
        'RESOURCE_MYSQL_USERNAME'   = $dbUser
        'RESOURCE_MYSQL_PASSWORD'   = $dbPass
    }
    $redisPwd = [Environment]::GetEnvironmentVariable([string](Get-JsonValue $cred 'redisPasswordEnv'))
    if ($redisPwd) { $mappings['REDIS_PASSWORD'] = $redisPwd }
    foreach ($name in $script:DerivedFromDbUrl) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { $mappings[$name] = $dbUrl }
    }
    if (-not [Environment]::GetEnvironmentVariable('REDIS_PORT')) { $mappings['REDIS_PORT'] = '6379' }
    $mappings['BOOKING_MYSQL8_TEST'] = 'true'

    $saved = @{}
    foreach ($name in $mappings.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name) # $null == absent
        [Environment]::SetEnvironmentVariable($name, $mappings[$name])
    }
    $classList = ($script:ApiClasses | ForEach-Object { ($_ -split '/')[-1] }) -join ','
    $logPath = Join-Path $Artifacts 'mvn-integration.log'
    try {
        Push-Location $bookingApiDir
        try {
            & mvn -B test "-Dtest=$classList" '-DfailIfNoTests=false' 2>&1 |
                Tee-Object -FilePath $logPath | Out-Null
            $mvnExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    } finally {
        foreach ($name in $saved.Keys) {
            if ($null -eq $saved[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
        }
    }
    # Redact the captured log in place (Authorization/passwords/etc.).
    & node (Join-Path $PSScriptRoot 'redact-artifacts.mjs') $Artifacts | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ("redaction pass failed with exit {0}" -f $LASTEXITCODE)
        return 1
    }
    if ($mvnExit -ne 0) {
        Write-Warning ("mvn test exited {0}; log kept (redacted)." -f $mvnExit)
        return 1
    }
    return 0
}

function Invoke-StudentBrowser {
    if (-not (Test-StrictTrue (Get-JsonValue $profile0 'fixtureAttested'))) {
        Write-Warning 'BLOCKED: StudentBrowser requires profile.fixtureAttested=true (deterministic fixture owner-attested).'
        return 3
    }
    $t08run = Join-Path $t08Dir 'run.ps1'
    if (-not (Test-Path -LiteralPath $t08run)) {
        Write-Warning "BLOCKED: T08 harness entry not found: $t08run"
        return 3
    }
    $copyDir = Join-Path $Artifacts 't08-copy'
    New-Item -ItemType Directory -Path $copyDir -Force | Out-Null
    $savedFe = $env:T08_QA_FRONTEND
    $savedBe = $env:T08_QA_BACKEND
    $env:T08_QA_FRONTEND = [string]$profile0.frontendUrl
    $env:T08_QA_BACKEND  = [string]$profile0.backendUrl

    # Snapshot pre-existing T08 run dirs: ONLY newly created directories count
    # as evidence for THIS execution - stale runs must never be presented.
    $existingRunDirs = @(Get-ChildItem -LiteralPath $t08Dir -Directory -Filter 'run-*' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName)

    try {
        & $t08run -Action Run 2>&1 |
            Tee-Object -FilePath (Join-Path $copyDir 't08-command.log') | Out-Null
        $t08Exit = $LASTEXITCODE
    } finally {
        $env:T08_QA_FRONTEND = $savedFe
        $env:T08_QA_BACKEND  = $savedBe
    }

    $newRunDirs = @(Get-ChildItem -LiteralPath $t08Dir -Directory -Filter 'run-*' -ErrorAction SilentlyContinue |
        Where-Object { $existingRunDirs -notcontains $_.FullName } |
        Sort-Object Name -Descending)
    if ($newRunDirs.Count -eq 0) {
        Write-Warning 'FAIL: T08 produced NO new run directory - refusing to present stale evidence.'
        return 1
    }
    $src = $newRunDirs[0].FullName

    foreach ($name in @('summary.json', 'summary.meta.json', 'REPORT.md', 'network.jsonl', 'api-driver-calls.jsonl', 'console.jsonl')) {
        $f = Join-Path $src $name
        if (Test-Path -LiteralPath $f) { Copy-Item -LiteralPath $f -Destination (Join-Path $copyDir $name) -Force }
    }
    & node (Join-Path $PSScriptRoot 'redact-artifacts.mjs') $copyDir | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning 'redaction pass failed on t08 copy.'; return 1 }

    # Screenshots CANNOT be auto-redacted: copy them into an explicit
    # "unreviewed" area with a marker + index. Never claimed redacted/pass.
    $shots = Join-Path $src 'screenshots'
    if (Test-Path -LiteralPath $shots) {
        $unreviewed = Join-Path $Artifacts 'screenshots-unreviewed'
        New-Item -ItemType Directory -Path $unreviewed -Force | Out-Null
        $index = @()
        foreach ($png in (Get-ChildItem -LiteralPath $shots -File -Filter '*.png')) {
            Copy-Item -LiteralPath $png.FullName -Destination (Join-Path $unreviewed $png.Name) -Force
            $index += $png.Name
        }
        Set-Content -LiteralPath (Join-Path $unreviewed 'REQUIRES-MANUAL-VISUAL-PII-REVIEW.txt') `
            -Value "Every PNG in this directory requires manual visual PII review before any publication. T13 does not claim these screenshots are redacted." -Encoding utf8NoBOM
        Set-Content -LiteralPath (Join-Path $copyDir 'screenshots.index.txt') `
            -Value (("requires manual visual PII review`n") + ($index -join "`n")) -Encoding utf8NoBOM
    }

    if ($t08Exit -ne 0) { Write-Warning ("T08 harness exited {0}." -f $t08Exit); return 1 }
    return 0
}

function Invoke-ApprovalBrowser {
    if (-not (Test-StrictTrue (Get-JsonValue $profile0 'approvalBrowserFixtureAttested'))) {
        Write-Warning 'BLOCKED: ApprovalBrowser requires approvalBrowserFixtureAttested=true (deterministic fixture).'
        return 3
    }
    $rawCmd = Get-JsonValue $profile0 'approvalBrowserCommand'
    if (-not $rawCmd) {
        Write-Warning 'BLOCKED: ApprovalBrowser requires profile.approvalBrowserCommand (owner-approved command/path). Mocks are forbidden.'
        return 3
    }
    # Command contract: a JSON ARRAY whose FIRST element is an EXISTING LOCAL
    # FILE PATH below an explicit owner root inside this repository. Remaining
    # elements are argv values. Shell strings/PATH guessing are forbidden.
    $rawCmd = $profile0.approvalBrowserCommand
    if ($rawCmd -is [string] -or $rawCmd -isnot [array]) {
        Write-Warning 'REFUSED: approvalBrowserCommand must be a JSON array like ["<existing local path>", "<arg>", ...].'
        return 2
    }
    $cmd = @($rawCmd)
    if ($cmd.Count -lt 1 -or [string]::IsNullOrWhiteSpace([string]$cmd[0])) {
        Write-Warning 'REFUSED: approvalBrowserCommand[0] (executable path) is empty.'
        return 2
    }
    $exe = [string]$cmd[0]
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        Write-Warning ("REFUSED: approvalBrowserCommand[0] is not an existing local file: '{0}' (PATH guessing forbidden)." -f $exe)
        return 2
    }
    $ownerRootRaw = [string](Get-JsonValue $profile0 'approvalBrowserOwnerRoot')
    if ([string]::IsNullOrWhiteSpace($ownerRootRaw) -or -not (Test-Path -LiteralPath $ownerRootRaw -PathType Container)) {
        Write-Warning 'BLOCKED: ApprovalBrowser requires an existing owner-approved harness root.'
        return 3
    }
    $repoPrefix = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $ownerRoot = (Resolve-Path -LiteralPath $ownerRootRaw).Path
    $ownerPrefix = [System.IO.Path]::GetFullPath($ownerRoot).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $exeResolved = (Resolve-Path -LiteralPath $exe).Path
    if (-not $ownerPrefix.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $exeResolved.StartsWith($ownerPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Write-Warning 'REFUSED: ApprovalBrowser executable must be inside the repository-local owner-approved harness root.'
        return 2
    }
    # Prefix checks cannot see junction/symlink redirection: every component
    # between the executable and the repository root must be a real directory.
    if (-not (Test-ReparseFreeAncestry -StartPath $exeResolved -StopPrefix $repoRoot)) {
        Write-Warning 'REFUSED: ApprovalBrowser owner root/executable path contains a reparse point (junction/symlink); repository-locality cannot be proven.'
        return 2
    }
    # .ps1 runs in a SEPARATE pwsh child process (owner code must never share
    # this session state); .exe runs natively. Batch/script types that PowerShell
    # or cmd.exe would interpret specially are refused.
    $exeExt = [System.IO.Path]::GetExtension($exeResolved).ToLowerInvariant()
    if ($exeExt -notin @('.exe', '.ps1')) {
        Write-Warning ("REFUSED: ApprovalBrowser executable type '{0}' is not allowed (only .exe and .ps1; .bat/.cmd/interpreter scripts are refused)." -f $exeExt)
        return 2
    }

    $rest = @($cmd | Select-Object -Skip 1 | ForEach-Object { [string]$_ })
    if ($rest.Count -lt 2 -or $rest[0] -cne '{T13_RUN_ID}' -or $rest[1] -cne '{T13_ARTIFACT_ROOT}' -or
        @($rest | Where-Object { $_ -ceq '{T13_RUN_ID}' }).Count -ne 1 -or
        @($rest | Where-Object { $_ -ceq '{T13_ARTIFACT_ROOT}' }).Count -ne 1) {
        Write-Warning 'REFUSED: approvalBrowserCommand argv[1:2] must be exactly {T13_RUN_ID}, {T13_ARTIFACT_ROOT}, each used once.'
        return 2
    }
    $ownerOutput = Join-Path $Artifacts 'approval-owner-output'
    if (Test-Path -LiteralPath $ownerOutput) {
        $ownerOutputItem = Get-Item -LiteralPath $ownerOutput -Force
        if (-not $ownerOutputItem.PSIsContainer -or
            (($ownerOutputItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)) {
            Write-Warning 'REFUSED: ApprovalBrowser owner output path is not a real local directory.'
            return 2
        }
        # No SilentlyContinue: a failed listing must refuse, never read as empty.
        if (@(Get-ChildItem -LiteralPath $ownerOutput -Force).Count -gt 0) {
            Write-Warning 'REFUSED: ApprovalBrowser owner output directory is not empty; stale evidence cannot be reused.'
            return 2
        }
    } else {
        New-Item -ItemType Directory -Path $ownerOutput -Force | Out-Null
    }
    $resolvedArgs = @($rest | ForEach-Object {
        if ($_ -ceq '{T13_RUN_ID}') { $RunId }
        elseif ($_ -ceq '{T13_ARTIFACT_ROOT}') { $ownerOutput }
        else { $_ }
    })

    $startedAtUtc = [DateTime]::UtcNow
    $commandLog = Join-Path $Artifacts 'approval-browser-command.log'
    $cmdExit = 1
    $redactionExit = 1
    $commandExceptionType = ''
    try {
        Write-Host ("Executing owner-approved executable: {0}" -f $exeResolved)
        if ($exeExt -eq '.ps1') {
            & pwsh -NoProfile -File $exeResolved @resolvedArgs 2>&1 |
                Tee-Object -FilePath $commandLog | Out-Null
        } else {
            & $exeResolved @resolvedArgs 2>&1 |
                Tee-Object -FilePath $commandLog | Out-Null
        }
        $cmdExit = $LASTEXITCODE
    } catch {
        $commandExceptionType = $_.Exception.GetType().FullName
        Set-Content -LiteralPath $commandLog -Value 'owner command raised an exception; exception text omitted pending redaction policy' -Encoding utf8NoBOM
        $cmdExit = 1
    } finally {
        & node (Join-Path $PSScriptRoot 'redact-artifacts.mjs') $Artifacts | Out-Null
        $redactionExit = $LASTEXITCODE
    }

    $contractErrors = [System.Collections.Generic.List[string]]::new()
    if ($cmdExit -ne 0) { [void]$contractErrors.Add("owner command exit=$cmdExit") }
    if ($redactionExit -ne 0) { [void]$contractErrors.Add("redaction exit=$redactionExit") }
    $manifestPath = Join-Path $ownerOutput 'approval-evidence.json'
    $manifest = $null
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        [void]$contractErrors.Add('approval-evidence.json missing')
    } elseif ((Get-Item -LiteralPath $manifestPath).LastWriteTimeUtc -lt $startedAtUtc.AddSeconds(-1)) {
        [void]$contractErrors.Add('approval-evidence.json is stale')
    } else {
        try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json } catch {
            [void]$contractErrors.Add('approval-evidence.json is invalid JSON')
        }
    }

    $reviewedScreenshots = [System.Collections.Generic.List[string]]::new()
    $usedEvidencePaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($null -ne $manifest) {
        try {
            if ([string](Get-JsonValue $manifest 'schemaVersion') -cne '1') {
                [void]$contractErrors.Add('manifest schemaVersion must be 1')
            }
            if ([string](Get-JsonValue $manifest 'runId') -cne $RunId) {
                [void]$contractErrors.Add('manifest runId mismatch')
            }
            $cleanup = Get-JsonValue $manifest 'cleanup'
            if ($null -eq $cleanup -or -not (Test-StrictTrue (Get-JsonValue $cleanup 'performed')) -or
                [string](Get-JsonValue $cleanup 'status') -cne 'PASS') {
                [void]$contractErrors.Add('manifest cleanup must be performed/PASS')
            }
            $casesRaw = Get-JsonValue $manifest 'cases'
            $cases = if ($null -eq $casesRaw) { @() } else { @($casesRaw) }
            $caseIds = @($cases | ForEach-Object { [string](Get-JsonValue $_ 'id') })
            if (@($caseIds | Where-Object { -not $_ }).Count -gt 0) {
                [void]$contractErrors.Add('manifest case id missing')
            }
            if (@($caseIds | Select-Object -Unique).Count -ne $caseIds.Count) {
                [void]$contractErrors.Add('manifest case ids must be unique')
            }
            foreach ($requiredCase in $script:ApprovalRequiredCases) {
                $matched = @($cases | Where-Object { [string](Get-JsonValue $_ 'id') -ceq $requiredCase })
                if ($matched.Count -ne 1) {
                    [void]$contractErrors.Add("required case missing/duplicated: $requiredCase")
                    continue
                }
                $case = $matched[0]
                if ([string](Get-JsonValue $case 'status') -cne 'PASS' -or
                    -not (Test-StrictTrue (Get-JsonValue $case 'refreshObserved')) -or
                    -not (Test-StrictTrue (Get-JsonValue $case 'apiReloadObserved')) -or
                    [string](Get-JsonValue $case 'routeAfterRefresh') -notmatch '^/') {
                    [void]$contractErrors.Add("case contract incomplete: $requiredCase")
                }
                $shot = $null
                $network = $null
                try {
                    $shot = Resolve-ApprovalEvidenceFile -Root $ownerOutput -RelativePath ([string](Get-JsonValue $case 'screenshot')) -AllowedExtensions @('.png')
                    $network = Resolve-ApprovalEvidenceFile -Root $ownerOutput -RelativePath ([string](Get-JsonValue $case 'networkEvidence')) -AllowedExtensions @('.json', '.jsonl')
                } catch {
                    [void]$contractErrors.Add("case evidence unresolvable: $requiredCase")
                    continue
                }
                if (-not $shot) { [void]$contractErrors.Add("case screenshot missing/unsafe: $requiredCase") }
                elseif (-not $usedEvidencePaths.Add($shot)) { [void]$contractErrors.Add("case evidence must be distinct: $requiredCase") }
                else { [void]$reviewedScreenshots.Add($shot) }
                if (-not $network) { [void]$contractErrors.Add("case network evidence missing/unsafe: $requiredCase") }
                elseif ([System.IO.Path]::GetFullPath($network) -eq [System.IO.Path]::GetFullPath($manifestPath)) {
                    [void]$contractErrors.Add("case network evidence must not be the manifest itself: $requiredCase")
                }
                elseif (-not $usedEvidencePaths.Add($network)) { [void]$contractErrors.Add("case evidence must be distinct: $requiredCase") }
            }
            # Screenshots of EXTRA manifest cases are never auto-passed either:
            # every referenced PNG lands in the manual-review marker file.
            $requiredSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$script:ApprovalRequiredCases, [StringComparer]::Ordinal)
            foreach ($case in $cases) {
                $caseId = [string](Get-JsonValue $case 'id')
                if ($requiredSet.Contains($caseId)) { continue }
                try {
                    $extraShot = Resolve-ApprovalEvidenceFile -Root $ownerOutput -RelativePath ([string](Get-JsonValue $case 'screenshot')) -AllowedExtensions @('.png')
                } catch { $extraShot = $null }
                if ($extraShot -and $usedEvidencePaths.Add($extraShot)) { [void]$reviewedScreenshots.Add($extraShot) }
            }
        } catch {
            # A structurally hostile manifest must degrade to a contract error
            # (exit 2 + status file), never an unhandled abort without evidence.
            [void]$contractErrors.Add("manifest structure unreadable: $($_.Exception.GetType().Name)")
        }
    }

    if ($reviewedScreenshots.Count -gt 0) {
        Set-Content -LiteralPath (Join-Path $ownerOutput 'REQUIRES-MANUAL-VISUAL-PII-REVIEW.txt') `
            -Value ("Every referenced PNG requires manual visual PII review before publication.`n" + (($reviewedScreenshots | ForEach-Object { [System.IO.Path]::GetRelativePath($ownerOutput, $_) }) -join "`n")) -Encoding utf8NoBOM
    }
    $contractComplete = ($contractErrors.Count -eq 0)
    $status = [ordered]@{
        status = 'EXECUTED_UNPROVEN'
        commandExit = $cmdExit
        commandExceptionType = $commandExceptionType
        redactionExit = $redactionExit
        contractComplete = $contractComplete
        requiredCaseIds = $script:ApprovalRequiredCases
        errors = @($contractErrors)
        manualScreenshotReviewRequired = $true
        note = 'This mode never reports pass until owner/runtime/manual-review acceptance closes OCR-8.'
    }
    Set-Content -LiteralPath (Join-Path $Artifacts 'approval-browser-status.json') `
        -Value ($status | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM
    Write-Warning ("ApprovalBrowser executed but remains UNPROVEN (contractComplete={0}) - not a pass." -f $contractComplete)
    return 2
}

$modeResults = [ordered]@{}
$overall = 0
$modesToRun = @()
switch ($Mode) {
    'All'             { $modesToRun = @('ApiIntegration', 'StudentBrowser', 'ApprovalBrowser') }
    default           { $modesToRun = @($Mode) }
}

foreach ($m in $modesToRun) {
    Write-Output ("==== mode: {0} ====" -f $m)
    $rc = switch ($m) {
        'ApiIntegration'  { Invoke-ApiIntegration }
        'StudentBrowser'  { Invoke-StudentBrowser }
        'ApprovalBrowser' { Invoke-ApprovalBrowser }
    }
    $modeResults[$m] = $rc
    if ($rc -ne 0) { $overall = $rc }
}

$plan.modeResults = $modeResults
$plan.overallExit = $overall
Set-Content -LiteralPath (Join-Path $Artifacts 'mode-summary.json') `
    -Value ($plan | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM

foreach ($m in $modeResults.Keys) {
    Write-Output ("{0}: exit {1}" -f $m, $modeResults[$m])
}
if ($overall -ne 0) { Write-Warning ("E2E RUN FAILED/BLOCKED (overall exit {0})." -f $overall); exit $overall }
Write-Output 'E2E RUN COMPLETE.'
exit 0
