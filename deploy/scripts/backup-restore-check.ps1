#Requires -Version 7.0
<#
.SYNOPSIS
    T13 lane B: consistent backup of the local compose MySQL, restore into a
    random isolated database, and evidence-graded comparison.
.DESCRIPTION
    STATIC PLAN - never executed yet (tasks.md 5.2 stays unchecked until real run).

    Guarantees by design:
      * mysqldump --single-transaction (consistent snapshot) executed INSIDE the
        compose mysql container; dump copied out via docker cp to
        deploy\artifacts\<RunId>\ ; remote temp removed afterwards.
      * Password hygiene: the root credential lives ONLY inside the mysql
        container. mysqldump/mysql run via container-side
        `MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"` - no host process reads or carries
        any root password value, and none appears in argv or artifacts.
      * SourceDb is strictly validated: must match ^[A-Za-z0-9_]+$ AND equal
        booking_db before any destructive-capable statement exists.
      * Restore target is a RANDOM isolated database (internally generated safe
        suffix); script REFUSES restore/drop against anything matching the
        source name. Source data/volumes are never dropped or overwritten.
      * BOTH databases must contain exactly the 12 contract tables; comparison
        covers FULL normalized information_schema definitions (tables/columns/
        indexes), plus representative evidence retained: CHECKSUM TABLE diffs,
        booking / booking_slot row counts and id aggregates.
      * Records measured restore elapsed seconds plus operator-fill RPO/RTO
        fields (placeholders - humans own those assumptions).
    Exit codes: 0 pass | 2 verification/assertion failure | 3 blocked (no
    running local stack); unexpected errors abort non-zero.
#>
[CmdletBinding()]
param(
    [string]$ComposeFile = '',
    [string]$Service = 'mysql',
    [string]$SourceDb = 'booking_db',
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# RunId feeds container paths, artifact paths and shell payloads: strictly bound.
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning ("REFUSED: RunId '{0}' fails ^[A-Za-z0-9][A-Za-z0-9_-]{{0,63}}$" -f $RunId)
    exit 2
}

if (-not $ComposeFile)  { $ComposeFile  = (Resolve-Path (Join-Path $PSScriptRoot '..\compose.yml')).Path }
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\artifacts')).Path }
New-Item -ItemType Directory -Path $ArtifactRoot -Force | Out-Null
$Artifacts = Join-Path $ArtifactRoot $RunId
New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null

$ExpectedTables = @('user','resource_category','resource','resource_time_rule','resource_closure',
    'booking','booking_slot','approval_record','violation_record','blacklist',
    'notification','operation_log')

function Redact {
    param([AllowEmptyCollection()][string[]]$Secrets, [string]$Text)
    foreach ($s in $Secrets) { if ($s) { $Text = $Text.Replace($s, '***REDACTED***') } }
    return $Text
}

# ---- Source name safety gate ----------------------------------------------------
if ($SourceDb -notmatch '^[A-Za-z0-9_]+$') {
    Write-Warning "REFUSED: SourceDb '$SourceDb' fails ^[A-Za-z0-9_]+$"
    exit 2
}
if ($SourceDb -ne 'booking_db') {
    Write-Warning "BLOCKED: this lane validates the contract database booking_db only (got '$SourceDb')"
    exit 3
}

# ---- Root credential policy -----------------------------------------------------
# The root password lives ONLY inside the mysql container (MYSQL_ROOT_PASSWORD
# env injected at compose runtime). Every client invocation authenticates via
# container-side `MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"`; this host process never
# reads, requires, or redacts any root password value.

# ---- Locate local stack ------------------------------------------------------------
& docker @('compose', '-f', $ComposeFile, 'ps', '-q', $Service) | Tee-Object -Variable cidLines | Out-Null
if ($LASTEXITCODE -ne 0) { throw "docker compose ps failed" }
$cid = ($cidLines | Where-Object { $_ }) | Select-Object -First 1
if (-not $cid) {
    Write-Warning "BLOCKED: service '$Service' not running for $ComposeFile - start the local stack first"
    exit 3
}

# All client invocations authenticate INSIDE the container by exporting
# MYSQL_PWD from the container's own MYSQL_ROOT_PASSWORD; query text rides an
# exec-local env var. No secret value is placed on any argv.
function Invoke-MysqlExec {
    param([string]$Query)
    $out = & docker @('exec', '-e', "T13Q=$Query", $cid,
        'sh', '-c', 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -uroot --batch --skip-column-names -e "$T13Q"')
    if ($LASTEXITCODE -ne 0) { throw "mysql exec failed (query redacted)" }
    return ($out -join "`n")
}

$results = [ordered]@{ runId = $RunId; sourceDb = $SourceDb; overallPass = $true }
try {
    # ---- Consistent dump inside the container ---------------------------------
    # Single-quoted PS format string keeps ${MYSQL_ROOT_PASSWORD} literal for the
    # container shell; {0}/{1} are regex-validated values only.
    $remoteDump = "/tmp/t13dump-$RunId.sql"
    $dumpPayload = 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysqldump -u root --single-transaction --quick --routines --triggers --databases {0} > {1}' -f $SourceDb, $remoteDump
    & docker @('exec', $cid, 'sh', '-c', $dumpPayload)
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed inside container" }

    $localDump = Join-Path $Artifacts "$SourceDb-backup.sql"
    & docker @('cp', "${cid}:$remoteDump", $localDump)
    if ($LASTEXITCODE -ne 0) { throw "docker cp of dump failed" }
    $dumpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $localDump).Hash

    # ---- Random isolated restore database --------------------------------------
    $suffixBytes = New-Object byte[] 4
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($suffixBytes)
    $ridClean   = ($RunId -replace '[^a-zA-Z0-9]', '')
    $rndClean   = ([Convert]::ToBase64String($suffixBytes) -replace '\+|/|=', '')
    $restoreDb  = "t13_restore_${ridClean}_${rndClean}"
    if (($restoreDb -notmatch '^[A-Za-z0-9_]+$') -or ($restoreDb -eq $SourceDb) -or ($restoreDb -like "$SourceDb*")) {
        throw "refusing unsafe restore database name '$restoreDb'"
    }

    # --databases header contains CREATE DATABASE/USE of the source name; create
    # the isolated db then REWRITE those header lines to point at it.
    $null = Invoke-MysqlExec -Query "CREATE DATABASE IF NOT EXISTS $restoreDb DEFAULT CHARACTER SET utf8mb4"
    $rewritten = Join-Path $Artifacts "$SourceDb-backup.rewritten-for-$restoreDb.sql"
    (Get-Content -LiteralPath $localDump) |
        ForEach-Object { $_ -replace ("CREATE DATABASE .*", "CREATE DATABASE IF NOT EXISTS ``$restoreDb``;") -replace ("USE ``$SourceDb``", "USE ``$restoreDb``") } |
        Set-Content -LiteralPath $rewritten -Encoding utf8NoBOM
    & docker @('cp', $rewritten, "${cid}:/tmp/t13restore-$RunId.sql")
    if ($LASTEXITCODE -ne 0) { throw "docker cp of rewritten dump failed" }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $restorePayload = 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" exec mysql -u root {0} < /tmp/t13restore-{1}.sql' -f $restoreDb, $RunId
    & docker @('exec', $cid, 'sh', '-c', $restorePayload)
    if ($LASTEXITCODE -ne 0) { throw "restore into $restoreDb failed" }
    $sw.Stop()

    # ---- Full normalized definitions compare (source vs restored) -----------------
    function Get-NormalizedDefinitions {
        param([string]$Db)
        $tblQ = "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME, ENGINE, TABLE_COLLATION"
        $colQ = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, IFNULL(COLUMN_DEFAULT,'NULL'), EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$Db' ORDER BY TABLE_NAME, ORDINAL_POSITION"
        $idxQ = "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$Db' GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME"
        return ((Invoke-MysqlExec -Query $tblQ) + "`n" +
                (Invoke-MysqlExec -Query $colQ) + "`n" +
                (Invoke-MysqlExec -Query $idxQ))
    }
    $srcDefs = Get-NormalizedDefinitions -Db $SourceDb
    Set-Content -LiteralPath (Join-Path $Artifacts 'source-definitions.txt') -Value $srcDefs -Encoding utf8NoBOM

    # ---- Exact 12-table contract on BOTH sides --------------------------------------
    function Test-ExactTwelveTables {
        param([string]$Db)
        $rows = Invoke-MysqlExec -Query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Db' AND TABLE_TYPE='BASE TABLE'"
        $names = $rows -split "`n" | Where-Object { $_ } | ForEach-Object { $_.Trim() }
        return (@(Compare-Object -ReferenceObject $ExpectedTables -DifferenceObject $names).Count -eq 0)
    }
    $srcTwelve = Test-ExactTwelveTables -Db $SourceDb
    $rstTwelve = Test-ExactTwelveTables -Db $restoreDb
    if (-not $srcTwelve) { $results.overallPass = $false; Write-Warning "source does not match the 12-table contract" }
    if (-not $rstTwelve) { $results.overallPass = $false; Write-Warning "restored DB does not match the 12-table contract" }

    $rstDefs = Get-NormalizedDefinitions -Db $restoreDb | ForEach-Object { $_ -replace ('`' + $restoreDb + '`'), '`booking_db`' }
    $definitionsIdentical = (($srcDefs -split "`n" | Where-Object { $_ }) -join "`n") -eq (($rstDefs -split "`n" | Where-Object { $_ }) -join "`n")
    Set-Content -LiteralPath (Join-Path $Artifacts 'restore-definitions.txt') -Value ($rstDefs -join "`n") -Encoding utf8NoBOM

    # ---- Representative checksum/count evidence ---------------------------------------
    $checksumDiffs = [System.Collections.Generic.List[string]]::new()
    foreach ($t in $ExpectedTables) {
        $csSrc = (Invoke-MysqlExec -Query "CHECKSUM TABLE booking_db.$t").Split("`t")[-1].Trim()
        $csRst = (Invoke-MysqlExec -Query "CHECKSUM TABLE $restoreDb.$t").Split("`t")[-1].Trim()
        if ($csSrc -ne $csRst) { $checksumDiffs.Add("$t src=$csSrc rst=$csRst") }
    }

    $bookingRows   = (Invoke-MysqlExec -Query "SELECT COUNT(*) FROM booking_db.booking").Trim()
    $slotRows      = (Invoke-MysqlExec -Query "SELECT COUNT(*) FROM booking_db.booking_slot").Trim()
    $bookingAggSrc = (Invoke-MysqlExec -Query "SELECT COALESCE(BIT_XOR(CRC32(id)),0) FROM booking_db.booking").Trim()
    $slotAggSrc    = (Invoke-MysqlExec -Query "SELECT COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,resource_id,slot_time))),0) FROM booking_db.booking_slot").Trim()
    $bookingRowsR  = (Invoke-MysqlExec -Query "SELECT COUNT(*) FROM $restoreDb.booking").Trim()
    $slotRowsR     = (Invoke-MysqlExec -Query "SELECT COUNT(*) FROM $restoreDb.booking_slot").Trim()
    $bookingAggR   = (Invoke-MysqlExec -Query "SELECT COALESCE(BIT_XOR(CRC32(id)),0) FROM $restoreDb.booking").Trim()
    $slotAggR      = (Invoke-MysqlExec -Query "SELECT COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,resource_id,slot_time))),0) FROM $restoreDb.booking_slot").Trim()

    $results.exactTwelveTables       = [ordered]@{ source = [bool]$srcTwelve; restored = [bool]$rstTwelve }
    $results.definitionsIdentical    = [bool]$definitionsIdentical
    $results.checksumDiffs           = @($checksumDiffs)
    $results.representative = [ordered]@{
        booking      = [ordered]@{ sourceRows = $bookingRows; restoredRows = $bookingRowsR; sourceAgg = $bookingAggSrc; restoredAgg = $bookingAggR }
        booking_slot = [ordered]@{ sourceRows = $slotRows;  restoredRows = $slotRowsR;  sourceAgg = $slotAggSrc;  restoredAgg = $slotAggR }
    }
    $results.dumpSha256              = $dumpHash
    $results.measuredRestoreSeconds  = [math]::Round($sw.Elapsed.TotalSeconds, 3)
    # Operator-owned assumptions; intentionally blank placeholders.
    $results.rpoAssumptionOperatorField = '<operator fills: acceptable data-loss window>'
    $results.rtoAssumptionOperatorField = '<operator fills: acceptable recovery window>'

    if (-not $definitionsIdentical)                                { $results.overallPass = $false }
    if ($checksumDiffs.Count -gt 0)                               { $results.overallPass = $false }
    if ($bookingRows -ne $bookingRowsR -or $slotRows -ne $slotRowsR) { $results.overallPass = $false }
    if ($bookingAggSrc -ne $bookingAggR -or $slotAggSrc -ne $slotAggR) { $results.overallPass = $false }
}
finally {
    # Remote temp cleanup only; NEVER touches source schema or volumes.
    try {
        if ($cid) {
            & docker @('exec', $cid, 'sh', '-c',
                ("rm -f /tmp/t13dump-{0}.sql /tmp/t13restore-{0}.sql" -f $RunId)) *> $null
            if ($LASTEXITCODE -ne 0) { Write-Warning "remote temp cleanup exited nonzero" }
        }
    } catch { Write-Warning "remote temp cleanup warning" }
    if (Get-Variable -Name restoreDb -ErrorAction SilentlyContinue) {
        try {
            if (($restoreDb -ne $SourceDb) -and ($restoreDb -notlike "$SourceDb*")) {
                $null = Invoke-MysqlExec -Query "DROP DATABASE IF EXISTS $restoreDb"
            } else {
                Write-Warning "cleanup skipped: restore db name failed safety guard"
            }
        } catch { Write-Warning "restore-db cleanup raised a warning (server may be stopped)" }
    }
}

$outJson = ($results | ConvertTo-Json -Depth 6)
# Defense-in-depth scrub before writing artifacts.
$secretPool = @($env:T13_STUDENT_TOKEN) | Where-Object { $_ }
Set-Content -LiteralPath (Join-Path $Artifacts 'result.json') `
    -Value (Redact -Secrets $secretPool -Text $outJson) -Encoding utf8NoBOM
Write-Output ("Backup file: {0} (sha256 recorded)" -f $localDump)
if ($results.overallPass) { Write-Output "BACKUP-RESTORE-CHECK PASS ($RunId)"; exit 0 }
Write-Warning "BACKUP-RESTORE-CHECK FAIL ($RunId)"
exit 2
