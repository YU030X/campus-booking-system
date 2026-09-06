# 本地验收手册（ACCEPTANCE GUIDE）

> 适用状态：main 分支（本地 compose 栈，无公网）。
> 三种深度任选：**A 快速体验（30 分钟，纯浏览器）**、**B 完整人工验收（半天，浏览器+SQL）**、**C 自动化门禁（命令逐条）**。
> 约束提醒：全程**不删库**；所有清理只针对下文创建的验收账号/数据。

---

## 0. 环境准备（一次性）

```powershell
# 1) 启动 Docker Desktop,等待绿色状态
# 2) 启动四服务栈(mysql/redis/api/edge,edge 仅 127.0.0.1:18080)
cd deploy
docker compose --env-file .env -f compose.yml up -d
docker ps            # 四个 campus-booking-* 容器应为 healthy
# 3) 数据库迁移(仅全新数据卷需要;数据卷已持久化则跳过)
cd ..
Get-Content sql/V001__create_database.sql, sql/V002__create_user_and_resource_tables.sql,
  sql/V003__create_booking_tables.sql, sql/V004__create_support_tables.sql,
  sql/V005__post_seed_placeholder.sql |
  docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
```

前端入口二选一：
- **A/B 验收看这里**：直接浏览器打开 `http://127.0.0.1:18080`（edge 托管的生产构建 + 同源 API 代理，无需 node）
- 开发调试：`cd booking-web && npm ci && npm run dev` → `http://127.0.0.1:4173`

---

## A. 快速体验（30 分钟，纯浏览器）

1. 打开 `http://127.0.0.1:18080` → 注册一个学生账号 → 登录
2. 资源目录：看到资源卡片 → 进入详情 → 选择明天日期 → 可用时段加载（绿色可选/灰色不可选）
3. 创建预约：任选 30 分钟时段提交 → 列表出现新预约（状态徽标正确）
4. **冲突复现**：再次提交完全相同的时段 → 得到 409「该时段已被占用，请刷新后重试」且页面时段自动刷新
5. 打开预约详情：核对 14 个字段 + 当前状态时间线
6. 取消该预约：确认后状态变已取消、时段释放（再订同时段又能成功）
7. 右上角退出 → 401 会自动跳回登录页

预期：以上全部无报错完成。任何一步异常先看 `docker logs campus-booking-api-1 --tail 50`。

---

## B. 完整人工验收（半天）

### B1 管理员链路

```powershell
# 产生管理员(注册一个账号后,容器内提升角色):
docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot booking_db' <<'SQL'
UPDATE user SET role='ADMIN' WHERE username='你的管理员账号';
SQL
```

用该账号登录 → 验证：
- `/admin/resources`、`/admin/rules`、`/admin/closures`：资源与时段规则 CRUD（新建一个测试资源,设 need_approval=1）
- 学生账号对 need_approval 资源下单 → 状态 `PENDING_APPROVAL`
- `/admin/approvals`：批准一件、驳回一件（驳回必填备注、空备注被拦截）
- 学生侧刷新：批准件变已确认、驳回件显示驳回原因

### B2 违约/黑名单链路（no-show 定时任务实证）

```powershell
# 用验收学生+测试资源,SQL 造一条"昨天已结束仍 CONFIRMED"的预约(严格匹配拥有者,不动他人数据):
docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot booking_db' <<'SQL'
INSERT INTO booking(booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,deleted,created_at,updated_at)
SELECT CONCAT('ACC-',id),id,880001,DATE_SUB(NOW(),INTERVAL 2 HOUR),DATE_SUB(NOW(),INTERVAL 1 HOUR),
       'acceptance no-show probe',1,'CONFIRMED',0,NOW(),NOW() FROM user WHERE username='你的学生账号';
INSERT INTO booking_slot(resource_id,slot_time,booking_id)
SELECT resource_id,start_time,id FROM booking WHERE purpose='acceptance no-show probe';
SQL
# 等待 no-show 扫描周期(分钟级,默认每分钟),然后核对:
docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot booking_db' <<'SQL'
SELECT b.status, v.violation_type, v.score_change FROM booking b
LEFT JOIN violation_record v ON v.booking_id=b.id WHERE b.purpose='acceptance no-show probe';
SQL
```

预期：状态变 `NO_SHOW`、出现 `NO_SHOW` 违约记录、信用分扣减；浏览器里该预约详情同步呈现。验收后如需清理，仅删除本探针创建的行（purpose 前缀定位）。

### B3 通知与统计

- 学生侧留有未读通知 → 顶部通知中心有红点 → 打开列表 → 一键已读
- 管理端 `/admin/statistics`：选定日期范围 → 资源使用率与预约状态分布出数

---

## C. 自动化门禁（逐条命令 + 通过标准）

| # | 门禁 | 命令 | 通过标准 |
|---|---|---|---|
| C1 | 后端全量（真 MySQL/Redis） | 见下方"临时验证库"配方 | `Tests run: 389, Failures: 0` + `BUILD SUCCESS` |
| C2 | 前端构建 | `cd booking-web && npm ci && npm run build` | `built in ...` 无 error |
| C3 | 部署静态总门禁 | `pwsh deploy/verify/run.ps1 -Mode Check -RunId <自定义>` | `STATIC CHECK OK` |
| C4 | OpenSpec 规格 | `openspec validate --specs --strict --no-interactive` | 26 passed, 0 failed |
| C5 | OpenSpec 变更 | `openspec validate verify-and-deploy-system --type change --strict --no-interactive` | valid |
| C6 | 浏览器 E2E（学生 15 用例） | 见下方"T08 配方" | `Result: PASS (passed 15/15)` |
| C7 | 漏洞扫描证据校验 | `pwsh deploy/scan/run.ps1 -Action Validate -ManifestPath deploy/artifacts/t13-real-scan-20260904-deps2/manifest.json -RunId <自定义>` | `VALIDATED_SCAN_PASS`, exit 0 |
| C8 | Demo 生命周期 | `pwsh deploy/demo/run.ps1 -Mode All -Execute -ProfilePath <profile> -RunId <自定义>` | `DEMO RUN COMPLETE`，teardown 行增量归零 |

**C1 临时验证库配方**（后端测试需要宿主机可达的 MySQL/Redis,compose 栈是内网不给宿主端口,用一次性容器）：

```powershell
docker run -d --name acc-mysql -e MYSQL_ROOT_PASSWORD=acc-root -p 127.0.0.1:13306:3306 mysql:8.0.40
docker run -d --name acc-redis -p 127.0.0.1:16380:6379 redis:7.4.9
Start-Sleep -Seconds 25
Get-Content sql/V001__create_database.sql, sql/V002__create_user_and_resource_tables.sql,
  sql/V003__create_booking_tables.sql, sql/V004__create_support_tables.sql, sql/V005__post_seed_placeholder.sql |
  docker exec -i acc-mysql sh -c 'MYSQL_PWD="acc-root" mysql -uroot'
cd booking-api
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/booking_db?serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
$env:DB_USERNAME='root'; $env:DB_PASSWORD='acc-root'; $env:BOOKING_MYSQL8_TEST='true'
$env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT='16380'
$env:JWT_SECRET='acc-jwt-secret-0123456789abcdef-0123456789'
mvn verify
# 结束后清理一次性容器(仅本验收创建):
docker rm -f acc-mysql acc-redis
```

**C6 T08 配方**（依赖：前端 4173 由仓库内 vite 配置启动、18080 栈、Redis 转发侧车）：

```powershell
# redis 转发侧车(backend 内网不给宿主端口,用 sidecar 暴露到 127.0.0.1:26379):
docker create --name t08-redis-fwd -p 127.0.0.1:26379:16379 `
  -v "$PWD\deploy\artifacts\t13-redis-fwd\nginx-stream.conf:/etc/nginx/nginx.conf:ro" `
  nginxinc/nginx-unprivileged:1.27.4-alpine
docker network connect campus-booking_frontend t08-redis-fwd
docker network connect campus-booking_backend t08-redis-fwd
docker start t08-redis-fwd
# 前端 dev server(4173,同源代理到 18080):
cd booking-web; ./node_modules/.bin/vite.cmd --config ..\scripts\tests\t08\vite.config.mjs
# 种子 + 运行(T08 自注册自己的 QA 账号,互不影响业务数据):
printf "SET time_zone='+08:00';\n" | cat - scripts/tests/t08/seed.sql |
  docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot booking_db'
$env:T08_QA_FRONTEND='http://127.0.0.1:4173'; $env:T08_QA_BACKEND='http://127.0.0.1:18080'
$env:T08_QA_REDIS_HOST='127.0.0.1'; $env:T08_QA_REDIS_PORT='26379'
pwsh scripts/tests/t08/run.ps1 -Action Run
# 结束: docker rm -f t08-redis-fwd
```

---

## 证据导览（已归档,可直接查看）

| 证据 | 位置 |
|---|---|
| 并发三轮压测报告 | `deploy/evidence/jmeter-concurrency-2026-09-04.md` |
| 缓存冷热对比 | `deploy/evidence/cache-benchmark-2026-09-04.md` |
| 漏洞扫描 PASS 证据 | `deploy/artifacts/t13-real-scan-20260904-deps2/`（validator result: `VALIDATED_SCAN_PASS`） |
| 浏览器学生端 15/15 | `deploy/artifacts/e2e-StudentBrowser-*`（52 PNG 已人工复核） |
| 审批链路浏览器证据 | `deploy/artifacts/e2e-ApprovalBrowser-approval-real-20260904/` |
| 备份/恢复、重启持久化、Redis 故障 | `deploy/artifacts/t13-*20260901*/` |
| 每轮交接审计 | 根目录 `handoff.md` |

## 验收结论记录（建议自留）

```
验收人: ____________  日期: ____________
A 快速体验:        全部通过 / 异常项: ____________
B1 管理员链路:      通过 / 异常: ____________
B2 违约链路:        NO_SHOW+扣分 可见 / 异常: ____________
B3 通知与统计:      通过 / 异常: ____________
C1 后端 389/0:      是 / 否
C2 前端构建:        是 / 否
C3 静态总门禁:      OK / 失败项: ____________
C4/C5 OpenSpec:     26/26 + valid / 异常: ____________
C6 浏览器 15/15:    是 / 否(可复用既有证据 deploy/artifacts/e2e-StudentBrowser-*)
C7 扫描 PASS:       是 / 否(可复用既有证据 t13-real-scan-20260904-deps2)
结论: 本地验收 通过 / 不通过
```
