#Requires -Version 7.0
<#
.SYNOPSIS
    Validate the optional T13 TLS Compose overlay without starting containers.
.DESCRIPTION
    PLAN mode is the default. -Execute writes non-secret, explicitly invalid
    certificate sentinels under ignored deploy/artifacts, runs only
    `docker compose config`, and records a redacted topology summary. It never
    reads deploy/.env, validates a real certificate, opens a socket, or proves
    HTTPS/public deployment.
#>
[CmdletBinding()]
param(
    [switch]$Execute,
    [string]$ArtifactRoot = '',
    [string]$RunId = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Warning "REFUSED: invalid RunId '$RunId'."
    exit 2
}
if (-not $Execute) {
    Write-Output 'PLAN MODE - would validate compose.yml + compose.tls.yml with non-secret invalid sentinels; no container or network action.'
    exit 0
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Warning 'BLOCKED: docker CLI is unavailable; compose config was not run.'
    exit 3
}

$deployRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ArtifactRoot) { $ArtifactRoot = (Resolve-Path (Join-Path $deployRoot 'artifacts')).Path }
$artifactDir = Join-Path $ArtifactRoot "tls-overlay-$RunId"
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

$baseCompose = Join-Path $deployRoot 'compose.yml'
$tlsCompose = Join-Path $deployRoot 'compose.tls.yml'
$tlsConfig = Join-Path $deployRoot 'nginx\tls.conf'
$certSentinel = Join-Path $artifactDir 'not-a-certificate.txt'
$keySentinel = Join-Path $artifactDir 'not-a-private-key.txt'
$contractEnv = Join-Path $artifactDir 'static-contract.env'
$missingTlsEnv = Join-Path $artifactDir 'missing-tls-paths.env'
$stderrLog = Join-Path $artifactDir 'compose-stderr.log'
$missingTlsStderrLog = Join-Path $artifactDir 'missing-tls-paths-stderr.log'
$resultPath = Join-Path $artifactDir 'result.json'

foreach ($required in @($baseCompose, $tlsCompose, $tlsConfig)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        Write-Warning "BLOCKED: required TLS overlay file is missing: $required"
        exit 3
    }
}

Set-Content -LiteralPath $certSentinel -Value 'STATIC CONFIG ONLY - NOT A CERTIFICATE' -Encoding ascii
Set-Content -LiteralPath $keySentinel -Value 'STATIC CONFIG ONLY - NOT A PRIVATE KEY' -Encoding ascii
$certForCompose = $certSentinel.Replace('\', '/')
$keyForCompose = $keySentinel.Replace('\', '/')
$contractValues = [ordered]@{
    MYSQL_IMAGE = 'mysql:8.0.40'
    MYSQL_ROOT_PASSWORD = 'STATIC_CONFIG_ONLY_NOT_A_SECRET'
    DB_USERNAME = 'static_config_only'
    DB_PASSWORD = 'STATIC_CONFIG_ONLY_NOT_A_SECRET'
    REDIS_IMAGE = 'redis:7.4.9'
    MAVEN_BUILD_IMAGE = 'maven:3.9.9-eclipse-temurin-17'
    JRE_RUNTIME_IMAGE = 'eclipse-temurin:17.0.12_7-jre-jammy'
    API_IMAGE_TAG = 'static-config-only'
    JWT_SECRET = 'STATIC_CONFIG_ONLY_NOT_A_SECRET_32_BYTES'
    REDIS_PASSWORD = 'STATIC_CONFIG_ONLY_NOT_A_SECRET'
    BOOKING_OPERATION_LOG_ENABLED = 'false'
    BOOKING_CACHE_ENABLED = 'true'
    BOOKING_NOTIFICATIONS_ENABLED = 'false'
    BOOKING_STATISTICS_ENABLED = 'false'
    SPRINGDOC_ENABLED = 'false'
    NODE_BUILD_IMAGE = 'node:22.14.0-alpine'
    NGINX_RUNTIME_IMAGE = 'nginxinc/nginx-unprivileged:1.27.4-alpine'
    EDGE_IMAGE_TAG = 'static-config-only'
    EDGE_HTTP_BIND = '127.0.0.1'
    EDGE_HTTP_PORT = '18080'
    EDGE_TLS_BIND = '127.0.0.1'
    EDGE_TLS_PORT = '443'
    TLS_CERT_PATH = $certForCompose
    TLS_KEY_PATH = $keyForCompose
}
$envLines = @($contractValues.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value })
Set-Content -LiteralPath $contractEnv -Value $envLines -Encoding ascii
Set-Content -LiteralPath $missingTlsEnv -Value @($envLines | Where-Object { $_ -notmatch '^TLS_(CERT|KEY)_PATH=' }) -Encoding ascii
$environmentBackup = @{}
foreach ($name in $contractValues.Keys) {
    $environmentBackup[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
}

try {
    # Process environment wins over --env-file in Compose. Pin every interpolated
    # value to a safe contract sentinel, then explicitly remove the TLS paths for
    # the negative check so inherited operator settings cannot affect the result.
    foreach ($entry in $contractValues.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, [EnvironmentVariableTarget]::Process)
    }
    [Environment]::SetEnvironmentVariable('TLS_CERT_PATH', $null, [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('TLS_KEY_PATH', $null, [EnvironmentVariableTarget]::Process)
    & docker compose --env-file $missingTlsEnv -f $baseCompose -f $tlsCompose config --quiet 2> $missingTlsStderrLog
    $missingTlsError = if (Test-Path -LiteralPath $missingTlsStderrLog) { Get-Content -LiteralPath $missingTlsStderrLog -Raw } else { '' }
    $missingTlsPathsRefused = ($LASTEXITCODE -ne 0 -and $missingTlsError -match 'TLS_(CERT|KEY)_PATH')

    [Environment]::SetEnvironmentVariable('TLS_CERT_PATH', $certForCompose, [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('TLS_KEY_PATH', $keyForCompose, [EnvironmentVariableTarget]::Process)

    $composeArgs = @(
        'compose', '--env-file', $contractEnv,
        '-f', $baseCompose, '-f', $tlsCompose,
        'config', '--format', 'json'
    )
    $configText = (& docker @composeArgs 2> $stderrLog | Out-String)
    $composeExit = $LASTEXITCODE
    if ($composeExit -ne 0) {
        $result = [ordered]@{
            runId = $RunId
            status = 'FAIL'
            exitCode = $composeExit
            staticOnly = $true
            tlsRuntimeExecuted = $false
            detail = 'docker compose config failed; inspect compose-stderr.log'
        }
        Set-Content -LiteralPath $resultPath -Value ($result | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
        Write-Warning 'TLS OVERLAY STATIC CHECK FAILED'
        exit 1
    }

    $config = $configText | ConvertFrom-Json
    $services = @($config.services.PSObject.Properties)
    $edge = $config.services.edge
    $edgePorts = @($edge.ports)
    $tlsPort = $edgePorts | Where-Object { [int]$_.target -eq 8443 } | Select-Object -First 1
    $publishingServices = @($services | Where-Object {
        $_.Value.PSObject.Properties['ports'] -and @($_.Value.ports).Count -gt 0
    } | ForEach-Object { $_.Name })
    $secretTargets = @($edge.secrets | ForEach-Object { [string]$_.target })
    $tlsConfigMount = $edge.volumes | Where-Object { [string]$_.target -eq '/etc/nginx/conf.d/tls.conf' } | Select-Object -First 1
    $tlsText = Get-Content -LiteralPath $tlsConfig -Raw

    $checks = [ordered]@{
        onlyEdgePublishes = ($publishingServices.Count -eq 1 -and $publishingServices[0] -eq 'edge')
        tlsContainerPort8443 = ($null -ne $tlsPort)
        tlsHostPort443 = ($null -ne $tlsPort -and [int]$tlsPort.published -eq 443)
        tlsLoopbackDefault = ($null -ne $tlsPort -and [string]$tlsPort.host_ip -eq '127.0.0.1')
        certificateSecretMounted = ($secretTargets -contains 'tls.crt')
        privateKeySecretMounted = ($secretTargets -contains 'tls.key')
        tlsConfigReadOnlyMounted = ($null -ne $tlsConfigMount -and [bool]$tlsConfigMount.read_only)
        missingTlsPathsRefused = $missingTlsPathsRefused
        nginxListensWithSsl = ($tlsText -match '(?m)^\s*listen 8443 ssl;')
        nginxUsesCertificateSecret = ($tlsText -match 'ssl_certificate /run/secrets/tls\.crt;')
        nginxUsesPrivateKeySecret = ($tlsText -match 'ssl_certificate_key /run/secrets/tls\.key;')
    }
    $passed = -not ($checks.Values -contains $false)
    $result = [ordered]@{
        runId = $RunId
        status = $(if ($passed) { 'PASS' } else { 'FAIL' })
        exitCode = $(if ($passed) { 0 } else { 1 })
        staticOnly = $true
        tlsRuntimeExecuted = $false
        certificateValidated = $false
        publicEndpointValidated = $false
        composeFiles = @('deploy/compose.yml', 'deploy/compose.tls.yml')
        publishingServices = $publishingServices
        checks = $checks
        generatedAt = (Get-Date).ToString('o')
    }
    Set-Content -LiteralPath $resultPath -Value ($result | ConvertTo-Json -Depth 6) -Encoding utf8NoBOM
    if (-not $passed) { Write-Warning 'TLS OVERLAY STATIC CHECK FAILED'; exit 1 }
    Write-Output "TLS OVERLAY STATIC CHECK PASS - result=$resultPath; no container, certificate, HTTPS, or public endpoint was tested."
    exit 0
}
catch {
    $result = [ordered]@{
        runId = $RunId
        status = 'FAIL'
        exitCode = 1
        staticOnly = $true
        tlsRuntimeExecuted = $false
        detail = $_.Exception.Message
    }
    Set-Content -LiteralPath $resultPath -Value ($result | ConvertTo-Json -Depth 5) -Encoding utf8NoBOM
    Write-Warning 'TLS OVERLAY STATIC CHECK FAILED'
    exit 1
}
finally {
    foreach ($name in $contractValues.Keys) {
        [Environment]::SetEnvironmentVariable($name, $environmentBackup[$name], [EnvironmentVariableTarget]::Process)
    }
}
