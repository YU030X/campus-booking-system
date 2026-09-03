## Purpose

Define comparable concurrency evidence for booking correctness and lock granularity while preserving the database uniqueness contract.

## ADDED Requirements

### Requirement: Three isolated concurrency rounds
The test report MUST contain three separately identified rounds: an isolated vulnerable baseline for evidence only, database unique-index protection, and unique-index plus Redisson optimization. Each round MUST use the same booking request shape and a clean data scope; the protected same-slot assertion additionally requires healthy Redis and a valid approved seed/fixture.

#### Scenario: Same-slot contention
- **WHEN** 100 concurrent requests target one resource and one aligned slot in each round, with healthy Redis and a valid seed/fixture for the protected assertion
- **THEN** the baseline may demonstrate multiple successes only as isolated evidence, while each protected round produces exactly one success and 99 business conflicts with HTTP 409, zero system errors, and no duplicate slot rows; HTTP 409/code `43000` responses whose message/category is `SYSTEM_BUSY` are counted separately as errors and never as business conflicts.

### Requirement: Lock-granularity comparison
The plan MUST include concurrent requests for distinct resources and/or dates and MUST record whether independent work proceeds without global serialization.

#### Scenario: Independent resources and dates
- **WHEN** equivalent requests target different resource/date lock keys
- **THEN** they can make progress concurrently and the report identifies any unexpected global serialization as a failure.

### Requirement: Complete performance record
Every round MUST record hardware/software environment, database and Redis versions, thread count, ramp-up, request parameters, success/conflict/error counts, average/P95/P99 latency, final booking and slot row counts, and a comparison conclusion. Canonical business-conflict 409 responses MUST NOT be counted as system errors; HTTP 409/code `43000` `SYSTEM_BUSY` responses MUST be counted separately as errors by message/category.

#### Scenario: Auditable report
- **WHEN** a round completes or aborts
- **THEN** the report contains all required fields, distinguishes business-conflict 409, `SYSTEM_BUSY` 409/code `43000`, and 500/connection/data-consistency failures, and links raw JMeter output and database evidence.

### Requirement: Safety of baseline evidence
The vulnerable baseline MUST run only in an isolated disposable environment with no public exposure and MUST never be presented as an acceptable production configuration.

#### Scenario: Baseline isolation
- **WHEN** the baseline round is executed
- **THEN** it cannot reach public endpoints or production data, and the resulting artifact is labeled historical vulnerability evidence.
