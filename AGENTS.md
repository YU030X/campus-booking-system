# Repository Guidelines

## Project Structure & Module Organization

Business plans and DDL live in `docs/`; main specs and active changes live in `openspec/`. Planned modules are:

- `booking-api/`: Spring Boot sources and tests in standard Maven `src/` paths.
- `booking-web/`: Vue 3 code in `src/`; static assets in `public/`.
- `sql/`: ordered migrations such as `V003__create_booking_tables.sql`.
- `deploy/`: deployment files.

Group backend code by business module (`auth`, `resource`, `booking`), then by layer.

## Build, Test, and Development Commands

After scaffolding, run commands from each module:

- `cd booking-api && mvn spring-boot:run`: start the API.
- `cd booking-api && mvn test` or `mvn verify`: run tests or the full Maven lifecycle.
- `cd booking-web && npm install`: install pinned dependencies.
- `cd booking-web && npm run dev` or `npm run build`: develop or build the frontend.
- `openspec validate <change> --type change --strict`: validate an active change.

## Coding Style & Naming Conventions

Use four spaces in Java and two in Vue, JavaScript, YAML, and JSON. Use `PascalCase` for classes, `camelCase` for members, and `UPPER_SNAKE_CASE` for constants and states. Keep Entity, DTO, and VO types separate. Controllers handle HTTP; services own rules and transactions; mappers own persistence.

## Testing Guidelines

Name unit tests `*Test.java` and API/database tests `*IntegrationTest.java`. Cover slot boundaries, state transitions, slot release, authorization, and concurrent conflicts. Record exact commands and results; never claim unrun checks passed.

## Commit & Pull Request Guidelines

Use Conventional Commits, e.g. `feat: add booking slot discretization` or `fix: release slots after rejection`. Codex branches use `codex/<openspec-change-name>`. Follow [CONTRIBUTING.md](CONTRIBUTING.md) and use the [pull request template](.github/pull_request_template.md).

Map each PR to one OpenSpec change. Include scope, validation evidence, spec-sync status, risks, rollback, and UI screenshots. Keep incomplete PRs in Draft.

## OpenSpec & Agent Workflow

For scoped work, explicit user instructions override repository defaults; otherwise follow this guide, active OpenSpec artifacts, main specs, then `docs/`. For observable changes, follow `propose → review → apply → validate → sync specs → validate specs → archive`; never bypass delta specs.

The primary agent owns decisions, edits, and verification. Use default-role, read-only subagents with `fork_turns="none"`, self-contained scope, and `file:line` evidence unless a task card or user assigns file ownership. Run at most six, wait immediately, and intervene after ten minutes.

## Security & Configuration

Commit only `.env.example`; inject database, Redis, and JWT secrets through environment variables. Never log passwords or full tokens. Keep MySQL and Redis private.
