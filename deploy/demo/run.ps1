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
        (wait window optional). Non-secret fixture-map.json records ids,
        usernames, purpose strings, timestamps - never passwords.
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
        finally still attempts Teardown (if a fixture map exists) and secret
        deletion. Standalone StudentFlow needs no fixture password; standalone
        Teardown requires an explicit fixture-map.json path.
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

$secretFile = $null
$secretCleanupFailed = $false
$createdMapPath = (Join-Path $Artifacts 'fixture-map.json')
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
    $categoryName = ('T13D-' + $RunId)
    if ($categoryName.Length -gt 50) { $categoryName = $categoryName.Substring(0, 50) }
    $expectedUsernames = @('admin', 'student', 'intruder') | ForEach-Object { "${userPrefix}_$_" }
    $expectedUserIn = '(' + (@($expectedUsernames | ForEach-Object { "'$_'" }) -join ', ') + ')'
    $expectedPurposeIn = "('${purposePrefix}pending', '${purposePrefix}past-confirmed')"
    $existingUsers = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM ``user`` WHERE username IN $expectedUserIn").Trim()
    $existingCategory = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE name='$categoryName'").Trim()
    $existingResource = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE name='$resourceName'").Trim()
    $existingBookings = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking WHERE purpose IN $expectedPurposeIn").Trim()
    if ($existingUsers -ne '0' -or $existingCategory -ne '0' -or $existingResource -ne '0' -or $existingBookings -ne '0') {
        Write-Warning 'REFUSED: demo RunId namespace is not empty; recover/review the exact prior scope before retrying.'
        return 2
    }

    # 2) Register minimal users via the local API (no PII: optional fields null).
    $users = [ordered]@{}
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
    }

    # ids via SQL (values validated numeric before reuse)
    $ids = [ordered]@{}
    foreach ($role in $users.Keys) {
        $u = $users[$role].username
        $id = Invoke-RootSql -File $composeFile -Query "SELECT id FROM ``user`` WHERE username='$u'"
        if ($id -notmatch '^\d+$') { throw "register did not persist $role user" }
        $ids[$role] = $id
    }

    # 3) Promote admin via container-side SQL.
    $null = Invoke-RootSql -File $composeFile -Query "UPDATE ``user`` SET role='ADMIN' WHERE id=$($ids['admin']) AND username='$($users['admin'].username)'"

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

    $resBody = @{
        categoryId = $categoryId; name = $resourceName; location = 'T13-DEMO'
        capacity = 1; description = 'T13 ephemeral demo approval room'
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

    # 7) PAST CONFIRMED booking via direct SQL - EPHEMERAL-SETUP-NOT-ACCEPTANCE-
    #    EVIDENCE. Params are validated ids + known literals only. The OWNER
    #    no-show scan (<=1/min) turns it into violation + credit deduction; we
    #    wait briefly and record the outcome without asserting.
    $pastStart = (Get-Date).AddHours(-2).ToString('yyyy-MM-dd HH:mm:ss')
    $pastEnd   = (Get-Date).AddHours(-1).ToString('yyyy-MM-dd HH:mm:ss')
    $null = Invoke-RootSql -File $composeFile -Query ("INSERT INTO ``booking`` (booking_no, user_id, resource_id, start_time, end_time, purpose, status) SELECT CONCAT('T13DEMO', LPAD(id, 8, '0')), {0}, {1}, '{2}', '{3}', '{4}past-confirmed', 'CONFIRMED' FROM ``user`` WHERE id = {0}" -f $ids['student'], $resId, $pastStart, $pastEnd, $purposePrefix)
    $pastBookingId = (Invoke-RootSql -File $composeFile -Query "SELECT id FROM ``booking`` WHERE purpose='${purposePrefix}past-confirmed'").Trim()
    if ($pastBookingId -notmatch '^\d+$') { throw 'past CONFIRMED booking seed failed' }
    # Two 30-minute slots for the 60-minute window (matches owner splitter shape).
    $null = Invoke-RootSql -File $composeFile -Query "INSERT INTO ``booking_slot`` (resource_id, slot_time, booking_id) SELECT resource_id, start_time, id FROM ``booking`` WHERE id=$pastBookingId"
    $null = Invoke-RootSql -File $composeFile -Query "INSERT INTO ``booking_slot`` (resource_id, slot_time, booking_id) SELECT resource_id, DATE_ADD(start_time, INTERVAL 30 MINUTE), id FROM ``booking`` WHERE id=$pastBookingId"
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

    # 8) fixture-map.json: NON-SECRET facts only (ids/usernames/purposes/times).
    $map = [ordered]@{
        runId = $RunId
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
        demoTimeRuleIds = $ruleIds
        pendingBookingId = $pendingBookingId
        pastConfirmedBookingId = $pastBookingId
        pastWindow = [ordered]@{ start = $pastStart; end = $pastEnd }
        noShowState = $noShowState
        t08SeedReference = $seedPath
        createdAt = (Get-Date).ToString('o')
    }
    Set-Content -LiteralPath $createdMapPath -Value ($map | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM
    # Make the exact map available to the same process's StudentFlow/finally
    # teardown without relying on case-insensitive $MapPath/$mapPath aliases.
    $MapPath = $createdMapPath

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

function Invoke-DemoTeardown {
    if (-not $MapPath -or -not (Test-Path -LiteralPath $MapPath)) {
        Write-Warning 'BLOCKED: Teardown requires -MapPath to an existing fixture-map.json (never guess scope).'
        return 3
    }
    $map = Get-Content -LiteralPath $MapPath -Raw | ConvertFrom-Json
    if ([string]$map.runId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$' -or
        [string]$map.userPrefix -ne ('t13demo_' + ([string]$map.runId -replace '-', '_')) -or
        [string]$map.purposePrefix -ne ("T13DEMO:$($map.runId):") -or
        [string]$map.userPrefix -notmatch '^t13demo_[A-Za-z0-9_]+$') {
        Write-Warning 'REFUSED: fixture map namespace markers are invalid.'
        return 2
    }
    $resId = [string]$map.demoResourceId
    $resourceNameFromMap = [string]$map.demoResourceName
    $categoryNameFromMap = [string]$map.demoCategoryName
    if (-not $resourceNameFromMap -or $resourceNameFromMap -ne ("T13 DEMO $($map.runId) approval room") -or
        -not $categoryNameFromMap -or $categoryNameFromMap -ne ("T13D-$($map.runId)")) {
        Write-Warning 'REFUSED: fixture map resource name does not match its run namespace.'
        return 2
    }
    $usernames = @(
        [string]$map.users.admin.username,
        [string]$map.users.student.username,
        [string]$map.users.intruder.username
    )
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

    # Validate every destructive root tuple before the first DELETE. Numeric ids
    # alone are insufficient because a tampered map could otherwise point at a
    # foreign booking/resource/user. Exact purpose, username and ownership links
    # must all match the map's deterministic namespace.
    $ownedUsers = (Invoke-RootSql -File $composeFile -Query ("SELECT COUNT(*) FROM ``user`` WHERE (id={0} AND username='{1}') OR (id={2} AND username='{3}') OR (id={4} AND username='{5}')" -f `
        $adminId, $usernames[0], $studId, $usernames[1], $intrId, $usernames[2])).Trim()
    $ownedResource = (Invoke-RootSql -File $composeFile -Query ("SELECT COUNT(*) FROM resource r JOIN resource_category c ON c.id=r.category_id WHERE r.id={0} AND r.name='{1}' AND c.id={2} AND c.name='{3}'" -f `
        $resId, $resourceNameFromMap, $categoryId, $categoryNameFromMap)).Trim()
    $ownedBookings = (Invoke-RootSql -File $composeFile -Query ("SELECT COUNT(*) FROM booking WHERE (id={0} AND purpose='{1}' AND user_id={2} AND resource_id={3}) OR (id={4} AND purpose='{5}' AND user_id={2} AND resource_id={3})" -f `
        $bookingIds[0], $purposes[0], $studId, $resId, $bookingIds[1], $purposes[1])).Trim()
    if ($ownedUsers -ne '3' -or $ownedResource -ne '1' -or $ownedBookings -ne '2') {
        Write-Warning 'REFUSED: fixture map ownership tuples do not match current database rows; zero teardown deletes executed.'
        return 2
    }

    $preTotals = Invoke-RootSql -File $composeFile -Query 'SELECT (SELECT COUNT(*) FROM booking), (SELECT COUNT(*) FROM `user`)'

    # Children before parents; STRICTLY fixture-scoped: exact usernames and
    # exact purposes/user ids only. The demo resource id is used only for
    # deleting the fixture resource itself; it must never broaden booking
    # deletion to unrelated bookings made against that resource. NEVER drop a
    # database, volume, or foreign row. No LIKE wildcards are used.
    $quotedUsernames = @($usernames | ForEach-Object { "'" + ($_ -replace "'", "''") + "'" })
    $userIn = '(' + ($quotedUsernames -join ', ') + ')'
    $quotedPurposes = @($purposes | ForEach-Object { "'" + ($_ -replace "'", "''") + "'" })
    $purposeIn = '(' + ($quotedPurposes -join ', ') + ')'
    $bookingIn = '(' + ($bookingIds -join ', ') + ')'
    $scoped = "booking.id IN $bookingIn"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE violation_record FROM violation_record JOIN booking ON violation_record.booking_id = booking.id WHERE $scoped"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE approval_record FROM approval_record JOIN booking ON approval_record.booking_id = booking.id WHERE $scoped"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE booking_slot FROM booking_slot JOIN booking ON booking_slot.booking_id = booking.id WHERE $scoped"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE booking FROM booking WHERE $scoped"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE notification FROM notification JOIN ``user`` ON notification.user_id = ``user``.id WHERE ``user``.username IN $userIn"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE blacklist FROM blacklist JOIN ``user`` ON blacklist.user_id = ``user``.id WHERE ``user``.username IN $userIn"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE FROM resource_time_rule WHERE resource_id = $resId"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE FROM resource_closure WHERE resource_id = $resId"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE FROM resource WHERE id = $resId AND name = '$resourceNameFromMap'"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE FROM resource_category WHERE id = $categoryId AND name = '$categoryNameFromMap'"
    $null = Invoke-RootSql -File $composeFile -Query "DELETE FROM ``user`` WHERE username IN $userIn"

    # Verify zero leftovers within scope.
    $leftUsers  = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM ``user`` WHERE username IN $userIn").Trim()
    $leftBook   = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM booking WHERE id IN $bookingIn").Trim()
    $leftRes    = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource WHERE id = $resId AND name = '$resourceNameFromMap'").Trim()
    $leftCat    = (Invoke-RootSql -File $composeFile -Query "SELECT COUNT(*) FROM resource_category WHERE id = $categoryId AND name = '$categoryNameFromMap'").Trim()
    $postTotals = Invoke-RootSql -File $composeFile -Query 'SELECT (SELECT COUNT(*) FROM booking), (SELECT COUNT(*) FROM `user`)'
    $ok = ($leftUsers -eq '0' -and $leftBook -eq '0' -and $leftRes -eq '0' -and $leftCat -eq '0')

    Set-Content -LiteralPath (Join-Path $Artifacts 'teardown-evidence.txt') -Value (@(
        "scope: usernames=$($usernames -join ',') purposes=$($purposes -join ',') resourceId=$resId",
        "preTotals(booking,user)=$preTotals",
        "postTotals(booking,user)=$postTotals",
        "leftover users=$leftUsers leftover scoped bookings=$leftBook leftover demo resource=$leftRes leftover demo category=$leftCat",
        "verdict: $(if ($ok) { 'TEARDOWN CLEAN (scope-limited)' } else { 'LEFTOVERS REMAIN - operator action required' })"
    ) -join "`r`n") -Encoding utf8NoBOM
    if (-not $ok) { return 1 }
    return 0
}

# ---- Orchestration --------------------------------------------------------------
$script:secret = $null
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
    }
}

if ($secretCleanupFailed) { exit 1 }
if ($overall -ne 0) { Write-Warning ("DEMO RUN FAILED/BLOCKED (exit {0})." -f $overall); exit $overall }
Write-Output 'DEMO RUN COMPLETE.'
exit 0
