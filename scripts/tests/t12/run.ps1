# T12 operation-log / availability-cache / notification / statistics slices harness
# Reusable acceptance/regression harness for scripts/tests/t12 scope.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Check', 'List', 'OperationLog', 'Cache', 'RealCache', 'Notifications', 'Statistics', 'Unit')]
    [string]$Mode = 'Check'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$apiDir = Join-Path $repoRoot 'booking-api'

$slices = @(
    @{ Name = 'operation-log'; Main = 'src/main/java/com/yu030x/booking/log'; Test = 'src/test/java/com/yu030x/booking/log'; Selector = 'com.yu030x.booking.log.**' },
    @{ Name = 'cache';         Main = 'src/main/java/com/yu030x/booking/cache'; Test = 'src/test/java/com/yu030x/booking/cache'; Selector = 'com.yu030x.booking.cache.**' },
    @{ Name = 'notifications'; Main = 'src/main/java/com/yu030x/booking/notification'; Test = 'src/test/java/com/yu030x/booking/notification'; Selector = 'com.yu030x.booking.notification.**' },
    @{ Name = 'statistics';    Main = 'src/main/java/com/yu030x/booking/statistics'; Test = 'src/test/java/com/yu030x/booking/statistics'; Selector = 'com.yu030x.booking.statistics.**' }
)

# Strongly forbidden shared/owner paths: any local drift here aborts the check.
$forbiddenPatterns = @(
    '(^|[\\/])pom\.xml',
    'src[\\/]main[\\/]resources',
    '(^|[\\/])sql[\\/]',
    '(^|[\\/])deploy[\\/]',
    '(^|[\\/])booking-web[\\/]'
)

function Write-Info {
    param([string]$Message)
    Write-Host "[t12/$Mode] $Message"
}

function Assert-Trees {
    foreach ($slice in $script:slices) {
        foreach ($relative in @($slice.Main, $slice.Test)) {
            $dir = Join-Path $apiDir ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
            if (-not (Test-Path -LiteralPath $dir)) { throw "missing expected tree: $dir" }
            $count = (Get-ChildItem -LiteralPath $dir -Recurse -Filter *.java | Measure-Object).Count
            if ($count -eq 0) { throw "no java sources in $dir" }
            Write-Info ("  {0}: {1} java files" -f ("booking-api/" + $relative), $count)
        }
    }
}

function Assert-NoSharedDrift {
    Write-Info 'Forbidden shared-path drift check:'
    git -C $repoRoot diff --check
    foreach ($line in (git -C $repoRoot status --porcelain)) {
        foreach ($pattern in $forbiddenPatterns) {
            if ($line -match $pattern) { throw "forbidden shared file touched: $line" }
        }
        Write-Info ("  tracked drift (informational): {0}" -f $line)
    }
}

function Invoke-SliceTests {
    param(
        [string]$Selector,
        [string]$ExcludedGroups = ''
    )
    Write-Info "Running narrow unit slice via Surefire selection '$Selector' (single module, no verify/DB unless slice demands it):"
    Push-Location $apiDir
    try {
        $arguments = @('test', "-Dtest=$Selector")
        if (-not [string]::IsNullOrWhiteSpace($ExcludedGroups)) {
            $arguments += "-DexcludedGroups=$ExcludedGroups"
        }
        & mvn @arguments
        if ($LASTEXITCODE -ne 0) { throw "mvn test exited with $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Write-Info 'OK: narrow slice finished.'
}

switch ($Mode) {
    'Check' {
        Write-Info 'Static checks only:'
        Assert-Trees
        Assert-NoSharedDrift
        Write-Info 'OK: static checks passed.'
    }
    'List' {
        Write-Info 'Full T12 test inventory (log+cache+notification, not executed here):'
        foreach ($slice in $slices) {
            Write-Info ("[{0}]" -f $slice.Name)
            Get-ChildItem -LiteralPath (Join-Path $apiDir $slice.Test) -Recurse -Filter *Test.java |
                Sort-Object FullName | ForEach-Object {
                $rel = $_.FullName.Substring((Join-Path $apiDir 'src/test/java').Length + 1)
                $fqcn = ($rel -replace '[\\/]', '.') -replace '\.java$', ''
                Write-Info ("  {0}" -f $fqcn)
            }
        }
    }
    'OperationLog' { Invoke-SliceTests $slices[0].Selector }
    'Cache'        { Invoke-SliceTests $slices[1].Selector 'real-redis' }
    'RealCache'    { Invoke-SliceTests 'com.yu030x.booking.cache.redis.*RealIntegrationTest' }
    'Notifications'{ Invoke-SliceTests $slices[2].Selector }
    'Statistics'   { Invoke-SliceTests $slices[3].Selector }
    'Unit' {
        $combined = ($slices | ForEach-Object { $_.Selector }) -join ','
        Invoke-SliceTests $combined 'real-redis'
    }
}
