# T13 验证矩阵

状态：**DRAFT / 未执行**。本文件只定义可复用的验证入口和证据要求，不代表任何命令已经运行。所有真实运行必须在 T04-T12 merge/rebase/spec-sync 门禁满足后进行，并将原始输出写入 `deploy/artifacts/<run-id>/`。

## 证据规则

- 每次运行使用唯一 `run-id`，记录命令、工作目录、分支 HEAD、工具版本、开始/结束时间和退出码。
- 凭据只能通过当前进程环境或本地未跟踪 `.env` 注入；日志、JTL、截图、网络记录发布前必须执行对应脱敏，并人工复核 PNG。
- 本地默认只接受 `127.0.0.1`、`localhost` 或 `::1`。任何非回环地址、域名、443/TLS 或外部 DNS 操作都属于任务 8.1-8.2，必须先取得用户授权。
- 失败、缺少 fixture、缺少工具或缺少 owner 证明都要保留为 `BLOCKED`/`FAIL`，不能改写为 `PASS`。
- `HTTP 409/code 43000` 的 `SYSTEM_BUSY` 单独统计；只有精确的 slot-conflict 语义才计入 business conflict。

## 门禁矩阵

| 门禁 | 唯一入口 | 前置条件 | 原始证据 | 通过标准 | 当前状态 |
|---|---|---|---|---|---|
| 基线/ownership | `preconditions.md` | 已选 worktree 和分支 | `preconditions.md` | 基线、路径所有权、stop template 完整；T04-T12 merge/rebase/spec-sync 缺一项仍 Draft | 部分完成 |
| runner availability | `scripts/tests/t08/run.ps1 -Action Check` | Node、Chrome 可用 | `runner-check.txt` | 检查命令真实退出 0；未执行不宣称可用 | 未执行 |
| backend verify | `cd booking-api; mvn verify` | JDK 17、隔离 MySQL/Redis 或测试所需服务 | `backend-mvn-verify.log` | 真实退出 0，记录环境版本 | 未执行 |
| frontend build | `cd booking-web; npm ci; npm run build` | `package-lock.json`、Node 版本满足项目要求 | `frontend-build.log` | clean install/build 真实退出 0；T13 不修改清单 | 未执行 |
| compose topology | `docker compose --env-file .env -f deploy/compose.yml config` | 由 `.env.example` 生成本地 `.env`，只填运行时值 | `compose-config.yml` | 仅 edge 发布端口；MySQL/Redis 无 host ports；private network、healthcheck、依赖顺序和 limits 存在 | 未执行 |
| image/dependency scan | 选定仓库扫描器 + `docker image inspect` | 镜像已构建，tag→digest 已记录 | `image-scan.*` | 固定引用、无 secret、无高危未处置项 | 未执行 |
| empty migration | `deploy/scripts/empty-migration-check.ps1` | Docker、MySQL 8 镜像、两个 disposable scope | `deploy/artifacts/<run-id>/` | 两个 fresh DB 均恰好 12 张 InnoDB/utf8mb4 表、零业务行、DDL indexes/PRIMARY 完整、schema hash 相同 | 未执行 |
| backup/restore | `deploy/scripts/backup-restore-check.ps1` | 本地 compose MySQL 正常，已声明 RPO/RTO | `backup.sql`、`result.json`、definition/checksum files | source 不变；隔离 restore DB 12 表定义、代表性行数/聚合一致；RPO/RTO 有 operator 记录 | 未执行 |
| restart persistence | `deploy/scripts/restart-persistence-check.ps1 -Execute` | stack healthy、已备份 | `pre-state.txt`、`post-state.txt`、config/log/result | API/MySQL/Redis 全部 healthy；数据/定义相同；未使用 `down`、`-v` 或删卷 | 未执行 |
| Redis outage | `deploy/scripts/redis-failure-check.ps1` | 本地 token、fixture、T07 lock 已接线 | `t07-response.txt`、`result.json` | T07 409/43000/SYSTEM_BUSY、零 mutation、Redis 恢复；T12 当前 OCR-1 未解决，默认 BLOCKED_OWNER_WIRING | 未执行/受 owner 阻塞 |
| JMeter protected rounds | `deploy/jmeter/run.ps1` + `summarize.ps1` | valid seed、clean scope、JMeter 5.6.3、healthy Redis | XML JTL、metadata、report | protected same-slot 才能断言 1 success + 99 business conflict + 0 system/data/other errors；baseline/distinct 不套该断言 | 未执行/fixture 阻塞 |
| StudentBrowser | `deploy/e2e/run.ps1 -Execute -Mode StudentBrowser` | fixture owner attestation、T08 runner availability | 新建 T08 run copy、redaction manifest、PNG review index | 只接受本次新 run；文本残留为零；PNG 人工复核完成后才可发布 | 未执行/fixture 阻塞 |
| ApprovalBrowser | `deploy/e2e/run.ps1 -Execute -Mode ApprovalBrowser` | 确定性 fixture + owner-approved local executable | command status + reviewed evidence | 当前 OCR-8 未解决；即使命令执行也保持 `EXECUTED_UNPROVEN`，不计 PASS | 未执行/owner 阻塞 |
| external acceptance | 任务 8.1-8.2 | 用户授权 host/domain/DNS/TLS/credentials | 部署、HTTPS、public smoke、rollback、monitoring evidence | 所有授权和真实证据齐全；否则必须标记 not run/blocked | 未执行/未授权 |

## 推荐执行顺序

1. 完成 `preconditions.md` 中 T04-T12 的 owner merge、rebase 和 spec-sync 证明。
2. 运行静态入口和工具 availability check，记录真实版本。
3. 运行 backend/frontend build 与 compose config，随后执行空库迁移和本地 health smoke。
4. 在隔离数据范围内运行 backup/restore、restart persistence、Redis failure。
5. 分别执行三轮 JMeter，并离线生成报告；再运行 StudentBrowser。ApprovalBrowser 只有 OCR-8 解决后才进入执行。
6. 扫描所有 artifacts，人工复核截图，更新 evidence index；所有未运行或 blocked 门禁保持 Draft。
7. 外部部署仅在获得明确授权后执行，并单独记录 rollback 与 monitoring 证据。

## 当前阻塞

- `deploy/owner-change-requests.md` 中的 OCR-1、OCR-5、OCR-6、OCR-7、OCR-8 未解决。
- T13 当前分支包含必要 merge commit，但尚未合入 `main`，delta specs 尚未 sync，owner rebase 证明未收齐。
- 默认镜像 tag 尚未解析并记录 immutable digest。
- 本地 JDK/Node/Docker/JMeter/Chrome 和真实数据库、Redis 运行证据尚未采集。
- 不存在任何可发布的公共 URL、域名、TLS 证书或自动化云资源授权。
