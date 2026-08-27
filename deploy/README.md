# T13 Deployment Slice 1 - Static Plan Artifacts

Scope: T13 `deploy/**` files only. Nothing in this directory has been built,
run, or validated. Every command below is **documented, not executed**; actual
execution belongs to the local verification gates (tasks.md 7.x) and stays
forbidden until the ownership/merge gates (tasks 1.2) pass.

## Files

| Path | Purpose | Status |
|---|---|---|
| `api/Dockerfile` | JDK17 multi-stage Maven build → non-root JRE runtime, jar-only copy, port 8080 | authored, unverified |
| `web/Dockerfile` | pinned Node builder (`npm ci`, fail-closed on missing lock) + Vite dist served by pinned `nginx-unprivileged` (non-root, 8080) | authored, unverified |
| `nginx/default.conf` | 8080 SPA fallback, `/api` → `http://api:8080` prefix-preserve proxy (controllers own `/api/v1/...` mappings), security headers, body limit/timeouts, upstream header hiding | authored, CSP not browser-validated |
| `compose.yml` | edge/api/mysql8/redis topology, internal backend network, named volumes, healthchecks, restart/logging/mem/cpu bounds, `${VAR:?}` secret gating | authored, `compose config` not run |
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

1. Resolve the new immutable digest with your registry tooling (documented, not
   automated here).
2. Record old→new digest, reason, and date in the change's evidence index —
   never silently switch to a floating tag.
3. Re-run local build/config gates before promoting.
   ⚠ Open item: none of the default tag→digest pairs has been resolved yet
   (tracked under task 4.1).

## package-lock fail-closed rule

`deploy/web/Dockerfile` explicitly COPYs `package-lock.json` and aborts if it is
missing/empty; there is no `npm install` fallback. Verified present today at
`booking-web/package-lock.json`. If the lock disappears or falls out of sync,
that is a booking-web owner blocker: file a T01/owner change request instead of
editing manifests in T13.

## Certificates

No key material exists or will be committed. TLS activation is an external-gate
operation: operator-provided cert/key mounts plus a renewal command recorded in
`.env.example` comments and this README's gate table above.

## Port safety summary

- Only `edge` publishes to the host: loopback bind `127.0.0.1` on host port 80
  by default; container side fixed at 8080 because nginx runs unprivileged.
- MySQL and Redis: no `ports:` key, reachable only on the `internal: true`
  backend network (tasks.md 4.2; deployment-runtime spec private-topology MUST).
- API is `expose`-only on the compose networks; never published directly.

## Commands (documented, NOT run)

```bash
cd deploy && docker compose --env-file .env config     # validate interpolation/topology
docker compose --env-file .env up -d --build           # local evidence run (post-gates)
docker compose --env-file .env down                    # volumes preserved
```

## Verification scripts & runbooks (slice 2)

| Path | Purpose | Status |
|---|---|---|
| `scripts/empty-migration-check.ps1` | V001–V005 on two throwaway MySQL8 containers; 12 tables/InnoDB/utf8mb4/seed-free + every DDL-declared index (unique+plain) exact-matched + SHA256 schema identity | authored, never run |
| `scripts/backup-restore-check.ps1` | consistent mysqldump → isolated random restore DB → exact-12 + full normalized definition comparison, checksum/count/aggregate evidence, RPO/RTO fields | authored, never run |
| `scripts/restart-persistence-check.ps1` | plan-only default (no secret needed); `-Execute` restarts/recreates with volumes preserved; requires all three containers healthy and proves persistence | authored, never run |
| `scripts/redis-failure-check.ps1` | T07 fail-closed (409/43000/SYSTEM_BUSY, zero mutation); T12 fallback is BLOCKED_OWNER_WIRING by default (exit 3) until owner wiring lands (`-T12FallbackWired` attestation) | authored, never run |
| `runbooks/recovery.md` | lanes, rollback doctrine, RPO/RTO operator fields, stop conditions | planning doc |
| `owner-change-requests.md` | T12 cache-port wiring blocker + external/digest/merge/fixture/concurrency-history blockers with file:line evidence | living doc |
| `jmeter/booking-concurrency.jmx` | 5.6.3 plan; 100-thread same-slot group + distinct granularity group, property-gated, no assertions (offline classification) | authored, never run |
| `jmeter/rounds.example.json` | three-round template; placeholders only; baseline double-gated | authored, never run |
| `jmeter/run.ps1` | plan default; one round per execution, deep-validated loopback BaseUrl, same-slot token via temp secret props (never argv) with verified cleanup, distinct via validated 100-row runtime CSV path; pre/post row evidence via container auth | authored, never run |
| `jmeter/summarize.ps1` | offline XML JTL classification (success/business_conflict/system_busy/server/connection/data_error/other) + strict protected 1/99/0 + slot-delta assertions; digest-only redacted reports | authored, never run |
| `jmeter/README.md` | flow, JTL privacy/redaction, comparability rules, blockers | planning doc |
| `e2e/profile.example.json` | isolated loopback E2E profile template; credential ENV NAMES only; public/prod denied | authored, never run |
| `e2e/run.ps1` | Plan default; ApiIntegration (explicit class set, missing class blocks) / StudentBrowser (reuses T08 harness) / ApprovalBrowser (never passes; blocked without owner attestation) / All | authored, never run |
| `e2e/redact-artifacts.mjs` | offline artifact redactor; manifest path/rule/count only; residual-scan exit 2 | authored, never run |
| `e2e/inventory.md` | requirement → real `file:class` coverage map + browser cases + honest gaps | authored |
| `e2e/README.md` | runner approval rationale, flow, redaction, blockers | planning doc |
| `artifacts/` | raw run evidence; git-ignored except its `.gitignore` | empty until real runs |

All scripts require PowerShell 7, are parameterized, refuse non-local
endpoints, redact credentials in artifacts, and clean up throwaway containers
in `finally`. Nothing in this table has been executed.

## Open items / honest gaps

- T12 owner blocker (OCR-1, `owner-change-requests.md`): availability reads do
  not consume `AvailabilityCachePort`; Redis-fallback lane cannot fully pass.
- No build, image, migration, health, or browser evidence exists yet for this
  slice; `mvn verify`/E2E/scan gates untouched so far.
- Tag→digest resolution pending (above); digest-pinned promotion untested.
- API healthcheck inside its image container is TCP-connect liveness only
  (Temurin JRE ships no curl/wget); actuator HTTP `/actuator/health` probe
  parity still to be chosen/tested before relying on deep-health gating.
- Datasource database + least-privilege account are created by the official
  MySQL image first-init via `MYSQL_DATABASE`/`MYSQL_USER`/`MYSQL_PASSWORD`
  service variables (same values the API consumes). This path is authored but
  NOT yet exercised by a real `up`; first init is also the only time these are
  applied, so changing them later requires a volume reset.
- Nginx CSP header is conservative but not browser-tested; Element Plus runtime
  behavior must be confirmed during headless E2E before any external claim.
