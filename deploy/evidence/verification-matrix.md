# T13 验证矩阵

状态：**DRAFT / 部分实测**。T04-T12 已集成且主规格 strict validation 为 21/21；本地构建、四服务运行、backend/frontend、API integration、StudentBrowser、空库迁移、备份恢复、重启持久化和 Redis outage 均有忽略的原始证据。JMeter/Demo/扫描证据验证器的实现合同已离线通过，但真实三轮、Demo、漏洞扫描、ApprovalBrowser 和外部验收仍为 BLOCKED/NOT RUN。

## 证据规则

- 每次运行使用唯一 `run-id`，记录命令、工作目录、分支 HEAD、工具版本、开始/结束时间和退出码。
- 凭据只能通过当前进程环境或本地未跟踪 `.env` 注入；日志、JTL、截图、网络记录发布前必须执行对应脱敏，并人工复核 PNG。
- 本地默认只接受 `127.0.0.1`、`localhost` 或 `::1`。任何非回环地址、域名、443/TLS 或外部 DNS 操作都属于任务 8.1-8.2，必须先取得用户授权。
- 失败、缺少 fixture、缺少工具或缺少 owner 证明都要保留为 `BLOCKED`/`FAIL`，不能改写为 `PASS`。
- `HTTP 409/code 43000` 的 `SYSTEM_BUSY` 单独统计；只有精确的 slot-conflict 语义才计入 business conflict。

## 门禁矩阵

| 门禁 | 唯一入口 | 前置条件 | 原始证据 | 通过标准 | 当前状态 |
|---|---|---|---|---|---|
| 基线/ownership | `preconditions.md` | 已选 worktree 和分支 | `preconditions.md` | 基线、路径所有权、stop template 完整；T04-T12 merge/rebase/spec-sync 缺一项仍 Draft | PASS：merge `19649b5`，spec sync `070155f`，21/21 |
| runner availability | `scripts/tests/t08/run.ps1 -Action Check` | Node、Chrome 可用 | `runner-check.txt` | 检查命令真实退出 0；未执行不宣称可用 | PASS：`CHECK_OK`，Chrome 152 |
| backend verify | `deploy/verify/run.ps1 -Gate Backend` | JDK 17、隔离 MySQL/Redis 或测试所需服务 | `deploy/artifacts/verify-backend-t13-backend-pass/` | 真实退出 0，记录环境版本 | PASS：387/387，0 failure/error/skip |
| frontend build | `deploy/verify/run.ps1 -Gate Frontend` | `package-lock.json`、Node 版本满足项目要求 | `deploy/artifacts/verify-frontend-t13-frontend-pass/` | clean install/build 真实退出 0；T13 不修改清单 | PASS：`npm ci` + build exit 0 |
| compose topology/runtime | config + `docker compose up -d --no-build` | 本地未跟踪 env、缓存固定镜像 | `deploy/artifacts/t13-runtime-current-20260901/` | 仅 edge loopback 发布；DB/Redis private；四服务 healthy；SPA/API/limit/timeout 行为符合配置 | PASS：四服务 healthy；200/401/413/504 |
| optional TLS overlay config | `deploy/verify/run.ps1 -Mode Run -Gate tls-overlay-config -Execute` | Docker Compose CLI；无需真实证书或 daemon | `deploy/artifacts/tls-overlay-t13-tls-overlay-final/` + coordinator result | 仅 edge 发布；443→8443 loopback；证书/私钥 secrets 与只读 tls.conf 挂载；缺路径 fail-closed；结果明确未运行 TLS/HTTPS/public endpoint | PASS：11/11 config assertions；static only |
| image build/digest | `docker compose ... build --pull=false api edge` + inspect | 固定基础镜像已缓存 | `deploy/artifacts/t13-image-build-20260901-current/` | 当前 checkout 构建成功；base RepoDigests 与非 root 用户记录；无 secret | PASS：API/edge built；四个 base RepoDigest 已记录 |
| empty migration | `deploy/scripts/empty-migration-check.ps1` | Docker、MySQL 8 digest、两个 disposable scope | `deploy/artifacts/t13-empty-migration-20260901-final/` | 两个 fresh DB 均恰好 12 张 InnoDB/utf8mb4 表、零业务行、34 keys、schema hash 相同 | PASS：exit 0；完整运行元数据已记录 |
| backup/restore | `deploy/scripts/backup-restore-check.ps1` | 本地 compose MySQL、显式 RPO/RTO | `deploy/artifacts/t13-backup-restore-20260901-nonzero/` | 隔离 restore DB 12 表定义/checksum/非空代表数据一致；restore≤RTO | PASS：booking 1→1，slots 2→2；2.131s ≤ 14400s；exit 0 |
| restart persistence | `deploy/scripts/restart-persistence-check.ps1 -Execute` | stack healthy、已备份 | `deploy/artifacts/t13-restart-persistence-20260901-audited/` | API/MySQL/Redis healthy；数据/定义相同；未使用 `down`、`-v` 或删卷 | PASS：fingerprint identical；count diffs empty |
| Redis outage | `deploy/scripts/redis-failure-check.ps1` | 生成 token、owner-scoped T08 fixture、T07/T12 wiring | `deploy/artifacts/t13-redis-outage-20260901-final/` | T07 409/43000/SYSTEM_BUSY、零 mutation；T12 MySQL fallback；Redis 恢复 | PASS：T07/T12 true；Redis recovered；exit 0 |
| JMeter implementation contract | `deploy/jmeter/contract-tests.ps1`（亦由 static gate 调用） | PowerShell 7；无需 JMeter/Docker/HTTP | 合成 JTL、临时 metadata/report（默认清理） | 三轮模板、100/1/1、loopback/baseline/CSV 门、分类、元数据、证据链接、1/99、非零退出、隐私和 fail-closed 全部通过 | PASS：45 assertions；仅实现合同 |
| Demo implementation contract | `deploy/demo/contract-tests.ps1`（亦由 static gate 调用） | PowerShell 7；无需 Docker/SQL/HTTP/E2E/browser | 临时 profiles/maps（默认清理） | owner/RunId/固定角色/attestation 拒绝、零碰撞 preflight、随机 ownership tag 绑定、非秘密增量 journal、精确 child-ID 集、UTF-8 SQL 字面量、SERIALIZABLE 事务内重验/range lock/完整 child+parent cleanup/rollback、secret finally、全 Draft evidence | PASS：184 assertions；另有两次无业务写 `T13TD:0:1:1`；仅实现合同/空范围语法证据 |
| image scan evidence contract | `deploy/scan/contract-tests.ps1`（亦由 static gate 调用） | PowerShell 7；无需 scanner/Docker/registry/advisory/network | 合成 Trivy/Grype reports/manifests（默认清理） | 覆盖路径/junction、hash、image ID/ref/可选 digest、报告结构、DB freshness、counts、decision、scanner exit 与有界日志凭据筛查的 fail-closed 分支 | PASS：28 assertions；仅验证器合同 |
| local vulnerability scan | `deploy/scan/run.ps1 -Action Environment/Validate` | 已安装 Trivy/Grype、本地 advisory DB、API/edge 原始 JSON 和执行日志 | ignored scan bundle + normalized result | 两镜像、fresh DB、hash/image/count 一致、scanner exits 0、UNKNOWN/HIGH/CRITICAL 为零；SBOM 不计 | BLOCKED：无支持的本地 scanner+DB；未扫描 |
| JMeter protected rounds | `deploy/jmeter/run.ps1` + `summarize.ps1` | valid seed、clean scope、JMeter 5.6.3、healthy Redis | XML JTL、metadata、report | protected same-slot 才能断言 1 success + 99 business conflict + 0 system/data/other errors；baseline/distinct 不套该断言 | 未执行/JMeter+fixture+history 阻塞 |
| API integration | `deploy/e2e/run.ps1 -Execute -Mode ApiIntegration` | 隔离 loopback MySQL/Redis、运行时凭据 | `deploy/artifacts/e2e-ApiIntegration-t13-api-integration-pass/` | 固定 37 类全部存在并执行；任何失败非零 | PASS：195/195，37 类，exit 0 |
| StudentBrowser | `deploy/e2e/run.ps1 -Execute -Mode StudentBrowser` | fixture attestation、T08 runner availability | `deploy/artifacts/e2e-StudentBrowser-t13-student-browser-final/` | 只接受本次新 run；文本残留为零；PNG 人工复核完成后才可发布 | PASS：15/15；52/52 PNG 人工复核；redaction residual 0 |
| ApprovalBrowser | `deploy/e2e/run.ps1 -Execute -Mode ApprovalBrowser` | 确定性 fixture + owner-approved local executable | command status + reviewed evidence | 当前 OCR-8 未解决；即使命令执行也保持 `EXECUTED_UNPROVEN`，不计 PASS | 未执行/owner 阻塞 |
| external acceptance | 任务 8.1-8.2 | 用户授权 host/domain/DNS/TLS/credentials | 部署、HTTPS、public smoke、rollback、monitoring evidence | 所有授权和真实证据齐全；否则必须标记 not run/blocked | 未执行/未授权 |

## 推荐执行顺序

1. 已完成 `preconditions.md` 中 T04-T12 integration/spec-sync 证明。
2. 已完成静态入口、runner availability、backend/frontend、Compose config、API integration 与 StudentBrowser。
3. 已从当前 checkout 构建并运行 API/edge，完成 health/HTTP smoke、空库迁移、backup/restore 和 restart persistence。
4. Redis outage 已通过；保留失败重跑记录以说明两个 T13 harness 修复，不执行 `down -v`。
5. 可选 TLS overlay 的静态拓扑已通过；真实证书/HTTPS 仍等待 8.1 授权。JMeter/Demo/扫描验证器离线实现合同已通过；取得 JMeter 5.6.3、fixture、历史镜像、Demo owner attestation 或本地 scanner+advisory DB 后再运行相应真实门禁。ApprovalBrowser 只有 OCR-8 解决后才进入执行。
6. 对已有 artifacts 做 secret/PII 复核并更新 evidence index；漏洞扫描仍保持 BLOCKED，不能用 SBOM 或合成合同替代。
7. 外部部署仅在获得明确授权后执行，并单独记录 rollback 与 monitoring 证据。

## 当前阻塞

- OCR-1 已由 owner 分支解决；OCR-5、OCR-6、OCR-7、OCR-8 仍阻塞 Demo/JMeter/ApprovalBrowser。
- T13 integration/spec-sync 门禁已满足；change 仍因运行时与外部门禁保持 Draft。
- 固定标签的远端刷新曾失败，但当前缓存镜像均有 RepoDigest，API/edge 已成功构建并运行；本机没有受支持的本地 scanner+advisory DB，漏洞扫描未执行。Docker Scout/SBOM 缓存不构成离线 CVE 证据。
- Docker 四服务和 Redis outage 本地证据已采集；JMeter 5.6.3 未安装。
- Demo Setup 的 journaled 范围已有事务补偿设计，但 mutation commit→journal write 的硬中断窗口仍需人工按 recovery scope 审核；owner review/attestation 前不得真实执行。
- 不存在任何可发布的公共 URL、域名、TLS 证书或自动化云资源授权。
