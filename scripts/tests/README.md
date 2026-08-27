# scripts/tests — 可复用测试脚本目录

本目录收录**固化在仓库内的可复用验收/回归脚本**。规则:

1. **先复用已有脚本**:执行任何验证前,先查本目录是否已有对应入口,直接调用。
2. **缺失才新增**:确无覆盖时新增脚本(含 README 说明与自身 `.gitignore`),并遵循所在模块的命名约定。
3. **不得在会话里临时重写相同测试**:禁止为一次性执行把等价逻辑重写到 `target/` 或其他临时位置;发现脚本有缺陷应修复脚本本身。

## 目录

| 路径 | 用途 | 入口 |
| --- | --- | --- |
| `t08/` | T08 学生预约 headless 真实链路 QA(CDP + 本机 Chrome) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Check|List|Smoke|Run` |

各子目录的详细前置条件、环境变量与退出码见其各自 `README.md`(如 `t08/README.md`)。
