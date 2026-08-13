## Context

仓库当前只有业务设计文档、OpenSpec 的 `spec-driven` 配置以及多种客户端生成的 OpenSpec 技能镜像；尚无提交历史、main specs、贡献指南或 PR 模板。现有 `.gitignore` 将 `docs/`、`openspec/`、`AGENTS.md` 和客户端技能目录一并排除，导致业务与规范资产无法正常进入版本控制。参见 `proposal.md` 和 `specs/development-governance/spec.md`。

## Goals / Non-Goals

**Goals:**

- 用一个仓库级入口统一模型协作、Git/PR 和 OpenSpec 生命周期规则。
- 让规则既能被人阅读，也能在 PR 检查清单和 OpenSpec 验证中被核验。
- 版本化稳定的项目知识，同时隔离客户端生成的技能镜像和敏感文件。
- 让 delta spec 同步成为实施完成与归档之间的显式步骤。

**Non-Goals:**

- 不修改校园预约系统的业务需求、DDL、API 或技术栈。
- 不在本变更中创建前后端工程骨架或 CI 平台集成。
- 不把某个供应商的具体模型版本永久写死为项目依赖。
- 不重写 `.agents/`、`.claude/`、`.cursor/` 中由工具生成的 OpenSpec 技能。

## Decisions

### 1. 使用分层但单一入口的治理结构

创建仓库级 `AGENTS.md` 作为 AI 执行入口，`CONTRIBUTING.md` 作为人类贡献入口，`.github/pull_request_template.md` 把关键要求变成逐次 PR 检查项。OpenSpec main spec 保存可验证的治理契约，避免把所有细节只留在提示词文件里。

备选方案是只写一份 README。该方案入口简单，但无法分别服务代理运行时、贡献者和 PR 审查，也不能形成规范同步链路，因此不采用。

### 2. 模型规则描述职责与调用参数，不固定易漂移版本

`AGENTS.md` 将固定主模型与子代理的职责、子代理角色、`fork_turns = "none"`、并行上限、提示字段、证据格式、等待和超时处理。具体商业模型名称仅在用户或运行环境明确要求时使用，以免模型下线或别名变化后治理规范失效。

备选方案是将当前主模型和子模型版本写死。它能提供短期一致性，但维护成本高且无法在不同客户端复用，因此不采用。

### 3. 主模型持有修改权，子代理默认只读

跨目录探索、日志分析和独立核验交给一次性子代理；主模型亲自阅读奠基性文档与即将修改的代码，负责文件修改、设计取舍和最终验证。这样既利用并行压缩上下文，也避免多代理同时写入造成冲突。

备选方案是让多个 worker 并行修改。当前项目规模和单开发者定位不需要这种复杂度，且会降低首次全栈项目的可理解性。

### 4. PR 与 OpenSpec change 一一对应

默认一个 PR 只承载一个 OpenSpec change；Codex 分支使用 `codex/<change-name>`。PR 模板要求摘要、非目标、change 链接、任务状态、验证证据、Spec sync、风险和回滚。纯内部变更也必须明确 `skip_specs` 理由。

备选方案是允许一个 PR 混合多个 change。它减少 PR 数量，但会让 delta 同步、回滚和验收边界含糊，因此不作为默认做法。

### 5. Spec 同步是独立且强制的生命周期关口

标准顺序为：`propose → review → apply → validate implementation → sync specs → validate specs → archive`。同步使用 OpenSpec sync 的语义合并规则，而非手工覆盖 main spec；归档前确认 tasks、实现验证和 main specs 全部完成。

备选方案是在 archive 时顺便同步。虽然步骤更少，但容易把同步问题拖到归档最后一刻，也不利于 PR 在合并前展示 main spec 的最终差异，因此采用显式 sync。

### 6. 调整忽略规则而不提交客户端镜像

从 `.gitignore` 移除 `docs/`、`openspec/` 和 `AGENTS.md`，新增常见 Java、Node、IDE、环境变量、日志和构建产物规则；继续忽略 `.agents/`、`.claude/`、`.cursor/` 与本地专用文件。这样首次提交可以包含权威项目资产，而不会把多个客户端生成镜像带入仓库。

备选方案是继续全量忽略并用 `git add -f`。它依赖操作者记忆且容易漏交，因此不采用。

## Risks / Trade-offs

- [治理文件重复导致规则漂移] → 明确 `AGENTS.md`、`CONTRIBUTING.md`、PR 模板和 main spec 各自受众，核心生命周期只在 main spec 定义并由其他入口引用。
- [严格 OpenSpec 流程增加小改动成本] → 对真正无行为变更允许 `skip_specs: true`，但要求记录理由。
- [首次提交包含大量现有文档] → 实施时先检查 `git status` 和文件范围，按治理、业务文档与后续代码分清提交目的。
- [没有 CI 自动阻止违规合并] → 本次先以模板、检查清单和本地验证落地；后续可用独立 OpenSpec change 增加 CI。
- [模型供应商能力不同] → 规范使用职责和证据要求描述行为，只有明确请求时才约束具体模型。

## Migration Plan

1. 更新 `.gitignore`，使权威文档与 OpenSpec 可跟踪，同时补齐敏感和生成文件规则。
2. 创建 `AGENTS.md`、`CONTRIBUTING.md` 和 PR 模板，并在 OpenSpec 配置中加入稳定项目上下文、artifact rules 与 apply/archive guidance。
3. 严格验证当前 change，随后按 apply tasks 实施并执行文件级检查。
4. 实施完成后运行 OpenSpec spec sync，生成 `openspec/specs/development-governance/spec.md`，再执行 specs 严格验证。
5. 审查 Git diff，确认没有客户端技能镜像或密钥进入提交；若需要回滚，在提交前按文件恢复，在提交后通过反向提交恢复治理文件。
