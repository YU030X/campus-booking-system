## Context

The proposal defines a student booking workflow over the frozen shared routes and contracts. T04/T05/T06/T07 planning artifacts do exist in sibling worktrees (`../add-web-auth-shell/`, `../add-web-resource-management/`, `../add-resource-availability/`, and `../add-concurrent-booking-core/` respectively), but all four are still planning-only and unmerged into this worktree. Therefore this design treats the user-provided availability, booking, error, status, ownership, and cancellation-gate statements as the interim planning authority and keeps real integration behind explicit dependency gates: merge or rebase each sibling change, then reread its authoritative route/DTO/payload/status/error contract before integration.

The implementation surface is intentionally narrow: booking views, my-bookings views, booking components, and their booking-domain API/store tests. T04 remains the owner of the shared route component handoff and HTTP/auth shell; T05 remains the owner of resource pages; T06/T07/T09 remain the authorities for availability, booking, and cancellation contracts.

## Goals / Non-Goals

**Goals:**

- Provide a deterministic slot-selection model that accepts only same-day, contiguous, available 30-minute slots and derives the request interval.
- Keep booking requests, pagination, detail rendering, status timelines, and refresh/error behavior aligned with the frozen DTOs and shared status machine.
- Make pre-merge work testable with pure API mappers, stores, and component fixtures without inventing backend success or mutating global mocks.
- Define explicit handoff and feature gates so shared-file ownership and contract drift are visible before real integration.
- Leave headless browser evidence, build, strict validation, diff-check, and screenshot capture as auditable acceptance work.

**Non-Goals:**

- No new route, router edit, Axios client edit, auth-shell edit, resource-view edit, shared-contract/type edit, package change, backend change, or database change.
- No implementation of availability calculation, booking concurrency, approval, check-in, arbitrary status updates, or cancellation before T09.
- No global booking mock expansion, fabricated resource/slot/status data, or browser/desktop automation with visible windows.

## Decisions

### 1. Treat the API module as the only transport boundary

Booking views and stores call a booking-domain API module that uses the existing shared HTTP client. Direct Axios use is prohibited. This preserves T04's auth/error behavior and makes API mappers independently testable. T07 is authoritative for transport error semantics: a slot duplicate is identified by HTTP 409, `code=43000`, and backend message `该时段已被占用，请刷新后重试`, then presented by T08 as `该时段刚被其他人预约，请刷新` with an affected-slots refresh. Lock-busy uses the same HTTP status/code but backend message `当前预约请求较多，请稍后重试`; T08 presents a system-busy message and never calls it a slot conflict. The mapper MUST use the status/code/message combination, not the code alone. A local transport seam can be fixture-backed for unit tests, while the real adapter is enabled only after the dependency gates.

**Alternative considered:** calling the HTTP client from each component. Rejected because it duplicates error mapping, makes loading deduplication fragile, and violates the shared API boundary.

### 2. Model slots as a contiguous interval, not free-form times

The store derives `startTime` from the first selected slot and `endTime` from the last selected slot, validates same-date/`:00`/`:30` boundaries and contiguity, and formats the POST payload as `yyyy-MM-dd HH:mm:ss`. The end boundary is not selected as an occupied slot. Purpose is trimmed, converted to null when blank, and counted by Unicode code points after trimming; non-null purpose MUST be at most 500 code points. Attendee validation happens before the API call.

**Alternative considered:** a time picker with arbitrary start/end values. Rejected because it permits values the availability response did not authorize and duplicates backend rule logic in a less safe form.

### 3. Keep list/detail state separate but share refresh keys

The `/bookings` view owns pagination/status/filter/loading/empty/error state. The detail view owns one booking and its timeline. A shared booking-domain refresh mechanism invalidates the affected list/detail/availability keys after create and, when enabled, cancel. This avoids stale list data while preventing unrelated resources from refetching.

**Alternative considered:** one global booking cache. Rejected because it would blur current-user scope and make query-driven creation refreshes harder to reason about.

### 4. Make cancellation a capability gate

The UI renders a disabled or unavailable cancel affordance until T09's authoritative endpoint, request, response, and error contract is merged and reread. The gate is a planning/configuration decision, not a guessed endpoint. Once enabled, the same API module maps errors and triggers list/detail/slot refreshes.

**Alternative considered:** wiring the endpoint from docs/15 immediately. Rejected because the user explicitly reserves cancellation authority for T09 and forbids false success.

### 5. Validate same-origin query handoff defensively

The list page parses only expected `resourceId` and `date` query keys, validates string IDs and `yyyy-MM-dd`, and ignores unsafe/unknown values. It never trusts a query to fabricate a resource or availability response. Resource detail may link to this handoff without T08 editing that view.

**Alternative considered:** passing the full resource object in serialized query state. Rejected because it enlarges the trust boundary and can drift from T05's authoritative resource data.

### 6. Separate pre-gate unit evidence from real browser evidence

Before dependency merges, tests use pure fixtures for mapper, validation, store, and component behavior. They must not change T04's global mock. After all required gates are available, headless CLI browser checks exercise real requests and save screenshots/request evidence. The evidence set explicitly includes boundary slots, deduped loading, 201/409, pagination/status/timeline, query safety, cancellation gate/refresh, build, strict validate, and diff-check.

**Alternative considered:** declaring mock-only tests as end-to-end acceptance. Rejected because mocked booking success cannot prove the T06/T07/T09 contracts or concurrency conflict behavior.

## Risks / Trade-offs

- [Dependency contract drift] → Re-read merged T04/T05/T06/T07/T09 DTOs and errors before real integration; update planning and stop if they differ.
- [Stale availability between read and create] → Treat slots as advisory, apply T07's 409/43000 plus backend-message mapping, show the friendly duplicate text only for the duplicate message, and refetch slots after that conflict.
- [Lock contention message collision] → Preserve the same 43000 code for lock-busy but distinguish its exact backend message, show system-busy feedback, and never mislabel it as a slot already taken.
- [Duplicate submissions] → Disable the submit action while pending and assert one transport call in unit tests.
- [Unsafe query or identifier] → Validate same-origin query values and booking IDs before transport; render not-found/closed state without fabricating data.
- [Status timeline invents transitions] → Render only the seven shared statuses and returned booking timestamps; do not expose arbitrary target-status actions.
- [Shared-file collision] → Record T04 route-component handoff as a separate owner action and keep T08 changes outside router/http/auth/resource paths.
- [Weak browser evidence before backend readiness] → Keep browser QA feature-gated and retain a clear distinction between fixture tests and real request evidence.

## Migration Plan

1. Keep this change planning-only until reviewed and all dependency gates are identified.
2. Merge or rebase the sibling T04/T05/T06/T07 planning changes into this worktree, then reread each authoritative route, DTO, payload, status, error, and mode contract; update the plan if needed and stop on drift before implementation.
3. Implement and test the owned booking views/components/API/store only; coordinate the `/bookings` and `/bookings/:id` route handoff through T04 without editing the router in T08.
4. Keep cancel disabled until T09 merges its contract; then enable the capability and add success/error/refresh tests.
5. Run pure tests, frontend build, strict OpenSpec validation, and `git diff --check`. Run headless real-browser acceptance only when T06/T07/T09 backend gates are available, capturing screenshots and request evidence.
6. If rollback is needed, remove the T08-owned views/components/API/store changes and leave shared router/http/auth/resource/backend files untouched; no migration or data rollback is required.
