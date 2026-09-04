# Campus Booking — 校园场地/设备预约管理系统

> Spring Boot 3.5 · MySQL 8 · Redis 7 · Vue 3 + Element Plus · Docker Compose
> 完整走通「需求 → 数据库 → 接口 → 前端 → 部署 → 压测」全链路的实战项目。
> 业务方案见本地 `docs/06-项目一详细方案.md`；规格与变更历史见 `openspec/`。

## 功能总览

- **学生端**：注册/登录 · 资源目录与可用时段 · 直接/待审批预约 · 冲突提示与自动刷新 · 取消与时段释放 · 预约详情（14 字段 + 七状态时间线）· 通知中心
- **管理端**：资源/分类/时段规则/闭馆管理 · 预约审批（批准/驳回）· 用户检索与禁用 · 违约与黑名单 · 统计看板（资源使用率/预约状态分布）
- **横切能力**：JWT 认证与 RBAC · AOP 操作日志（脱敏/限长/失败隔离）· 可用时段 Cache Aside（TTL 5–15 分钟、提交后失效）· 应用内通知（投递幂等）· 四个独立默认关闭的功能开关

## 架构

```mermaid
flowchart LR
    subgraph Client["浏览器 (Vue 3 + Element Plus)"]
        V["SPA 静态资源 + Axios"]
    end
    subgraph Edge["Nginx (edge, 仅 127.0.0.1:18080)"]
        N["静态资源 + /api 反向代理 + 安全响应头"]
    end
    subgraph API["Spring Boot (api, 内网)"]
        C["Controllers (@Valid)"]
        S["Services (事务/业务规则)"]
        L["AOP 操作日志"]
        K["BookingLockCoordinator (Redisson 看门狗锁)"]
        B["BookingCreator (30 分钟时间片离散化)"]
        M["Mappers (MyBatis)"]
        T["NoShow 定时扫描 (幂等)"]
    end
    subgraph Data[("数据层 (内网)")]
        DB[("MySQL 8<br/>booking_slot.uk_resource_slot<br/>= 并发正确性最终防线")]
        R[("Redis 7<br/>分布式锁 + 可用性缓存")]
    end
    V --> N --> C --> S
    S --> K --> B --> M --> DB
    S --> R
    L --> DB
    T --> DB
```

**并发正确性三层防线**（压测实证见 `deploy/evidence/jmeter-concurrency-2026-09-04.md`）：

1. **Redisson 分布式锁**（性能层）：按 `resourceId:date` 加锁,只传 waitTime 让看门狗续期,unlock 前判 `isHeldByCurrentThread`
2. **`booking_slot.uk_resource_slot` 唯一索引**（正确性层）：30 分钟时间片把"区间重叠"变成"离散值冲突",数据库兜底
3. **业务规则**：提前天数/单次时长/并发持有上限/黑名单/信用分

压测数据（100 线程同槽）：无索引基线 **11 个重复预约成功**；仅唯一索引 1/99/0;索引+锁 1/99/0（锁队列 p99 2.7s,用延迟换数据库保护）。

## 快速启动（本地 Docker）

```bash
cd deploy
cp .env.example .env            # 按需修改口令
docker compose up -d            # mysql/redis/api/edge 四服务,edge 仅 127.0.0.1:18080
cd ..
docker exec -i campus-booking-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot booking_db' < sql/V001__create_database.sql
# 依次应用 V002..V005
cd booking-web && npm ci && npm run dev   # 前端 dev server(同源代理 /api)
```

默认四项可选能力（操作日志/可用性缓存/通知/统计）全部**默认关闭**,按环境变量独立开启（见 `deploy/.env.example`）。

## 测试与验收

| 层 | 入口 |
|---|---|
| 后端全量 | `cd booking-api && mvn verify`（387 tests,真 MySQL/Redis） |
| 前端构建 | `cd booking-web && npm run build` |
| 验收回归 | `pwsh scripts/tests/<scope>/run.ps1`（t08 浏览器 15 用例、t11 管理端、t12 功能开关矩阵等） |
| 部署门禁 | `pwsh deploy/verify/run.ps1 -Mode Check`（静态门禁）/ 各 runtime gate |
| 并发压测 | `deploy/evidence/jmeter-concurrency-2026-09-04.md`（三轮对比 + harness） |
| 漏洞扫描 | `pwsh deploy/scan/run.ps1`（离线 Trivy 证据校验） |
| OpenSpec | `openspec validate <change> --type change --strict` |

## 接口一览

共 32 个端点（认证 `POST /api/v1/auth/register|login`;学生 `GET /api/v1/resources[/{id}/availability]`、`/bookings`、`POST /api/v1/bookings`、`POST /api/v1/bookings/{id}/cancel|checkin`、`GET /api/v1/notifications`、`POST /api/v1/notifications/{id}/read`;管理端见下表）。统一响应 `Result<T>`；错误码按模块分段（40xxx 通用/认证/授权/未找到,41xxx 用户,42xxx 资源,43xxx 预约）。

| 方法 | 路径 | 模块 |
|---|---|---|
| `GET` | `/api/v1/admin/approvals` | ApprovalAdmin |
| `POST` | `/api/v1/admin/bookings/{id}/approve` | ApprovalAdmin |
| `POST` | `/api/v1/admin/bookings/{id}/reject` | ApprovalAdmin |
| `POST` | `/api/v1/admin/categories` | Category |
| `DELETE` | `/api/v1/admin/categories/{id}` | Category |
| `PUT` | `/api/v1/admin/categories/{id}` | Category |
| `POST` | `/api/v1/admin/resources` | Resource |
| `PUT` | `/api/v1/admin/resources/{id}` | Resource |
| `POST` | `/api/v1/admin/resources/{id}/closures` | Resource |
| `DELETE` | `/api/v1/admin/resources/{id}/closures/{closureId}` | Resource |
| `PATCH` | `/api/v1/admin/resources/{id}/status` | Resource |
| `PUT` | `/api/v1/admin/resources/{id}/time-rules` | Resource |
| `GET` | `/api/v1/admin/statistics/bookings` | AdminStatistics |
| `GET` | `/api/v1/admin/statistics/resources` | AdminStatistics |
| `GET` | `/api/v1/admin/users` | AdminUser |
| `PATCH` | `/api/v1/admin/users/{id}/status` | AdminUser |
| `POST` | `/api/v1/auth/login` | Auth |
| `POST` | `/api/v1/auth/register` | Auth |
| `GET` | `/api/v1/bookings` | Booking |
| `POST` | `/api/v1/bookings` | Booking |
| `GET` | `/api/v1/bookings/{id}` | Booking |
| `POST` | `/api/v1/bookings/{id}/cancel` | BookingCancel |
| `POST` | `/api/v1/bookings/{id}/check-in` | CheckIn |
| `GET` | `/api/v1/categories` | Category |
| `GET` | `/api/v1/notifications` | Notification |
| `POST` | `/api/v1/notifications/{id}/read` | Notification |
| `GET` | `/api/v1/resources` | Resource |
| `GET` | `/api/v1/resources/{id}` | Resource |
| `GET` | `/api/v1/resources/{id}/available-slots` | Availability |
| `GET` | `/api/v1/users/me` | User |
| `PUT` | `/api/v1/users/me` | User |
| `GET` | `/api/v1/users/me/violations` | Violation |

## 踩坑记录（真实事故,全部有提交/测试佐证）

1. **`sh -c "…$VAR…"` 会吞反引号**：JMeter 计数 SQL 的 `` `booking` `` 经 `docker exec sh -c "-e "$T13Q""` 被 shell 当命令替换执行,行数统计永远失败——离线合成数据测不出,真实运行才暴露。修复：非保留字表名不加反引号（deploy/jmeter）。
2. **JMeter `s="false"` ≠ 传输失败**：任何非 2xx 样本都带 s=false,按它分类会把全部 409 冲突错判为 connection_error。修复：按响应码/「Non HTTP response」判定（summarize.ps1）。
3. **`Z` 结尾时间戳被 PowerShell 按本地时区解析**：UTC+8 机器上校验器把正确时间错移 8 小时。修复:`TryParse` + InvariantCulture + RoundtripKind（deploy/scan）。
4. **`[uri]` 对象插值自带尾斜杠**:`"$beUrl/api/..."` 拼出 `//api/...` 被 nginx 403。修复：插值前 `ToString().TrimEnd('/')`（deploy/demo）。
5. **compose `internal: true` 网络静默丢弃端口发布**：同容器双网络 + 发布也无效;需要宿主访问内网服务时,用「先 create→connect→start」的 sidecar 转发容器。
6. **多语句 SQL 经 spawnSync stdin 会静默丢最后一条**：逐语句执行 + 结果断言（deploy/demo）。
7. **PowerShell `Write-Output` 污染函数返回值**：管道里的输出会把 `int` 返回变成 `Object[]`,exit 码错乱——输出一律走 Write-Host/Tee。
8. **`ConvertFrom-Json` 把 ≤6 位小数的 ISO 时间转成本地时区 Date**：9 位纳秒串反而保持字符串——时间戳统一 9 位 + 显式偏移。
9. **Element Plus 对话框两步确认有竞态**：轮询刷新会重置 `armed`,必须把 arm→confirm 原子化或在微任务窗口内完成（T11 runner）。
10. **Python 补丁脚本注意 CRLF**：Git Bash 工作树多为 CRLF,`open()` 读写需显式 newline 策略,否则锚点匹配失败。

## 文档与规格

- `docs/`（本地,不入库）：06 详细方案 / 11 数据库设计 / 15 实施手册 / 16 AI 并行任务计划
- `openspec/`：全部变更的 proposal/specs/design/tasks 与归档（21 个主规格,strict 校验通过）
- `handoff.md`（本地）：逐轮交接审计记录
- `deploy/`：部署、扫描、压测、E2E、Demo、TLS overlay 的完整门禁与证据体系
