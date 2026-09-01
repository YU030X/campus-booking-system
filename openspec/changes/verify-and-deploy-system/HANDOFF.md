# T13 Verify and Deploy System — Handoff

Date: 2026-09-01
Branch: `codex/verify-and-deploy-system`  
Implementation baseline: `924da16 fix: compensate partial Demo setup safely`
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
- The committed `deploy/scan/` validator passes 28 offline synthetic
  assertions for Trivy/Grype report structure, relative/non-reparse paths,
  hashes, fresh database metadata, image ID/reference/optional digest binding,
  exact severity counts, scan completion/exit codes, and fail-closed decisions.
  Its Environment action invoked no scanner, Docker daemon, registry, advisory
  service, or network and recorded `BLOCKED_NO_OFFLINE_SCANNER_DB`. This proves
  validator behavior and the local blocker only, not image safety or a CVE scan.
- JMeter tasks 3.1-3.3 are implementation-complete and offline contract-tested.
  `deploy/jmeter/contract-tests.ps1` passed 45 assertions covering the three
  round template, 100/1/1 groups, loopback/baseline/CSV gates, strict response
  classification, report metadata/evidence links, protected 1/99 rules,
  row-delta checks, non-zero JMeter exits, privacy and fail-closed inputs. It
  invoked no JMeter, Docker or HTTP request and is not a real three-round
  performance result.
- Demo tasks 6.1-6.4 remain unchecked, but the authored harness now has a
  119-assertion offline contract suite covering profile ownership/namespace/wait
  gates, loopback and attestation refusal, missing/invalid teardown maps,
  32-byte RNG and temp-secret finally behavior, exact children-first teardown,
  zero-collision preflight, pre-mutation recovery scope, tamper-resistant
  owner-tuple teardown, incremental non-secret recovery journaling,
  SERIALIZABLE parent/range locks, complete child+parent cleanup conditions,
  conditional rollback, and all-Draft evidence. It invoked no Docker, SQL, HTTP,
  E2E or browser action. A separate no-business-write MySQL 8.0.40 probe returned
  `T13COMP:1:1`, proving the conditional transaction-control syntax only.
- Demo hardening is committed through `86de80c`: Setup performs an exact
  zero-collision preflight before mutation, writes a non-secret recovery scope
  before mutation, and Teardown validates unique numeric IDs plus exact
  user/resource/booking owner tuples before issuing any DELETE. It deliberately
  refuses ambiguous cleanup instead of guessing ownership.
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
- The combined static gate now includes JMeter, Demo, and image-scan offline
  contracts and passes on the committed scan batch. Strict change validation,
  main-spec validation 21/21, the focused scan contract, sensitive-format scan,
  and `git diff --check` also pass.

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
- T13 commits `4416e2f` and `86de80c`: harden the Demo orchestration contract,
  pre-mutation collision/recovery behavior, and owner-attested teardown. These
  are offline contract improvements only; they do not complete tasks 6.1-6.4.
- T13 commit `7b336f4`: adds the offline Trivy/Grype evidence validator, 28-case
  synthetic contract suite, read-only local environment blocker, static-gate
  integration, and truthful task/matrix documentation. It does not complete the
  real scan portion of task 7.4.
- T13 commit `924da16`: journals each created Demo owner tuple, transactionally
  revalidates and locks journaled parent/child ranges, refuses unjournaled child
  rows, and rolls back any incomplete partial compensation. This is implementation
  hardening only; no real Setup/compensation/Demo was run.

## Remaining blockers — keep tasks unchecked

1. JMeter 5.6.3 is absent. A current audit of PATH/common caches, Docker images,
   every Git ref/object and stash metadata found no runnable JMeter, vulnerable
   baseline, unique-index-only history, approved valid seed, runtime 100-row CSV,
   JTL or real report (OCR-5/6/7). No three-round performance claim exists.
2. ApprovalBrowser lacks an owner-attested deterministic fixture and approved
   executable (OCR-8). `scripts/tests/t11/run.ps1` is a real local candidate with
   approve/reject evidence, but it is unattested and lacks the required per-state
   refresh matrix; it was not executed or silently adopted by T13.
3. Vulnerability/dependency scanning is not run. A local audit found Docker
   Scout v1.20.4 and its cached SBOM, but no local advisory/CVE database and no
   documented offline-CVE mode. Trivy, Grype, Syft CLI and OSV-Scanner are not
   installed; only package-manager manifests were found. Fixed local base
   RepoDigests, application image IDs, and local Syft-format SBOM inventories are
   recorded, but no scanner result or CVE claim exists. Task 7.4 stays unchecked.
4. No host/domain, DNS ownership, TLS mechanism, credentials, or external
   deployment authorization exists (OCR-2). Tasks 8.1-8.3 remain blocked and no
   public URL/TLS claim is allowed.
5. Demo fixture owner attestation and execution remain absent; tasks 6.1-6.4
   stay Draft. Journaled partial tuples can now be revalidated and compensated in
   one SERIALIZABLE transaction, but no runtime recovery evidence exists. A hard
   interruption, API response loss, or journal-write failure after mutation commit
   can still leave an unjournaled row; exact recovery scope/manual review remains
   the honest boundary until the owner accepts it.

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
- The implementation baseline is `924da16`; the only follow-up change is this
  handoff refresh. No push has been performed.
- The scan batch contains `run.ps1`, `contract-tests.ps1`, README guidance and an
  example manifest intended to validate owner-supplied offline Trivy/Grype JSON
  evidence. It has been parsed, contract-tested (28 assertions), integrated into
  the passing static gate, and independently reviewed. Review findings on
  reparse-point escape, malformed zero-finding reports, image-reference binding,
  optional digest wording, Scout plugin invocation, and execution-log credential
  screening were fixed. It is committed but must not be cited as real scan
  evidence.
- The JMeter report-schema/synthetic contract-test, optional TLS-overlay, and
  119-assertion Demo compensation/teardown contract batches are committed.
  JMeter/Trivy/Grype/OSV-Scanner
  CLIs remain absent; Docker's local SBOM command was available and produced the
  inventories listed above, while Docker Scout lacks a locally verified advisory
  database.

## Safe continuation order

1. Obtain a supported local scanner plus fresh advisory database, or separately
   authorize a controlled network-backed scanner/database workflow, before any
   real vulnerability claim. Validate both API/edge evidence bundles with
   `deploy/scan/run.ps1`; do not substitute SBOM or synthetic contracts.
2. Obtain JMeter plus owner-provided historical/fixture artifacts before any
   concurrency execution. Never fabricate weakened migrations or mock results.
3. Obtain the ApprovalBrowser owner contract before executing that lane.
4. Update `tasks.md`, `verification-matrix.md`, and this handoff only from real
   evidence; rerun both strict OpenSpec validations and `git diff --check`.
5. Sync/archive only after every required local gate is complete. External
   acceptance remains a separate authorization gate.
