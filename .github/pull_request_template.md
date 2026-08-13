## 摘要

<!-- 用几句话说明改动解决的问题与可观察结果。一个 PR 只对应一个 OpenSpec change。 -->

## OpenSpec change

- Change 路径：`openspec/changes/<change-name>/`
- [ ] 已关联 proposal、design、specs 与 tasks
- [ ] 本 PR 为纯内部维护并明确 `skip_specs: true` 理由：

<!-- 若选择 skip_specs，请说明为何不改变可观察行为、接口、数据约束或协作契约。 -->

## 范围与非目标

### 范围

- <!-- 填写 -->

### 非目标

- <!-- 填写 -->

## 任务状态

- [ ] OpenSpec tasks 已全部完成（请列出未完成项）
- [ ] 只修改任务卡允许的路径
- [ ] 已复核与 [CONTRIBUTING.md](../CONTRIBUTING.md) 及仓库规则的交叉引用

## 验证证据

逐条记录实际执行的命令和结果；未执行的命令必须标记“未执行”，不得勾选为通过。

| 命令 | 实际结果 | 状态 |
| --- | --- | --- |
| `openspec validate <change> --type change --strict` | <!-- 粘贴摘要或失败原因 --> | [ ] 通过 |
| <!-- 测试/构建命令 --> | <!-- 实际输出摘要；未执行请写明“未执行” --> | [ ] 通过 |
| `git diff --check` | <!-- 实际结果；未执行请写明“未执行” --> | [ ] 通过 |

## Spec sync 状态

- [ ] 已使用 OpenSpec sync 工作流合并 delta（禁止直接复制覆盖）
- Main spec 路径：`openspec/specs/<capability-path>/spec.md`
- [ ] main specs 已验证
- [ ] 若尚未完成，已保持 Draft 并说明阻塞项：

## 风险与回滚

- 风险：
- 监测/缓解：
- 回滚方式（反向提交或恢复的文件/步骤）：

## UI 截图

<!-- 涉及界面时必须附必要的前后截图；不涉及界面请写“不适用”。 -->

- 前：
- 后：

## 安全

- [ ] 未提交真实 `.env`、密钥、完整 JWT 或个人敏感数据
- [ ] 未记录密码、完整 Token 或其他敏感日志
- [ ] 数据库、Redis 等内部服务未被暴露到公网

## Draft / 合并门

<!-- tasks、验证、Spec sync 任一未完成时，必须保持 Draft，禁止合并。 -->

- [ ] tasks 全部完成
- [ ] 所有相关验证已实际执行且通过
- [ ] delta 已同步并通过 main specs 验证
- [ ] 满足以上条件，可从 Draft 转为 Ready；否则保持 Draft / 禁止合并
