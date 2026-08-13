# 贡献指南

本指南是人类贡献者的入口；AI 代理执行时同时遵循仓库级 [AGENTS.md](AGENTS.md)。规则冲突时按以下优先级处理：用户明确要求 → `AGENTS.md` → 当前 OpenSpec change artifacts → 已同步的 main specs → `docs/` 计划与设计文档 → 本指南。当前治理 change 为 [`initialize-project-governance`](openspec/changes/initialize-project-governance/)，其 delta spec 位于 [`spec.md`](openspec/changes/initialize-project-governance/specs/development-governance/spec.md)；同步后生成的权威 main spec 预期为 [`openspec/specs/development-governance/spec.md`](openspec/specs/development-governance/spec.md)，在文件生成前不要伪造链接目标或状态。

## OpenSpec 工作流

任何可观察行为、接口、数据约束或协作契约变化，都必须按完整生命周期推进：

`propose → review → apply → validate implementation → openspec-sync-specs（语义合并）→ validate specs → archive`

1. `propose`：在 `openspec/changes/<change-name>/` 创建 proposal、design、delta specs 和 tasks。
2. `review`：由人工或主控 AI 审阅范围、非目标、依赖和验收标准；获准前不得改实现文件。
3. `apply`：按 tasks 实施，需求或设计变化时更新同一 change artifacts，不直接绕过 delta 修改 main spec。
4. `validate implementation`：运行与改动相称的真实测试、构建、迁移或检查，记录命令和实际结果。
5. `openspec-sync-specs`：使用 OpenSpec sync 技能的语义合并将 delta 合入对应 main spec，保留未变更要求与场景；禁止用复制文件覆盖。
6. `validate specs`：验证 change 与 main specs；失败则修复并保持 change 活动状态。
7. `archive`：仅在实现、验证和 spec sync 全部成功后归档。

只有纯重构、工具维护或不改变行为的文档维护可以设置 `skip_specs: true`。PR 和 change artifacts 必须写明具体理由及验证证据；任何行为变化不得以此跳过 delta spec。

## 分支、提交与 PR

- 每个 PR 只对应一个 OpenSpec change，并在主题分支上工作；Codex 分支命名为 `codex/<change-name>`，例如 `codex/add-user-auth`。
- 提交遵循 Conventional Commits（如 `feat: ...`、`fix: ...`、`test: ...`、`docs: ...`、`refactor: ...`、`chore: ...`）。每个提交应小而完整，聚焦一个可说明且可验证的目的，不为凑数量拆分，也不混入无关改动。
- PR 描述必须包含摘要、范围与非目标、对应 change 链接、tasks 状态、真实验证命令及结果、Spec sync 状态、风险与回滚；UI 改动附截图。
- 未完成 tasks、验证或 Spec sync 时，PR 必须保持 Draft 或禁止合并。不得把未运行的命令写成通过。

常用本地检查（按改动范围选择并如实记录结果）：

```bash
openspec validate <change> --type change --strict
cd booking-api && mvn test
cd booking-api && mvn verify
cd booking-web && npm run build
git diff --check
```

## 安全与协作边界

只提交 `.env.example` 等模板；真实 `.env`、数据库/Redis/JWT 密钥、完整 token、密码、个人敏感数据、依赖目录和构建产物必须保持忽略。不得在日志或测试夹具中输出密码或完整 token；MySQL 与 Redis 不得暴露公网。

共享文件遵循 [`docs/16-AI并行开发任务计划.md`](docs/16-AI并行开发任务计划.md) 的单写者规则。需要修改他人所有权目录或共享契约时，先在 PR 提交“共享文件变更申请”，由所有者以独立提交完成；不得直接越界编辑。发现契约不清、设计冲突、验证失败或需要扩大范围时立即停止并报告，禁止自行绕过规则或降低验收标准。
