# Operation Log Specification

## Purpose

Define safe, allowlisted AOP operation logging against the existing `operation_log` table without coupling logging failures to business success.

## Requirements

### Requirement: Independent operation-log flag and approved pointcuts

The operation-log capability MUST have an independent shared-config feature flag defaulting to `false`. Only methods explicitly marked with the approved operation-log annotation and action allowlist may be intercepted; package-wide or catch-all controller pointcuts are forbidden. T12 MUST consume the shared annotation/config contract without editing common or owner business packages.

#### Scenario: Unapproved method

- **WHEN** an unannotated or non-allowlisted method executes
- **THEN** no operation-log row is attempted.

### Requirement: Exact operation-log field mapping

For an approved action, the capability MUST write only the docs/11 `operation_log` fields: `user_id`, `module`, `operation`, `method`, `params`, `ip`, `cost_ms`, `success`, `error_msg`, and `created_at` (plus the database-generated `id`). `success` MUST reflect the outcome, `cost_ms` MUST be measured in milliseconds, and user/IP/time context MUST be captured when available. No invented operator/target fields are allowed.

#### Scenario: Successful approved action

- **WHEN** an authenticated approved action returns normally
- **THEN** one bounded log record contains the current user ID, module/operation/method, request IP when available, `success=1`, non-negative cost milliseconds, and creation time.

### Requirement: Redaction and bounded parameters

Logged parameters and error messages MUST be bounded and deterministically redacted. Passwords, complete JWTs, database/Redis credentials, full phone numbers, and unbounded request bodies MUST never be persisted. Redaction MUST apply to nested maps/objects and exception text before serialization.

#### Scenario: Sensitive request

- **WHEN** an approved action receives a body containing a password, Authorization token, phone number, or a very large nested value
- **THEN** the stored `params`/`error_msg` contain redacted or truncated representations only, with no complete secret or unbounded body.

### Requirement: Logging failure isolation

A database or serialization failure while writing an operation log MUST be isolated (synchronously or asynchronously) and MUST NOT change the primary business response, status transition, or transaction outcome. The failure MUST be observable through bounded application diagnostics without recursive operation logging.

#### Scenario: Log database outage

- **WHEN** the primary action succeeds but the operation-log insert fails
- **THEN** the primary success is returned unchanged, the business transaction remains committed, and the log failure is recorded only as bounded diagnostic evidence.
- **WHEN** the primary action fails
- **THEN** its error response is preserved while a best-effort `success=0` record may be attempted without masking the original exception.

### Requirement: No unrequested administration endpoint

This capability MUST not add an operation-log query endpoint or expose log rows to administrators unless a separate approved change defines that route and ownership.

#### Scenario: Unsupported query

- **WHEN** a caller requests an undocumented operation-log query
- **THEN** no T12 endpoint exists and the request is handled by the existing fallback/security behavior.

### Requirement: Scope and handoff fence

Production and tests MUST remain under `log/**` and its tests. Annotation adoption by auth/resource/availability/booking/approval/checkin owners is a handoff request; T12 MUST not edit those packages, common, config, pom, SQL, router, HTTP, or deploy files.

#### Scenario: Missing annotation owner

- **WHEN** an owner has not approved an action key and pointcut target
- **THEN** the log task stops for that action and does not broaden interception to compensate.

