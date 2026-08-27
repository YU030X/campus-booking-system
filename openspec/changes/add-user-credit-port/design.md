## Context

现状：`UserMapper` 仅继承 MyBatis-Plus `BaseMapper<User>`（booking-api/src/main/java/com/yu030x/booking/user/UserMapper.java:7），`user.credit_score` 只在注册时写初始值 100（`AuthService`）；T10 `add-checkin-no-show-violation` 尚未开工，但依赖一个 user 模块拥有的原子扣分端口。约束：`sql/V002__create_user_and_resource_tables.sql` 中 `credit_score INT NOT NULL DEFAULT 100`、`deleted TINYINT` 为冻结基线，本变更不改 SQL/pom/config/common/auth/booking/violation/frontend。仓库已有"注解 mapper 条件 SQL + REQUIRED 事务缝合"先例（`BookingActions`/`DefaultBookingActions`），本设计沿用同一模式。

## Goals / Non-Goals

**Goals:**

- 在 `com.yu030x.booking.user` 内新增端口接口 + 服务实现 + `UserMapper` 单条条件 UPDATE 方法。
- 数据库侧原子计算 `resultingCredit = max(0, currentCredit + scoreChange)`，同事务回读并返回新值。
- REQUIRED 传播加入调用方事务；缺失/逻辑删除用户与非法入参均显式失败。
- 单元测试 + MySQL 8 集成测试提供可复现证据。

**Non-Goals:**

- 不实现 HTTP 端点、violation 记录表操作、黑名单/通知联动（归 T10）。
- 不定义 LATE_CANCEL=-5、NO_SHOW=-10 常量（归 T10，作为参数传入）。
- 不做信用分查询端口、加分通道、定时恢复、前端展示改动。
- 不改任何共享文件（SQL/pom/config/common 等）。

## Decisions

1. **归属与形态：** 端口接口（如 `UserCreditPort`）+ 实现（如 `UserCreditService`）放 user 模块，随既有 `@ConditionalOnProperty(booking.identity.enabled)` 门控注册；`UserMapper` 新增注解式方法。理由：user 模块拥有 `user` 表与 creditScore 字段语义，跨模块直写会破坏所有权边界。替代方案：放 common 工具/由 violation 直接 UPDATE——均违反模块所有权与冻结分层，否决。
2. **并发原子性：单条 GREATEST 条件 UPDATE。** 形态：`UPDATE \`user\` SET credit_score = GREATEST(0, credit_score + #{scoreChange}), updated_at = #{now} WHERE id = #{userId} AND deleted = 0`，以影响行数判定成败。理由：MySQL 行锁使读改写在一个语句内完成，天然免疫丢失更新。替代方案：(a) 先 SELECT 再 updateById——经典丢失更新；(b) SELECT ... FOR UPDATE 再更新——多一次往返且锁窗口更长；(c) 应用层加 Redis 锁——引入外部依赖且 DB 唯一真相原则要求最终正确性落在 SQL 上。选 (GREATEST) 方案。
3. **返回值：同事务回读。** 更新成功后 `SELECT credit_score ... WHERE id=? AND deleted=0` 读回新值返回。理由：截断发生在 SQL 侧，应用层无法仅凭入参推断结果；同一事务内回读读到的是本事务刚写的值，一致且简单。替代方案：让 mapper 用输出参数带回——MyBatis 注解风格下复杂度高、收益小。
4. **失败模型：**
   - 影响行数 = 0 → 抛 `BizException(ErrorCode.NOT_FOUND, "user not found")`，与 `UserService.requireUser`（UserService.java:82-88）语义一致；运行时异常自动将加入的事务标记 rollback-only，满足"不可伪装成功"。
   - `scoreChange` 为 null/零/正数 → 进 SQL 前抛 `BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter")`，零数据库副作用。
   - 替代方案：返回 Optional/结果对象让调用方分支处理——调用方可能忽略失败分支造成伪装成功，异常模型更难误用，选异常。
5. **事务传播：默认 `@Transactional`（REQUIRED）。** 与 `BookingActions` 缝合 T09/T10 的方式相同；明确禁用 REQUIRES_NEW，避免扣分脱离调用方违约记录事务造成部分提交。无外层事务时 Spring 自动创建独立事务，符合规格。
6. **扣分常量归属：** user 模块仅校验"非零负整数"，不感知 -5/-10 具体值；T10 在自己的域内定义常量并传参。理由：扣分政策属于 violation 业务规则，未来调整不应触碰 user 模块。
7. **验证路径：** 单元测试 mock `UserMapper` 验证参数校验、行数映射与回读；新建 `UserCreditMysqlIntegrationTest`（沿用既有 `*MysqlIntegrationTest` 显式失败约定）覆盖：正常扣分回读、下限截断、并发多线程叠加无丢失更新、调用方事务回滚连带撤销、缺失/删除用户 NOT_FOUND 且无变更。集成库不可用时显式记录失败，不得跳过。

## Risks / Trade-offs

- [自定义注解 SQL 绕过 MyBatis-Plus `@TableLogic`，若 WHERE 漏掉 `deleted=0` 会改到已删用户] → 条件 UPDATE 显式携带 `AND deleted=0`，并以"逻辑删除用户"集成测试锁定行为。
- [`updated_at` 由参数注入而非 DB `ON UPDATE CURRENT_TIMESTAMP` 触发的口径问题] → 表定义含 `ON UPDATE CURRENT_TIMESTAMP`，显式传 `LocalDateTime.now(clock)` 与 `UserService` 既有做法保持一致；测试断言不依赖秒级精度。
- [并发集成测试在 CI 无 MySQL 时无法执行] → 沿用仓库既有约定：显式记录失败与外部前置，不静默跳过、不伪造通过。
- [GREATEST 截断掩盖真实透支幅度（-10 打在 3 分上记为 0）] → 这是规格明确定义的截断语义；violation_record（T10 域）仍保留原始 score_change，审计不受影响。

## Migration Plan

纯新增代码，无 schema/配置变更：合并后随下次部署自动生效，无数据迁移。回滚 = revert 本次提交即可，端口无持久化痕迹。T10 后续按 specs/user-credit 合同接入。

## Open Questions

无。未知点（T10 具体调用时机、violation_record 写入顺序）属于 T10 自身设计范围，不影响本端口合同。
