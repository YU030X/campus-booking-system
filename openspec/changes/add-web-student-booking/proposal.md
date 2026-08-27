## Why

学生端已经冻结了资源浏览、可用时段和预约 API 的边界，但缺少一条可验证的“选择时段 → 创建预约 → 查看详情/状态”的前端闭环。现在先把这条闭环规划成独立、可交接的 T08 change，可以让页面、纯 store/API 单测和后续真实联调在不侵入共享路由壳、资源视图或后端的前提下并行推进。

## What Changes

- 新增学生预约 capability 的页面、组件、API module/store 及对应单元测试规划，范围仅限 `booking-web/src/views/booking/**`、`views/my-bookings/**`、`components/booking/**` 与对应 booking-domain API/store 测试。
- 复用冻结的 `/bookings` 与 `/bookings/:id`：列表页承载“我的预约”并可由安全同源 query `resourceId`/`date` 打开创建 drawer/panel；详情页展示精确预约字段和状态时间线。
- 通过共享 HTTP/API 层消费 T06 可用时段和 T07 预约契约。T04、T05、T06、T07 的 planning artifacts 分别位于 sibling worktree `../add-web-auth-shell/`、`../add-web-resource-management/`、`../add-resource-availability/`、`../add-concurrent-booking-core/`，当前均未 merge 到本 worktree；必须先 merge 或 rebase，再重读各自权威 route/DTO/payload/status/error，才能进行真实 integration，期间仅规划基于冻结契约的纯组件/store/API mapper 单测。
- 规划 30 分钟、同日、连续可用 slot 选择，派生 start/end、purpose trim 后按 Unicode code points 计数且不超过 500、空值转 null、attendeeCount>=1、提交 loading 去重，以及 201/401/403/404 与 T07 权威 409/43000 错误处理和刷新策略。
- T07 transport 以 `code=43000` 与 backend message 联合判定：slot duplicate 的 backend exact message 为“该时段已被占用，请刷新后重试”，T08 显示“该时段刚被其他人预约，请刷新”并刷新 slots；lock-busy 仍为 `43000` 但 backend message 为“当前预约请求较多，请稍后重试”，T08 显示系统繁忙，不得误称 slot 已被抢，且不得仅按 code 混淆两者。
- 取消能力先保留为 T09 合并后的 capability gate/disabled state；在 T09 权威 contract 合并前不调用或伪造 cancel 成功。
- 明确不新增路由、不修改 `router/index.js`、`api/http.js`、auth shell、resource views、shared contracts/types、package manifests 或 backend；T04 负责两条 route component 的独立 shared-file handoff。

## Capabilities

### New Capabilities

- `student-booking`: 学生预约创建、我的预约列表、预约详情/时间线、可用时段选择、错误/刷新和取消门禁的前端行为契约。

### Modified Capabilities

- 无。共享契约、身份访问和资源目录的主 spec 不在本 change 中修改；若真实 T04/T05/T06/T07 契约发生漂移，应先更新 planning 并停止 real integration。

## Impact

- Affected frontend areas: booking views, my-bookings views, booking components, and their domain API/store tests only.
- Consumes `GET /api/v1/resources/{id}/available-slots?date=yyyy-MM-dd`, `POST /api/v1/bookings`, `GET /api/v1/bookings`, and `GET /api/v1/bookings/{id}` through the shared booking API module/client; cancel remains gated on T09.
- Depends on merged/rebased T04 auth shell, T05 resource handoff, T06 availability payload, and T07 booking DTO/errors. Long IDs remain strings and booking status is the frozen shared state machine.
- Verification planning includes strict OpenSpec validation, `git diff --check`, frontend build, pure mapper/store/component tests, and headless browser evidence only after the backend feature gates are available. No implementation code, commit, push, or generated artifacts are part of this planning change.
