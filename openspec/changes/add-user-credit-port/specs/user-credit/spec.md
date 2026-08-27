# user-credit Delta Specification

## Purpose

定义 user 模块对外提供的用户信用分事务性原子调整端口：任何模块（当前为 T10 violation）必须经由该端口扣减信用分，保证并发下不丢失更新、结果可回读、失败不可伪装成功。

## ADDED Requirements

### Requirement: 原子信用分调整端口
user 模块 SHALL 暴露一个进程内事务端口，供其他业务模块（violation/T10）以 `(userId, scoreChange)` 调用；端口 MUST 以单条条件 SQL 在数据库侧一次性完成 `resultingCredit = max(0, currentCredit + scoreChange)` 的计算与写入（禁止先读后写两步更新），并在同一事务内返回更新后的信用分。端口 MUST NOT 提供 HTTP 端点，且 MUST 是修改 `user.credit_score` 的唯一受控入口。

#### Scenario: 正常扣分并返回新值
- **WHEN** 对 `credit_score=100`、未删除的活跃用户调用端口并传入 `scoreChange=-10`
- **THEN** 该用户 `credit_score` 变为 90，端口返回 90

#### Scenario: 下限截断为零
- **WHEN** 当前信用分不足以覆盖扣减（如 `credit_score=3`、`scoreChange=-10`）
- **THEN** 结果被截断为 `resultingCredit=0`，不出现负数

#### Scenario: 并发扣减不丢失更新
- **WHEN** 多个事务对同一用户并发执行扣减（如两个 `-5` 叠加在 `credit_score=100` 上）
- **THEN** 最终 `credit_score` 等于逐次截断后的正确累计值（90），无丢失更新

### Requirement: 非法 scoreChange 必须拒绝
端口 SHALL 仅接受非零负整数作为 `scoreChange`。当 `scoreChange` 为 null、零或正数时，端口 MUST 在执行任何 SQL 前以 INVALID_PARAMETER 失败拒绝，且 MUST NOT 对数据库产生任何变更。冻结的违约扣分常量（LATE_CANCEL=-5、NO_SHOW=-10）由 violation/T10 域定义并作为参数传入，user 模块 MUST NOT 硬编码这些取值。

#### Scenario: 正数或零被拒绝
- **WHEN** 以 `scoreChange=+5`、`scoreChange=0` 或 `scoreChange=null` 调用端口
- **THEN** 返回 INVALID_PARAMETER 失败，该用户 `credit_score` 保持不变

### Requirement: 加入调用方现有事务
端口 MUST 以 REQUIRED 传播加入调用方现有 Spring 事务，MUST NOT 使用 REQUIRES_NEW 或独立提交点；若不存在外层事务则开启自身事务完成。当调用方事务回滚时，已执行的信用分调整 MUST 一并回滚。

#### Scenario: 调用方回滚连带撤销扣分
- **WHEN** T10 在其事务中先经端口扣分、随后自身抛出异常回滚
- **THEN** 该用户的 `credit_score` 回到事务前的值，本次扣分不留痕

#### Scenario: 无外层事务时独立完成
- **WHEN** 在没有外层事务的上下文中调用端口
- **THEN** 扣分在自身事务内原子提交并返回更新后的信用分

### Requirement: 缺失或逻辑删除用户显式失败
当目标用户不存在或已被逻辑删除（`deleted=1`）时，条件 UPDATE 影响 0 行，端口 MUST 以明确的 NOT_FOUND 失败终止（与 user 模块既有"用户不存在"语义一致），MUST NOT 返回任何可被解读为成功的信用值，且该失败 MUST 使加入的调用方事务标记为只回滚。

#### Scenario: 用户不存在或已删除
- **WHEN** 以不存在的 userId 或 `deleted=1` 的用户调用端口并传入合法负数 `scoreChange`
- **THEN** 端口返回明确的 NOT_FOUND 失败，任何用户数据不变，调用方事务整体回滚
