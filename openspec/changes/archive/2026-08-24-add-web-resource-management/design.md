## Context

See `proposal.md` for motivation. The baseline is `main` commit `0e53b7ef` (the requested `main0e53b7e` baseline) with T03 resource-catalog contracts available in the main specs. T04 authentication-shell work exists in a separate worktree and is not merged here. The implementation therefore has a strict frontend ownership boundary: resource views, resource-management components, resource API modules, and resource stores only.

The T03 contract is authoritative: canonical `{code,message,data}` envelopes, exact `PageResult` keys (`pageNumber`, `pageSize`, `total`, `records`), Long ids serialized as strings, status values `0|1|2`, nested time-rule/closure routes, and global closure scope id `0`. T04 is merged at `679bea8` and this worktree is rebased; the router has one narrow exception: replace only the components on its six existing resource records (`/resources`, `/resources/:id`, `/admin/categories`, `/admin/resources`, `/admin/rules`, `/admin/closures`) with T05 views. Preserve the exact 12-record count, every path, metadata, guards, auth handlers, and all bookings/approvals/users placeholders. The shared HTTP client, auth handlers, shared package, and booking flows are outside this change.

## Goals / Non-Goals

**Goals:**

- Provide a coherent student catalogue and administrator resource-management surface with predictable request, mutation, and refresh behavior.
- Keep all T03 DTO, route, pagination, validation, authorization, and error semantics visible at the UI boundary.
- Make loading, empty, error, forbidden, and not-found states testable without inventing resource mocks.
- Keep the global closure scope explicit and prevent id `0` from becoming a resource detail.

**Non-Goals:**

- Do not add/remove/reshape router records or edit their paths, metadata, guards, auth handlers, or non-resource placeholders; only substitute components on the six named resource records. Do not edit `api/http`, authentication shell, shared packages, appointment views, or backend code.
- Do not implement available-slots queries, booking creation/submission, booking-slot writes, or booking state transitions.
- Do not widen T03 DTOs, normalize Long ids to numbers, or replace backend permission checks with UI-only checks.

## Decisions

### 1. Organize by owned web capability

Keep student pages under `views/resources/**`; keep admin pages under `views/admin/resources/**`, `views/admin/categories/**`, `views/admin/rules/**`, and `views/admin/closures/**`. Put request serialization and endpoint functions in corresponding resource-domain API modules and put list/detail/form state in corresponding stores. This makes the user's ownership contract auditable.

Alternative considered: one shared `resourceApi` and one global store. Rejected because it obscures admin-only boundaries and makes accidental booking/router coupling easier.

### 2. Treat the T03 envelope and PageResult as an adapter boundary

Each API function will unwrap only the canonical success envelope and return the exact payload shape; non-zero codes retain HTTP/code/message/data for view-level error mapping. Pagination state will use string ids and numeric page metadata without client-side reshaping of record fields.

Alternative considered: map responses into a second frontend DTO. Rejected because it can silently drop fields or turn Long ids into unsafe JavaScript numbers.

### 3. Use explicit finite UI states and post-success refresh

Stores will model idle/loading/success/empty/error plus mutation-pending states. Mutations will disable duplicate submission and avoid optimistic list changes. Where T03 has no read endpoint, the successful full time-rule replacement response is the current session's authoritative rule state, and the closure view shows only authoritative records returned by successful POSTs in the current session; returned closure ids are used for DELETE. List/tree reads still refresh only after successful mutations, preserving current filters and page where applicable.

Alternative considered: optimistic updates. Rejected because status changes, rule replacement, and closure conflicts are server-authoritative and can race with another administrator.

### 4. Validate at the form boundary, trust the server for authorization

Forms will trim and validate the exact T03 limits before sending; blank optional text becomes null only where the DTO contract says so. Role-aware views hide or disable admin actions for students, but every request still relies on T04's authenticated HTTP behavior and surfaces 401/403 responses.

Alternative considered: client-only permission enforcement. Rejected because menu visibility is not authorization and would diverge from backend policy.

### 5. Represent global closures as a separate scope

Closure screens will carry an explicit `scopeId`, permitting `0` for global closures and positive resource ids for resource closures. The UI will call only nested closure routes, render global scope labels, and reject/redirect any attempt to open `/resources/0` as a detail.

Alternative considered: a top-level `/closures` endpoint or treating `0` as a synthetic resource. Rejected because T03 freezes nested routes and reserves `0` solely for global closure scope.

### 6. Gate integration on T04 merge and contract re-verification

Before T04 is merged, local browser evidence may use only the reviewed zero-network auth mock (`POST /auth/register`, `POST /auth/login`, `GET/PUT /users/me`); unknown mock endpoints remain 404/code `40400`. No resource mock will be added. After T04 merge/rebase, re-check real DTO names, status codes, envelope parsing, bearer/session behavior, and router mount points before declaring apply complete.

Alternative considered: implement a parallel resource mock to unblock visual work. Rejected because it would create a second contract and mask T03/T04 integration drift.

## Risks / Trade-offs

- [T03 DTO or error-code drift] → Block apply completion until post-T04 merge contract review compares every endpoint, field, PageResult key, Long serialization rule, and code mapping against the current main specs.
- [Auth shell is not yet merged] → Keep browser checks at the mock gate and record real integration as a post-merge task; do not modify `api/http` or router files here.
- [Concurrent administrator mutations make stale data visible] → Disable duplicate submits; use successful PUT rule responses and current-session POST closure responses as authoritative where T03 exposes no GET, and refresh readable lists/trees after success; expose conflict errors without overwriting unsaved form values.
- [Large descriptions/images make client validation inconsistent with backend UTF-8 limits] → Validate the documented character/length boundaries and preserve server 400 responses as a second line of defense.
- [Global closure id `0` is mistaken for a resource] → Centralize scope checks in the closure view/store contract and add a negative detail-route acceptance check.

## Migration Plan

1. Start from T04 merge `679bea8` (this worktree is rebased), then substitute only the six existing resource-record components while preserving the router's exact 12-record shape and unchanged metadata/guards/auth handlers/placeholders.
2. Apply only inside the owned views/components/API/store roots, then run focused browser/unit checks for pagination, filters, states, permissions, form limits, and mutation refresh.
3. After T04 merge, run real API/browser integration and revalidate the OpenSpec change; if integration is rejected, rollback by removing only the change's owned files and leave shared/auth/booking code untouched.
