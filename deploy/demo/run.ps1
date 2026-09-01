#Requires -Version 7.0
<#
.SYNOPSIS
    T13 slice 5: ephemeral demo fixture lifecycle (Setup / StudentFlow / Teardown).
.DESCRIPTION
    Default is PLAN MODE. -Execute actually runs the selected mode. Offline
    refusal/safety contracts are tested, but no real Demo lifecycle has run; all
    demo evidence remains DRAFT (tasks 6.1-6.4 unchecked).

    Contract (static review only):
      * This is an EPHEMERAL RUNTIME FIXTURE, not a migration seed: rows are
        created at run time under a run-id-scoped namespace and removed by
        Teardown. No database, volume, or foreign row is ever dropped.
      * Loopback deep URL validation; publicAccessDenied must be true; RunId
        strictly gated; usernames derived as t13demo_<RunId-with-underscores>*
        (matches the API username pattern, dashes mapped to underscores).
      * Passwords: per-user cryptographically random (32 bytes), generated at
        Execute time, NEVER printed, never written to artifacts. They live in
        ONE system-random temp JSON for the same run only; finally deletes it
        and re-verifies - a failed deletion fails the run with a rotation
        instruction.
      * Setup: verifies the EXISTING T08 seed.sql as an owner reference only
        (never executes its destructive SQL; T08 file untouched); registers
        minimal admin/student/intruder users via the local API (realName
        T13Fixture*, phone/email/studentNo null, no PII); promotes the admin
        role via container-side authenticated SQL; creates a T13-owned approval
        resource + time rule through the ADMIN API; creates a deterministic
        PENDING booking via the student API; seeds a PAST CONFIRMED booking via
        direct SQL strictly labeled EPHEMERAL-SETUP-NOT-ACCEPTANCE-EVIDENCE so
        the OWNER no-show scan task can produce the violation/deduction itself
        (wait window optional). A non-secret incremental compensation journal
        records each created id+owner tuple; fixture-map.json records the final
        ids, usernames, purpose strings and timestamps - never passwords.
      * StudentFlow: generates a temporary e2e profile (fixtureAttested=true)
        and calls deploy/e2e/run.ps1 -Mode StudentBrowser. T08 registers its own
        browser users, so no fixture password is needed. ApprovalBrowser is
        NEVER invoked here. Evidence mapping is appended to the demo
        evidence-index.md.
      * Teardown: deletes ONLY fixture-owned rows (username prefix / demo
        resource id / purpose prefix), children before parents, verifies zero
        leftovers, and never touches other rows, databases, or volumes.
        Missing fixture-map.json => BLOCKED.
      * All = Setup -> StudentFlow -> Teardown; any failure is non-zero and
        finally still attempts full Teardown when a map exists, or transactionally
        compensates only journaled/revalidated partial tuples. Standalone
        StudentFlow needs no fixture password; standalone Teardown requires an
        explicit fixture-map.json path.
    Exit codes: 0 pass | 1 environment/secret-cleanup | 2 refused | 3 blocked.
#>
[CmdletBinding()]
param(
    [ValidateSet('Setup', 'StudentFlow', 'Teardown', 'All')]
    [string]$Mode = 'All',
    [switch]$Execute,
    [string]$ProfilePath = '',
    [string]$MapPath = '',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateRange(0, 3600)]
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RunId) -or $RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$ (value omitted)" )
    exit 2
}
# API usernames allow [A-Za-z0-9_] only: derive the SQL-safe user prefix.
$userPrefix = 't13demo_' + ($RunId -replace '-', '_')
if (($userPrefix.Length + 7) -gt 50) {
    Write-Warning 'REFUSED: RunId leaves no room for the demo username suffix.'
    exit 2
}
$purposePrefix = "T13DEMO:$RunId`:"
$resourceName = "T13 DEMO $RunId approval room"

function Get-DemoCategoryName {
    param([string]$CategoryRunId = $RunId)
    $name = 'T13D-' + $CategoryRunId
    if ($name.Length -gt 50) { return $name.Substring(0, 50) }
    return $name
}

if (-not $ProfilePath)  { $ProfilePath  = (Join-Path $PSScriptRoot 'profile.example.json') }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null

$localHosts = @('127.0.0.1', 'localhost', '::1')

function Assert-LocalUrl {
    param([string]$Url, [string]$Label)
    try { $u = [uri]$Url } catch {
        Write-Warning ("REFUSED: {0} is not a valid absolute URL." -f $Label); exit 2
    }
    if (-not $u.IsAbsoluteUri -or $u.Scheme -notin @('http', 'https') -or
        [string]::IsNullOrEmpty($u.Host) -or $u.Port -lt 1 -or $u.Port -gt 65535) {
        Write-Warning ("REFUSED: {0} must be an absolute http/https URL with a valid port." -f $Label); exit 2
    }
    $hostName = $u.DnsSafeHost.TrimEnd('.').ToLowerInvariant()
    $isLoopback = $hostName -eq 'localhost'
    $ip = $null
    if ([System.Net.IPAddress]::TryParse($hostName, [ref]$ip)) {
        $isLoopback = [System.Net.IPAddress]::IsLoopback($ip)
    }
    if (-not $isLoopback -or $hostName.Contains('%')) {
        Write-Warning ("REFUSED: {0} host is not loopback; public/prod denied." -f $Label); exit 2
    }
    if ($u.AbsolutePath -ne '/' -or $u.Query -or $u.UserInfo -or $u.Fragment) {
        Write-Warning ("REFUSED: {0} must not carry a path, query, userinfo, or fragment." -f $Label); exit 2
    }
    return $u
}

function Get-ComposeContainerId {
    param([string]$File, [string]$Svc)
    & docker @('compose', '-f', $File, 'ps', '-q', $Svc) | Tee-Object -Variable lines | Out-Null
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($lines | Where-Object { $_ }) | Select-Object -First 1)
}

function Invoke-RootSql {
    # Authenticates with the container's OWN MYSQL_ROOT_PASSWORD; the query
    # rides an exec-local env var. No credential on argv.
    param([string]$File, [string]$Query)
    $cid = Get-ComposeContainerId -File $File -Svc 'mysql'
    if (-not $cid) { throw 'mysql container not running for the demo compose file' }
    $out = & docker @('exec', '-e', "T13Q=$Query", $cid,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -uroot --batch --skip-column-names booking_db -e "$T13Q"')
    if ($LASTEXITCODE -ne 0) { throw 'root SQL failed (query redacted)' }
    return (($out -join "`n").Trim())
}

function Invoke-Api {
    param([string]$Method, [string]$Url, [hashtable]$Headers = @{}, [string]$Body = $null)
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $Headers `
            -ContentType 'application/json' -Body $Body -SkipHttpErrorCheck -TimeoutSec 30
    } catch {
        throw ("API call failed: {0} {1}" -f $Method, $Url)
    }
    $json = $null
    try { $json = $resp.Content | ConvertFrom-Json } catch { }
    return [pscustomobject]@{
        Status = [int]$resp.StatusCode
        Code   = if ($null -ne $json -and $json.PSObject.Properties['code']) { [string]$json.code } else { '' }
        Data   = if ($null -ne $json) { $json.data } else { $null }
        Message= if ($null -ne $json -and $json.PSObject.Properties['message']) { [string]$json.message } else { '' }
    }
}

function ConvertTo-SqlUtf8Literal {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    if ($bytes.Length -eq 0) { return "CONVERT(X'' USING utf8mb4)" }
    return ('CONVERT(0x{0} USING utf8mb4)' -f [Convert]::ToHexString($bytes))
}

# ---- Load profile ---------------------------------------------------------------
if (-not (Test-Path -LiteralPath $ProfilePath)) { Write-Warning "BLOCKED: demo profile not found: $ProfilePath"; exit 3 }
$profile0 = Get-Content -LiteralPath $ProfilePath -Raw | ConvertFrom-Json
if (-not $profile0.publicAccessDenied) { Write-Warning 'REFUSED: publicAccessDenied must be true.'; exit 2 }
$fixtureOwner = if ($profile0.PSObject.Properties['fixtureOwner']) { [string]$profile0.fixtureOwner } else { '' }
if ([string]::IsNullOrWhiteSpace($fixtureOwner) -or $fixtureOwner -match '^<.*>$') {
    Write-Warning 'REFUSED: fixtureOwner must identify the owner-reviewed ephemeral fixture contract.'
    exit 2
}
if (-not $profile0.PSObject.Properties['namespacePrefix'] -or [string]$profile0.namespacePrefix -ne 't13demo') {
    Write-Warning 'REFUSED: namespacePrefix must remain exactly t13demo.'
    exit 2
}
$waitProperty = $profile0.PSObject.Properties['noShowScanWaitSeconds']
[long]$noShowWaitValue = 0
if (-not $waitProperty -or $null -eq $waitProperty.Value -or
    -not [long]::TryParse([string]$waitProperty.Value, [ref]$noShowWaitValue) -or
    $noShowWaitValue -lt 0 -or $noShowWaitValue -gt 3600) {
    Write-Warning 'REFUSED: noShowScanWaitSeconds must be an integer from 0 through 3600.'
    exit 2
}
$script:noShowWait = [int]$noShowWaitValue
$beUrl = Assert-LocalUrl -Url ([string]$profile0.backendUrl)  -Label 'backendUrl'
$feUrl = Assert-LocalUrl -Url ([string]$profile0.frontendUrl) -Label 'frontendUrl'
$composeFile = [string]$profile0.composeFile
$seedPath    = [string]$profile0.t08SeedPath
$t08Dir      = [string]$profile0.t08HarnessDir
$e2eRun      = [string]$profile0.e2eRunPath

if (-not $Execute) {
    Write-Output 'PLAN MODE - nothing invoked. Demo lifecycle:'
    Write-Output "  Setup       : inspect T08 seed reference (never execute) -> register admin/student/intruder ($userPrefix*) -> admin role via container SQL -> T13 category/resource+time rule via API -> deterministic PENDING booking via API -> PAST CONFIRMED booking via SQL [EPHEMERAL-SETUP-NOT-ACCEPTANCE-EVIDENCE] -> owner no-show scan produces violation"
    Write-Output '  StudentFlow : owner-attested temp e2e profile -> deploy/e2e/run.ps1 -Mode StudentBrowser (T08 self-registers; ApprovalBrowser never invoked)'
    Write-Output "  Teardown    : delete fixture-owned rows only (prefix $userPrefix / purpose $purposePrefix / demo resource), children first; never drop database/volume"
    Write-Output 'Run with -Execute -Mode <Setup|StudentFlow|Teardown|All>.'
    exit 0
}

# ---- Execute preconditions ------------------------------------------------------
function Assert-RepositoryPath {
    param([string]$Candidate, [string]$Expected, [string]$Label, [bool]$Leaf)
    if (-not $Candidate -or -not (Test-Path -LiteralPath $Candidate)) {
        Write-Warning "BLOCKED: demo profile path placeholder not expanded for $Label."
        exit 3
    }
    $resolved = (Resolve-Path -LiteralPath $Candidate).Path
    $expectedResolved = (Resolve-Path -LiteralPath $Expected).Path
    if (-not [string]::Equals($resolved, $expectedResolved, [StringComparison]::OrdinalIgnoreCase) -or
        ($Leaf -and -not (Test-Path -LiteralPath $resolved -PathType Leaf)) -or
        (-not $Leaf -and -not (Test-Path -LiteralPath $resolved -PathType Container))) {
        Write-Warning "REFUSED: $Label must resolve to the repository-owned path."
        exit 2
    }
    return $resolved
}
$composeFile = Assert-RepositoryPath $composeFile (Join-Path $repoRoot 'deploy\compose.yml') 'composeFile' $true
$seedPath = Assert-RepositoryPath $seedPath (Join-Path $repoRoot 'scripts\tests\t08\seed.sql') 't08SeedPath' $true
$t08Dir = Assert-RepositoryPath $t08Dir (Join-Path $repoRoot 'scripts\tests\t08') 't08HarnessDir' $false
$e2eRun = Assert-RepositoryPath $e2eRun (Join-Path $repoRoot 'deploy\e2e\run.ps1') 'e2eRunPath' $true
$Artifacts = Join-Path $ArtifactRoot "demo-$Mode-$RunId"
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null

function New-DemoPassword {
    $b = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    return [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

function New-DemoOwnershipTag {
    $bytes = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

$secretFile = $null
$secretCleanupFailed = $false
$createdMapPath = (Join-Path $Artifacts 'fixture-map.json')
$recoveryScopePath = (Join-Path $Artifacts 'recovery-scope.json')
$recoveryJournalPath = (Join-Path $Artifacts 'partial-fixture-journal.json')
$overall = 0

function Save-SecretFile {
    # Random system temp name; same-run use only; never inside artifacts.
    $script:secretFile = Join-Path ([System.IO.Path]::GetTempPath()) `
        ([System.IO.Path]::GetRandomFileName() + '.json')
    Set-Content -LiteralPath $script:secretFile -Value ($script:secret | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
}

function Remove-SecretFileOrFail {
    if ($script:secretFile -and (Test-Path -LiteralPath $script:secretFile)) {
        Remove-Item -LiteralPath $script:secretFile -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $script:secretFile) {
            $script:secretCleanupFailed = $true
            Write-Warning ("FATAL: demo secret file could not be deleted: {0} - ROTATE the generated passwords." -f $script:secretFile)
        }
    }
}

function Save-RecoveryJournal {
    if ($null -eq $script:recoveryJournal) { throw 'recovery journal state is unavailable' }
    $temporaryPath = $recoveryJournalPath + '.tmp'
    Set-Content -LiteralPath $temporaryPath -Value ($script:recoveryJournal | ConvertTo-Json -Depth 8) -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporaryPath -Destination $recoveryJournalPath -Force
}

function Invoke-DemoSetup {
    if (-not (Get-ComposeContainerId -File $composeFile -Svc 'mysql')) {
        Write-Warning 'BLOCKED: demo compose mysql is not running.'
        return 3
    }

    # 1) Owner fixture reference: verify the existing T08 seed file exists, but
    #    NEVER execute it. It contains destructive fixed-scope DELETE/INSERT
    #    statements and is not a read-only operation. T13 creates its own
    #    category/resource below, so no shared T08 row is touched.
    if (-not (Test-Path -LiteralPath $seedPath -PathType Leaf)) {
        throw 'T08 seed reference file is missing'
    }

    # Refuse namespace collisions before the first mutation. Exact deterministic
    # names/purposes make the later owner-tuple teardown meaningful; a retry or a
    # foreign row in the same RunId scope must be recovered/reviewed, never
    # silently adopted as this run's fixture.
    $categoryName = Get-DemoCategoryName
    $ownershipTag = New-DemoOwnershipTag
    $resourceDescription = "T13 ephemeral demo approval room ownership:$ownershipTag"
    $expectedUsernames = @('admin', 'student', 'intruder') | ForEach-Object { "${userPrefix}_$_" }
    $expectedUserIn = '(' + (@($expectedUsernames | ForEach-Object { ConvertTo-SqlUtf8Literal $_ }) -join ', ') + ')'
    $expectedPurposes = @("${purposePrefix}pending", "${purposePrefix}past-confirmed")
    $expectedPurposeIn = '(' + (@($expectedPurposes | ForEach-Object { ConvertTo-SqlUtf8Literal $_ }) -join ', ') + ')'
    $categoryNameSql = ConvertTo-SqlUtf8Literal $categoryName
    $resourceNameSql = ConvertTo-SqlUtf8Literal $resourceName
    $existingUsers = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM ``user`` WHERE username IN $expectedUserIn").Trim()
    $existingCategory = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE name=$categoryNameSql").Trim()
    $existingResource = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE name=$resourceNameSql").Trim()
    $existingBookings = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking WHERE purpose IN $expectedPurposeIn").Trim()
    if ($existingUsers -ne '0' -or $existingCategory -ne '0' -or $existingResource -ne '0' -or $existingBookings -ne '0') {
        Write-Warning 'REFUSED: demo RunId namespace is not empty; recover/review the exact prior scope before retrying.'
        return 2
    }

    # Persist a non-secret recovery scope before the first mutation. This is not
    # an executable teardown map (ids do not exist yet), but it prevents an
    # operator from guessing which deterministic names/purposes may need review
    # if Setup fails before fixture-map.json is complete.
    $recoveryScope = [ordered]@{
        runId = $RunId
        fixtureOwner = $fixtureOwner
        ownershipTag = $ownershipTag
        userPrefix = $userPrefix
        usernames = $expectedUsernames
        purposePrefix = $purposePrefix
        purposes = @("${purposePrefix}pending", "${purposePrefix}past-confirmed")
        categoryName = $categoryName
        resourceName = $resourceName
        resourceDescription = $resourceDescription
        preflightCounts = [ordered]@{
            users = $existingUsers
            category = $existingCategory
            resource = $existingResource
            bookings = $existingBookings
        }
        status = 'PRE-MUTATION SCOPE ONLY - NOT A TEARDOWN MAP OR ACCEPTANCE EVIDENCE'
        createdAt = (Get-Date).ToString('o')
    }
    Set-Content -LiteralPath $recoveryScopePath -Value ($recoveryScope | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM

    # Persist an incremental, non-secret compensation journal. Every recorded
    # entity carries its exact id + deterministic owner tuple. If a later setup
    # step fails in-process, finally may delete only these revalidated rows. The
    # journal is not acceptance evidence and never contains passwords or tokens.
    $script:recoveryJournal = [ordered]@{
        schemaVersion = 2
        runId = $RunId
        fixtureOwner = $fixtureOwner
        ownershipTag = $ownershipTag
        userPrefix = $userPrefix
        purposePrefix = $purposePrefix
        categoryName = $categoryName
        resourceName = $resourceName
        resourceDescription = $resourceDescription
        status = 'SETUP_IN_PROGRESS - NON-SECRET COMPENSATION JOURNAL'
        users = @()
        category = $null
        resource = $null
        timeRuleIds = @()
        bookings = @()
        bookingSlotIds = @()
        violationRecordIds = @()
        approvalRecordIds = @()
        updatedAt = (Get-Date).ToString('o')
    }
    Save-RecoveryJournal

    # 2) Register minimal users via the local API (no PII: optional fields null).
    $users = [ordered]@{}
    $ids = [ordered]@{}
    foreach ($role in @('admin', 'student', 'intruder')) {
        $uname = ('{0}_{1}' -f $userPrefix, $role)
        if ($uname.Length -gt 50) { Write-Warning "REFUSED: derived username too long: $uname"; return 2 }
        $pwd = New-DemoPassword
        $realName = 'T13Fixture' + $role.Substring(0, 1).ToUpper() + $role.Substring(1)
        $r = Invoke-Api -Method Post -Url "$beUrl/api/v1/auth/register" -Body (@{ username = $uname; password = $pwd; realName = $realName } | ConvertTo-Json -Compress)
        if ($r.Status -notin @(200, 201) -or $r.Code -ne '0') {
            Write-Warning ("SETUP FAIL: register {0} -> HTTP {1} code {2} msg {3}" -f $role, $r.Status, $r.Code, $r.Message)
            return 1
        }
        $users[$role] = [ordered]@{ username = $uname; password = $pwd }
        $unameSql = ConvertTo-SqlUtf8Literal $uname
        $id = Invoke-RootSql -File $composeFile -Query "SELECT id FROM ``user`` WHERE username=$unameSql"
        if ($id -notmatch '^\d+$') { throw "register did not persist $role user" }
        $ids[$role] = $id
        $script:recoveryJournal.users = @($script:recoveryJournal.users) + @([ordered]@{
            role = $role; id = $id; username = $uname
        })
        $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
        Save-RecoveryJournal
    }

    # 3) Promote admin via container-side SQL.
    $adminUsernameSql = ConvertTo-SqlUtf8Literal ([string]$users['admin'].username)
    $null = Invoke-RootSql -File $composeFile -Query "UPDATE ``user`` SET role='ADMIN' WHERE id=$($ids['admin']) AND username=$adminUsernameSql"

    # 4) Logins (tokens stay in memory + secret temp file only).
    foreach ($role in @('admin', 'student')) {
        $lr = Invoke-Api -Method Post -Url "$beUrl/api/v1/auth/login" -Body (@{ username = $users[$role].username; password = $users[$role].password } | ConvertTo-Json -Compress)
        if ($lr.Status -ne 200 -or $lr.Code -ne '0' -or -not $lr.Data -or -not $lr.Data.token) {
            Write-Warning ("SETUP FAIL: login {0} -> HTTP {1} code {2}" -f $role, $lr.Status, $lr.Code)
            return 1
        }
        $users[$role].token = $lr.Data.token
    }

    # 5) T13-owned category and approval resource via ADMIN API. This avoids
    #    mutating the shared T08 seed scope; the category id is runtime-owned.
    $catBody = @{ name = $categoryName; parentId = '0'; sortOrder = 0; icon = $null } |
        ConvertTo-Json -Compress
    $cr = Invoke-Api -Method Post -Url "$beUrl/api/v1/admin/categories" `
        -Headers @{ Authorization = "Bearer $($users['admin'].token)" } -Body $catBody
    if ($cr.Status -notin @(200, 201) -or $cr.Code -ne '0' -or -not $cr.Data -or -not $cr.Data.id) {
        Write-Warning ("SETUP FAIL: create demo category -> HTTP {0} code {1} msg {2}" -f $cr.Status, $cr.Code, $cr.Message)
        return 1
    }
    $categoryId = [string]$cr.Data.id
    if ($categoryId -notmatch '^\d+$') { throw 'non-numeric category id returned' }
    $script:recoveryJournal.category = [ordered]@{ id = $categoryId; name = $categoryName }
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal

    $resBody = @{
        categoryId = $categoryId; name = $resourceName; location = 'T13-DEMO'
        capacity = 1; description = $resourceDescription
        needApproval = $true; maxAdvanceDays = 7; minDurationMinutes = 30
        maxDurationMinutes = 120; status = 1
    } | ConvertTo-Json -Compress
    $rr = Invoke-Api -Method Post -Url "$beUrl/api/v1/admin/resources" `
        -Headers @{ Authorization = "Bearer $($users['admin'].token)" } -Body $resBody
    if ($rr.Status -notin @(200, 201) -or $rr.Code -ne '0' -or -not $rr.Data -or -not $rr.Data.id) {
        Write-Warning ("SETUP FAIL: create demo resource -> HTTP {0} code {1} msg {2}" -f $rr.Status, $rr.Code, $rr.Message)
        return 1
    }
    $resId = [string]$rr.Data.id
    if ($resId -notmatch '^\d+$') { throw 'non-numeric resource id returned' }
    $script:recoveryJournal.resource = [ordered]@{
        id = $resId; name = $resourceName; categoryId = $categoryId; description = $resourceDescription
    }
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal

    # time rule for tomorrow 08:00-20:00 (owner-defined shape: dayOfWeek/startTime/endTime)
    $dow = ([int](Get-Date).Date.AddDays(1).DayOfWeek) % 7; if ($dow -eq 0) { $dow = 7 }
    $trBody = ConvertTo-Json @(@{ dayOfWeek = $dow; startTime = '08:00:00'; endTime = '20:00:00' }) -Compress
    $tr = Invoke-Api -Method Put -Url "$beUrl/api/v1/admin/resources/$resId/time-rules" `
        -Headers @{ Authorization = "Bearer $($users['admin'].token)" } -Body $trBody
    if ($tr.Status -notin @(200, 201) -or $tr.Code -ne '0') {
        Write-Warning ("SETUP FAIL: time rule -> HTTP {0} code {1} msg {2}" -f $tr.Status, $tr.Code, $tr.Message)
        return 1
    }
    $ruleIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM resource_time_rule WHERE resource_id=$resId" |
        Where-Object { $_ -match '^\d+$' })
    if ($ruleIds.Count -eq 0 -or @($ruleIds | Select-Object -Unique).Count -ne $ruleIds.Count) { throw 'time-rule ids are missing or duplicated' }
    $script:recoveryJournal.timeRuleIds = $ruleIds
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal

    # 6) Deterministic PENDING booking via STUDENT API (tomorrow 10:00-11:00).
    $tomorrow = (Get-Date).Date.AddDays(1)
    $st = $tomorrow.ToString('yyyy-MM-dd') + ' 10:00:00'
    $en = $tomorrow.ToString('yyyy-MM-dd') + ' 11:00:00'
    $bkBody = @{
        resourceId = $resId; startTime = $st; endTime = $en
        purpose = "${purposePrefix}pending"; attendeeCount = 1
    } | ConvertTo-Json -Compress
    $bk = Invoke-Api -Method Post -Url "$beUrl/api/v1/bookings" `
        -Headers @{ Authorization = "Bearer $($users['student'].token)" } -Body $bkBody
    if ($bk.Status -ne 201 -or $bk.Code -ne '0' -or -not $bk.Data -or -not $bk.Data.id) {
        Write-Warning ("SETUP FAIL: pending booking -> HTTP {0} code {1} msg {2}" -f $bk.Status, $bk.Code, $bk.Message)
        return 1
    }
    $pendingBookingId = [string]$bk.Data.id
    if ($pendingBookingId -notmatch '^\d+$') { throw 'non-numeric pending booking id returned' }
    $script:recoveryJournal.bookings = @($script:recoveryJournal.bookings) + @([ordered]@{
        id = $pendingBookingId; purpose = "${purposePrefix}pending"; userId = $ids['student']; resourceId = $resId
    })
    $pendingSlotIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM booking_slot WHERE booking_id=$pendingBookingId ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    if ($pendingSlotIds.Count -ne 2 -or @($pendingSlotIds | Select-Object -Unique).Count -ne 2) {
        throw 'pending booking did not create exactly two distinct slot ids'
    }
    $script:recoveryJournal.bookingSlotIds = $pendingSlotIds
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal

    # 7) PAST CONFIRMED booking via direct SQL - EPHEMERAL-SETUP-NOT-ACCEPTANCE-
    #    EVIDENCE. Params are validated ids + known literals only. The OWNER
    #    no-show scan (<=1/min) turns it into violation + credit deduction; we
    #    wait briefly and record the outcome without asserting.
    $pastStart = (Get-Date).AddHours(-2).ToString('yyyy-MM-dd HH:mm:ss')
    $pastEnd   = (Get-Date).AddHours(-1).ToString('yyyy-MM-dd HH:mm:ss')
    $pastStartSql = ConvertTo-SqlUtf8Literal $pastStart
    $pastEndSql = ConvertTo-SqlUtf8Literal $pastEnd
    $pastPurposeSql = ConvertTo-SqlUtf8Literal "${purposePrefix}past-confirmed"
    $null = Invoke-RootSql -File $composeFile -Query ("INSERT INTO ``booking`` (booking_no, user_id, resource_id, start_time, end_time, purpose, status) SELECT CONCAT('T13DEMO', LPAD(id, 8, '0')), {0}, {1}, {2}, {3}, {4}, 'CONFIRMED' FROM ``user`` WHERE id = {0}" -f $ids['student'], $resId, $pastStartSql, $pastEndSql, $pastPurposeSql)
    $pastBookingId = (Invoke-RootSql -File $composeFile -Query "SELECT id FROM ``booking`` WHERE purpose=$pastPurposeSql").Trim()
    if ($pastBookingId -notmatch '^\d+$') { throw 'past CONFIRMED booking seed failed' }
    $script:recoveryJournal.bookings = @($script:recoveryJournal.bookings) + @([ordered]@{
        id = $pastBookingId; purpose = "${purposePrefix}past-confirmed"; userId = $ids['student']; resourceId = $resId
    })
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal
    # Two 30-minute slots for the 60-minute window (matches owner splitter shape).
    $null = Invoke-RootSql -File $composeFile -Query "INSERT INTO ``booking_slot`` (resource_id, slot_time, booking_id) SELECT resource_id, start_time, id FROM ``booking`` WHERE id=$pastBookingId"
    $null = Invoke-RootSql -File $composeFile -Query "INSERT INTO ``booking_slot`` (resource_id, slot_time, booking_id) SELECT resource_id, DATE_ADD(start_time, INTERVAL 30 MINUTE), id FROM ``booking`` WHERE id=$pastBookingId"
    $allSlotIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM booking_slot WHERE booking_id IN ($pendingBookingId, $pastBookingId) ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    if ($allSlotIds.Count -ne 4 -or @($allSlotIds | Select-Object -Unique).Count -ne 4) {
        throw 'demo bookings did not create exactly four distinct slot ids'
    }
    $script:recoveryJournal.bookingSlotIds = $allSlotIds
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal
    Write-Warning 'PAST CONFIRMED booking inserted as EPHEMERAL-SETUP-NOT-ACCEPTANCE-EVIDENCE (owner scan will own the violation).'

    $violBefore = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM violation_record WHERE booking_id=$pastBookingId").Trim()
    if ($script:noShowWait -gt 0) {
        $deadline = (Get-Date).AddSeconds($script:noShowWait)
        do {
            Start-Sleep -Seconds 5
            $violNow = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM violation_record WHERE booking_id=$pastBookingId").Trim()
        } while (($violNow -eq '0') -and ((Get-Date) -lt $deadline))
    }
    $violAfter = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM violation_record WHERE booking_id=$pastBookingId").Trim()
    $noShowState = if ($violAfter -ne '0') { 'owner-scan-produced' } elseif ($violBefore -ne '0') { 'pre-existing' } else { 'pending-owner-scan' }

    $bookingIdIn = "($pendingBookingId, $pastBookingId)"
    $violationRecordIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM violation_record WHERE booking_id IN $bookingIdIn ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    $approvalRecordIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM approval_record WHERE booking_id IN $bookingIdIn ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    $userIdIn = "($($ids['admin']), $($ids['student']), $($ids['intruder']))"
    $notificationIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM notification WHERE user_id IN $userIdIn ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    $blacklistIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM blacklist WHERE user_id IN $userIdIn OR operator_id IN $userIdIn ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    $operationLogIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM operation_log WHERE user_id IN $userIdIn ORDER BY id" |
        Where-Object { $_ -match '^\d+$' })
    $script:recoveryJournal.violationRecordIds = $violationRecordIds
    $script:recoveryJournal.approvalRecordIds = $approvalRecordIds
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal

    # 8) fixture-map.json: NON-SECRET facts only (ids/usernames/purposes/times).
    $map = [ordered]@{
        runId = $RunId
        fixtureOwner = $fixtureOwner
        ownershipTag = $ownershipTag
        userPrefix = $userPrefix
        purposePrefix = $purposePrefix
        users = [ordered]@{
            admin   = [ordered]@{ username = $users['admin'].username;   id = $ids['admin'] }
            student = [ordered]@{ username = $users['student'].username; id = $ids['student'] }
            intruder= [ordered]@{ username = $users['intruder'].username;id = $ids['intruder'] }
        }
        demoCategoryId = $categoryId
        demoCategoryName = $categoryName
        demoResourceId = $resId
        demoResourceName = $resourceName
        demoResourceDescription = $resourceDescription
        demoTimeRuleIds = $ruleIds
        bookingSlotIds = $allSlotIds
        violationRecordIds = $violationRecordIds
        approvalRecordIds = $approvalRecordIds
        notificationIds = $notificationIds
        blacklistIds = $blacklistIds
        operationLogIds = $operationLogIds
        pendingBookingId = $pendingBookingId
        pastConfirmedBookingId = $pastBookingId
        pastWindow = [ordered]@{ start = $pastStart; end = $pastEnd }
        noShowState = $noShowState
        t08SeedReference = $seedPath
        recoveryScopeArtifact = 'recovery-scope.json'
        createdAt = (Get-Date).ToString('o')
    }
    Set-Content -LiteralPath $createdMapPath -Value ($map | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM
    # Make the exact map available to the same process's StudentFlow/finally
    # teardown without relying on case-insensitive $MapPath/$mapPath aliases.
    $script:MapPath = $createdMapPath
    $script:recoveryJournal.status = 'SETUP_COMPLETE - fixture-map.json is authoritative'
    $script:recoveryJournal.updatedAt = (Get-Date).ToString('o')
    Save-RecoveryJournal
    Remove-Item -LiteralPath $recoveryJournalPath -Force

    # 9) secret temp JSON for the same run (passwords + tokens; NEVER artifact).
    $script:secret = [ordered]@{
        runId = $RunId
        users = [ordered]@{
            admin   = [ordered]@{ username = $users['admin'].username;   password = $users['admin'].password;   token = $users['admin'].token }
            student = [ordered]@{ username = $users['student'].username; password = $users['student'].password; token = $users['student'].token }
            intruder= [ordered]@{ username = $users['intruder'].username;password = $users['intruder'].password; token = '' }
        }
    }
    Save-SecretFile
    return 0
}

function Invoke-DemoStudentFlow {
    if (-not $profile0.fixtureAttested) {
        Write-Warning 'BLOCKED: StudentFlow requires owner-reviewed fixtureAttested=true; this script cannot self-attest.'
        return 3
    }
    if (-not (Test-Path -LiteralPath $e2eRun)) {
        Write-Warning "BLOCKED: e2e runner not found: $e2eRun"
        return 3
    }
    # Temp e2e profile: URLs only, NO secrets. fixtureAttested=true is justified
    # by THIS demo run's Setup (deterministic local fixture); T08 self-registers
    # its browser users, so no fixture password is needed.
    $tmpProfile = Join-Path ([System.IO.Path]::GetTempPath()) `
        ([System.IO.Path]::GetRandomFileName() + '.e2e-profile.json')
    $childProfile = [ordered]@{
        publicAccessDenied = $true
        frontendUrl = [string]$profile0.frontendUrl
        backendUrl  = [string]$profile0.backendUrl
        bookingApiDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..\booking-api')).Path
        t08HarnessDir = $t08Dir
        composeFile = $composeFile
        fixtureAttested = [bool]$profile0.fixtureAttested
        approvalBrowserFixtureAttested = $false
        approvalBrowserCommand = $null
        '$comment' = "attested by T13 demo Setup run $RunId (ephemeral fixture)"
    }
    Set-Content -LiteralPath $tmpProfile -Value ($childProfile | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
    $existingE2eDirs = @{}
    Get-ChildItem -LiteralPath $ArtifactRoot -Directory -Filter 'e2e-StudentBrowser-*' -ErrorAction SilentlyContinue |
        ForEach-Object { $existingE2eDirs[$_.FullName] = $true }
    try {
        & $e2eRun -Mode StudentBrowser -Execute -ProfilePath $tmpProfile
        $e2eExit = $LASTEXITCODE
    } finally {
        if (Test-Path -LiteralPath $tmpProfile) {
            Remove-Item -LiteralPath $tmpProfile -Force -ErrorAction SilentlyContinue
        }
    }

    # Map produced evidence into the demo evidence index (no pass claims for
    # approval; ApprovalBrowser was never invoked).
    $e2eDirs = @(Get-ChildItem -LiteralPath $ArtifactRoot -Directory -Filter 'e2e-StudentBrowser-*' -ErrorAction SilentlyContinue |
        Where-Object { -not $existingE2eDirs.ContainsKey($_.FullName) } |
        Sort-Object LastWriteTimeUtc -Descending)
    $idx = Join-Path $Artifacts 'evidence-index.md'
    $lines = @(
        "## StudentFlow evidence (run $RunId)",
        '',
        "| requirement slice | artifact | status |",
        '|---|---|---|'
    )
    if ($e2eDirs.Count -gt 0) {
        $d = $e2eDirs[0].FullName
        $lines += @(
            "| student register/login + direct booking + conflict/refresh + cancel/slot release (browser) | $d/t08-copy/REPORT.md | redacted text evidence |",
            '| network traces (Authorization/Cookie/PII scrubbed) | ' + "$d/t08-copy/network.jsonl | redacted |",
            '| screenshots | ' + "$d/screenshots-unreviewed/ | REQUIRES MANUAL VISUAL PII REVIEW |"
        )
    } else {
        $lines += '| (any) | NO E2E ARTIFACT PRODUCED | NOT RUN / FAILED |'
    }
    Add-Content -LiteralPath $idx -Value (($lines -join "`r`n") + "`r`n") -Encoding utf8NoBOM

    if ($e2eExit -ne 0) { Write-Warning ("StudentFlow (e2e) exited {0}." -f $e2eExit); return 1 }
    if ($e2eDirs.Count -eq 0) { Write-Warning 'StudentFlow returned zero but produced no new evidence directory.'; return 1 }
    return 0
}

function Invoke-DemoPartialRecovery {
    if (-not (Test-Path -LiteralPath $recoveryJournalPath -PathType Leaf)) {
        Write-Warning 'BLOCKED: partial recovery requires its exact recovery journal.'
        return 3
    }
    try { $journal = Get-Content -LiteralPath $recoveryJournalPath -Raw | ConvertFrom-Json } catch {
        Write-Warning 'REFUSED: partial recovery journal is not valid JSON.'
        return 2
    }
    foreach ($required in @('schemaVersion', 'runId', 'fixtureOwner', 'ownershipTag', 'userPrefix', 'purposePrefix', 'categoryName', 'resourceName', 'resourceDescription', 'users', 'timeRuleIds', 'bookings', 'bookingSlotIds', 'violationRecordIds', 'approvalRecordIds')) {
        if (-not $journal.PSObject.Properties[$required]) {
            Write-Warning "REFUSED: partial recovery journal is missing $required."
            return 2
        }
    }
    $expectedCategoryName = Get-DemoCategoryName
    $journalOwnershipTag = [string]$journal.ownershipTag
    $expectedResourceDescription = "T13 ephemeral demo approval room ownership:$journalOwnershipTag"
    if ([long]$journal.schemaVersion -ne 2 -or [string]$journal.runId -ne $RunId -or
        [string]$journal.fixtureOwner -ne $fixtureOwner -or $journalOwnershipTag -notmatch '^[0-9a-f]{32}$' -or
        [string]$journal.userPrefix -ne $userPrefix -or [string]$journal.purposePrefix -ne $purposePrefix -or
        [string]$journal.categoryName -ne $expectedCategoryName -or [string]$journal.resourceName -ne $resourceName -or
        [string]$journal.resourceDescription -ne $expectedResourceDescription) {
        Write-Warning 'REFUSED: partial recovery journal namespace does not match this exact run.'
        return 2
    }

    $journalUsers = @($journal.users)
    $allowedRoles = @('admin', 'student', 'intruder')
    $userIds = @()
    $userPredicates = @()
    foreach ($user in $journalUsers) {
        $role = [string]$user.role
        $id = [string]$user.id
        $username = [string]$user.username
        if ($role -notin $allowedRoles -or $id -notmatch '^\d+$' -or $username -ne "${userPrefix}_$role") {
            Write-Warning 'REFUSED: partial recovery user tuple is invalid.'
            return 2
        }
        $userIds += $id
        $usernameSql = ConvertTo-SqlUtf8Literal $username
        $userPredicates += "(id=$id AND username=$usernameSql)"
    }
    if (@($userIds | Select-Object -Unique).Count -ne $userIds.Count -or
        @($journalUsers | ForEach-Object { [string]$_.role } | Select-Object -Unique).Count -ne $journalUsers.Count) {
        Write-Warning 'REFUSED: partial recovery user tuples must be unique.'
        return 2
    }

    $category = if ($journal.PSObject.Properties['category']) { $journal.category } else { $null }
    $resource = if ($journal.PSObject.Properties['resource']) { $journal.resource } else { $null }
    $categoryId = ''
    $resourceId = ''
    if ($null -ne $category) {
        $categoryId = [string]$category.id
        if ($categoryId -notmatch '^\d+$' -or [string]$category.name -ne $expectedCategoryName) {
            Write-Warning 'REFUSED: partial recovery category tuple is invalid.'
            return 2
        }
    }
    if ($null -ne $resource) {
        $resourceId = [string]$resource.id
        if ($null -eq $category -or $resourceId -notmatch '^\d+$' -or [string]$resource.name -ne $resourceName -or
            [string]$resource.categoryId -ne $categoryId -or [string]$resource.description -ne $expectedResourceDescription) {
            Write-Warning 'REFUSED: partial recovery resource tuple is invalid.'
            return 2
        }
    }

    $ruleIds = @($journal.timeRuleIds | ForEach-Object { [string]$_ })
    if (($ruleIds.Count -gt 0 -and $null -eq $resource) -or
        @($ruleIds | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0 -or
        @($ruleIds | Select-Object -Unique).Count -ne $ruleIds.Count) {
        Write-Warning 'REFUSED: partial recovery time-rule ids are invalid.'
        return 2
    }

    $bookings = @($journal.bookings)
    $bookingIds = @()
    $bookingPredicates = @()
    $studentTuple = @($journalUsers | Where-Object { [string]$_.role -eq 'student' })
    $adminTuple = @($journalUsers | Where-Object { [string]$_.role -eq 'admin' })
    foreach ($booking in $bookings) {
        $id = [string]$booking.id
        $purpose = [string]$booking.purpose
        $userId = [string]$booking.userId
        $bookingResourceId = [string]$booking.resourceId
        if ($id -notmatch '^\d+$' -or $purpose -notin @("${purposePrefix}pending", "${purposePrefix}past-confirmed") -or
            $studentTuple.Count -ne 1 -or $userId -ne [string]$studentTuple[0].id -or
            $null -eq $resource -or $bookingResourceId -ne $resourceId) {
            Write-Warning 'REFUSED: partial recovery booking tuple is invalid.'
            return 2
        }
        $bookingIds += $id
        $purposeSql = ConvertTo-SqlUtf8Literal $purpose
        $bookingPredicates += "(id=$id AND purpose=$purposeSql AND user_id=$userId AND resource_id=$bookingResourceId)"
    }
    if (@($bookingIds | Select-Object -Unique).Count -ne $bookingIds.Count -or
        @($bookings | ForEach-Object { [string]$_.purpose } | Select-Object -Unique).Count -ne $bookings.Count) {
        Write-Warning 'REFUSED: partial recovery booking tuples must be unique.'
        return 2
    }
    if ($bookingIds.Count -gt 0 -and $adminTuple.Count -ne 1) {
        Write-Warning 'REFUSED: partial recovery booking tuples require exactly one journaled admin.'
        return 2
    }

    $journalChildIds = [ordered]@{}
    foreach ($propertyName in @('bookingSlotIds', 'violationRecordIds', 'approvalRecordIds')) {
        $values = @($journal.$propertyName | ForEach-Object { [string]$_ })
        if (@($values | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0 -or
            @($values | Select-Object -Unique).Count -ne $values.Count) {
            Write-Warning "REFUSED: partial recovery $propertyName must contain distinct numeric ids."
            return 2
        }
        $journalChildIds[$propertyName] = $values
    }
    $slotIds = @($journalChildIds.bookingSlotIds)
    $violationIds = @($journalChildIds.violationRecordIds)
    $approvalIds = @($journalChildIds.approvalRecordIds)
    if (($slotIds.Count -gt 0 -or $violationIds.Count -gt 0 -or $approvalIds.Count -gt 0) -and $bookingIds.Count -eq 0) {
        Write-Warning 'REFUSED: partial recovery child ids require journaled booking parents.'
        return 2
    }
    $slotIn = if ($slotIds.Count -gt 0) { '(' + ($slotIds -join ', ') + ')' } else { '' }
    $violationIn = if ($violationIds.Count -gt 0) { '(' + ($violationIds -join ', ') + ')' } else { '' }
    $approvalIn = if ($approvalIds.Count -gt 0) { '(' + ($approvalIds -join ', ') + ')' } else { '' }

    $userIn = if ($userIds.Count -gt 0) { '(' + ($userIds -join ', ') + ')' } else { '' }
    $bookingIn = if ($bookingIds.Count -gt 0) { '(' + ($bookingIds -join ', ') + ')' } else { '' }
    $bookingScopeClauses = [System.Collections.Generic.List[string]]::new()
    if ($userIds.Count -gt 0) { [void]$bookingScopeClauses.Add("user_id IN $userIn") }
    if ($null -ne $resource) { [void]$bookingScopeClauses.Add("resource_id=$resourceId") }
    if ($bookingIds.Count -gt 0) { [void]$bookingScopeClauses.Add("id IN $bookingIn") }
    $bookingScopeWhere = $bookingScopeClauses -join ' OR '

    $violationScopeClauses = [System.Collections.Generic.List[string]]::new()
    $approvalScopeClauses = [System.Collections.Generic.List[string]]::new()
    $slotScopeClauses = [System.Collections.Generic.List[string]]::new()
    if ($userIds.Count -gt 0) {
        [void]$violationScopeClauses.Add("user_id IN $userIn")
        [void]$approvalScopeClauses.Add("approver_id IN $userIn")
    }
    if ($bookingIds.Count -gt 0) {
        [void]$violationScopeClauses.Add("booking_id IN $bookingIn")
        [void]$approvalScopeClauses.Add("booking_id IN $bookingIn")
        [void]$slotScopeClauses.Add("booking_id IN $bookingIn")
    }
    if ($null -ne $resource) { [void]$slotScopeClauses.Add("resource_id=$resourceId") }
    $violationScopeWhere = $violationScopeClauses -join ' OR '
    $approvalScopeWhere = $approvalScopeClauses -join ' OR '
    $slotScopeWhere = $slotScopeClauses -join ' OR '

    # Revalidate every recorded owner tuple before the first DELETE. Any missing,
    # extra, or foreign row blocks compensation rather than widening its scope.
    if ($userPredicates.Count -gt 0) {
        $ownedUsers = (Invoke-RootSql -File $composeFile -Query ('SELECT COUNT(*) FROM ``user`` WHERE ' + ($userPredicates -join ' OR '))).Trim()
        if ($ownedUsers -ne [string]$journalUsers.Count) { Write-Warning 'REFUSED: partial recovery user ownership mismatch; zero deletes executed.'; return 2 }
        $notificationCount = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM notification WHERE user_id IN $userIn").Trim()
        $blacklistCount = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIn OR operator_id IN $userIn").Trim()
        $operationLogCount = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM operation_log WHERE user_id IN $userIn").Trim()
        if ($notificationCount -ne '0' -or $blacklistCount -ne '0' -or $operationLogCount -ne '0') {
            Write-Warning 'REFUSED: partial recovery found unjournaled notification/blacklist/operation-log rows; zero deletes executed.'
            return 2
        }
    }
    if ($null -ne $category) {
        $expectedCategoryNameSql = ConvertTo-SqlUtf8Literal $expectedCategoryName
        $ownedCategory = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE id=$categoryId AND name=$expectedCategoryNameSql").Trim()
        if ($ownedCategory -ne '1') { Write-Warning 'REFUSED: partial recovery category ownership mismatch; zero deletes executed.'; return 2 }
    }
    if ($null -ne $resource) {
        $resourceNameSql = ConvertTo-SqlUtf8Literal $resourceName
        $resourceDescriptionSql = ConvertTo-SqlUtf8Literal $expectedResourceDescription
        $ownedResource = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE id=$resourceId AND name=$resourceNameSql AND category_id=$categoryId AND description=$resourceDescriptionSql").Trim()
        if ($ownedResource -ne '1') { Write-Warning 'REFUSED: partial recovery resource ownership mismatch; zero deletes executed.'; return 2 }
        $actualRuleIds = @(Invoke-RootSql -File $composeFile -Query "SELECT id FROM resource_time_rule WHERE resource_id=$resourceId ORDER BY id" | Where-Object { $_ -match '^\d+$' })
        if ((@($actualRuleIds | Sort-Object) -join ',') -ne (@($ruleIds | Sort-Object) -join ',')) {
            Write-Warning 'REFUSED: partial recovery time-rule ownership mismatch; zero deletes executed.'
            return 2
        }
        $closureCount = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resourceId").Trim()
        if ($closureCount -ne '0') { Write-Warning 'REFUSED: partial recovery found unrecorded resource closures; zero deletes executed.'; return 2 }
    }
    if ($bookingPredicates.Count -gt 0) {
        $ownedBookings = (Invoke-RootSql -File $composeFile -Query ('SELECT COUNT(*) FROM booking WHERE ' + ($bookingPredicates -join ' OR '))).Trim()
        if ($ownedBookings -ne [string]$bookings.Count) { Write-Warning 'REFUSED: partial recovery booking ownership mismatch; zero deletes executed.'; return 2 }
    }

    # The authoritative check+delete is a single MySQL transaction. Parent rows
    # are locked first; ownership and absence of unjournaled user/resource child
    # rows are recomputed inside the same connection. Deletes are conditional on
    # @ownership_ok, and any incomplete cleanup selects ROLLBACK rather than
    # committing a half-deleted fixture.
    $transaction = [System.Collections.Generic.List[string]]::new()
    [void]$transaction.Add('SET TRANSACTION ISOLATION LEVEL SERIALIZABLE')
    [void]$transaction.Add('START TRANSACTION')
    $ownershipConditions = [System.Collections.Generic.List[string]]::new()
    $cleanupConditions = [System.Collections.Generic.List[string]]::new()
    if ($userPredicates.Count -gt 0) {
        $userWhere = $userPredicates -join ' OR '
        [void]$transaction.Add("SELECT id FROM ``user`` WHERE $userWhere FOR UPDATE")
        [void]$transaction.Add("SELECT id FROM notification WHERE user_id IN $userIn FOR UPDATE")
        [void]$transaction.Add("SELECT id FROM blacklist WHERE user_id IN $userIn OR operator_id IN $userIn FOR UPDATE")
        [void]$transaction.Add("SELECT id FROM operation_log WHERE user_id IN $userIn FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM ``user`` WHERE $userWhere)=$($journalUsers.Count)")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM notification WHERE user_id IN $userIn)=0")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIn OR operator_id IN $userIn)=0")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM operation_log WHERE user_id IN $userIn)=0")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM ``user`` WHERE $userWhere)=0")
    }
    if ($null -ne $category) {
        $expectedCategoryNameSql = ConvertTo-SqlUtf8Literal $expectedCategoryName
        $categoryWhere = "id=$categoryId AND name=$expectedCategoryNameSql"
        [void]$transaction.Add("SELECT id FROM resource_category WHERE $categoryWhere FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource_category WHERE $categoryWhere)=1")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM resource_category WHERE $categoryWhere)=0")
    }
    if ($null -ne $resource) {
        $resourceNameSql = ConvertTo-SqlUtf8Literal $resourceName
        $resourceDescriptionSql = ConvertTo-SqlUtf8Literal $expectedResourceDescription
        $resourceWhere = "id=$resourceId AND name=$resourceNameSql AND category_id=$categoryId AND description=$resourceDescriptionSql"
        [void]$transaction.Add("SELECT id FROM resource WHERE $resourceWhere FOR UPDATE")
        [void]$transaction.Add("SELECT id FROM resource_time_rule WHERE resource_id=$resourceId FOR UPDATE")
        [void]$transaction.Add("SELECT id FROM resource_closure WHERE resource_id=$resourceId FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource WHERE $resourceWhere)=1")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resourceId)=0")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM resource WHERE $resourceWhere)=0")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resourceId)=0")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resourceId)=0")
        if ($ruleIds.Count -gt 0) {
            $ruleIn = '(' + ($ruleIds -join ', ') + ')'
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resourceId)=$($ruleIds.Count)")
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resourceId AND id IN $ruleIn)=$($ruleIds.Count)")
        } else {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resourceId)=0")
        }
    }
    if ($bookingScopeClauses.Count -gt 0) {
        [void]$transaction.Add("SELECT id FROM booking WHERE $bookingScopeWhere FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking WHERE $bookingScopeWhere)=$($bookings.Count)")
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM booking WHERE $bookingScopeWhere)=0")
    }
    if ($violationScopeClauses.Count -gt 0) {
        [void]$transaction.Add("SELECT id FROM violation_record WHERE $violationScopeWhere FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE $violationScopeWhere)=$($violationIds.Count)")
        if ($violationIds.Count -gt 0) {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE id IN $violationIn AND booking_id IN $bookingIn AND user_id=$([string]$studentTuple[0].id))=$($violationIds.Count)")
        }
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE $violationScopeWhere)=0")
    }
    if ($approvalScopeClauses.Count -gt 0) {
        [void]$transaction.Add("SELECT id FROM approval_record WHERE $approvalScopeWhere FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE $approvalScopeWhere)=$($approvalIds.Count)")
        if ($approvalIds.Count -gt 0) {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE id IN $approvalIn AND booking_id IN $bookingIn AND approver_id=$([string]$adminTuple[0].id))=$($approvalIds.Count)")
        }
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE $approvalScopeWhere)=0")
    }
    if ($slotScopeClauses.Count -gt 0) {
        [void]$transaction.Add("SELECT id FROM booking_slot WHERE $slotScopeWhere FOR UPDATE")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking_slot WHERE $slotScopeWhere)=$($slotIds.Count)")
        if ($slotIds.Count -gt 0) {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking_slot WHERE id IN $slotIn AND booking_id IN $bookingIn AND resource_id=$resourceId)=$($slotIds.Count)")
        }
        [void]$cleanupConditions.Add("(SELECT COUNT(*) FROM booking_slot WHERE $slotScopeWhere)=0")
    }
    if ($bookingIds.Count -gt 0) {
        $bookingWhere = $bookingPredicates -join ' OR '
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking WHERE $bookingWhere)=$($bookings.Count)")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE booking_id IN $bookingIn AND user_id<>$([string]$studentTuple[0].id))=0")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE booking_id IN $bookingIn AND approver_id<>$([string]$adminTuple[0].id))=0")
        if ($userIds.Count -gt 0) {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIn AND booking_id NOT IN $bookingIn)=0")
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIn AND booking_id NOT IN $bookingIn)=0")
        }
        if ($null -ne $resource) {
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking_slot WHERE booking_id IN $bookingIn AND resource_id<>$resourceId)=0")
            [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM booking_slot WHERE resource_id=$resourceId AND booking_id NOT IN $bookingIn)=0")
        }
    } elseif ($userIds.Count -gt 0) {
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIn)=0")
        [void]$ownershipConditions.Add("(SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIn)=0")
    }
    if ($ownershipConditions.Count -eq 0) {
        Write-Warning 'REFUSED: partial recovery journal contains no recorded entity to compensate.'
        return 2
    }
    [void]$transaction.Add('SET @ownership_ok := IF((' + ($ownershipConditions -join ') AND (') + '), 1, 0)')
    if ($bookingIds.Count -gt 0) {
        if ($violationIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM violation_record WHERE id IN $violationIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
        if ($approvalIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM approval_record WHERE id IN $approvalIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
        if ($slotIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM booking_slot WHERE id IN $slotIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
        [void]$transaction.Add("DELETE FROM booking WHERE id IN $bookingIn AND @ownership_ok=1")
    }
    if ($ruleIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM resource_time_rule WHERE id IN $ruleIn AND resource_id=$resourceId AND @ownership_ok=1") }
    if ($null -ne $resource) { [void]$transaction.Add("DELETE FROM resource WHERE $resourceWhere AND @ownership_ok=1") }
    if ($null -ne $category) { [void]$transaction.Add("DELETE FROM resource_category WHERE $categoryWhere AND @ownership_ok=1") }
    if ($userPredicates.Count -gt 0) { [void]$transaction.Add("DELETE FROM ``user`` WHERE ($userWhere) AND @ownership_ok=1") }
    [void]$transaction.Add('SET @cleanup_ok := IF((' + ($cleanupConditions -join ') AND (') + '), 1, 0)')
    [void]$transaction.Add("SET @finish_sql := IF(@ownership_ok=1 AND @cleanup_ok=1, 'COMMIT', 'ROLLBACK')")
    [void]$transaction.Add('PREPARE t13_finish FROM @finish_sql')
    [void]$transaction.Add('EXECUTE t13_finish')
    [void]$transaction.Add('DEALLOCATE PREPARE t13_finish')
    [void]$transaction.Add("SELECT CONCAT('T13COMP:', @ownership_ok, ':', @cleanup_ok)")
    $transactionResult = Invoke-RootSql -File $composeFile -Query (($transaction -join ";`n") + ';')
    if (@($transactionResult -split "`r?`n") -notcontains 'T13COMP:1:1') {
        Write-Warning 'REFUSED: transactional partial recovery rolled back after an ownership or cleanup mismatch.'
        return 2
    }

    $leftUsers = if ($userPredicates.Count -gt 0) { (Invoke-RootSql -File $composeFile -Query ('SELECT COUNT(*) FROM ``user`` WHERE ' + ($userPredicates -join ' OR '))).Trim() } else { '0' }
    $leftBookings = if ($bookingScopeClauses.Count -gt 0) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking WHERE $bookingScopeWhere").Trim() } else { '0' }
    $leftViolations = if ($violationScopeClauses.Count -gt 0) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM violation_record WHERE $violationScopeWhere").Trim() } else { '0' }
    $leftApprovals = if ($approvalScopeClauses.Count -gt 0) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM approval_record WHERE $approvalScopeWhere").Trim() } else { '0' }
    $leftSlots = if ($slotScopeClauses.Count -gt 0) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking_slot WHERE $slotScopeWhere").Trim() } else { '0' }
    $leftResource = if ($null -ne $resource) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE id=$resourceId AND name=$resourceNameSql AND description=$resourceDescriptionSql").Trim() } else { '0' }
    $leftRules = if ($null -ne $resource) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resourceId").Trim() } else { '0' }
    $leftClosures = if ($null -ne $resource) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resourceId").Trim() } else { '0' }
    $leftCategory = if ($null -ne $category) { (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE id=$categoryId AND name=$expectedCategoryNameSql").Trim() } else { '0' }
    if ($leftUsers -ne '0' -or $leftBookings -ne '0' -or $leftViolations -ne '0' -or $leftApprovals -ne '0' -or
        $leftSlots -ne '0' -or $leftResource -ne '0' -or $leftRules -ne '0' -or $leftClosures -ne '0' -or $leftCategory -ne '0') {
        Write-Warning 'PARTIAL RECOVERY FAILED: recorded fixture rows remain.'
        return 1
    }
    $script:recoveryJournal = [ordered]@{
        schemaVersion = 2; runId = $RunId; ownershipTag = $journalOwnershipTag; status = 'PARTIAL_SETUP_COMPENSATED'
        compensatedAt = (Get-Date).ToString('o')
        removed = [ordered]@{ users = $journalUsers.Count; bookings = $bookings.Count; resource = [int]($null -ne $resource); category = [int]($null -ne $category) }
    }
    Save-RecoveryJournal
    Write-Warning 'PARTIAL SETUP COMPENSATED: only journaled and revalidated owner tuples were removed.'
    return 0
}

function Invoke-DemoTeardown {
    if (-not $script:MapPath -or -not (Test-Path -LiteralPath $script:MapPath)) {
        Write-Warning 'BLOCKED: Teardown requires -MapPath to an existing fixture-map.json (never guess scope).'
        return 3
    }
    $map = Get-Content -LiteralPath $script:MapPath -Raw | ConvertFrom-Json
    if ([string]$map.runId -ne $RunId) {
        Write-Warning 'REFUSED: fixture map runId does not match the requested RunId.'
        return 2
    }
    if ([string]$map.runId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$' -or
        [string]$map.userPrefix -ne ('t13demo_' + ([string]$map.runId -replace '-', '_')) -or
        [string]$map.purposePrefix -ne ("T13DEMO:$($map.runId):") -or
        [string]$map.userPrefix -notmatch '^t13demo_[A-Za-z0-9_]+$') {
        Write-Warning 'REFUSED: fixture map namespace markers are invalid.'
        return 2
    }
    $mapFixtureOwner = if ($map.PSObject.Properties['fixtureOwner']) { [string]$map.fixtureOwner } else { '' }
    $ownershipTag = if ($map.PSObject.Properties['ownershipTag']) { [string]$map.ownershipTag } else { '' }
    $resourceDescriptionFromMap = if ($map.PSObject.Properties['demoResourceDescription']) { [string]$map.demoResourceDescription } else { '' }
    $expectedResourceDescription = "T13 ephemeral demo approval room ownership:$ownershipTag"
    if ($mapFixtureOwner -ne $fixtureOwner -or $ownershipTag -notmatch '^[0-9a-f]{32}$' -or
        $resourceDescriptionFromMap -ne $expectedResourceDescription) {
        Write-Warning 'REFUSED: fixture map ownership provenance is invalid.'
        return 2
    }
    $resId = [string]$map.demoResourceId
    $resourceNameFromMap = [string]$map.demoResourceName
    $categoryNameFromMap = [string]$map.demoCategoryName
    if (-not $resourceNameFromMap -or $resourceNameFromMap -ne ("T13 DEMO $($map.runId) approval room") -or
        -not $categoryNameFromMap -or $categoryNameFromMap -ne (Get-DemoCategoryName -CategoryRunId ([string]$map.runId))) {
        Write-Warning 'REFUSED: fixture map resource name does not match its run namespace.'
        return 2
    }
    $usernames = @(
        [string]$map.users.admin.username,
        [string]$map.users.student.username,
        [string]$map.users.intruder.username
    )
    $expectedMapUsernames = @(
        "$($map.userPrefix)_admin",
        "$($map.userPrefix)_student",
        "$($map.userPrefix)_intruder"
    )
    if (($usernames -join "`n") -cne ($expectedMapUsernames -join "`n")) {
        Write-Warning 'REFUSED: fixture map usernames do not match the fixed admin/student/intruder roles.'
        return 2
    }
    $purposes = @(
        ('{0}pending' -f [string]$map.purposePrefix),
        ('{0}past-confirmed' -f [string]$map.purposePrefix)
    )
    $bookingIds = @([string]$map.pendingBookingId, [string]$map.pastConfirmedBookingId)
    foreach ($bookingId in $bookingIds) {
        if ($bookingId -notmatch '^\d+$') { Write-Warning 'REFUSED: fixture map booking ids must be numeric.'; return 2 }
    }
    if (@($bookingIds | Select-Object -Unique).Count -ne 2) {
        Write-Warning 'REFUSED: fixture map booking ids must be distinct.'
        return 2
    }
    $adminId = [string]$map.users.admin.id
    $studId  = [string]$map.users.student.id
    $intrId  = [string]$map.users.intruder.id
    foreach ($v in @($adminId, $studId, $intrId)) {
        if ($v -notmatch '^\d+$') { Write-Warning 'REFUSED: fixture map user ids must be numeric.'; return 2 }
    }
    if (@(@($adminId, $studId, $intrId) | Select-Object -Unique).Count -ne 3) {
        Write-Warning 'REFUSED: fixture map user ids must be distinct.'
        return 2
    }
    foreach ($u in $usernames) {
        if ($u -notmatch '^[A-Za-z0-9_]{3,50}$' -or $u -notlike "$($map.userPrefix)_*") {
            Write-Warning "REFUSED: fixture map username failed namespace check: '$u'"; return 2
        }
    }
    if (@($usernames | Select-Object -Unique).Count -ne 3) {
        Write-Warning 'REFUSED: fixture map usernames must be distinct.'
        return 2
    }
    if ($resId -notmatch '^\d+$') { Write-Warning 'REFUSED: fixture map resource id must be numeric.'; return 2 }
    $categoryId = [string]$map.demoCategoryId
    if ($categoryId -notmatch '^\d+$') { Write-Warning 'REFUSED: fixture map category id must be numeric.'; return 2 }
    if (-not $map.PSObject.Properties['demoTimeRuleIds']) { Write-Warning 'REFUSED: fixture map time-rule ids are missing.'; return 2 }
    $timeRuleIds = @($map.demoTimeRuleIds | ForEach-Object { [string]$_ })
    if ($timeRuleIds.Count -eq 0 -or @($timeRuleIds | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0 -or
        @($timeRuleIds | Select-Object -Unique).Count -ne $timeRuleIds.Count) {
        Write-Warning 'REFUSED: fixture map time-rule ids must be non-empty, numeric and distinct.'
        return 2
    }
    $mapChildIds = [ordered]@{}
    foreach ($propertyName in @('bookingSlotIds', 'violationRecordIds', 'approvalRecordIds', 'notificationIds', 'blacklistIds', 'operationLogIds')) {
        if (-not $map.PSObject.Properties[$propertyName]) {
            Write-Warning "REFUSED: fixture map is missing exact child-id set $propertyName."
            return 2
        }
        $values = @($map.$propertyName | ForEach-Object { [string]$_ })
        if (@($values | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0 -or
            @($values | Select-Object -Unique).Count -ne $values.Count) {
            Write-Warning "REFUSED: fixture map $propertyName must contain distinct numeric ids."
            return 2
        }
        $mapChildIds[$propertyName] = $values
    }
    $slotIds = @($mapChildIds.bookingSlotIds)
    $violationIds = @($mapChildIds.violationRecordIds)
    $approvalIds = @($mapChildIds.approvalRecordIds)
    $notificationIds = @($mapChildIds.notificationIds)
    $blacklistIds = @($mapChildIds.blacklistIds)
    $operationLogIds = @($mapChildIds.operationLogIds)
    $slotIn = if ($slotIds.Count -gt 0) { '(' + ($slotIds -join ', ') + ')' } else { '' }
    $violationIn = if ($violationIds.Count -gt 0) { '(' + ($violationIds -join ', ') + ')' } else { '' }
    $approvalIn = if ($approvalIds.Count -gt 0) { '(' + ($approvalIds -join ', ') + ')' } else { '' }
    $notificationIn = if ($notificationIds.Count -gt 0) { '(' + ($notificationIds -join ', ') + ')' } else { '' }
    $blacklistIn = if ($blacklistIds.Count -gt 0) { '(' + ($blacklistIds -join ', ') + ')' } else { '' }
    $operationLogIn = if ($operationLogIds.Count -gt 0) { '(' + ($operationLogIds -join ', ') + ')' } else { '' }

    $preTotals = Invoke-RootSql -File $composeFile -Query 'SELECT (SELECT COUNT(*) FROM booking), (SELECT COUNT(*) FROM `user`)'

    # Build exact deterministic tuples. The authoritative ownership check and
    # children-first cleanup execute in one SERIALIZABLE transaction. A complete
    # already-absent scope is an idempotent success; any partial/mismatched scope
    # or incomplete child cleanup selects ROLLBACK.
    $usernameLiterals = @($usernames | ForEach-Object { ConvertTo-SqlUtf8Literal $_ })
    $usernameIn = '(' + ($usernameLiterals -join ', ') + ')'
    $purposeLiterals = @($purposes | ForEach-Object { ConvertTo-SqlUtf8Literal $_ })
    $purposeIn = '(' + ($purposeLiterals -join ', ') + ')'
    $userIdIn = '(' + (@($adminId, $studId, $intrId) -join ', ') + ')'
    $bookingIn = '(' + ($bookingIds -join ', ') + ')'
    $ruleIn = '(' + ($timeRuleIds -join ', ') + ')'
    $categoryNameSql = ConvertTo-SqlUtf8Literal $categoryNameFromMap
    $resourceNameSql = ConvertTo-SqlUtf8Literal $resourceNameFromMap
    $resourceDescriptionSql = ConvertTo-SqlUtf8Literal $resourceDescriptionFromMap
    $userTupleWhere = "(id=$adminId AND username=$($usernameLiterals[0])) OR (id=$studId AND username=$($usernameLiterals[1])) OR (id=$intrId AND username=$($usernameLiterals[2]))"
    $bookingTupleWhere = "(id=$($bookingIds[0]) AND purpose=$($purposeLiterals[0]) AND user_id=$studId AND resource_id=$resId) OR (id=$($bookingIds[1]) AND purpose=$($purposeLiterals[1]) AND user_id=$studId AND resource_id=$resId)"
    $userCollisionWhere = "id IN $userIdIn OR username IN $usernameIn"
    $categoryCollisionWhere = "id=$categoryId OR name=$categoryNameSql"
    $resourceCollisionWhere = "id=$resId OR name=$resourceNameSql"
    $bookingCollisionWhere = "id IN $bookingIn OR purpose IN $purposeIn"

    $transaction = [System.Collections.Generic.List[string]]::new()
    [void]$transaction.Add('SET TRANSACTION ISOLATION LEVEL SERIALIZABLE')
    [void]$transaction.Add('START TRANSACTION')
    [void]$transaction.Add("SELECT id FROM ``user`` WHERE $userCollisionWhere FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM booking WHERE user_id IN $userIdIn OR resource_id=$resId OR $bookingCollisionWhere FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM violation_record WHERE user_id IN $userIdIn OR booking_id IN $bookingIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM approval_record WHERE approver_id IN $userIdIn OR booking_id IN $bookingIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM booking_slot WHERE resource_id=$resId OR booking_id IN $bookingIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM notification WHERE user_id IN $userIdIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM blacklist WHERE user_id IN $userIdIn OR operator_id IN $userIdIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM operation_log WHERE user_id IN $userIdIn FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM resource_category WHERE parent_id=$categoryId OR $categoryCollisionWhere FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM resource WHERE category_id=$categoryId OR $resourceCollisionWhere FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM resource_time_rule WHERE resource_id=$resId FOR UPDATE")
    [void]$transaction.Add("SELECT id FROM resource_closure WHERE resource_id=$resId FOR UPDATE")

    $ownershipConditions = @(
        "(SELECT COUNT(*) FROM ``user`` WHERE $userTupleWhere)=3",
        "(SELECT COUNT(*) FROM ``user`` WHERE $userCollisionWhere)=3",
        "(SELECT COUNT(*) FROM resource_category WHERE id=$categoryId AND name=$categoryNameSql)=1",
        "(SELECT COUNT(*) FROM resource_category WHERE $categoryCollisionWhere)=1",
        "(SELECT COUNT(*) FROM resource_category WHERE parent_id=$categoryId)=0",
        "(SELECT COUNT(*) FROM resource WHERE id=$resId AND name=$resourceNameSql AND category_id=$categoryId AND description=$resourceDescriptionSql)=1",
        "(SELECT COUNT(*) FROM resource WHERE category_id=$categoryId OR $resourceCollisionWhere)=1",
        "(SELECT COUNT(*) FROM booking WHERE $bookingTupleWhere)=2",
        "(SELECT COUNT(*) FROM booking WHERE user_id IN $userIdIn OR resource_id=$resId OR $bookingCollisionWhere)=2",
        "(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resId)=$($timeRuleIds.Count)",
        "(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resId AND id IN $ruleIn)=$($timeRuleIds.Count)",
        "(SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resId)=0",
        "(SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIdIn AND booking_id NOT IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM violation_record WHERE booking_id IN $bookingIn AND user_id<>$studId)=0",
        "(SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIdIn AND booking_id NOT IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM approval_record WHERE booking_id IN $bookingIn AND approver_id<>$adminId)=0",
        "(SELECT COUNT(*) FROM blacklist WHERE operator_id IN $userIdIn AND user_id NOT IN $userIdIn)=0",
        "(SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIdIn AND operator_id IS NOT NULL)=0",
        "(SELECT COUNT(*) FROM notification WHERE user_id IN $userIdIn AND biz_id IS NOT NULL AND biz_id NOT IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM booking_slot WHERE resource_id=$resId AND booking_id NOT IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM booking_slot WHERE booking_id IN $bookingIn AND resource_id<>$resId)=0"
    )
    $ownershipConditions += "(SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIdIn OR booking_id IN $bookingIn)=$($violationIds.Count)"
    $ownershipConditions += "(SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIdIn OR booking_id IN $bookingIn)=$($approvalIds.Count)"
    $ownershipConditions += "(SELECT COUNT(*) FROM booking_slot WHERE resource_id=$resId OR booking_id IN $bookingIn)=$($slotIds.Count)"
    $ownershipConditions += "(SELECT COUNT(*) FROM notification WHERE user_id IN $userIdIn)=$($notificationIds.Count)"
    $ownershipConditions += "(SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIdIn OR operator_id IN $userIdIn)=$($blacklistIds.Count)"
    $ownershipConditions += "(SELECT COUNT(*) FROM operation_log WHERE user_id IN $userIdIn)=$($operationLogIds.Count)"
    if ($violationIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM violation_record WHERE id IN $violationIn AND booking_id IN $bookingIn AND user_id=$studId)=$($violationIds.Count)"
    }
    if ($approvalIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM approval_record WHERE id IN $approvalIn AND booking_id IN $bookingIn AND approver_id=$adminId)=$($approvalIds.Count)"
    }
    if ($slotIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM booking_slot WHERE id IN $slotIn AND booking_id IN $bookingIn AND resource_id=$resId)=$($slotIds.Count)"
    }
    if ($notificationIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM notification WHERE id IN $notificationIn AND user_id IN $userIdIn)=$($notificationIds.Count)"
    }
    if ($blacklistIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM blacklist WHERE id IN $blacklistIn AND user_id IN $userIdIn AND operator_id IS NULL)=$($blacklistIds.Count)"
    }
    if ($operationLogIds.Count -gt 0) {
        $ownershipConditions += "(SELECT COUNT(*) FROM operation_log WHERE id IN $operationLogIn AND user_id IN $userIdIn)=$($operationLogIds.Count)"
    }
    $absenceConditions = @(
        "(SELECT COUNT(*) FROM ``user`` WHERE $userCollisionWhere)=0",
        "(SELECT COUNT(*) FROM resource_category WHERE parent_id=$categoryId OR $categoryCollisionWhere)=0",
        "(SELECT COUNT(*) FROM resource WHERE category_id=$categoryId OR $resourceCollisionWhere)=0",
        "(SELECT COUNT(*) FROM booking WHERE user_id IN $userIdIn OR resource_id=$resId OR $bookingCollisionWhere)=0",
        "(SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIdIn OR booking_id IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIdIn OR booking_id IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM booking_slot WHERE resource_id=$resId OR booking_id IN $bookingIn)=0",
        "(SELECT COUNT(*) FROM notification WHERE user_id IN $userIdIn)=0",
        "(SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIdIn OR operator_id IN $userIdIn)=0",
        "(SELECT COUNT(*) FROM operation_log WHERE user_id IN $userIdIn)=0",
        "(SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resId)=0",
        "(SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resId)=0"
    )
    [void]$transaction.Add('SET @ownership_ok := IF((' + ($ownershipConditions -join ') AND (') + '), 1, 0)')
    [void]$transaction.Add('SET @already_absent := IF((' + ($absenceConditions -join ') AND (') + '), 1, 0)')
    if ($violationIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM violation_record WHERE id IN $violationIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
    if ($approvalIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM approval_record WHERE id IN $approvalIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
    if ($slotIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM booking_slot WHERE id IN $slotIn AND booking_id IN $bookingIn AND @ownership_ok=1") }
    [void]$transaction.Add("DELETE FROM booking WHERE ($bookingTupleWhere) AND @ownership_ok=1")
    if ($notificationIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM notification WHERE id IN $notificationIn AND user_id IN $userIdIn AND @ownership_ok=1") }
    if ($blacklistIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM blacklist WHERE id IN $blacklistIn AND user_id IN $userIdIn AND @ownership_ok=1") }
    if ($operationLogIds.Count -gt 0) { [void]$transaction.Add("DELETE FROM operation_log WHERE id IN $operationLogIn AND user_id IN $userIdIn AND @ownership_ok=1") }
    [void]$transaction.Add("DELETE FROM resource_time_rule WHERE resource_id=$resId AND id IN $ruleIn AND @ownership_ok=1")
    [void]$transaction.Add("DELETE FROM resource WHERE id=$resId AND name=$resourceNameSql AND category_id=$categoryId AND description=$resourceDescriptionSql AND @ownership_ok=1")
    [void]$transaction.Add("DELETE FROM resource_category WHERE id=$categoryId AND name=$categoryNameSql AND @ownership_ok=1")
    [void]$transaction.Add("DELETE FROM ``user`` WHERE ($userTupleWhere) AND @ownership_ok=1")
    [void]$transaction.Add('SET @cleanup_ok := IF((' + ($absenceConditions -join ') AND (') + '), 1, 0)')
    [void]$transaction.Add("SET @finish_sql := IF((@ownership_ok=1 OR @already_absent=1) AND @cleanup_ok=1, 'COMMIT', 'ROLLBACK')")
    [void]$transaction.Add('PREPARE t13_teardown_finish FROM @finish_sql')
    [void]$transaction.Add('EXECUTE t13_teardown_finish')
    [void]$transaction.Add('DEALLOCATE PREPARE t13_teardown_finish')
    [void]$transaction.Add("SELECT CONCAT('T13TD:', @ownership_ok, ':', @already_absent, ':', @cleanup_ok)")
    $transactionResult = Invoke-RootSql -File $composeFile -Query (($transaction -join ";`n") + ';')
    $transactionLines = @($transactionResult -split "`r?`n")
    $teardownCommitted = ($transactionLines -contains 'T13TD:1:0:1')
    $teardownAlreadyClean = ($transactionLines -contains 'T13TD:0:1:1')
    if (-not $teardownCommitted -and -not $teardownAlreadyClean) {
        Set-Content -LiteralPath (Join-Path $Artifacts 'teardown-evidence.txt') -Value (@(
            "scope: usernames=$($usernames -join ',') purposes=$($purposes -join ',') resourceId=$resId",
            "preTotals(booking,user)=$preTotals",
            'transaction: ROLLED BACK - ownership, complete absence, or cleanup condition failed',
            'verdict: REFUSED / NO PARTIAL TEARDOWN COMMITTED'
        ) -join "`r`n") -Encoding utf8NoBOM
        Write-Warning 'REFUSED: transactional teardown rolled back after ownership or cleanup mismatch.'
        return 2
    }

    # Verify zero leftovers within scope.
    $leftUsers  = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM ``user`` WHERE $userCollisionWhere").Trim()
    $leftBook   = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking WHERE user_id IN $userIdIn OR resource_id=$resId OR $bookingCollisionWhere").Trim()
    $leftBookChildren = (Invoke-RootSql -File $composeFile -Query "SELECT (SELECT COUNT(*) FROM violation_record WHERE user_id IN $userIdIn OR booking_id IN $bookingIn), (SELECT COUNT(*) FROM approval_record WHERE approver_id IN $userIdIn OR booking_id IN $bookingIn), (SELECT COUNT(*) FROM booking_slot WHERE resource_id=$resId OR booking_id IN $bookingIn)").Trim()
    $leftUserChildren = (Invoke-RootSql -File $composeFile -Query "SELECT (SELECT COUNT(*) FROM notification WHERE user_id IN $userIdIn), (SELECT COUNT(*) FROM blacklist WHERE user_id IN $userIdIn OR operator_id IN $userIdIn), (SELECT COUNT(*) FROM operation_log WHERE user_id IN $userIdIn)").Trim()
    $leftRes    = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE category_id=$categoryId OR $resourceCollisionWhere").Trim()
    $leftResourceChildren = (Invoke-RootSql -File $composeFile -Query "SELECT (SELECT COUNT(*) FROM resource_time_rule WHERE resource_id=$resId), (SELECT COUNT(*) FROM resource_closure WHERE resource_id=$resId)").Trim()
    $leftCat    = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE parent_id=$categoryId OR $categoryCollisionWhere").Trim()
    $postTotals = Invoke-RootSql -File $composeFile -Query 'SELECT (SELECT COUNT(*) FROM booking), (SELECT COUNT(*) FROM `user`)'
    $ok = ($leftUsers -eq '0' -and $leftBook -eq '0' -and $leftBookChildren -eq "0`t0`t0" -and
        $leftUserChildren -eq "0`t0`t0" -and $leftRes -eq '0' -and $leftResourceChildren -eq "0`t0" -and $leftCat -eq '0')

    Set-Content -LiteralPath (Join-Path $Artifacts 'teardown-evidence.txt') -Value (@(
        "scope: usernames=$($usernames -join ',') purposes=$($purposes -join ',') resourceId=$resId",
        "preTotals(booking,user)=$preTotals",
        "postTotals(booking,user)=$postTotals",
        "transactionMarker=$(if ($teardownCommitted) { 'T13TD:1:0:1' } else { 'T13TD:0:1:1' })",
        "leftover users=$leftUsers bookings=$leftBook bookingChildren=$leftBookChildren userChildren=$leftUserChildren resource=$leftRes resourceChildren=$leftResourceChildren category=$leftCat",
        "verdict: $(if ($ok -and $teardownAlreadyClean) { 'TEARDOWN ALREADY CLEAN (idempotent no-op)' } elseif ($ok) { 'TEARDOWN CLEAN (transactional exact scope)' } else { 'POST-COMMIT LEFTOVERS - operator action required' })"
    ) -join "`r`n") -Encoding utf8NoBOM
    if (-not $ok) { return 1 }
    return 0
}

# ---- Orchestration --------------------------------------------------------------
$script:secret = $null
$script:recoveryJournal = $null
$modeResults = [ordered]@{}
$modesToRun = @()
switch ($Mode) {
    'All'         { $modesToRun = @('Setup', 'StudentFlow', 'Teardown') }
    default       { $modesToRun = @($Mode) }
}

# Labeled loop: Setup failure in All mode stops the remaining modes here, but
# the finally block still attempts Teardown (fixture map exists) + secret
# deletion. StudentFlow failure does NOT skip Teardown.
try {
    :modes foreach ($m in $modesToRun) {
        Write-Output ("==== demo mode: {0} ====" -f $m)
        $rc = 0
        if ($m -eq 'Setup') {
            $rc = Invoke-DemoSetup
            if ($rc -ne 0) {
                $overall = $rc
                if ($Mode -eq 'All') { break modes }
            }
        } elseif ($m -eq 'StudentFlow') {
            $rc = Invoke-DemoStudentFlow
            if ($rc -ne 0) { $overall = $rc }
        } elseif ($m -eq 'Teardown') {
            $rc = Invoke-DemoTeardown
            if ($rc -ne 0) { $overall = $rc }
        }
        $modeResults[$m] = $rc
    }
}
finally {
    # Runs on EVERY path, including terminating errors: the temp secret JSON is
    # deleted and its deletion verified; in All mode a teardown attempt is made
    # whenever this run created a fixture map (idempotent second pass is a
    # no-op delete of nothing).
    Remove-SecretFileOrFail
    if ($Mode -eq 'All' -and (Test-Path -LiteralPath $createdMapPath)) {
        try {
            Write-Output 'finally: attempting teardown of the demo fixture...'
            $rcT = Invoke-DemoTeardown
            if ($rcT -ne 0) {
                Write-Warning 'finally teardown reported a problem.'
                if ($overall -eq 0) { $overall = $rcT }
            }
        } catch {
            Write-Warning ("finally teardown raised: {0}" -f $_.Exception.Message)
            if ($overall -eq 0) { $overall = 1 }
        }
    } elseif ($Mode -in @('All', 'Setup') -and (Test-Path -LiteralPath $recoveryJournalPath)) {
        try {
            Write-Output 'finally: attempting compensation of journaled partial fixture rows...'
            $rcP = Invoke-DemoPartialRecovery
            if ($rcP -ne 0) {
                Write-Warning 'finally partial recovery reported a problem; review the journal and recovery scope.'
                if ($overall -eq 0) { $overall = $rcP }
            }
        } catch {
            Write-Warning ("finally partial recovery raised: {0}" -f $_.Exception.Message)
            if ($overall -eq 0) { $overall = 1 }
        }
    } elseif ($Mode -in @('All', 'Setup') -and (Test-Path -LiteralPath $recoveryScopePath)) {
        Write-Warning ("finally: Setup failed before any recoverable owner tuple was journaled. Review exact non-secret recovery scope: {0}" -f $recoveryScopePath)
        if ($overall -eq 0) { $overall = 1 }
    }
}

if ($secretCleanupFailed) { exit 1 }
if ($overall -ne 0) { Write-Warning ("DEMO RUN FAILED/BLOCKED (exit {0})." -f $overall); exit $overall }
Write-Output 'DEMO RUN COMPLETE.'
exit 0
