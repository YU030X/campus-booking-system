## Purpose

Define reproducible integration and headless end-to-end evidence for the frozen campus booking contracts without changing business or frontend implementation ownership.

## ADDED Requirements

### Requirement: Headless lifecycle coverage
The verification suite MUST run without a headed browser and MUST cover registration, login, authenticated resource/category browsing, available-slot discovery, direct booking, pending-approval booking, administrator approval and rejection, user cancellation, check-in, no-show/violation handling, and terminal-state slot release for `REJECTED`, `CANCELLED`, and `NO_SHOW`. `COMPLETED` release MUST be verified only when an explicitly enabled optional automatic-completion feature exists; it MUST NOT be implied as a P1 implementation requirement.

#### Scenario: Complete direct and pending flows
- **WHEN** the suite provisions isolated test data and executes the student and administrator flows
- **THEN** direct resources become `CONFIRMED`, approval resources become `PENDING_APPROVAL`, approval/rejection and cancellation produce the documented states, check-in and no-show behavior is observable, `REJECTED`/`CANCELLED`/`NO_SHOW` release their slots for a new booking, and `COMPLETED` is checked only when the optional automatic-completion feature is enabled.

### Requirement: Boundary and ownership evidence
The suite MUST assert authentication, authorization, ownership, and conflict behavior for missing/malformed/expired credentials, student-to-admin access, another user's booking read/cancel, invalid transitions, and same-slot conflicts using the canonical 401/403/409 responses.

#### Scenario: Protected and conflicting requests
- **WHEN** unauthorized, forbidden, cross-owner, invalid-transition, and already-occupied requests are submitted
- **THEN** they return the documented 401, 403, 404, or 409 envelope and no unauthorized mutation occurs; only the canonical booking-conflict 409 is recorded as a business conflict, while HTTP 409/code `43000` with message/category `SYSTEM_BUSY` is recorded separately as a system error.

### Requirement: Refresh and evidence capture
The suite MUST verify browser refresh/session persistence at each key route and MUST retain headless screenshots plus request/response network evidence with secrets, passwords, and personal data redacted.

#### Scenario: Refresh persistence
- **WHEN** the browser is refreshed after login, resource selection, booking creation, approval, cancellation, or check-in
- **THEN** the route remains valid, persisted state is reloaded from the API, and the evidence bundle contains the matching screenshot and redacted network trace.

### Requirement: Gate and defect routing
T13 verification MUST be gated on evidence that T04–T12 P0 changes are merged, rebased onto the selected baseline, and spec-synced; T12 optional cuts MUST be documented. A discovered business, frontend, common, Maven/npm, or migration defect MUST create an owner change request and MUST NOT be fixed by weakening a gate in T13.

#### Scenario: Missing prerequisite evidence
- **WHEN** T04–T12 or Redis-sibling artifacts remain only planning-state/unmerged/unsynced, or any required merge/rebase/spec-sync proof or T12 cut record is missing
- **THEN** the verification result is marked blocked/Draft and no completion claim is made; planning artifacts alone do not satisfy the gate.
