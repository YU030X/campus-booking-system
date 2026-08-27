## 1. 前置确认

- [x] 1.1 确认工作区基线：`git status` 干净（仅本变更工件为新增/修改），并复核本变更不触碰 SQL/pom/config/common/auth/booking/violation/frontend 文件；验证方式：`git status --porcelain` 输出仅包含 `openspec/changes/add-user-credit-port/**` 与后续 user 模块路径。证据：apply 开始时输出仅为 `?? openspec/changes/add-user-credit-port/`，无任何 SQL/pom/config/common/auth/booking/violation/frontend 改动。

## 2. user 模块端口实现

- [x] 2.1 在 `UserMapper` 新增注解式条件 UPDATE 方法：`UPDATE \`user\` SET credit_score = GREATEST(0, credit_score + #{scoreChange}), updated_at = #{now} WHERE id = #{userId} AND deleted = 0`，返回影响行数 int；验证方式：编译通过且代码评审确认单条语句、含 `deleted=0` 谓词、无先读后写。证据：`UserMapper.applyCreditScoreChange` 为单条注解 SQL，含 `deleted=0` 谓词，`mvn test -Dtest=UserCreditServiceTest` 编译并全绿。
- [x] 2.2 新增端口接口与实现（user 包内）：入参 `(long userId, Integer scoreChange)`；null/零/正数抛 `BizException(INVALID_PARAMETER, "invalid parameter")`；更新影响行数=0 抛 `BizException(NOT_FOUND, "user not found")`；成功后同事务回读 `credit_score` 并返回；方法标注默认 REQUIRED 的 `@Transactional`；类随既有 identity 门控注册；验证方式：单元测试全绿（见 3.x）。证据：新增 `UserCreditPort`、`UserCreditService`（与 `UserService` 相同的 `booking.identity.enabled` 门控，注入既有 `jwtClock`），单元测试 6/6 全绿。

## 3. 单元测试

- [x] 3.1 编写 `UserCreditServiceTest`（mock UserMapper）：覆盖非法 scoreChange（+5、0、null）在调用 mapper 前被拒、影响行数=0 映射 NOT_FOUND、成功路径回读并返回新值；验证方式：`cd booking-api && mvn test -Dtest=UserCreditServiceTest` 全绿。证据：surefire 报告 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。
- [x] 3.2 断言传播语义：实现方法标注 `@Transactional`（无 REQUIRES_NEW/propagation 覆盖）；验证方式：测试或静态断言中检查注解属性为默认 REQUIRED。证据：`UserCreditServiceTest.deductionUsesDefaultRequiredPropagationWithoutNewTransactionSemantics` 反射断言 propagation()==REQUIRED 且无自定义 value/transactionManager，通过。

## 4. MySQL 8 集成测试

- [x] 4.1 编写 `UserCreditMysqlIntegrationTest`（沿用既有 `*MysqlIntegrationTest` 显式失败约定，不静默跳过）：正常扣分回读（100→90）、下限截断（3 与 -10 → 0）；验证方式：外部 MySQL 可用时该测试全绿，否则记录显式失败与环境缺失证据。证据：使用仓库根 `docker-compose.yml` 启动并确认 MySQL 8.0 健康；`UserCreditMysqlIntegrationTest` 5/5 通过，覆盖正常扣分与零下限。
- [x] 4.2 同测试类补充失败路径：不存在用户与逻辑删除用户均 NOT_FOUND 且 `credit_score` 不变；事务内调用后抛异常回滚则扣分撤销；并发多线程对同一用户叠加扣分结果等于逐次截断累计值（无丢失更新）；验证方式：同 4.1 的执行命令与证据记录。证据：同一 5/5 集成测试运行覆盖缺失/逻辑删除、调用方事务回滚及 8 线程并发扣分，无失败、错误或跳过。

## 5. 验证与收尾

- [x] 5.1 运行收窄回归：`cd booking-api && mvn test -Dtest=UserCredit*` 全绿，并运行受影响的既有 user 测试（`UserMysqlIntegrationTest`、`UserMapperRegistrationIntegrationTest`、`UserControllerMockMvcTest`）确认无回归；验证方式：粘贴命令与结果摘要到任务卡，未运行不得勾选。证据：使用本地 Compose MySQL 执行 `mvn -Dtest=UserCreditServiceTest,UserCreditMysqlIntegrationTest,UserControllerMockMvcTest,UserMysqlIntegrationTest,UserMapperRegistrationIntegrationTest test`，共 21 个测试，Failures 0、Errors 0、Skipped 0，BUILD SUCCESS。
- [x] 5.2 范围核查：`git diff --check` 无空白错误；`git status --porcelain` 确认改动仅落在 `openspec/changes/add-user-credit-port/**` 与 user 模块 main/test；验证方式：输出贴证。证据：`git diff --check` 通过（无输出）；porcelain 仅含 `booking-api/src/main/java/com/yu030x/booking/user/{UserMapper.java,UserCreditPort.java,UserCreditService.java}`、`booking-api/src/test/java/com/yu030x/booking/user/{UserCreditMysqlIntegrationTest.java,UserCreditServiceTest.java}` 与 `openspec/changes/add-user-credit-port/`。
- [x] 5.3 运行 `openspec validate add-user-credit-port --type change --strict --no-interactive`（如版本不支持 `--no-interactive` 则用 `openspec validate add-user-credit-port --type change --strict`）通过；验证方式：命令输出贴证。证据：输出 `Change 'add-user-credit-port' is valid`。完成后停止，不开始 archive，不提交 Git。
