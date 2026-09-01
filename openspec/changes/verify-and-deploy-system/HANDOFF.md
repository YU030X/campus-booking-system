# T13 Verify and Deploy System — Handoff

Date: 2026-09-01
Branch: `codex/verify-and-deploy-system`  
Implementation baseline: `a31ac1e fix: close Docker recovery gates`
Worktree: `D:\Projects\project1_campus\target\worktrees\verify-and-deploy-system`  
Status: **DRAFT / partially accepted; do not archive**
OpenSpec apply progress: **24/34 tasks complete**

## Completed and verified

- Integrated the current accepted T04-T12 chain (`19649b5`) and synchronized
  all discovered delta capabilities into main specs (`070155f`). Main-spec
  strict validation passes 21/21.
- T13 backend gate passes 387/387 on JDK 17 with real loopback MySQL 8.0.46 and
  Redis 7.0.15. Evidence is ignored under
  `deploy/artifacts/verify-backend-t13-backend-pass/`.
- Frontend clean install/build passes. Evidence:
  `deploy/artifacts/verify-frontend-t13-frontend-pass/`.
- Compose config validation passes with safe non-interpolated evidence:
  `deploy/artifacts/verify-compose-config-local-compose-config/`.
- API/edge were rebuilt from the current checkout using cached fixed bases;
  `deploy/artifacts/t13-image-build-20260901-current/` records base RepoDigests,
  non-root users and exit 0. All four Compose services are healthy; only edge is
  published, on loopback `127.0.0.1:18080`.
- Local Docker SBOM generation completed with exit 0 for both application images:
  `deploy/artifacts/t13-local-sbom-20260901/api.syft.json` and
  `deploy/artifacts/t13-local-sbom-20260901/edge.syft.json`. These are ignored
  package inventories only; they are not vulnerability/CVE scan results.
- JMeter tasks 3.1-3.3 are implementation-complete and offline contract-tested.
  `deploy/jmeter/contract-tests.ps1` passed 45 assertions covering the three
  round template, 100/1/1 groups, loopback/baseline/CSV gates, strict response
  classification, report metadata/evidence links, protected 1/99 rules,
  row-delta checks, non-zero JMeter exits, privacy and fail-closed inputs. It
  invoked no JMeter, Docker or HTTP request and is not a real three-round
  performance result.
- Demo tasks 6.1-6.4 remain unchecked, but the authored harness now has a
  74-assertion offline contract suite covering profile ownership/namespace/wait
  gates, loopback and attestation refusal, missing/invalid teardown maps,
  32-byte RNG and temp-secret finally behavior, exact children-first teardown,
  zero-collision preflight, pre-mutation recovery scope, tamper-resistant
  owner-tuple teardown, and all-Draft evidence. It invoked no Docker, SQL, HTTP,
  E2E or browser action.
- Empty-database migration passes in
  `deploy/artifacts/t13-empty-migration-20260901-final/`: exact MySQL digest,
  two fresh databases, 12 tables, 34 keys, zero rows, identical fingerprints,
  complete tool/time/HEAD metadata and exit 0.
- Backup/restore passes in
  `deploy/artifacts/t13-backup-restore-20260901-nonzero/`: isolated restore,
  identical definitions/checksums, booking 1→1 and slot rows 2→2 with matching
  aggregates, and 2.131 seconds within the explicit 14,400-second RTO;
  RPO=24 hours and exit 0 are recorded.
- Restart persistence passes in
  `deploy/artifacts/t13-restart-persistence-20260901-audited/`: API/MySQL/Redis
  return healthy, schema fingerprint is identical, row-count diffs are empty,
  and volumes are preserved.
- Redis outage passes in `deploy/artifacts/t13-redis-outage-20260901-final/`:
  T07 returns 409/code43000/SYSTEM_BUSY with zero mutation, T12 availability
  returns 200/code0/data from MySQL fallback with zero mutation, and Redis
  recovers. Generated credentials remained in memory and fixture cleanup ran.
- Local Nginx/runtime evidence in `deploy/artifacts/t13-runtime-current-20260901/`
  covers SPA/deep route 200, API proxy 401, security headers, 3 MiB body→413,
  paused upstream→504, and the five non-secret runtime feature flags.
- Optional TLS mount support is implementation-complete without activating TLS:
  `compose.tls.yml` and `nginx/tls.conf` add loopback-default 443→8443 plus
  operator file-backed Compose secrets. The config-only artifact
  `deploy/artifacts/tls-overlay-t13-tls-overlay-final/result.json` passed all
  eleven assertions (including missing-path refusal) and explicitly records that
  no container, real certificate, HTTPS, or public endpoint was tested. Real TLS
  acceptance remains external.
- API integration executes the fixed 37-class inventory and passes 195/195:
  `deploy/artifacts/e2e-ApiIntegration-t13-api-integration-pass/`.
- StudentBrowser passes 15/15 in Chrome 152:
  `deploy/artifacts/e2e-StudentBrowser-t13-student-browser-final/`. Text
  redaction residual is zero. All 52/52 screenshots were manually reviewed;
  they contain generated `t08qa_*` QA data only, password inputs are masked,
  and no real PII, secret, cookie, or token is visible.
- Final static gate, change strict validation, main-spec strict validation, and
  `git diff --check` pass.

## Owner fixes integrated during acceptance

- T10 commit `4ef792c`: isolates the no-show processor fixture from the live
  scheduler. Three consecutive focused runs passed 4/4; the final full backend
  gate passed 387/387. Recorded as OCR-10.
- T08 commit `6e51a98`: waits for refreshed availability evidence in case 08.
  The final browser run passes 15/15. Recorded as OCR-11.
- T13 commits `2dbbfb5` and `2faecc8`: make the redactor executable as ESM,
  preserve child-command logs, and accept bounded text evidence up to 64 MiB
  while still failing closed above the limit.
- T13 commit `a31ac1e`: closes the local Docker image, empty-migration,
  backup/restore, restart-persistence, and Redis-outage gates and records the
  corresponding evidence and fail-closed harness checks.

## Remaining blockers — keep tasks unchecked

1. JMeter 5.6.3 is absent. Although the authored harness now passes its offline
   contract suite, the vulnerable-baseline, unique-index-only, valid seed, and
   100-row distinct fixture/history artifacts are absent (OCR-5/6/7). No real
   three-round performance claim exists.
2. ApprovalBrowser lacks an owner-attested deterministic fixture and approved
   executable (OCR-8). Direct StudentBrowser evidence does not satisfy the
   approval-path half of task 2.4.
3. Remote vulnerability/dependency scanning is not run. Fixed local base
   RepoDigests, application image IDs, and local Syft-format SBOM inventories are
   recorded, but no external scanner or CVE result is claimed.
4. No host/domain, DNS ownership, TLS mechanism, credentials, or external
   deployment authorization exists (OCR-2). Tasks 8.1-8.3 remain blocked and no
   public URL/TLS claim is allowed.
5. Demo fixture owner attestation and execution remain absent; tasks 6.1-6.4
   stay Draft. Additionally, Setup can create partial rows before the complete
   fixture map exists; its current finally block cannot safely auto-compensate
   that pre-map failure without a reviewed compensation design. The runner now
   preserves a non-secret exact recovery scope and never guesses a delete.

## 2026-09-01 system integrity audit

- Core code/evidence remains sound: backend 387/387, API inventory 195/195,
  frontend build, StudentBrowser 15/15, static gate, change strict validation,
  main-spec validation 21/21, and `git diff --check` all have passing evidence.
- `docker compose --env-file deploy/.env config --quiet` passes when invoked
  from the correct deployment directory; the resolved services are exactly
  `mysql`, `redis`, `api`, and `edge`.
- The current-checkout API/edge images and cached MySQL/Redis images form a
  healthy four-service local stack. API/MySQL/Redis remain unexposed; edge is
  loopback-only.
- A fresh `docker compose ps` check immediately before this handoff update still
  showed all four services running and healthy.
- `deploy/.env` is ignored and untracked; generated artifacts remain ignored.
- The Docker profile explicitly enables Redis and availability cache. Operation
  log, notifications, and statistics retain their independent default-false
  feature flags; enabling those optional capabilities is a deployment-profile
  decision, not implied by the current Compose file.
- API container health is currently a TCP liveness probe on port 8080, not an
  Actuator dependency-health assertion. Runtime acceptance must separately prove
  database/Redis readiness and migrations.
- The foundational `docs/` directory is local and ignored by Git, so a fresh
  checkout cannot reproduce the referenced business/task-plan documents. Do not
  treat their presence in the main checkout as branch-contained evidence.
- Local Docker diagnostic bundle (not uploaded):
  `C:\Users\yuu\AppData\Local\Temp\3E5EBC0D-34C6-439D-AD16-D16A5746ECF6\20260901015211.zip`.
  It may contain host diagnostics and must not be committed or published.

## Important repository state

- Raw artifacts and the actual E2E profile are intentionally git-ignored.
- A stash named `preserve T13 staged docs before final integration` contains
  pre-existing user-staged documentation. Do not drop or apply it blindly.
- The T12 change still has one governance confirmation open: task 0.1 names the
  unreachable base `0e53b7e`, while the verified shared base is `2ffae9d`.
  Do not rewrite or check that task without explicit user confirmation.
- No push has been performed.
- The tracked worktree was clean at `337277f` before the Demo-contract batch.
- The JMeter report-schema/synthetic contract-test and optional TLS-overlay
  implementation batches are complete; the Demo offline contract batch is the
  current continuation point.
  JMeter/Syft/Trivy/Grype CLIs remain absent; Docker's local SBOM command was
  available and produced the inventories listed above.

## Safe continuation order

1. Obtain JMeter plus owner-provided historical/fixture artifacts before any
   concurrency execution. Never fabricate weakened migrations or mock results.
2. Obtain the ApprovalBrowser owner contract before executing that lane.
3. Update `tasks.md`, `verification-matrix.md`, and this handoff only from real
   evidence; rerun both strict OpenSpec validations and `git diff --check`.
4. Sync/archive only after every required local gate is complete. External
   acceptance remains a separate authorization gate.
