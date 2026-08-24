## Why

Students and administrators currently lack the web surfaces needed to discover resources and maintain the resource catalog that the frozen T03 APIs already expose. This change turns those APIs into a bounded, testable Vue workflow now, while keeping authentication shell integration behind the T04 merge/apply gate.

## What Changes

- Add authenticated student resource list and detail views with pagination, filtering, loading, empty, and error states.
- Add administrator views for category management, resource management, weekly opening-rule replacement, and closure-day management.
- Add resource-domain API modules and stores for the read/write endpoints defined by T03, including status toggling with refresh.
- Respect the frozen time-rule and closure contracts: treat a successful full rule replacement response as the current session's authoritative rule state, and show only closure records returned by the current session's successful POST operations (deleting by returned id), without claiming historical completeness.
- Enforce UI permission states and form boundary validation while retaining backend authorization as the source of truth.
- Keep the global closure scope represented by resource id `0`; do not expose `0` as a real resource detail.
- Wire only the six existing resource route records (`/resources`, `/resources/:id`, `/admin/categories`, `/admin/resources`, `/admin/rules`, `/admin/closures`) to the T05 views after T04 merge `679bea8`; preserve the router's exact 12-record shape, metadata, guards, auth handlers, and all non-resource placeholders.
- Explicitly exclude available-slots queries, booking creation/submission, booking-slot writes, HTTP/auth/shared/package changes, and appointment workflows.

## Capabilities

### New Capabilities

- `web-resource-management`: Student resource browsing plus administrator catalog, category, opening-rule, and closure-day management over the frozen T03 contracts.

### Modified Capabilities

- None. Existing `resource-catalog`, `shared-contracts`, and `identity-access` requirements remain frozen; this change adds a frontend capability that consumes them.

## Impact

- Frontend views under `views/resources/**`, `views/admin/resources/**`, `views/admin/categories/**`, `views/admin/rules/**`, and `views/admin/closures/**`.
- Corresponding frontend components, API modules, and stores only, plus the six-record router component substitution described above; no path additions/removals, metadata/guard/auth-handler edits, HTTP client, shared package, or appointment code changes.
- Depends on T03 resource-catalog routes/DTOs/PageResult, canonical `Result` envelope, Long-as-string IDs, status vocabulary, and global closure id `0`.
- T04 authentication-shell integration is a prerequisite gate: before T04 is merged, use only its reviewed mock contract for local planning/evidence; after merge, re-verify real DTOs/statuses/envelope before apply completion.
