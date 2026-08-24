## 1. Contract and apply gate

- [x] 1.1 Confirm T03 `resource-catalog`, `shared-contracts`, and `identity-access` specs are the source of truth for exact routes, DTO fields, `PageResult`, Long-as-string ids, status `0|1|2`, error codes, and nested closure scope id `0`.
- [x] 1.2 The historical pre-merge gate used only T04's reviewed zero-network auth mock contract (`POST /auth/register`, `POST /auth/login`, `GET/PUT /users/me`) for local browser evidence; that mock gate is complete. Final acceptance remains gated on T04 merged and this worktree rebased, with no resource mocks added and unknown endpoints still rejected.
- [x] 1.3 After T04 merge/rebase, re-verify auth/session behavior, real DTO names, envelope parsing, status codes, and router mount points; stop apply completion if any contract differs from the frozen specs.
- [x] 1.4 In the rebased T04 router (`679bea8`), substitute components only on the six existing records `/resources`, `/resources/:id`, `/admin/categories`, `/admin/resources`, `/admin/rules`, and `/admin/closures`; verify the exact 12-record total and unchanged paths, metadata, guards, auth handlers, and bookings/approvals/users placeholders.

## 2. Owned API and store foundation

- [x] 2.1 Create resource-domain API modules only under the corresponding allowed `booking-web` API/component ownership, covering category tree, resource list/detail/admin mutations, nested time-rules, and nested closures; preserve exact paths and request field names.
- [x] 2.2 Create corresponding stores for list/detail, categories, resources, rules, and closures with idle/loading/success/empty/error and mutation-pending states; retain PageResult keys and string ids without unsafe numeric conversion.
- [x] 2.3 Add shared resource-management error mapping at the feature boundary for 400/401/403/404/409 while leaving `api/http`, router, shared packages, and auth stores untouched.

## 3. Student resource views

- [x] 3.1 Implement `views/resources/**` list UI with category/status/keyword filters, valid pagination controls, loading/empty/error states, retry, and stable refresh that preserves current filters and page.
- [x] 3.2 Implement `views/resources/**` detail UI for catalogue metadata and not-found handling; reject id `0` as a resource detail and do not issue available-slots or booking requests.
- [x] 3.3 Add focused view/store checks for successful filtered pages, empty results, server errors, 404 detail, Long string ids, and retry behavior.

## 4. Administrator category and resource views

- [x] 4.1 Implement `views/admin/categories/**` tree management with trimmed 1..50 names, string `parentId` default `"0"`, sort range `-100000..100000`, nullable blank icon, cycle/deletion conflict feedback, and post-success tree refresh.
- [x] 4.2 Implement `views/admin/resources/**` list and forms for the frozen resource fields and limits (name, location, images, description, capacity, advance days, durations, approval, and status).
- [x] 4.3 Implement status PATCH with duplicate-submit protection, no optimistic mutation, success announcement, and refresh using the active filters/page; cover 400/403/404/409 without discarding unsaved form values.

## 5. Opening rules and closures

- [x] 5.1 Implement `views/admin/rules/**` full replacement editing for half-hour aligned, non-overlapping weekly intervals, including empty-array submission to close all periods; render the successful PUT response as the current session's authoritative rule state (there is no time-rules GET).
- [x] 5.2 Implement `views/admin/closures/**` add/delete flows with `closureDate` and optional reason, nested routes only, explicit resource scope, and global scope id `0` rendering; display only successful current-session POST records, and delete by returned closure id without claiming historical completeness (there is no closures GET).
- [x] 5.3 Add checks for duplicate `(scope,date)` conflict, missing scope/closure 404, global/resource same-date coexistence, and the negative `/resources/0` detail case.

## 6. Permission and state acceptance

- [x] 6.1 Ensure admin surfaces show a forbidden state for `STUDENT`, never issue admin mutations for that role, and continue to rely on backend authorization for every request.
- [x] 6.2 Ensure 401 follows T04 session clearing/auth-shell behavior and 403 renders a no-permission state; every view exposes deterministic loading, empty, success, error, and retry states.
- [x] 6.3 Verify mutation controls re-enable on success/failure; only successful readable status/category/resource operations trigger data refresh, while rule/closure views retain successful PUT/POST response state because no corresponding GET endpoints exist.

## 7. Verification and handoff

- [x] 7.1 Run the narrowest frontend checks available for views, stores, API request shapes, pagination/filtering, form boundaries, permissions, global closure scope, and booking-flow exclusion.
- [x] 7.2 Run `openspec validate add-web-resource-management --type change --strict --no-interactive` from this worktree and resolve every reported planning error without editing project code.
- [x] 7.3 Run `openspec validate add-web-resource-management --type change --strict --no-interactive`, `git diff --check`, and trailing-whitespace checks from this worktree; inspect `git status --short`, and confirm the implementation boundary: only the five change planning artifacts plus `booking-web` resource API/store/components/views and the router's six component handoffs may be changed. `api/http`, auth, shared, booking, available-slots, appointment, and other router records/files are forbidden. Do not commit or push. Validate the implemented router handoff by asserting exact 12 records and unchanged metadata/guards/auth handlers outside the six component substitutions.

## Acceptance evidence (2026-08-25)

- After final scope review fixes, the hidden PowerShell 7 harness completed a fresh real MySQL 8.0.40, Java 17.0.20, built API/proxy, and headless Chromium run successfully.
- Browser result: `1 passed`, 11 screenshots, summary `PASS`, and `T05_RESOURCE_PASS.marker` present.
- Acceptance covered student filters/empty state/forbidden state, admin category and resource creation, status `1 -> 2 -> 1` with filter and pagination preservation, rule invalid/valid/empty/restore flows, global/resource closures with duplicate conflict and returned-id deletion, filtered student detail, `/resources/0` negative handling, and forbidden network calls.
- Cleanup verified no T05 acceptance processes, no generated MySQL data directory, and no harness listener remained; temporary credentials are not recorded here.
