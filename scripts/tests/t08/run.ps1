param(
  [ValidateSet('Check', 'List', 'Smoke', 'Run')]
  [string]$Action = 'Check'
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }
$harness = Join-Path $here 'qa-harness.mjs'

node --check $harness
$checkExit = $LASTEXITCODE
if ($checkExit -ne 0) {
  Write-Error "SYNTAX_CHECK_FAILED (exit=$checkExit): node --check qa-harness.mjs"
  exit $checkExit
}
if ($Action -eq 'Check') { Write-Host 'CHECK_OK'; exit 0 }

switch ($Action) {
  'List'  { node $harness --list;  exit $LASTEXITCODE }
  'Smoke' { node $harness --smoke; exit $LASTEXITCODE }
  'Run' { node $harness; exit $LASTEXITCODE }
}
