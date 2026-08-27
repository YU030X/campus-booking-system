## Purpose

Define the student-facing booking experience over the frozen availability and booking contracts: selecting valid slots, submitting a booking, viewing the current user's bookings and detail timeline, and preserving explicit gates for cancellation and real integration.

## ADDED Requirements

### Requirement: Frozen ownership and integration boundary

The capability MUST be limited to `booking-web/src/views/booking/**`, `booking-web/src/views/my-bookings/**`, `booking-web/src/components/booking/**`, and corresponding booking-domain API/store tests. It MUST NOT add routes or modify `router/index.js`, `api/http.js`, auth shell, resource views, shared contracts/types, package manifests, or backend code. The `/bookings` and `/bookings/:id` route components MUST be connected by the T04 shared-file owner as a separate handoff. T04/T05/T06/T07 planning artifacts exist in sibling worktrees but are currently unmerged; real integration MUST remain gated until each is merged or rebased and its authoritative DTO/error contract has been reread.

#### Scenario: Scope fence
- **WHEN** the change is implemented or reviewed
- **THEN** only the owned booking views/components and booking-domain API/store tests are changed, with no new route or shared-file edit.

#### Scenario: Contract drift gate
- **WHEN** a reread of a merged dependency shows DTO, status, error, or payload drift from this planning contract
- **THEN** real integration stops and the planning artifacts are updated before any implementation proceeds.

### Requirement: Availability payload and valid slot selection

The booking creation experience MUST consume authenticated `GET /api/v1/resources/{id}/available-slots?date=yyyy-MM-dd` and accept only the payload `{resourceId:string,date:string,slotMinutes:30,slots:[{startTime,endTime,available}]}`. It MUST allow selection only for `available=true` slots on the same calendar date, aligned to `:00` or `:30`, and a selection MUST be a contiguous sequence of slots. Past or disabled slots MUST be visibly unavailable and unselectable. Start and end MUST be derived from the selected sequence using left-closed/right-open semantics; users MUST NOT hand-enter arbitrary times.

#### Scenario: Contiguous half-hour selection
- **WHEN** a student selects adjacent available slots on the requested date
- **THEN** the UI derives the corresponding start/end, shows the duration, and does not include the end boundary as an occupied slot.

#### Scenario: Invalid slot attempt
- **WHEN** a student attempts to select a disabled, past, non-`:00`/`:30`, cross-date, or non-contiguous slot
- **THEN** the selection is rejected or remains disabled and no invalid booking request can be formed.

### Requirement: Create booking form and submission

The experience MUST submit `POST /api/v1/bookings` only through the booking API module/shared HTTP client using `CreateBookingRequest {resourceId:string,startTime:string,endTime:string,purpose:string|null,attendeeCount:integer}`. `startTime` and `endTime` MUST use `yyyy-MM-dd HH:mm:ss`, be same-day half-hour boundaries derived from selected slots, and `attendeeCount` MUST be at least 1. Purpose MUST be trimmed and converted to null when blank; a non-null purpose MUST be no more than 500 Unicode code points after trimming. Submission MUST enter loading immediately and deduplicate repeated activation until the request settles.

#### Scenario: Successful creation
- **WHEN** a valid form is submitted and the server returns HTTP 201
- **THEN** the UI reports success, refreshes the current user's booking list and the corresponding availability date, and clears or closes the creation panel according to the existing shell pattern.

#### Scenario: Invalid form
- **WHEN** purpose exceeds 500 Unicode code points, attendeeCount is below 1, or no valid contiguous slot is selected
- **THEN** the UI blocks submission with field-level feedback and sends no request.

#### Scenario: Duplicate activation
- **WHEN** the submit control is activated again while a create request is loading
- **THEN** no second create request is sent and the control remains in a loading/disabled state until settlement.

### Requirement: Conflict and transport error behavior

The experience MUST treat availability as advisory and surface server truth. For HTTP 409 with booking error code `43000`, the UI MUST inspect the T07 backend message: `该时段已被占用，请刷新后重试` MUST show `该时段刚被其他人预约，请刷新`, refresh the affected slots, and leave the student to reselect; `当前预约请求较多，请稍后重试` MUST show a system-busy message and MUST NOT claim that the slot was taken. The mapping MUST use status, code, and backend message, never code alone. HTTP 401 MUST delegate to T04 session clearing/unauthenticated handling; HTTP 403 MUST preserve the session and show forbidden; HTTP 404 MUST show not found; other errors MUST show an error state with retry where applicable. The UI MUST never convert a failed create into success.

#### Scenario: Slot duplicate conflict
- **WHEN** create returns HTTP 409, code `43000`, and backend message `该时段已被占用，请刷新后重试`
- **THEN** `该时段刚被其他人预约，请刷新` is displayed, availability is reloaded, and no success state is emitted.

#### Scenario: Lock-busy conflict
- **WHEN** create returns HTTP 409, code `43000`, and backend message `当前预约请求较多，请稍后重试`
- **THEN** a system-busy message is displayed without saying the slot was taken, and the response is not mapped by code alone to the duplicate-slot path.

#### Scenario: Authentication and authorization failures
- **WHEN** a booking or availability request returns 401 or 403
- **THEN** 401 follows the shared session-expiry path, while 403 keeps the session and renders a forbidden state.

### Requirement: My bookings list and pagination

The `/bookings` view MUST be the current user's list backed by authenticated `GET /api/v1/bookings` with `pageNumber`, `pageSize` (never above 100), and exact status-enum filtering. It MUST preserve Long booking/resource IDs as strings, provide loading, empty, error, and retry states, and keep pagination/filter state explicit. It MUST not request or display another user's records.

#### Scenario: Paged filtered list
- **WHEN** a student opens the list or changes page/status filter
- **THEN** the UI sends only valid pageNumber/pageSize/status values, renders canonical page fields, and keeps the current-user scope.

#### Scenario: Empty or failed list
- **WHEN** the server returns an empty page or a recoverable list error
- **THEN** the UI renders an empty or error/retry state without stale success data being presented as current.

### Requirement: Booking detail and status timeline

The `/bookings/:id` view MUST consume authenticated `GET /api/v1/bookings/{id}` and render the exact shared `BookingView` fields without inventing fields or statuses. The status timeline MUST use only `PENDING_APPROVAL`, `CONFIRMED`, `CHECKED_IN`, `COMPLETED`, `REJECTED`, `CANCELLED`, and `NO_SHOW`, and MUST not infer or offer arbitrary transitions.

#### Scenario: Detail rendering
- **WHEN** a current-user booking detail is returned
- **THEN** its exact fields and a timeline consistent with the returned shared status are shown, with the Long ID represented as a string.

#### Scenario: Detail not found or unsafe identifier
- **WHEN** the identifier is missing, malformed, unsafe, or the API returns 404
- **THEN** no request is made for an unsafe identifier (or the request is safely rejected) and the view shows not found without exposing another record.

### Requirement: Safe query handoff into creation

The `/bookings` view MAY open its creation drawer/panel from same-origin query parameters `resourceId` and `date`. Query values MUST be validated against the string ID and `yyyy-MM-dd` date contract, with unsafe, unknown, cross-origin, or malformed values ignored or rejected without navigation, code execution, or fabricated resource/slot data. T05 resource detail MAY provide an optional handoff but T08 MUST NOT modify the resource view.

#### Scenario: Valid same-origin handoff
- **WHEN** `/bookings?resourceId=<safe-id>&date=<valid-date>` is opened from the app
- **THEN** the creation panel requests availability for that resource/date through the shared API module.

#### Scenario: Unsafe query
- **WHEN** query values contain an unsafe scheme, malformed date, unexpected extra data, or an untrusted origin
- **THEN** the values are ignored or the panel remains closed, with no fabricated API success and no route change.

### Requirement: Cancellation capability gate

The UI MUST keep cancellation unavailable or disabled until the T09 authoritative cancel contract is merged and reread. Before that gate, it MUST not call a cancel endpoint or claim success. After the gate is explicitly enabled, cancel MUST use the shared booking API module; on success it MUST refresh list, detail, and affected availability. It MUST map 401 to session clearing, 403 to forbidden with session preserved, 404 to not found, and 409 to a business-conflict message.

#### Scenario: Pre-T09 gate
- **WHEN** T09 has not merged its authoritative cancel contract
- **THEN** cancel controls are disabled/hidden by capability state and no cancel request is issued.

#### Scenario: Enabled cancellation refresh
- **WHEN** the gate is enabled and cancel returns success
- **THEN** the list, detail, and affected availability are refreshed; 401/403/404/409 follow the shared mappings above.

### Requirement: Mock, real mode, and evidence gates

Pure store/API mapper/component fixtures MAY be used for unit tests, but the capability MUST NOT extend the global T04 mock with fabricated booking success, status, resource, or availability data. Real browser acceptance MUST use a headless CLI and only run when the T06/T07/T09 feature gates and backend contracts are available; it MUST capture screenshots and request/response evidence without opening windows or browser popups.

#### Scenario: Fixture isolation
- **WHEN** unit tests run before backend dependencies are merged
- **THEN** they use local pure fixtures and do not alter global mock behavior or claim real integration.

#### Scenario: Feature-gated browser QA
- **WHEN** all required backend gates are available
- **THEN** headless browser checks cover the listed success/error/refresh behavior and preserve screenshot/request evidence for the final PR.
