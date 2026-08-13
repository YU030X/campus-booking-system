## 1. 版本控制边界

- [x] 1.1 更新 `.gitignore`：允许跟踪 `docs/`、`openspec/` 和仓库治理文件，并补齐 Java、Node、IDE、日志、环境变量、密钥与构建产物忽略项
- [x] 1.2 使用 `git status` 与 `git check-ignore` 验证权威资料可跟踪、本地代理镜像和敏感文件仍被忽略

## 2. 模型与 OpenSpec 治理

- [x] 2.1 创建仓库级 `AGENTS.md`，写明指令优先级、主模型职责、子代理适用条件、调用参数、证据要求、等待/超时规则和禁止越权修改边界
- [x] 2.2 更新 `openspec/config.yaml`，加入项目技术与业务上下文、proposal/specs/tasks 规则以及 apply、sync、archive 操作指导
- [x] 2.3 创建 `CONTRIBUTING.md`，明确 `propose → review → apply → validate → sync specs → validate specs → archive` 流程和 `skip_specs` 的使用边界

## 3. Git 与 PR 规范

- [x] 3.1 在 `CONTRIBUTING.md` 中定义主题分支、`codex/<change-name>`、Conventional Commits、提交粒度、提交前验证和密钥保护要求
- [x] 3.2 创建 `.github/pull_request_template.md`，包含 OpenSpec change、范围/非目标、任务状态、验证证据、Spec sync、风险/回滚与 UI 截图检查项
- [x] 3.3 复核治理文件交叉引用，确保 PR 不允许把未执行的检查写成通过，未完成任务或未同步 Spec 时必须保持 Draft 或禁止合并

## 4. 验证与 Spec 同步

- [x] 4.1 运行 OpenSpec 严格验证并修复当前 change 的所有格式或一致性问题
- [x] 4.2 通过 OpenSpec spec sync 工作流将 `development-governance` delta 合并为 main spec，不以文件复制覆盖代替语义合并
- [x] 4.3 运行 main specs 验证、`git diff --check` 和最终工作区审计，记录实际执行结果并确认 change 可进入归档评审
