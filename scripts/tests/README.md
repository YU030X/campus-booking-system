# scripts/tests — 可复用测试脚本目录

本目录收录固化在仓库内的可复用验收、回归和环境脚本。规则：

1. **先复用已有脚本**：执行验证前先查找本目录中的对应入口并直接调用。
2. **缺失才新增**：确无覆盖时才新增脚本、README 和局部 `.gitignore`。
3. **禁止临时重写**：不得在会话、`target/` 或临时目录中重复编写等价 harness；发现缺陷应修复仓库脚本本身。
4. 每个 scope 只提供一个文档化的 Windows-first `run.ps1`；日志、截图、凭据和运行证据必须局部忽略。

## 目录

| Scope | 用途 | 入口 |
| --- | --- | --- |
| `t08/` | 学生预约 headless 真实链路 QA | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Check|List|Smoke|Run` |
| `t11/` | 管理端用户与审批纯测试 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t11\run.ps1 -Mode Check|List|Unit|All` |
| `t12/` | 日志、缓存、通知、统计后端切片 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t12\run.ps1 -Mode Check|List|OperationLog|Cache|Notifications|Statistics|Unit` |

T12 的 Maven 模式使用 `booking-api` 下的窄 Surefire 选择器；Notifications/Statistics 包含需要真实 MySQL 的集成类。各子目录的前置条件、退出码和证据位置见其 README；尚未实际执行的模式不得声明通过。
