#Requires -Version 7.0
<#
.SYNOPSIS
    T11 owner runner for the T13 ApprovalBrowser intake contract (OCR-8).
.DESCRIPTION
    Positional argv contract (exactly): <RunId> <ArtifactRoot>. Creates an
    ephemeral approval fixture with RUNTIME-GENERATED credentials (never in
    source), drives the six mandatory refresh cases through the real UI,
    verifies persisted state through API reloads, writes approval-evidence.json
    plus per-case screenshot/network files under <ArtifactRoot>, and finally
    removes only its own fixture rows (children first, exact scope). No fixed
    password exists in this repository file; secrets never reach artifacts.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$ArtifactRoot
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    Write-Error "REFUSED: RunId fails the contract pattern"; exit 2
}
if (-not (Test-Path -LiteralPath $ArtifactRoot -PathType Container)) {
    Write-Error "REFUSED: ArtifactRoot must be an existing directory"; exit 2
}
node (Join-Path $PSScriptRoot 'approval-contract-runner.mjs') $RunId $ArtifactRoot
exit $LASTEXITCODE
