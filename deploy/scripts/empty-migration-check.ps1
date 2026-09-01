#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane A: apply V001-V005 to two isolated, disposable MySQL 8 containers
    and prove identical, seed-free schema definitions.
.DESCRIPTION
    Reusable local verification lane; a run is evidence only when its recorded
    result exits zero.

    Guarantees by design:
      * Two temporary containers with run-id-scoped names/volumes; NO published
        ports (--network none); repository sql/ bind-mounted READ-ONLY.
      * Password hygiene: docker run resolves MYSQL_ROOT_PASSWORD from a
        temporarily-set HOST process env passed by NAME ONLY (-e
        MYSQL_ROOT_PASSWORD) - the value never appears in script argv. All mysql
        client access runs INSIDE the container via
        `sh -c 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" ...'` reusing the container's
        own env; host env is restored in finally.
      * Never touches compose volumes (mysql-data / redis-data) or any public
        endpoint; only local docker exec access.
      * Per-database assertions: exactly the 12 contract tables, ENGINE=InnoDB,
        utf8mb4 collation prefix, zero rows everywhere (seed-free), plus EVERY
        declared key extracted from DDL - PRIMARY KEY (as index name PRIMARY,
        non-unique), UNIQUE KEY and plain KEY alike - matched by exact table
        name, index name, NON_UNIQUE flag and ordered column list.
      * Normalized information_schema fingerprints (SHA256) compared between both
        databases = identical-definition proof.
      * Evidence under deploy\artifacts\<RunId>\ ; containers removed always;
        volumes removed unless failing run started with -KeepOnFailure.
    Exit codes: 0 pass | 1 environment/precondition | 2 assertion failure.
#>
[CmdletBinding()]
param(
    [string]$MySqlImage = 'mysql:8.0.40',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [switch]$KeepOnFailure,
    [string]$SqlDir = '',
    [string]$ArtifactRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$startedAt = (Get-Date).ToString('o')

# RunId feeds container/volume names, artifact paths and SQL text: strictly bound.
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

if (-not $SqlDir)       { $SqlDir       = (Resolve-Path (Join-Path $PSScriptRoot '..\..\sql')).Path }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
$Artifacts = Join-Path $ArtifactRoot $RunId
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$ExpectedTables = @('user','resource_category','resource','resource_time_rule','resource_closure',
    'booking','booking_slot','approval_record','violation_record','blacklist',
    'notification','operation_log')
$MigrationFiles = @('V001__create_database.sql','V002__create_user_and_resource_tables.sql',
    'V003__create_booking_tables.sql','V004__create_support_tables.sql',
    'V005__post_seed_placeholder.sql')

function Invoke-Docker {
    # Failure text deliberately EXCLUDES argv (defensive credential hygiene).
    param([string[]]$DockerArgs)
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("docker exited {0} (argv redacted)" -f $LASTEXITCODE)
    }
}

function Invoke-DockerCapture {
    param([string[]]$DockerArgs)
    $out = & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("docker exited {0} (argv redacted)" -f $LASTEXITCODE)
    }
    return ($out -join "`n")
}

# Query travels via an exec-local env var; authentication reuses the root
# password ALREADY present inside the container - no secret on any host argv.
function Invoke-Mysql {
    param([string]$Container, [string]$Query)
    return Invoke-DockerCapture -DockerArgs @(
        'exec', '-e', "T13Q=$Query", $Container,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -uroot --batch --skip-column-names -e "$T13Q"')
}

function Wait-MysqlReady {
    param([string]$Container, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $consecutiveSuccesses = 0
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-Mysql -Container $Container -Query 'SELECT 1' 2>$null
            $consecutiveSuccesses++
            if ($consecutiveSuccesses -ge 3) { return }
        } catch {
            $consecutiveSuccesses = 0
        }
        Start-Sleep -Seconds 3
    }
    throw "container ${Container}: readiness timeout after ${TimeoutSeconds}s"
}

function Get-FingerprintText {
    param([string]$Container)
    $tblQ = "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
    $colQ = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, IFNULL(COLUMN_DEFAULT,'NULL') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='booking_db' ORDER BY TABLE_NAME, ORDINAL_POSITION"
    $idxQ = "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='booking_db' GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME"
    $text  = "== TABLES ==" + "`n"
    $text += (Invoke-Mysql -Container $Container -Query $tblQ) + "`n"
    $text += "== COLUMNS ==" + "`n"
    $text += (Invoke-Mysql -Container $Container -Query $colQ) + "`n"
    $text += "== INDEXES ==" + "`n"
    $text += (Invoke-Mysql -Container $Container -Query $idxQ)
    return $text
}

# ---- Preconditions ------------------------------------------------------------
foreach ($f in $MigrationFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $SqlDir $f))) {
        Write-Error "missing migration file '$f' - fail closed"
        exit 1
    }
}
$ddlRaw = ''
foreach ($f in $MigrationFiles) { $ddlRaw += (Get-Content (Join-Path $SqlDir $f) -Raw) + "`n" }

# Per-table slice extraction: table bodies cannot bleed into each other and a
# PRIMARY KEY listed before UNIQUE/KEY can no longer break parsing.
$ddlTables     = New-Object System.Collections.Generic.List[string]
$ddlIndexes    = New-Object System.Collections.Generic.List[object]
$tableMatches  = [regex]::Matches($ddlRaw, 'CREATE TABLE\s+`(?<t>\w+)`')
for ($m = 0; $m -lt $tableMatches.Count; $m++) {
    $tbl   = $tableMatches[$m].Groups['t'].Value
    $start = $tableMatches[$m].Index
    $end   = if ($m + 1 -lt $tableMatches.Count) { $tableMatches[$m + 1].Index } else { $ddlRaw.Length }
    $segment = $ddlRaw.Substring($start, $end - $start)
    $ddlTables.Add($tbl)
    foreach ($pk in [regex]::Matches($segment, 'PRIMARY\s+KEY\s+\((?<c>[^)]+)\)')) {
        $ddlIndexes.Add([pscustomobject]@{
            Table  = $tbl
            Key    = 'PRIMARY'
            Unique = $true
            Cols   = (($pk.Groups['c'].Value -replace '\s', '') -replace '[`"]', '')
        })
    }
    foreach ($ix in [regex]::Matches($segment, '(?<u>UNIQUE\s+)?KEY\s+`(?<k>\w+)`\s*\((?<c>[^)]+)\)')) {
        $ddlIndexes.Add([pscustomobject]@{
            Table     = $tbl
            Key       = $ix.Groups['k'].Value
            Unique    = [bool]$ix.Groups['u'].Success
            Cols      = (($ix.Groups['c'].Value -replace '\s', '') -replace '[`"]', '')
        })
    }
}
if ($ddlTables.Count -ne 12) {
    Write-Error ("DDL parse found {0} tables; contract expects exactly 12 - fail closed" -f $ddlTables.Count)
    exit 1
}
Write-Output ("DDL declarations: {0} tables, {1} keys (PRIMARY+UNIQUE+plain KEY)" -f $ddlTables.Count, $ddlIndexes.Count)

# Only after migration inputs pass local validation may Docker be inspected.
# This lane is local-only and MUST NOT implicitly contact a registry.
& docker image inspect $MySqlImage *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Warning "BLOCKED: MySQL image '$MySqlImage' is not cached locally; implicit pull is forbidden"
    exit 3
}
$imageId = (& docker image inspect $MySqlImage --format '{{.Id}}' | Select-Object -First 1)
$repoDigests = @(& docker image inspect $MySqlImage --format '{{range .RepoDigests}}{{println .}}{{end}}') | Where-Object { $_ }
$gitHead = (& git -C $repoRoot rev-parse HEAD | Select-Object -First 1)
$dockerClientVersion = (& docker version --format '{{.Client.Version}}' | Select-Object -First 1)
$dockerServerVersion = (& docker version --format '{{.Server.Version}}' | Select-Object -First 1)

# ---- Throwaway credential lifecycle -------------------------------------------
$rngBytes  = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($rngBytes)
$generatedPassword = [Convert]::ToBase64String($rngBytes)
$hadHostPwd = Test-Path Env:MYSQL_ROOT_PASSWORD
$savedHostPwd = if ($hadHostPwd) { $env:MYSQL_ROOT_PASSWORD } else { $null }

$containers = @("t13-empty-a-$RunId", "t13-empty-b-$RunId")
$volumes    = @("t13-empty-a-$RunId-data", "t13-empty-b-$RunId-data")
$results = [ordered]@{
    runId = $RunId
    startedAt = $startedAt
    repositoryHead = $gitHead
    workingDirectory = $repoRoot
    powershellVersion = $PSVersionTable.PSVersion.ToString()
    dockerClientVersion = $dockerClientVersion
    dockerServerVersion = $dockerServerVersion
    mysqlImageRequested = $MySqlImage
    mysqlImageId = $imageId
    mysqlRepoDigests = @($repoDigests)
    expectedDeclaredKeys = $ddlIndexes.Count
    overallPass = $true
    lanes = [ordered]@{}
}

try {
    for ($i = 0; $i -lt 2; $i++) {
        $name   = $containers[$i]
        $volume = $volumes[$i]
        $bindMount = "type=bind,source={0},target=/docker-entrypoint-initdb.d,readonly" -f ((Get-Item $SqlDir).FullName -replace '\\', '/')
        # Name-only -e: value resolved from THIS process env at command build time.
        $env:MYSQL_ROOT_PASSWORD = $generatedPassword
        try {
            Invoke-Docker -DockerArgs @('run', '-d', '--name', $name,
                '--network', 'none', '--pull', 'never',
                '-e', 'MYSQL_ROOT_PASSWORD',
                '--mount', $bindMount,
                '-v', "${volume}:/var/lib/mysql",
                $MySqlImage) | Out-Null
        } finally {
            if ($hadHostPwd) { $env:MYSQL_ROOT_PASSWORD = $savedHostPwd }
            else             { Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue }
        }

        Wait-MysqlReady -Container $name -TimeoutSeconds 300

        $failures = [System.Collections.Generic.List[string]]::new()

        $tableRows    = Invoke-Mysql -Container $name -Query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
        $tablesPresent = $tableRows -split "`n" | Where-Object { $_ } | ForEach-Object { $_.Trim() }
        if (@(Compare-Object -ReferenceObject $ExpectedTables -DifferenceObject $tablesPresent).Count -ne 0) {
            $failures.Add("table set mismatch; got: $($tablesPresent -join ',')")
        }
        $engineRows = Invoke-Mysql -Container $name -Query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND ENGINE<>'InnoDB'"
        if (@($engineRows -split "`n" | Where-Object { $_ }).Count -gt 0) {
            $failures.Add("non-InnoDB tables: $($engineRows.Trim())")
        }
        $charsetRows = Invoke-Mysql -Container $name -Query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='booking_db' AND TABLE_COLLATION NOT LIKE 'utf8mb4%'"
        if (@($charsetRows -split "`n" | Where-Object { $_ }).Count -gt 0) {
            $failures.Add("non-utf8mb4 tables: $($charsetRows.Trim())")
        }

        $rowCounts = [ordered]@{}
        foreach ($t in $ExpectedTables) {
            $c = (Invoke-Mysql -Container $name -Query "SELECT COUNT(*) FROM booking_db.$t").Trim()
            $rowCounts[$t] = $c
            if ($c -ne '0') { $failures.Add("seed-free violation: ${t} has $c rows") }
        }

        # Exact-match EVERY declared index: table, name, uniqueness flag, columns.
        foreach ($ix in $ddlIndexes) {
            $expectedNonUnique = if ($ix.Unique) { '0' } else { '1' }
            $exactRows = (Invoke-Mysql -Container $name -Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MAX(NON_UNIQUE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='booking_db' AND TABLE_NAME='$($ix.Table)' AND INDEX_NAME='$($ix.Key)'").Trim()
            $actualCols = ''; $actualUniqueFlag = ''
            if ($exactRows -match '^(.*?)\t(.*)$') { $actualCols = $Matches[1]; $actualUniqueFlag = $Matches[2] }
            if ($actualCols -eq '' -or $actualCols -eq 'NULL') {
                $failures.Add("index missing: $($ix.Table).$($ix.Key)($($ix.Cols)) unique=$($ix.Unique)")
            } elseif ($actualCols -ne $ix.Cols) {
                $failures.Add("index column mismatch: $($ix.Table).$($ix.Key) expected=($($ix.Cols)) actual=($actualCols)")
            } elseif ($actualUniqueFlag -ne $expectedNonUnique) {
                $failures.Add("index uniqueness mismatch: $($ix.Table).$($ix.Key) expected_non_unique=$expectedNonUnique actual=$actualUniqueFlag")
            }
        }

        Set-Content -LiteralPath (Join-Path $Artifacts "$name-fingerprint.txt") `
            -Value (Get-FingerprintText -Container $name) -Encoding utf8NoBOM

        $results.lanes[$name] = [ordered]@{ failures = @($failures); rowCounts = $rowCounts }
        if ($failures.Count -gt 0) { $results.overallPass = $false }
    }

    $hashA = (Get-FileHash -Algorithm SHA256 (Join-Path $Artifacts "$($containers[0])-fingerprint.txt")).Hash
    $hashB = (Get-FileHash -Algorithm SHA256 (Join-Path $Artifacts "$($containers[1])-fingerprint.txt")).Hash
    $results.identicalSchemaFingerprint = ($hashA -eq $hashB)
    Set-Content -LiteralPath (Join-Path $Artifacts 'sha256.txt') `
        -Value "laneA=$hashA`nlaneB=$hashB" -Encoding utf8NoBOM
    if (-not $results.identicalSchemaFingerprint) { $results.overallPass = $false }
}
finally {
    if ($hadHostPwd) { $env:MYSQL_ROOT_PASSWORD = $savedHostPwd }
    else             { Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue }
    foreach ($name in $containers) {
        & docker @('rm', '-f', $name) *> $null
    }
    if ($KeepOnFailure -and -not $results.overallPass) {
        Write-Warning "KeepOnFailure active: retained volumes $($volumes -join ', ') for operator inspection"
    } else {
        foreach ($vol in $volumes) {
            & docker @('volume', 'rm', '-f', $vol) *> $null
        }
    }
}

$results.finishedAt = (Get-Date).ToString('o')
$results.exitCode = $(if ($results.overallPass) { 0 } else { 2 })
Set-Content -LiteralPath (Join-Path $Artifacts 'result.json') `
    -Value ($results | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM
if ($results.overallPass) {
    Write-Output "EMPTY-MIGRATION-CHECK PASS ($RunId)"
    exit 0
}
Write-Warning "EMPTY-MIGRATION-CHECK FAIL ($RunId)"
exit 2
