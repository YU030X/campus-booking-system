## Why

项目已经完成业务与数据库方案设计，但仓库尚未建立可版本化的协作治理规则，模型选择、代理调用、提交与 PR 边界以及 OpenSpec 的 Spec 同步时机都缺少统一约束。现在补齐这些约束，可以在进入工程骨架开发前减少上下文漂移、越权修改、不可审查提交和 delta spec 未同步等问题。

## What Changes

- 初始化仓库级开发治理文档，明确权威文档、指令优先级、模型职责和子代理调用边界。
- 规定模型调用必须记录任务范围、上下文来源、允许修改区域、验证要求和停止条件；主模型负责决策、修改与最终验收，子代理默认只做只读探索和独立核验。
- 建立 Git 与 PR 提交规范，包括分支命名、提交粒度、Conventional Commits、提交前检查、PR 描述、验证证据、OpenSpec change 关联以及禁止提交密钥。
- 将 OpenSpec 设为需求变更的唯一规范工作流：先提案和 delta spec，再实施；实现完成后必须通过 OpenSpec sync 流程智能合并到 main specs，验证通过后才允许归档。
- 调整版本控制边界，使业务文档、OpenSpec 配置、main specs、change artifacts 和仓库治理文件可进入提交，同时继续忽略本地代理工具镜像和敏感/生成文件。
- 初始化 GitHub PR 模板和贡献入口，使规范在每次 PR 中可操作、可核验。

## Capabilities

### New Capabilities

- `development-governance`: 定义模型与子代理调用、Git/PR 提交、OpenSpec 提案—实施—同步—验证—归档生命周期，以及治理文件版本化要求。

### Modified Capabilities

<!-- 当前没有既有 main specs。 -->

## Impact

- 受影响文件预计包括仓库级代理规范、贡献指南、PR 模板、`.gitignore`、`openspec/config.yaml` 以及新的 `openspec/specs/development-governance/spec.md`。
- 不改变校园预约系统的业务 API、数据库 DDL 或运行时依赖。
- 后续功能变更和 PR 将需要关联 OpenSpec change，并提供测试/构建/检查证据；涉及需求行为的变更必须同步 delta spec 到 main specs。
