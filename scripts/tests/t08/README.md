# T08 QA Harness(真实链路验收, 仓库权威入口)

本目录 `scripts/tests/t08/` 是 T08 headless QA 的**唯一权威入口**;`target/t08-qa/` 仅为历史临时副本,不再维护,禁止运行或编辑。

`qa-harness.mjs` 通过 CDP 驱动本机 Chrome(`--headless=new`,进程由 harness 自管并清理),对同源 dev server 走学生预约真实链路。**不以任何 mock/stub 替代 booking 或 availability 接口。**

## 统一入口 run.ps1

以下是 worktree 根调用示例；从其他 cwd 调用时，给 `-File` 传入 `run.ps1` 的绝对路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Check   # 默认: node --check
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action List    # 列出用例
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Smoke   # 本地 Chrome 冒烟(headless)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Run     # 真实链路验收
```

`-Action Run` 为真实验收,保留被调用进程退出码:`0` 全过、`1` 失败/启动失败、`2` 网关不可达(GATES_DOWN)。

## 环境与变量

- Node.js 18 或更高版本（harness 使用全局 `fetch` 与 `WebSocket`）。
- 本机 Google Chrome，Smoke/Run 始终使用 `--headless=new`。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `T08_QA_FRONTEND` | `http://127.0.0.1:4173` | 前端 dev server 地址 |
| `T08_QA_BACKEND` | `http://127.0.0.1:18080` | 后端 API 地址 |
| `T08_QA_REDIS_HOST` | `127.0.0.1` | 真实 Redis 锁忙分支注入地址 |
| `T08_QA_REDIS_PORT` | `6379` | 真实 Redis 锁忙分支注入端口 |

## Run 前置条件(Windows PowerShell, 手动执行; 未给出的值一律用占位符自行填写, 不要把真实密码写进任何文件)

1. 在 worktree 根启动数据库/缓存(compose 需要 root 密码变量):
   ```powershell
   $env:MYSQL_ROOT_PASSWORD = '<本地MySQL-root密码>'
   docker compose up -d mysql redis
   ```
   待两个容器健康后应用迁移 `sql/V001..V005`(首次或未应用时)。

2. **另开一个后端专用窗口**,在其中临时注入 Java 17 与必需环境变量后再启动(仅该窗口会话有效):
   ```powershell
   $env:JAVA_HOME = '<JDK17安装目录>'            # 例: C:\Program Files\Java\jdk-17
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   $env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/booking_db'
   $env:DB_USERNAME = '<本地MySQL用户名>'
   $env:DB_PASSWORD = '<同一本地MySQL密码>'
   $env:SERVER_PORT = '18080'                     # application.yml 默认 8080, 必须覆盖
   $env:JWT_SECRET = '<至少32字符随机串>'          # 少于32字节后端拒绝启动
   $env:REDIS_ENABLED = 'true'
   $env:REDIS_HOST = '127.0.0.1'
   $env:REDIS_PORT = '6379'
   mvn -f booking-api/pom.xml spring-boot:run     # 从 worktree 根执行
   ```

3. 重置 QA 夹具(随执行即时生效):用 MySQL 客户端执行本目录 `seed.sql`,创建资源 `880001` 与明日营业规则。
4. 在 worktree 根启动 Vite dev server(端口 4173,同源代理 `/api/v1 -> 127.0.0.1:18080`):
   ```powershell
   & "booking-web\node_modules\.bin\vite.cmd" --config "scripts\tests\t08\vite.config.mjs"
   ```
5. 运行验收:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\t08\run.ps1 -Action Run
   ```

产出写入本目录唯一的 `run-<时间戳>-<pid>/`(已 gitignore):截图、network/console/api-driver 证据、`REPORT.md`、`summary.json`;仅全部用例通过才生成 `PASS` 文件。本地日志属临时证据,同样被忽略。

## 重要声明

**服务未启动时不能宣称通过。** harness 启动时会预检两个网关(frontend `/` 与 backend `/actuator/health`);任一不可达则输出 `GATES_DOWN`、所有用例记为 skipped 并以退出码 2 结束,**不生成 PASS 文件**。此时报告不代表任何链路验证结果,须满足上述前置条件后重跑。

## 已知 gap(详见 REPORT.md)

- PENDING_APPROVAL/REJECTED/CHECKED_IN/COMPLETED/NO_SHOW 五状态的浏览器级展示无确定性夹具,浏览器级仅覆盖 CONFIRMED/CANCELLED。
- 当天跨零点运行会造成"明日"漂移,须与 seed 同日运行。

past-slot 由真实明日 availability 配合浏览器组件时钟 seam 验证；409 锁忙分支通过真实 Redis `RLock` key 的竞争状态验证，不使用 API mock/stub。
