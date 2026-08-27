## Purpose

Define safe, deterministic demonstration setup and evidence orchestration without taking ownership of T01 migrations or embedding credentials and personal data.

## ADDED Requirements

### Requirement: Owned demo data source
Demo data MUST come from a separately approved T01-owned seed change or an ephemeral runtime/test fixture. T13 MUST NOT edit V001–V005 or add seed rows to the empty-database acceptance path.

#### Scenario: Demo setup selection
- **WHEN** a demo run is requested
- **THEN** the orchestrator identifies the approved seed/fixture owner, creates data only in an isolated environment, and leaves the fresh-migration database seed-free.

### Requirement: Safe and deterministic identities
The fixture MUST create the minimum administrator, student, resource, approval, booking, and violation states needed by the E2E script using generated non-PII identities and strong generated passwords that are injected at runtime and never logged or committed.

#### Scenario: Repeatable demo run
- **WHEN** the fixture is run twice against clean isolated data
- **THEN** identifiers and timestamps are mapped deterministically for the script, no plaintext password/PII appears in artifacts, and cleanup can remove only the fixture-owned records or volume.

### Requirement: Demo evidence bundle
The orchestration MUST produce a script, redacted headless screenshots, network evidence, health/config output, and a mapping from each screenshot to an acceptance step; failed setup or missing owner approval MUST block the demo gate.

#### Scenario: Evidence completeness
- **WHEN** the demo flow finishes
- **THEN** every required lifecycle and authorization step has linked visual/network evidence, secrets are redacted, and the result is marked Draft if any prerequisite or owner approval is missing.
