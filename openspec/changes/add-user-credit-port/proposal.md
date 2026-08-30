## Why

T10 `add-checkin-no-show-violation`（签到/未到违约处理）依赖一个由 user 模块拥有的用户信用分事务端口来落地冻结扣分（LATE_CANCEL=-5、NO_SHOW=-10），但当前代码中不存在该端口：`user.credit_score` 只有初始值 100 的写入路径（注册时），没有任何受控的原子调整能力；`openspec/specs/data-schema/spec.md:35` 也明确「积分扣减/查询事务延迟到业务变更」。若各调用方自行拼装 UPDATE，会出现并发丢失更新与伪装成功的静默错误。

## What Changes

- 新增 user 模块拥有的进程内事务端口（无 HTTP 端点）：接收 `userId` 与负数 `scoreChange`，以单条条件 SQL 原子完成 `resultingCredit = max(0, currentCredit + scoreChange)`，并返回更新后的信用分。
- 端口以 REQUIRED 传播加入调用方（T10 violation）现有 Spring 事务，不使用 REQUIRES_NEW，不自行开独立提交点；调用方回滚时本次调整一并回滚。
- 对缺失用户或逻辑删除用户（`deleted=1`）返回明确失败（NOT_FOUND），不可能被解读为成功，也不返回任何"成功"的信用值。
- 对未知来源或非法的 `scoreChange`（null、零、正数）在触碰数据库前拒绝（INVALID_PARAMETER），保证端口语义只面向扣分场景。
- 仅新增 user 模块自有代码（main/test）与本变更工件；**不改** SQL 迁移、pom.xml、application.yml/config、common、auth、booking、violation、frontend。
- 向 T10 移交冻结扣分常量约定：本端口是通用扣分通道，LATE_CANCEL=-5 与 NO_SHOW=-10 由 T10 的 violation 域定义并作为参数传入，不在 user 模块硬编码。

## Capabilities

### New Capabilities

- `user-credit`: 用户信用分的事务性原子调整端口——user 模块对外提供的唯一受控扣分入口，覆盖并发原子性、下限截断、非法入参拒绝、缺失/逻辑删除用户的显式失败、调用方事务加入与回滚行为。

### Modified Capabilities

- 无。`data-schema` 的十二表冻结基线与「积分事务延迟到业务变更」表述被原样消费（本变更是该业务变更）；`identity-access` 现有需求不变。

## Impact

- **自有实现区域：** `booking-api/src/main/java/com/yu030x/booking/user/**`（新增端口接口/实现与 `UserMapper` 注解 SQL 方法）及 `booking-api/src/test/java/com/yu030x/booking/user/**`（单元测试 + MySQL 8 集成测试）。
- **下游移交：** T10 `add-checkin-no-show-violation` 通过该端口在自身事务内记录违约扣分；T10 不得绕过端口直写 `user.credit_score`。
- **外部前置：** MySQL 8 集成验证需要可用的本地集成库；不可用时必须像既有 `*MysqlIntegrationTest` 一样显式记录失败，不得静默跳过。
- **验证证据：** 端口单元测试、MySQL 8 并发/回滚/边界集成测试、`mvn test`（收窄到 user 相关）、`git diff --check`、OpenSpec 严格校验。
