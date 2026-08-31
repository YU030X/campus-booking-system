# T13 Verify and Deploy System — Handoff

Date: 2026-08-31  
Branch: `codex/verify-and-deploy-system`  
Worktree: `D:\Projects\project1_campus\target\worktrees\verify-and-deploy-system`  
Status: **DRAFT / partially accepted; do not archive**

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

1. Docker client 29.4.3 is installed, but the Docker Desktop Linux daemon is
   unavailable at `dockerDesktopLinuxEngine`. Therefore image builds/digests,
   Compose up/health, empty migration, backup/restore, restart persistence,
   Redis outage, and container scans are NOT RUN.
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

## Important repository state

- Raw artifacts and the actual E2E profile are intentionally git-ignored.
- A stash named `preserve T13 staged docs before final integration` contains
  pre-existing user-staged documentation. Do not drop or apply it blindly.
- The T12 change still has one governance confirmation open: task 0.1 names the
  unreachable base `0e53b7e`, while the verified shared base is `2ffae9d`.
  Do not rewrite or check that task without explicit user confirmation.
- No push has been performed.

## Safe continuation order

1. Start/repair Docker Desktop, then run the reusable gates from
   `deploy/verify/run.ps1`; preserve each unique run directory.
2. Obtain JMeter plus owner-provided historical/fixture artifacts before any
   concurrency execution. Never fabricate weakened migrations or mock results.
3. Obtain the ApprovalBrowser owner contract before executing that lane.
4. Update `tasks.md`, `verification-matrix.md`, and this handoff only from real
   evidence; rerun both strict OpenSpec validations and `git diff --check`.
5. Sync/archive only after every required local gate is complete. External
   acceptance remains a separate authorization gate.
