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

可复用验收、回归和环境测试脚本必须保存在仓库的 `scripts/tests/<scope>/` 下，并提供单一、文档化的调用入口（Windows 优先使用 `run.ps1`）。执行测试前必须先检查并复用已有脚本；只有确实缺少覆盖时才新增或修改脚本，禁止在会话、`target/` 或临时目录中重复编写等价 harness。脚本至少应支持静态检查和用例列表；涉及浏览器时还应支持 headless smoke 与真实运行入口。生成的截图、日志、凭据和运行证据必须被局部 `.gitignore` 排除，且不得在脚本中写入真实秘密。

## Commit & Pull Request Guidelines

Use Conventional Commits, e.g. `feat: add booking slot discretization` or `fix: release slots after rejection`. Codex branches use `codex/<openspec-change-name>`. Follow [CONTRIBUTING.md](CONTRIBUTING.md) and use the [pull request template](.github/pull_request_template.md).

Map each PR to one OpenSpec change. Include scope, validation evidence, spec-sync status, risks, rollback, and UI screenshots. Keep incomplete PRs in Draft.

## OpenSpec & Agent Workflow

For scoped work, explicit user instructions override repository defaults; otherwise follow this guide, active OpenSpec artifacts, main specs, then `docs/`. For observable changes, follow `propose → review → apply → validate → sync specs → validate specs → archive`; never bypass delta specs.

The primary agent owns decisions, edits, and verification. Use default-role, read-only subagents with `fork_turns="none"`, self-contained scope, and `file:line` evidence unless a task card or user assigns file ownership. Run at most six, wait immediately, and intervene after ten minutes.

## Security & Configuration

Commit only `.env.example`; inject database, Redis, and JWT secrets through environment variables. Never log passwords or full tokens. Keep MySQL and Redis private.
