# 本机 20 币对永续体验环境

在仓库根目录运行：

```bash
./scripts/local-perpetual.sh up
./scripts/local-perpetual.sh status
python3 scripts/check-local-perpetual.py
./scripts/local-perpetual.sh down
```

页面：<http://127.0.0.1:5174/trade/usd-perpetual>。
只启动 U 本位永续的 Core、gateway、price、realtime、derivatives-lifecycle、maker 六个 JAR，
以及本地 PostgreSQL、Kafka、Redis 和前端，不启动 wallet。本机演示不代表生产上线验收。

## 配置一次

需要 HotSpot JDK 27、Maven、Node 22+、Python 3、curl、nc、rsync，以及本地 PostgreSQL 18、
Kafka 4.3、Redis 可执行程序。macOS 可通过 Homebrew 安装依赖，启动器自动发现 keg-only 的
PostgreSQL 18。前端使用同级 `surprising-ex-web`，先在该目录安装锁定依赖（`npm ci`）。
Linux 需要 `setsid`；该路径尚未在本轮实机验证。

第一次 `up` 生成权限为 600 的
`~/.local/share/surprising-ex/perpetual-pmm-20/local.env`，自动生成数据库密码和 JWT 密钥。
本地依赖端口为 PostgreSQL 15432、Kafka 19092/19093、Redis 16379。
如使用已有数据库，将 `MANAGE_POSTGRES=false`，在此文件填写 PostgreSQL 连接信息；
已有 Redis/Kafka 同理设置 `MANAGE_REDIS=false` / `MANAGE_KAFKA=false`。
已有数据库的合约配置由其自身维护，启动器不会把测试目录的合约单位和测试资金写入外部数据库。

本地托管数据库首次初始化执行 `init.sql` 和 `catalog.sql`。后者只配置 `symbols.txt` 的 20 个
币对，其他合约为 PRE_TRADING：按资产 scale 与合约精度设置价格 tick、数量 step、每张合约基础币数量和名义金额乘数；指数每币对要求三路
有效真实源，其他币对的指数源关闭。不得对已产生订单的数据库运行这份初始化 SQL。
`catalog-ready` 标记和已有 Core 数据检查避免重启时重复修改合约单位。

Binance 的 20 个订阅合为一个请求，Bybit 每批最多 10 个参数，OKX 合为一个请求；
既有 WebSocket 客户端按相同消息去重发送。协议依据：
[Binance 官方 WebSocket 文档](https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md)、
[Bybit 官方连接文档](https://bybit-exchange.github.io/docs/v5/ws/connect)。不启用 REST fallback。

`application-local.yml` 沿用 Hummingbot PMM 的现有 Java 实现：每币对一个独立策略，
每侧目标 50 档、每账户每币对最多 100 单，外部 WebSocket 前 20 档提供参考价格与数量分布。`quantity-variation-ppm: 800000` 将稳定的分档数量与外部深度各按一半混合；外延档位沿用参考数量分布，不再统一填 1000。整个梯子仍按核心 OI/持仓预算缩放。每轮读取活跃订单，先补成交缺档，再分批换价；触发风控或行情异常时允许缩减报价，不保证绕过风控的满档。
做市账户 900101–900120 与模拟吃单账户 910001 各注入 100,000 USDT **测试资金**，
经原账户调整接口和固定幂等引用执行一次。模拟吃单通过正常核心批量市价 IOC 接口，每批至多 2 单，
与被动报价独立运行；实际成交速度和深度仍由资金、参考行情、风险校验和处理延迟决定。
`LOCAL_SIMULATED_TRADES_ENABLED` 在托管本机数据库默认开启，外部数据库默认关闭；可在 `local.env` 显式配置。
不会伪造盘口、成交或 K 线，也不把模拟成交称为真实用户成交。

初始化检查 `priceTickUnits × contractMultiplierPpm = notionalMultiplierUnits × 1000000`，
保证前端基础币数量与 Core 的名义金额一致。例如本目录 BTC 的价格 tick 为 0.1 USDT、
每张为 0.01 BTC，两个合约数量步长显示 0.02 BTC；两种精度互不影响。
旧 `perpetual-20` 目录保留原测试数据，不能对已有订单/持仓直接重跑初始化 SQL。

## 启动与数据边界

入口先检查 JDK、磁盘、端口和依赖，再启动 Core 并探测 Leader，随后启动业务服务。
realtime 的健康检查包含 Kafka Streams 状态，最后检查 20 个币对的三源指数、标记价及双边盘口。
任何币对未就绪，命令失败并把诊断写入 `market-readiness.json`，保留服务供排查。
市场检查是当时的可用性检查，外部实时行情断线后仍会按原风控规则拒单。

运行文件全部在上述用户目录，停机保留 PostgreSQL、Kafka、Core Archive 和行情 checkpoint。
macOS 使用 launchd 持有进程并启动 caffeinate；复制 JAR 和前端到运行目录，避开 Desktop 隐私权限。
前端运行副本会在下一次启动前同步；修改前端源码后执行 `down`、`up` 更新。
重新构建后端使用 `./scripts/local-perpetual.sh build`，再 `down`、`up` 装载新 JAR。
重复 `up` 不重启完整运行中的后端；半启动状态要求先 `down`，不覆盖交易数据。

日志：`runtime/local-perpetual/logs`；基础设施日志：`logs`。
JFR 默认关闭，Kafka 数据保留 24 小时并限制每分区大小。启动前至少需要 5 GiB 可用空间。
停机不会删状态；不要删除仍在运行的目录，也不要用新数据库配旧 Core Archive。

早期两档环境的历史验证记录见 [验证记录](VERIFICATION.md)，不代表 PMM 配置验收。

当前 PMM 配置及数量修正的实测、资金核对和已知边界见 [PMM 验证记录](PMM-VERIFICATION.md)。

### 模拟吃单手续费预算

自管本地数据库且 `LOCAL_SIMULATED_TRADES_ENABLED=true` 时，启动器为模拟账户 910001
在原 100,000 USDT 测试注资之外一次性追加 900,000 USDT，用于持续成交手续费。
调整通过正常余额调整 API，幂等编号为 `local-taker-fee-budget-910001`，并保存本地完成标记。
为延长持续演示，启动器另为每个做市账户一次性追加 900,000 USDT，为模拟吃单账户追加
9,000,000 USDT。使用正常余额调整 API，幂等编号为 `local-extended-demo-budget-<userId>`，
全部成功后保存 `extended-demo-budget-funded` 标记。重启不会重复注资，也不会按余额自动补亏。
仅适用于上述自管本地模拟环境，不影响外部数据库。
累计预算为每个做市账户 1,000,000 USDT、模拟吃单账户 10,000,000 USDT、演示账户
100,000 USDT；22 账户累计测试注资为 30,100,000 USDT，资金核验须包含所有调整流水。
预算仍会随持续交易消耗；余额不足时拒单是正常风控，不应通过放宽校验维持成交。

盘口因本地预算耗尽而消失的修复及复验见 [盘口恢复验证](BOOK-RESTORE-VERIFICATION.md)。

### 黑屏后恢复与定期快照

macOS 守护进程自动重启后，`start-product-line-providers.sh` 从已验证的 launchd label 读取
当前 PID，再核对 Java launcher identity，更新 PID 缓存。PID 变化不再被当作服务消失。
`local-perpetual.sh status` 另检查实际 Core 盘口查询；HTTP health 通过而 Core 正在重放时，
明确报查询不可用，不把进程存活当作交易已恢复。

启动器通过 `checkpoint-local-perpetual.py` 为该本地单节点环境维护五分钟快照目标。
每分钟检查已完成的快照时间，只有 Core 查询成功且本节点是 leader 时才调用现有
`ClusterTool snapshot`；同一 term、时间、日志位置的 service 0 与 consensus 有效快照均存在，
才记录 COMPLETED。请求成功不等于快照完成。恢复中、查询失败或磁盘不足 5 GiB 时不触发。
快照工具沿用 JDK 27 和已构建 tools JAR，不修改交易规则、不清空或截断 Archive。
它从运行目录 `bin/` 执行，避开 macOS 后台进程直接读取 Desktop 项目的访问限制。
`down` 先停止快照任务再停止交易服务。日志位于运行目录 `logs/checkpoints.log`。
定期快照缩短后续恢复窗口，不是修复异步命令超时本身；已有大段历史仍须完整重放。

验证：快照调度 5 项 Python 测试覆盖完整配对、不同 term/位置拒绝配对、近期快照不重复、Core
未就绪不触发、磁盘不足停止及请求后的完成确认；两个启动脚本 `bash -n` 与 diff 检查通过。
运行环境已经验证 launchd 更换 PID 后能识别在运行进程，以及 HTTP health 正常但 Core 重放时
状态检查返回失败。本次未改 Java 交易逻辑，不因脚本改动重跑其他产品线的 Java 测试。

本次排查、用户授权清空测试数据后的重启、资金核对与延迟缺口见
[黑屏恢复与本地重置验证](WAKE-RESET-VERIFICATION.md)。

### 模拟下单数量与方向

本地测试吃单每张订单独立选择方向、数量（1–10 steps，偏向小单），不再复制同方向同数量八单或强制买卖交替。
库存阈值为 100 steps，每批分别扣减买卖方向的可成交数量及库存额度；不假设相反方向一定成交。
最大数量还受合约上限约束。实际成交可能拆成多笔，重复最小单位仍可能出现。此功能仅为显式启用的本地模拟负载。

本机 20 币对演示：`engine.quote-interval: 100ms`、`engine.trade-interval: 150ms` 分别控制两类工作线程的轮后间隔，避免成功后无限循环挤占实时投影。每笔模拟数量仍为 1～10 steps，买卖方向独立抽样并受库存限制；不以刷满核心吞吐作为页面成交频率。

### 指数源网络与本轮页面验收

Binance 现货指数流使用官方 `wss://stream.binance.com:443/ws`。本机观测到 9443 端口连接失败而 443 可持续收到行情，因此初始化 SQL 改为 443；三来源数量、权重和新鲜度校验不变。已有本地数据库只调整启用的 U 本位永续 BINANCE 行的 `websocket_url`，重启 price 服务重新加载，不重新运行初始 catalog，也不修改历史审计记录或交易编码。

本轮盘口、下单区域和响应式页面验收见 [页面与盘口验证记录](UI-BOOK-VERIFICATION-20260927.md)。

### 本地前端运行模式

启动器将前端复制到运行目录，先 `npm run build`，成功后启动 Vite preview 提供生产资源及本地 API/WS 代理。生产构建避免高频行情下 React 开发模式逐组件调试记录占满浏览器主线程；构建失败直接停止启动流程，日志为 `logs/frontend-build.log`。前端源代码修改后需重新构建发布，现有标签页刷新后加载新版本。六个 Java 服务不受此模式调整影响。
