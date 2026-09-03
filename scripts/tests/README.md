# scripts/tests — 可复用测试脚本目录

本目录收录固化在仓库内的可复用验收、回归和环境脚本。执行前先复用已有入口；确无覆盖时才新增或修改脚本，禁止在会话、`target/` 或临时目录中重复编写等价 harness。每个 scope 提供一个文档化的 Windows-first `run.ps1`，日志、截图、凭据和运行证据由局部 `.gitignore` 排除。

## 目录

| Scope | 用途 | 入口 |
| --- | --- | --- |
| `t08/` | 学生预约 headless 真实链路 QA | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Check\|List\|Smoke\|Run` |
| `t11/` | 管理端用户与审批纯测试 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t11\run.ps1 -Mode Check\|List\|Unit\|All` |
| `t12/` | 日志、缓存、通知、统计与 P1 页面 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t12\run.ps1 -Mode Check\|List\|OperationLog\|Cache\|RealCache\|Notifications\|Statistics\|Frontend\|Flags\|CutMatrix\|Unit` |

## T12 modes

T12 的 Maven 模式使用 `booking-api/` 下的窄 Surefire 选择器；不等同于完整 `verify`。Notifications/Statistics 及 RealCache/CutMatrix 包含需要真实 MySQL 或 Redis 的集成类。

- `Check`：验证四个主/测试树、运行 `git diff --check`，并拒绝未交接的共享路径漂移。
- `List`：列出后端切片与前端契约测试，不执行。
- `OperationLog`：`com.yu030x.booking.log.**`。
- `Cache`：`com.yu030x.booking.cache.**`，排除真实 Redis 标签。
- `RealCache`：真实 MySQL 8 + Redis 7 缓存及 owner 变更集成选择。
- `Notifications`：`com.yu030x.booking.notification.**`。
- `Statistics`：`com.yu030x.booking.statistics.**`，包含 MySQL EXPLAIN 证据。
- `Frontend`：通知/统计 Node 契约测试及 `npm run build`。
- `Flags`：四个独立 opt-in/default-false 契约。
- `CutMatrix`：依次切断统计、通知、缓存，每阶段重跑完整 `booking/**` T07 选择。
- `Unit`：四个后端切片的联合选择，排除真实 Redis 标签。

各子目录 README 记录前置条件、退出码和证据位置；尚未实际执行的模式不得声明通过。
