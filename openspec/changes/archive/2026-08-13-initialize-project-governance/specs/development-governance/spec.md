## Purpose

为本仓库的人类开发者与 AI 编码代理提供统一、可审查且可验证的协作契约，确保模型调用、Git/PR 交付和 OpenSpec 规范生命周期不会彼此脱节。

## ADDED Requirements

### Requirement: 模型调用必须有明确的职责与上下文边界
每次 AI 开发任务 MUST 明确主模型负责最终决策、范围控制、实际修改和验收；传给模型或子代理的任务说明 MUST 包含仓库根目录、允许的操作、检索或修改范围、待回答问题、期望证据以及停止条件。仓库规范 MUST NOT 依赖易漂移的具体商业模型版本名称才能成立。

#### Scenario: 主模型开始实现任务
- **WHEN** AI 收到需要修改仓库的任务
- **THEN** 主模型先读取适用的仓库规则和 OpenSpec 上下文，并对修改范围与验证标准负责

#### Scenario: 任务需要固定模型能力
- **WHEN** 用户或适用规则明确要求某个模型、角色或推理等级
- **THEN** 调用方按该明确要求调用，并在不可用时报告降级，而不是静默替换

### Requirement: 子代理只承担可压缩的探索与核验
子代理 MUST 默认用于跨文件、跨目录、日志量大或相互独立的只读探索与核验；即将修改的确切代码和奠基性文档 MUST 由主模型亲自阅读。子代理调用 MUST 使用干净上下文、默认角色和自包含提示，且 MUST NOT 修改文件，除非用户或更高优先级规则明确授权其实现责任。

#### Scenario: 多个独立探索问题
- **WHEN** 存在两个或以上彼此独立且较重的检索或核验问题
- **THEN** 主模型可并行派发最多六个一次性子代理，并要求返回文件路径、行号和必要原文

#### Scenario: 子代理返回结论
- **WHEN** 主模型收到子代理的探索结果
- **THEN** 主模型只沿关键证据点做抽查并承担最终判断，不把子代理摘要当作未经核验的最终验收

#### Scenario: 子代理运行异常
- **WHEN** 子代理累计运行十分钟仍未完成
- **THEN** 主模型介入检查、采用可用的部分结果并停止异常代理，而不是无限等待

### Requirement: 治理资料必须进入版本控制
仓库 MUST 版本化业务文档、OpenSpec 配置、main specs、active change artifacts、仓库级代理规则、贡献指南和 PR 模板；本地代理技能镜像、编辑器私有配置、密钥、环境文件、构建产物和依赖目录 MUST 保持忽略。

#### Scenario: 创建治理类提交
- **WHEN** 开发者检查待提交文件
- **THEN** `docs/`、`openspec/`、`AGENTS.md`、贡献指南和 PR 模板能够被 Git 正常跟踪

#### Scenario: 检查本地或敏感文件
- **WHEN** 工作区包含本地代理镜像、真实 `.env`、密钥或构建产物
- **THEN** 这些文件不会进入普通提交候选范围

### Requirement: Git 提交必须小而完整
开发 MUST 在主题分支上进行，Codex 创建的分支 MUST 使用 `codex/<openspec-change-name>`；提交信息 MUST 遵循 Conventional Commits，单个提交 MUST 聚焦一个可说明的目的并在提交前通过与该改动相称的检查。开发者 MUST NOT 为追求数量拆分无意义提交，也 MUST NOT 把不相关改动混入同一提交。

#### Scenario: AI 为 OpenSpec change 开分支
- **WHEN** Codex 需要为 `add-user-auth` 创建开发分支
- **THEN** 分支命名为 `codex/add-user-auth`

#### Scenario: 完成一个可验证改动
- **WHEN** 一个小功能、修复、测试或文档更新已完成且相关检查通过
- **THEN** 使用 `feat:`、`fix:`、`test:`、`docs:`、`refactor:`、`chore:` 等合适类型提交该聚焦改动

### Requirement: PR 必须提供完整审查证据
所有合并到主分支的改动 MUST 通过 PR。PR MUST 关联一个 OpenSpec change 或明确说明为何属于允许 `skip_specs` 的无行为变更，并包含摘要、范围与非目标、验证命令及结果、Spec 同步状态、风险与回滚方式；涉及界面的 PR MUST 提供必要的前后截图。PR 在 OpenSpec 任务、相关验证或 Spec 同步尚未完成时 MUST 保持 Draft 或不得合并。

#### Scenario: 行为变更 PR 准备审查
- **WHEN** PR 改变了可观察的业务或协作行为
- **THEN** PR 描述列出对应 OpenSpec change、delta spec、已执行验证和 main spec 同步状态

#### Scenario: 纯内部维护 PR
- **WHEN** PR 仅包含不改变规范行为的重构、工具或文档维护
- **THEN** PR 明确记录 `skip_specs` 理由，并仍提供与风险相称的验证证据

#### Scenario: PR 尚未完成
- **WHEN** 必需任务、测试、构建、检查或 Spec 同步仍有未完成项
- **THEN** PR 保持 Draft 或阻止合并，且不得把未执行的验证写成已通过

### Requirement: OpenSpec 是需求变更的规范生命周期
任何改变可观察行为、接口、数据约束或仓库协作契约的工作 MUST 先创建 OpenSpec proposal 与 delta spec，经审阅后再 apply。实现期间发现需求或设计变化时 MUST 更新同一 change artifacts，禁止通过直接修改 main spec 绕过 delta。纯重构、工具或无行为文档变更只有在明确设置并解释 `skip_specs: true` 时才可省略 delta spec。

#### Scenario: 开始实现新能力
- **WHEN** change 的 proposal、delta spec、design 和 tasks 尚未准备完成或未获准应用
- **THEN** 不开始修改项目实现文件

#### Scenario: 实现暴露设计偏差
- **WHEN** apply 过程中发现既有 delta spec 或设计无法准确描述所需行为
- **THEN** 暂停相关实现并更新 change artifacts，保持任务、设计和 delta spec 一致

### Requirement: Delta spec 必须通过 OpenSpec 同步到 main specs
实现完成且 delta spec 最终确定后，开发者 MUST 使用 OpenSpec 的 spec sync 工作流把所有适用 delta 智能合并到对应 main specs，并运行 OpenSpec specs 验证。同步 MUST 保留 delta 未修改的既有要求与场景，MUST NOT 直接复制 delta 文件覆盖 main spec，也 MUST NOT 在同步和验证成功前归档 change。

#### Scenario: 行为变更实现完成
- **WHEN** OpenSpec change 的实现任务全部完成且 delta spec 已确认
- **THEN** 执行 OpenSpec sync，将 delta 合并到 `openspec/specs/<capability-path>/spec.md`，随后执行 specs 验证

#### Scenario: 同步验证失败
- **WHEN** OpenSpec specs 验证报告格式、需求或场景错误
- **THEN** change 保持活动状态并修复 main spec，不宣称同步成功或归档

#### Scenario: 准备归档 change
- **WHEN** 实现、项目验证和 main spec 同步均已成功
- **THEN** 才允许执行 OpenSpec archive，并在 PR 中记录最终状态
