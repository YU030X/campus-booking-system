#requires -Version 5.1
<#
T11 admin-operations reusable test entry. Modes:
  Check - repository/static checks (boundary ownership + git diff --check)
  List  - enumerate pure test cases
  Unit  - run node --test against booking-web/tests/admin-operations
  All   - Check + List + Unit (default), stop on first failure
Artifacts (logs/screenshots/credentials) are written to artifacts\ and git-ignored locally.
#>
[CmdletBinding()]
param(
    [ValidateSet('Check', 'List', 'Unit', 'All')]
    [string]$Mode = 'All'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$webRoot = Join-Path $repoRoot 'booking-web'
$testDir = Join-Path $webRoot 'tests\admin-operations'
$artifactsDir = Join-Path $PSScriptRoot 'artifacts'
if (-not (Test-Path $artifactsDir)) { New-Item -ItemType Directory -Path $artifactsDir | Out-Null }

function Assert-Boundary {
    Write-Host '[Check] verifying changed-file ownership boundary...'
    $diffOutput = & git -C $repoRoot diff --check
    if ($LASTEXITCODE -ne 0 -or $diffOutput) {
        throw "[Check] git diff --check reported issues:`n$($diffOutput -join "`n")"
    }
    $allow = @(
        '^booking-web/src/api/adminUsers\.js$',
        '^booking-web/src/stores/adminUsers\.js$',
        '^booking-web/src/components/admin/users/',
        '^booking-web/src/views/admin/users/',
        '^booking-web/tests/admin-operations/',
        '^scripts/tests/t11/',
        '^scripts/tests/README\.md$',
        '^openspec/changes/add-web-admin-operations/tasks\.md$'
    )
    $changed = & git -C $repoRoot status --porcelain=v1
    foreach ($line in $changed) {
        $path = ($line.Substring(3) -replace '"', '')
        $normalized = $path -replace '\\', '/'
        if ($line.StartsWith('??') -and $normalized -notmatch '\.(mjs|ps1|md|gitignore)$' -and $normalized -notmatch '/$') { continue }
        if ($normalized -match '^docs/') { continue }
        $ok = $false
        foreach ($pattern in $allow) { if ($normalized -match $pattern) { $ok = $true; break } }
        if (-not $ok) { throw "[Check] changed path outside T11 write scope: $path" }
    }
    Write-Host '[Check] boundary OK'
}

function Invoke-List {
    Write-Host '[List] enumerated cases:'
    Get-ChildItem -Path $testDir -Filter '*.test.mjs' | Sort-Object Name | ForEach-Object {
        Write-Host "-- $($_.Name)"
        Select-String -Path $_.FullName -Pattern "^test\('([^']+)'" | ForEach-Object {
            Write-Host ("     * " + $_.Matches[0].Groups[1].Value)
        }
    }
}

function Invoke-Unit {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw '[Unit] node not found in PATH' }
    Write-Host ('[Unit] node ' + (node --version))
    Push-Location $webRoot
    try {
        $log = Join-Path $artifactsDir 'unit.log'
        & node --test --test-reporter=spec 'tests/admin-operations/**/*.test.mjs' 2>&1 | Tee-Object -FilePath $log
        if ($LASTEXITCODE -ne 0) { throw "[Unit] node --test failed (see $log)" }
    } finally {
        Pop-Location
    }
}

switch ($Mode) {
    'Check' { Assert-Boundary }
    'List' { Invoke-List }
    'Unit' { Invoke-Unit }
    'All' {
        Assert-Boundary
        Invoke-List
        Invoke-Unit
    }
}
Write-Host "done: $Mode"
