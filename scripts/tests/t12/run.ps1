# T12 operation-log slice test harness (Windows-first entry point).
# Reusable acceptance/regression harness for scripts/tests/t12 scope.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Check', 'List', 'Unit')]
    [string]$Mode = 'Check'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$apiDir = Join-Path $repoRoot 'booking-api'
$ignoredArtifacts = Join-Path $PSScriptRoot '.gitignore'

function Write-Info {
    param([string]$Message)
    Write-Host "[t12/$Mode] $Message"
}

if (-not (Test-Path -LiteralPath $apiDir)) {
    throw "booking-api not found under $repoRoot"
}

switch ($Mode) {
    'Check' {
        Write-Info 'Static checks only (no Maven run):'
        $main = Join-Path $apiDir 'src/main/java/com/yu030x/booking/log'
        $test = Join-Path $apiDir 'src/test/java/com/yu030x/booking/log'
        foreach ($dir in @($main, $test)) {
            if (-not (Test-Path -LiteralPath $dir)) { throw "missing expected log tree: $dir" }
            $count = (Get-ChildItem -LiteralPath $dir -Recurse -Filter *.java | Measure-Object).Count
            Write-Info ("  {0}: {1} java files" -f $dir.Replace($repoRoot, ''), $count)
            if ($count -eq 0) { throw "no java sources in $dir" }
        }
        Write-Info 'Forbidden shared-file drift check:'
        git -C $repoRoot diff --check
        git -C $repoRoot status --porcelain | ForEach-Object {
            if ($_ -match '^\s*[AM?]+\s+(pom\.xml|booking-api/pom\.xml|booking-api/src/main/resources/application\.yml|sql/|deploy/)' ) {
                throw "forbidden shared file touched: $_"
            }
        }
        Write-Info 'OK: static checks passed.'
    }
    'List' {
        Write-Info 'Test inventory (unit slices, not executed here):'
        Get-ChildItem -LiteralPath (Join-Path $apiDir 'src/test/java/com/yu030x/booking/log') `
            -Recurse -Filter *Test.java | ForEach-Object {
            $rel = $_.FullName.Substring((Join-Path $apiDir 'src/test/java').Length + 1) -replace '\\', '.' -replace '\.java$', ''
            Write-Info "  $rel"
        }
    }
    'Unit' {
        Write-Info 'Running narrow unit slice (mvn test, single-module, no verify/DB/Redis):'
        Push-Location $apiDir
        try {
            # Narrow surefire selection scoped to this slice's packages only.
            & mvn test "-Dtest=com.yu030x.booking.log.**"
            if ($LASTEXITCODE -ne 0) { throw "mvn test exited with $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
        Write-Info 'OK: unit slice finished.'
    }
}
