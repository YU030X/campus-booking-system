# T13 Verify and Deploy System — Handoff

Date: 2026-09-01
Branch: `codex/verify-and-deploy-system`  
Worktree: `D:\Projects\project1_campus\target\worktrees\verify-and-deploy-system`  
Status: **DRAFT / partially accepted; do not archive**
OpenSpec apply progress: **14/34 tasks complete**

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
  `deploy/artifacts/verify-compose-config-local-compose-config/`. This does not
  prove daemon runtime or container health.
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

## Remaining blockers — keep tasks unchecked

1. The user selected Docker as the temporary backend runtime. The static Compose
   contract resolves successfully (`mysql`, `redis`, `api`, `edge`), but the
   Docker Desktop Linux daemon is still unavailable. A real `docker desktop
   start` attempt on 2026-09-01 did not create `dockerDesktopLinuxEngine` or the
   backend/diagnostic pipes. Error-level logs show Docker Desktop's remote policy
   request to Docker Hub failing through the configured local proxy
   `127.0.0.1:7897` with `TLS connect ... EOF`. Therefore image builds/digests,
   Compose up/health, empty migration, backup/restore, restart persistence,
   Redis outage, and container scans remain NOT RUN.
2. JMeter 5.6.3 is absent. The vulnerable-baseline, unique-index-only, valid
   seed, and 100-row distinct fixture/history artifacts are also absent
   (OCR-5/6/7). No three-round performance claim exists.
3. ApprovalBrowser lacks an owner-attested deterministic fixture and approved
   executable (OCR-8). Direct StudentBrowser evidence does not satisfy the
   approval-path half of task 2.4.
4. Image tag-to-digest pairs remain unresolved (OCR-4).
5. No host/domain, DNS ownership, TLS mechanism, credentials, or external
   deployment authorization exists (OCR-2). Tasks 8.1-8.3 remain blocked and no
   public URL/TLS claim is allowed.
6. Demo fixture owner attestation and execution remain absent; tasks 6.1-6.4
   stay Draft.

## 2026-09-01 system integrity audit

- Core code/evidence remains sound: backend 387/387, API inventory 195/195,
  frontend build, StudentBrowser 15/15, static gate, change strict validation,
  main-spec validation 21/21, and `git diff --check` all have passing evidence.
- `docker compose --env-file deploy/.env config --quiet` passes when invoked
  from the correct deployment directory; the resolved services are exactly
  `mysql`, `redis`, `api`, and `edge`. This is configuration evidence only.
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

## Safe continuation order

1. Restore the local proxy listening on `127.0.0.1:7897`, or correct/clear the
   Docker Desktop proxy setting, then restart Docker Desktop. Do not publish or
   upload the diagnostic bundle.
2. Once `docker version` reports a server, use Docker/Compose as the temporary
   backend runtime and run the reusable gates from `deploy/verify/run.ps1`;
   preserve each unique run directory. If required images are not cached, obtain
   explicit authorization before contacting an external registry.
3. Obtain JMeter plus owner-provided historical/fixture artifacts before any
   concurrency execution. Never fabricate weakened migrations or mock results.
4. Obtain the ApprovalBrowser owner contract before executing that lane.
5. Update `tasks.md`, `verification-matrix.md`, and this handoff only from real
   evidence; rerun both strict OpenSpec validations and `git diff --check`.
6. Sync/archive only after every required local gate is complete. External
   acceptance remains a separate authorization gate.
