# T13 Verify and Deploy System — Handoff

Date: 2026-09-02
Branch: `codex/verify-and-deploy-system`  
Implementation baseline: `5bcc920 fix: bind Demo teardown to exact ownership`
Committed branch HEAD before the current Approval batch: `f8dcbea docs: refresh T13 Demo ownership handoff`
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
- Demo tasks 6.1-6.4 remain unchecked. The committed harness has a
  184-assertion offline contract suite covering profile ownership/namespace/wait
  gates, loopback and attestation refusal, missing/invalid teardown maps,
  32-byte password RNG, 128-bit ownership-tag RNG, temp-secret finally behavior,
  exact RunId/fixed-role binding, UTF-8 SQL literal encoding, zero-collision
  preflight, pre-mutation recovery scope, incremental non-secret parent/child-ID
  journaling, exact child-set teardown, SERIALIZABLE parent/range locks, complete
  child+parent cleanup conditions, conditional rollback, and all-Draft evidence.
  It invoked no Docker, SQL, HTTP, E2E or browser action. A separate
  no-business-write MySQL 8.0.40 partial probe returned `T13COMP:1:1`; the full
  already-clean path ran twice and returned `T13TD:0:1:1`. These prove only
  transaction syntax/idempotency without fixture mutation.
- Demo hardening is committed through `5bcc920`: Setup binds a random non-secret
  ownership tag to resource.description/recovery/journal/map; full and partial
  cleanup require each scoped child set to equal the exact recorded ID set and
  owner tuple before deleting only those IDs. Additional/missing rows roll back.
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
- T13 commit `f15538b`: refreshes this handoff for the committed partial-recovery
  state. It contains no runtime or acceptance-status upgrade.
- T13 commit `5bcc920`: fixes `All`-mode map sharing, binds Setup/Teardown to the
  same RunId, owner, fixed role usernames and random resource ownership tag,
  encodes all SQL text as UTF-8 hexadecimal data literals, and requires exact
  recorded IDs for booking/user children. This is implementation hardening only;
  no populated fixture or real Demo was run.

## Latest Demo ownership-v2 batch

- Commit `5bcc920` contains the full ownership-v2 implementation and truthful
  README/matrix/tasks evidence. It passed PowerShell parsing, the 184/184 offline
  Demo contract, the combined static gate, strict change validation, main-spec
  validation 21/21 and `git diff --check` before commit.
- Full Teardown is a single generated SERIALIZABLE transaction. It locks every
  parent and scoped child range, requires owner/RunId/tag/tuple equality plus exact
  child-ID set equality, deletes children before parents, verifies complete absence,
  and emits `T13TD:<ownership>:<alreadyAbsent>:<cleanup>`.
- Two independent security/runtime review rounds found and then verified fixes for
  foreign violation/approval/slot/user-child deletion, SQL literal interpolation,
  non-secret map provenance, `All`-mode MapPath scope, cross-run maps and role-name
  substitution. No remaining external-row deletion path was identified in the
  reviewed code; this is code-review evidence, not populated-fixture runtime proof.
- The absent-scope MySQL 8.0.40 probe ran twice after the final fixes, performed no
  business write, returned `T13TD:0:1:1`, and left every scoped group at zero.
- The ownership tag is a non-secret row-provenance marker under the local operator
  trust boundary, not a credential or protection against an attacker who already
  controls both the database and artifacts.

## ApprovalBrowser intake batch — reviewed, hardened, committed

- The T13-only ApprovalBrowser intake batch (modified
  `deploy/e2e/{README.md,inventory.md,profile.example.json,run.ps1}`,
  `deploy/{evidence/verification-matrix.md,owner-change-requests.md,verify/run.ps1}`,
  `openspec/changes/verify-and-deploy-system/tasks.md`, and new
  `deploy/e2e/approval-contract-tests.ps1`) completed two independent
  read-only review passes on 2026-09-02: (1) a security/path/evidence-boundary
  review and (2) a PowerShell runtime/test-semantics review. The three
  previously reported defects (Write-Output return pollution, argv splatting
  position binding, reparse/path walker parameter sets) were re-verified as
  correctly fixed.
- Review findings that were fixed before commit:
  - MAJOR: owner root/executable repository-locality was bypassable via
    junctions/symlinks; every path component between the executable and the
    repository root is now reparse-point checked
    (`Test-ReparseFreeAncestry`), as are the run artifacts and owner-output
    directories.
  - MAJOR: `.ps1` owner runners executed in-process and could share session
    state with the validator; they now run in a separate
    `pwsh -NoProfile -File` child process, and `.bat`/`.cmd`/interpreter
    script types are refused outright (only `.exe` and `.ps1` are allowed).
  - MAJOR: fail-closed redaction silently skipped text files with unlisted
    extensions; `redact-artifacts.mjs` now classifies every file — extended
    text set, known binary set recorded as `SKIPPED_BINARY`, and any other
    extension is sniffed: text-like content is redacted, binary-like content
    becomes `UNSCANNED_BINARY_UNLISTED_EXT` and fails closed (exit 2).
  - MAJOR: a structurally incomplete manifest/profile threw under
    `Set-StrictMode` and aborted without a status file; all JSON property
    access is now guarded (`Get-JsonValue`) so missing fields degrade to
    contract errors with `approval-browser-status.json` still written.
  - MAJOR: the 22-assertion suite never exercised the unsafe-path and
    cleanup/refresh refusals; six negative scenarios were added (traversal
    screenshot with the target really existing outside the root,
    `cleanup.performed=false`, string `"false"`, unscannable binary file,
    manifest without cases, case missing boolean fields).
  - MINOR: strict-boolean gates (`Test-StrictTrue`) now back
    `publicAccessDenied`, fixture attestations, `cleanup.performed`,
    `refreshObserved`/`apiReloadObserved` (a JSON string `"false"` never
    passes); the owner-output listing no longer swallows errors
    (`SilentlyContinue` removed); extra manifest cases' screenshots join the
    manual-PII-review marker; evidence files must be distinct across cases
    and the manifest cannot serve as network evidence; malformed-manifest
    cases degrade to status-file contract errors.
  - Redaction rules extended with Authorization Basic, api-key/x-api-key
    headers, raw JWTs (rule + residual check); assertion count is now 32.
- `deploy/e2e/approval-contract-tests.ps1` passes 32/32 offline assertions
  using only an ignored local file-writing stub. It invoked no Chrome, Docker,
  SQL, HTTP, Maven, npm, T11 or other T01-T12 harness. After the fixes, the
  combined static gate (`deploy/verify/run.ps1 -Mode Check`), strict change
  validation, main-spec validation 21/21 and `git diff --check` all pass.
- Leftover ignored debug runs under `deploy/artifacts/approval-contract-debug*`
  were deleted; committed evidence bundles were not touched.
- Remaining known (documented, non-blocking) boundaries: extra owner-chosen
  argv after the two placeholders is passed through (owner-authored input);
  `-ArtifactRoot` remains operator-consented; the owner process inherits the
  host environment (`DB_URL`/`REDIS_HOST`). All three are documented in
  `deploy/e2e/README.md`.
- The intake contract still cannot self-promote OCR-8 or task 2.4: every
  executed path stays `EXECUTED_UNPROVEN`/exit 2 and the runtime gate stays
  `BLOCKED_OWNER_APPROVAL`.

## Remaining blockers — keep tasks unchecked

1. JMeter 5.6.3 is absent. A current audit of PATH/common caches, Docker images,
   every Git ref/object and stash metadata found no runnable JMeter, vulnerable
   baseline, unique-index-only history, approved valid seed, runtime 100-row CSV,
   JTL or real report (OCR-5/6/7). No three-round performance claim exists.
2. ApprovalBrowser lacks an owner-attested deterministic fixture/root/executable
   and runtime evidence (OCR-8). T11 is only a candidate for an owner change: it
   still needs generated credentials, finally teardown, complete T13 redaction,
   and the six-case post-state refresh/API-reload evidence contract. The
   32-assertion intake suite is offline structure only and does not complete 2.4.
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
   the honest boundary until the owner accepts it. The populated full-Teardown path
   is still unexecuted even though its static and already-clean paths pass.

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
- The committed implementation baseline is `5bcc920`; the ApprovalBrowser intake
  batch is now committed after independent review (see the review section for
  the commit hash). No push has been performed.
- The scan batch contains `run.ps1`, `contract-tests.ps1`, README guidance and an
  example manifest intended to validate owner-supplied offline Trivy/Grype JSON
  evidence. It has been parsed, contract-tested (28 assertions), integrated into
  the passing static gate, and independently reviewed. Review findings on
  reparse-point escape, malformed zero-finding reports, image-reference binding,
  optional digest wording, Scout plugin invocation, and execution-log credential
  screening were fixed. It is committed but must not be cited as real scan
  evidence.
- The JMeter report-schema/synthetic contract-test, optional TLS-overlay, and
  184-assertion Demo ownership/compensation/teardown contract batches are committed.
  JMeter/Trivy/Grype/OSV-Scanner
  CLIs remain absent; Docker's local SBOM command was available and produced the
  inventories listed above, while Docker Scout lacks a locally verified advisory
  database.

## Safe continuation order

1. DONE (2026-09-02): the ApprovalBrowser intake batch received two independent
   read-only reviews (security/path/evidence boundary; PowerShell runtime/test
   semantics), all MAJOR/MINOR findings were fixed, 32/32 assertions plus the
   combined static gate, both strict validations and `git diff --check` pass,
   and the batch is committed. Do not execute T11.
2. Obtain explicit owner attestation for the ephemeral Demo fixture before any
   populated Setup/All execution. If granted, preserve the exact ignored map,
   journal/evidence and manual screenshot review; otherwise tasks 6.1-6.4 stay Draft.
3. Obtain a supported local scanner plus fresh advisory database, or separately
   authorize a controlled network-backed scanner/database workflow, before any
   real vulnerability claim. Validate both API/edge evidence bundles with
   `deploy/scan/run.ps1`; do not substitute SBOM or synthetic contracts.
4. Obtain JMeter plus owner-provided historical/fixture artifacts before any
   concurrency execution. Never fabricate weakened migrations or mock results.
5. Obtain the ApprovalBrowser owner contract before executing that lane.
6. Update `tasks.md`, `verification-matrix.md`, and this handoff only from real
   evidence; rerun both strict OpenSpec validations and `git diff --check`.
7. Sync/archive only after every required local gate is complete. External
   acceptance remains a separate authorization gate.
