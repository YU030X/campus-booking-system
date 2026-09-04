# 并发预约压测报告（T13 任务 3.4）

执行日期：2026-09-04 · 工具：Apache JMeter 5.6.3（便携，`deploy/artifacts/tools/`）·
计划：`deploy/jmeter/booking-concurrency.jmx`（同槽 100 线程，ramp 1s，loop 1）·
分类器：`deploy/jmeter/summarize.ps1`（修复后 45/45 离线合同通过）

依据 docs/06-项目一详细方案 W7 叙事与验收清单「并发预约问题有压测数据证明已解决」。
用户 2026-09-04 授权 T13 以 owner 身份自建 OCR-5/6/7 所需工件（seed/历史基底/隔离栈）。

## 三轮结果

| 轮次 | 防线 | 成功 | 业务冲突 | 系统忙 | 其他 | DB 行增量（booking/slot） | 延迟 avg/p95/p99 |
|---|---|---|---|---|---|---|---|
| R1 vulnerable-baseline | **无唯一索引、无锁**（隔离栈 `jmeter-baseline`，`uk_resource_slot` 已卸载） | **11** | 0 | 0 | 89* | +11 / +11（同槽重复占用 11 次） | 694 / 870 / 897 ms |
| R2 unique-index-only | **仅唯一索引**（隔离栈 `jmeter-protected`，`BOOKING_REDIS_LOCK_DISABLED=true`，redis 健康、锁层被测性开关跳过） | 1 | 99 | 0 | 0 | +1 / +1 | 551 / 735 / 811 ms |
| R3 unique-index-redisson | **唯一索引 + Redisson 锁**（当前 compose 栈） | 1 | 99 | 0 | 0 | +1 / +1 | 1774 / 2560 / 2664 ms |

*R1 的 89 个"其他" = `active booking limit reached`（并发持有限制防线在 11 个重复预约吃满配额后拦下剩余请求，消息为历史版非规范文案，按分类器严格匹配规则归入 other）。

## 受保护断言（R2/R3）

`1 success + 99 business_conflict + 0 system_busy + 0 data_error + 0 server/connection/other`，
booking 行增量 = 成功数，slot 行增量 = 成功数 × slotsPerBooking(=1)——**两轮全部 PASS**
（`report.json` 的 `pass: true`，断言明细见各工件目录）。

## 结论（对应 docs/06 面试叙事）

1. **R1 复现了 Check-Then-Act 竞态**：无唯一索引时同一时段 11 个预约同时成功（0→11 行），时段被重复占用——bug 亲眼可见。
2. **R2 证明数据库唯一索引是正确性的唯一必要防线**：锁层关闭后仍 1/99/0,行增量精确。
3. **R3 证明 Redisson 锁是性能优化层**：把 99 个冲突拦截在数据库之前（锁队列效应使 p99 升至 2.7s,是"用延迟换数据库保护"的直观体现），且唯一索引仍然兜底——两层缺一不可,与 docs/06「分布式锁是性能优化,数据库约束才是正确性保证」完全一致。
4. 无锁基线的重复预约被并发持有限制（M=10）二次兜底拦下 89 个——第三层业务防线同样有真实数据。

## 证据与可复现性

- 原始 JTL/元数据/分类报告（ignored）：`deploy/artifacts/jmeter-{vulnerable-baseline,unique-index-only,unique-index-redisson}-r{1,2,3}-20260904/`
- 隔离栈定义：`deploy/jmeter/isolated-compose-{baseline,protected}.yml`（独立项目/卷，loopback-only 18081，从不触碰生产库）
- 基底引导：`deploy/jmeter/isolated-baseline-bootstrap.sql`（仅卸载隔离栈内的 `uk_resource_slot`，无任何数据删除）
- 种子：`deploy/jmeter/isolated-seed.sql`（t13jm_ 前缀 fixture，幂等）
- 运行配置：`deploy/artifacts/jmeter/rounds-live.json`（ignored）
- 环境事实：rounds 中 R1/R2 的 `historyMirror` 如实声明"当前镜像 + `BOOKING_REDIS_LOCK_DISABLED=true` 测性开关"，非历史镜像；开关由 T07 分支 `85ce311`/`a79a3b8` 提供（默认关闭，14/14 单测+真实 Redis 集成测试通过）。

## 执行前修复的两个 harness 真实缺陷

1. `run.ps1` 计数查询的反引号经 `sh -c "...$T13Q"` 被 shell 命令替换吞掉 → 计数恒失败（此前从未被真实运行暴露）；表名非保留字，直接去反引号。
2. `summarize.ps1` 把 JMeter `s="false"`（任意失败样本，含全部 409）误判为传输失败 → 全部冲突样本错入 connection_error；改为按 `rm`（Non HTTP response）+ rc==0 判定，且 `rm` 属性安全访问（合成样本无该属性，StrictMode 下不再抛错）。修复后 45/45 合同通过。
