# T13 Deployment and Verification Assets

Scope: T13 `deploy/**` files only. Static checks, local runner checks, backend/
frontend acceptance, Compose config validation, API integration, StudentBrowser,
image build/runtime, empty migration, backup/restore, and restart persistence
are recorded from real runs. Load, ApprovalBrowser, and external gates retain
explicit NOT RUN/BLOCKED states.

## Files

| Path | Purpose | Status |
|---|---|---|
| `api/Dockerfile` | JDK17 multi-stage Maven build → non-root JRE runtime, jar-only copy, port 8080 | built from current checkout; runtime healthy |
| `web/Dockerfile` | pinned Node builder (`npm ci`, fail-closed on missing lock) + Vite dist served by pinned `nginx-unprivileged` (non-root, 8080) | built from current checkout; runtime healthy |
| `nginx/default.conf` | 8080 SPA fallback, `/api` → `http://api:8080` prefix-preserve proxy, headers, body limit/timeouts, upstream hiding | local HTTP scenarios verified |
| `nginx/tls.conf` | optional non-root 8443 TLS server with the same SPA/proxy/security contract and `/run/secrets` certificate paths | static config only; no certificate/HTTPS claim |
| `compose.yml` | edge/api/mysql8/redis topology, internal backend network, named volumes, healthchecks, restart/logging/mem/cpu bounds, `${VAR:?}` secret gating | config + four-service runtime verified |
| `compose.tls.yml` | explicit overlay adding edge 443→8443 plus read-only operator cert/key Compose secrets | static config only; inert unless explicitly supplied |
| `.env.example` | non-secret placeholders only; secrets generated locally into git-ignored `.env` | template |
| `.gitignore` | keeps a local `deploy/.env` out of Git | active |

## Local vs external gates

**Local/container evidence (may proceed after ownership gates, tasks 1.2):**
compose config validation, image builds, empty-DB migration via the ro-mounted
`sql/`, health smoke, Redis-failure checks. Loopback defaults
(`EDGE_HTTP_BIND=127.0.0.1`, `EDGE_HTTP_PORT=80`) keep everything host-local.

**External acceptance (blocked by default):**
any non-loopback publish, port 443/TLS, domain/DNS use, or public smoke requires
explicit user authorization of target, DNS, certificate mechanism, and
credentials (tasks.md 8.1–8.2). Until then these stay marked *not run* and the
change remains Draft.

## Image pin and digest update procedure

Bases are pinned to fixed tags via `ARG` defaults overridable from `.env`
(e.g. set `JRE_RUNTIME_IMAGE=eclipse-temurin@sha256:<digest>`). To update:

1. Resolve the new immutable digest with registry tooling or locally cached
   image metadata.
2. Record old→new digest, reason, and date in the change's evidence index —
   never silently switch to a floating tag.
3. Re-run local build/config gates before promoting. The 2026-09-01 local build
   recorded every base RepoDigest and both application image IDs under
   `deploy/artifacts/t13-image-build-20260901-current/`.

## package-lock fail-closed rule

`deploy/web/Dockerfile` explicitly COPYs `package-lock.json` and aborts if it is
missing/empty; there is no `npm install` fallback. Verified present today at
`booking-web/package-lock.json`. If the lock disappears or falls out of sync,
that is a booking-web owner blocker: file a T01/owner change request instead of
editing manifests in T13.

## Certificates

No key material exists or will be committed. `compose.tls.yml` and
`nginx/tls.conf` implement an optional certificate/key mount contract while
leaving the default local stack unchanged. The static check uses clearly invalid
sentinel text files and never starts Nginx:

```powershell
pwsh deploy/scripts/tls-overlay-check.ps1 -Execute
```

Supplying real operator files, starting the overlay, publishing beyond loopback,
validating a domain/HTTPS endpoint, and accepting a renewal command remain the
external tasks 8.1-8.2. After those inputs are authorized, the operator would
validate the combined topology before any start:

```bash
cd deploy
docker compose --env-file .env -f compose.yml -f compose.tls.yml config --quiet
```

## Port safety summary

- Only `edge` publishes to the host: loopback bind `127.0.0.1` on host port 80
  by default; container side fixed at 8080 because nginx runs unprivileged.
- MySQL and Redis: no `ports:` key, reachable only on the `internal: true`
  backend network (tasks.md 4.2; deployment-runtime spec private-topology MUST).
- API is `expose`-only on the compose networks; never published directly.

## Commands

```bash
cd deploy && docker compose --env-file .env config     # validate interpolation/topology
docker compose --env-file .env up -d --build           # local evidence run (post-gates)
docker compose --env-file .env down                    # volumes preserved
```

## Verification scripts & runbooks (slice 2)

| Path | Purpose | Status |
|---|---|---|
| `scripts/empty-migration-check.ps1` | V001–V005 on two throwaway MySQL8 containers; 12 tables/InnoDB/utf8mb4/seed-free + all 34 DDL keys + SHA256 identity | PASS: `t13-empty-migration-20260901-final` |
| `scripts/backup-restore-check.ps1` | consistent mysqldump → isolated random restore DB → exact-12 + definitions/checksums/aggregates + explicit RPO/RTO threshold | PASS: `t13-backup-restore-20260901-nonzero`; restore 2.131s ≤ RTO 14400s |
| `scripts/restart-persistence-check.ps1` | volume-preserving restart; all cycled services healthy; schema/count identity | PASS: `t13-restart-persistence-20260901-audited` |
| `scripts/redis-failure-check.ps1` | T07 fail-closed plus T12 availability MySQL fallback, zero mutation, latency, and Redis recovery | PASS: `t13-redis-outage-20260901-final` |
| `scripts/tls-overlay-check.ps1` | config-only optional TLS overlay validation using invalid sentinels; no container/certificate/HTTPS/public endpoint | local static gate |
| `runbooks/recovery.md` | lanes, rollback doctrine, RPO/RTO operator fields, stop conditions | planning doc |
| `owner-change-requests.md` | resolved historical owner requests plus current external/digest/fixture/concurrency-history blockers | living doc |
| `jmeter/booking-concurrency.jmx` | 5.6.3 plan; 100-thread same-slot group + distinct granularity group, property-gated, no assertions (offline classification) | authored, never run |
| `jmeter/rounds.example.json` | three-round template; placeholders only; baseline double-gated | authored, never run |
| `jmeter/run.ps1` | plan default; one round per execution, deep-validated loopback BaseUrl, same-slot token via temp secret props (never argv) with verified cleanup, distinct via validated 100-row runtime CSV path; pre/post row evidence via container auth | authored, never run |
| `jmeter/summarize.ps1` | offline XML JTL classification (success/business_conflict/system_busy/server/connection/data_error/other) + strict protected 1/99/0 + slot-delta assertions; digest-only redacted reports | implementation contract-tested; no real JTL |
| `jmeter/contract-tests.ps1` | parses JMX/template and exercises runner/report/privacy/fail-closed behavior with synthetic JTL only | PASS: 45 assertions; no JMeter/Docker/HTTP |
| `jmeter/README.md` | flow, JTL privacy/redaction, comparability rules, blockers | planning doc |
| `e2e/profile.example.json` | isolated loopback E2E profile template; credential ENV NAMES only; public/prod denied | template; ignored runtime instance exercised |
| `e2e/run.ps1` | Plan default; ApiIntegration (explicit class set, missing class blocks) / StudentBrowser (reuses T08 harness) / ApprovalBrowser (never passes; blocked without owner attestation) / All | API 195/195 + Student 15/15 executed |
| `e2e/redact-artifacts.mjs` | offline artifact redactor; manifest path/rule/count only; residual-scan exit 2 | executed; residual 0 |
| `e2e/inventory.md` | requirement → real `file:class` coverage map + browser cases + honest gaps | authored |
| `e2e/README.md` | runner approval rationale, flow, redaction, blockers | planning doc |
| `evidence/preconditions.md` | baseline/worktree record, integrated owner/spec-sync evidence, T04–T12 owner index, T13 ownership map + stop-and-request template, runner selection | living evidence doc |
| `evidence/verification-matrix.md` | local/external verification gates, exact commands, evidence paths, pass criteria, and current blockers | DRAFT, local evidence in progress |
| `demo/profile.example.json` | ephemeral demo fixture profile template (loopback, env NAMES only, publicDenied) | authored, never run |
| `demo/run.ps1` | Setup/StudentFlow/Teardown/All; RNG passwords in verified temp secret file; scope-limited teardown; EPHEMERAL-SETUP labeling | authored, never run |
| `demo/contract-tests.ps1` | offline Plan/refusal plus profile/secret/teardown/evidence contract suite; no Docker/SQL/HTTP/E2E/browser | PASS: 70 assertions; no real Demo |
| `demo/evidence-index.template.md` | requirement→artifact mapping; all NOT RUN/DRAFT placeholders | template |
| `demo/README.md` | ephemeral-vs-seed contract, safety, generated passwords, Draft evidence, blockers | planning doc |
| `artifacts/` | raw run evidence; git-ignored except its `.gitignore` | contains local backend/frontend/config/API/browser evidence |

All scripts require PowerShell 7, are parameterized, refuse non-local
endpoints, redact credentials in artifacts, and clean up throwaway containers
in `finally`. A script is accepted only when its own recorded run exits zero.

## Open items / honest gaps

- T12 OCR-1/OCR-9 are resolved; the deployment-specific live Redis outage lane
  passes for both T07 fail-closed and T12 MySQL fallback behavior.
- Docker/Compose four-service runtime is healthy and loopback-only. API/edge were
  rebuilt from the current checkout using cached fixed bases; build metadata
  records base RepoDigests and non-root application images. Earlier failed pull
  attempts remain historical refresh failures, not current build failures.
- Empty migration, backup/restore and restart persistence have audited local
  evidence under `deploy/artifacts/`. JMeter 5.6.3 and historical/fixture
  artifacts remain absent; ApprovalBrowser still lacks its owner contract.
- API healthcheck inside its image container is TCP-connect liveness only
  (Temurin JRE ships no curl/wget); actuator HTTP `/actuator/health` probe
  parity still to be chosen/tested before relying on deep-health gating.
- Datasource database + least-privilege account were exercised through the official
  MySQL image first-init via `MYSQL_DATABASE`/`MYSQL_USER`/`MYSQL_PASSWORD`
  service variables (same values the API consumes). This path is authored but
  MySQL first-init path; changing them later still requires a volume reset.
- Local Nginx evidence covers SPA/deep route, API proxy, security headers,
  3 MiB→413, and paused-upstream→504. The optional TLS overlay/mount contract is
  statically testable; real certificate, HTTPS and public acceptance remain
  external-gated.
