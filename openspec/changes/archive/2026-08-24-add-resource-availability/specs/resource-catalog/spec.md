## MODIFIED Requirements

### Requirement: Exact routes and envelopes

The service MUST expose authenticated `GET /api/v1/categories`; ADMIN `POST /api/v1/admin/categories`, `PUT /api/v1/admin/categories/{id}`, `DELETE /api/v1/admin/categories/{id}`; authenticated `GET /api/v1/resources`, `/api/v1/resources/{id}`, and `/api/v1/resources/{id}/available-slots?date=yyyy-MM-dd`; ADMIN resource POST/PUT/PATCH status, nested time-rules PUT, and nested closures POST/DELETE exactly as frozen in docs/15. No top-level closure routes exist. Responses use canonical `Result/PageResult` (or canonical `Result` for the availability payload); response Long IDs are strings and `deleted` is omitted.

#### Scenario: Frozen route request

- **WHEN** an authenticated caller requests a documented `/api/v1` route, including the resource availability route with a valid date
- **THEN** the matching canonical envelope is returned and undocumented top-level closure routes are absent.
