# Notifications Specification

## Purpose

Define optional in-app notifications with strict ownership, stable pagination, and post-commit idempotent creation.

## Requirements

### Requirement: Independent notification flag and exact fields

The notification capability MUST have an independent shared-config feature flag defaulting to `false`. Persisted notification records MUST use exactly the existing fields `id`, `userId`, `title` (<=100), `content` (<=1000), `type` (<=30), nullable `bizId`, `isRead`, and `createdAt`; response Long IDs MUST serialize as strings and no password/token or deleted field may be returned.

#### Scenario: Field boundaries

- **WHEN** a notification title, content, or type exceeds its limit
- **THEN** creation is rejected with the canonical 400/40000 validation response and no row is inserted.

### Requirement: Current-user paginated listing

The service MUST expose authenticated `GET /api/v1/notifications` for the current user only. It MUST accept `pageNumber` (default 1, >=1) and `pageSize` (default 10, 1..100), return the canonical page envelope, and order rows stably by `created_at DESC, id DESC`.

#### Scenario: Ownership and ordering

- **WHEN** a student requests page 1
- **THEN** only that principal's notifications are returned in the stable createdAt/id descending order; another user's rows are never included.

### Requirement: Owner-only idempotent read acknowledgement

The service MUST expose authenticated `POST /api/v1/notifications/{id}/read`. Only the notification owner may change it; repeating the request after it is already read MUST remain a successful no-op. Missing notifications and another user's IDs MUST not reveal ownership and MUST use the existing not-found/authorization contract.

#### Scenario: Repeat read

- **WHEN** the owner posts the read action twice
- **THEN** both calls are successful, the second makes no additional state change, and `isRead=1` remains true.
- **WHEN** another authenticated user posts the action
- **THEN** the request is rejected without changing the row.

### Requirement: Post-commit event creation and idempotency

Notifications MUST be created only from approved domain events after the upstream transaction commits. With no database unique key and no SQL/schema change, creation MUST lock the recipient user row using `SELECT ... FOR UPDATE` or an equivalent database serialization primitive, and hold that lock through the identity check and insert in one transaction. Each event MUST carry a deterministic idempotency identity (for business events, recipient + type + bizId); `bizId = NULL` participates in identity comparison and is equal only to another NULL (`biz_id = :bizId OR (biz_id IS NULL AND :bizId IS NULL)`). No notification may be emitted for a rolled-back transaction, and no email/SMS/push channel is included.

#### Scenario: Commit, rollback, and retry

- **WHEN** an approved booking/violation event commits
- **THEN** one notification may be inserted after commit.
- **WHEN** the upstream transaction rolls back
- **THEN** no notification is inserted.
- **WHEN** the same event is delivered repeatedly
- **THEN** the deterministic idempotency check returns the existing row and does not duplicate it.
- **WHEN** two consumers concurrently deliver the same event for the first time
- **THEN** recipient-row serialization allows exactly one notification row for that identity, including when `bizId` is NULL.

### Requirement: Authorization and security

All notification routes MUST require authentication; listing and read acknowledgement MUST enforce current-user ownership in the service layer, not only in the frontend. Inputs and responses MUST not contain passwords, complete JWTs, DB/Redis credentials, full phone numbers, or raw unbounded request bodies.

#### Scenario: Unauthenticated and foreign access

- **WHEN** a request has no valid authentication or targets another user's notification
- **THEN** it receives the canonical 401/403-or-not-found behavior and no foreign data changes or leaks.

### Requirement: Scope and handoff fence

Production/tests MUST remain under `notification/**` and the agreed P1 frontend notification directories. Booking, approval, violation, task, user, common, config, SQL, router, HTTP, pom, and deploy changes are handoff requests only; event producers and route registration require owner approval.

#### Scenario: Missing event producer

- **WHEN** T07/T09/T10 has not supplied an after-commit event contract
- **THEN** notification creation work stops at the gate and does not edit those owner packages.

