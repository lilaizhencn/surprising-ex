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
每侧目标 50 档、数量上限 1000 steps，使用 Binance 永续参考盘口 WebSocket 前 20 档及既有外侧扩展。
做市账户 900101–900120 与模拟吃单账户 910001 各注入 100,000 USDT **测试资金**，
经原账户调整接口和固定幂等引用执行一次。模拟吃单通过正常核心批量市价 IOC 接口，每批至多 8 单，
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
