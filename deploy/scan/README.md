# T13 Image Scan Evidence Contract

Status: **validator implemented; real CVE scan NOT RUN**.

The current machine has Docker Scout 1.20.4 and local SBOM capability, but no
explicit Scout offline advisory database/mode. Trivy, Grype, Syft, OSV-Scanner
and Snyk CLIs plus their advisory databases are absent. Therefore no networked
scanner command was attempted and the current scan gate remains blocked.

## Actions

```powershell
# Inert documentation only.
pwsh deploy/scan/run.ps1 -Action Plan

# Read-only local CLI/cache inspection; never invokes a scan.
pwsh deploy/scan/run.ps1 -Action Environment

# Validate an already-produced, local evidence bundle.
pwsh deploy/scan/run.ps1 -Action Validate `
  -ManifestPath deploy/artifacts/<scan-run>/manifest.json
```

`Validate` accepts Trivy JSON or Grype JSON only. It requires exactly `api` and
`edge`, scanner/database metadata, execution-log and raw-report hashes,
fresh database evidence, matching image IDs and image references, completed
scans and exact severity counts. `imageDigest` is optional for locally built
images without a RepoDigest; when supplied, it must match the raw report.
UNKNOWN/HIGH/CRITICAL findings cannot be labeled PASS; PASS also requires both
scanner exit codes to be zero.

All referenced paths must be relative to the manifest directory, may not escape
it lexically, and may not traverse a symbolic link or junction. The normalized
result contains counts/hashes only, not packages, vulnerability descriptions,
credentials or raw report content.

The validator reads at most 8 MiB of the execution log and rejects common
Authorization/password/token/cookie/credential-URL patterns. This is a bounded
defense, not proof of complete redaction; the operator must still run the
repository secret/PII review before retaining or publishing raw evidence.

## Honest boundary

- `docker sbom`/Syft-format inventories are package inventories, not CVE results.
- Contract-test PASS proves validator behavior only.
- Environment `BLOCKED_NO_OFFLINE_SCANNER_DB` is evidence of an unavailable
  local scanner path, not a vulnerability pass.
- Downloading a scanner/database, querying Docker Scout services, registries or
  public advisory endpoints requires separate network authorization.
