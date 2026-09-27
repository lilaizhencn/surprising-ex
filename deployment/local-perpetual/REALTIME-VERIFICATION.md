# Redis 连接复用与实时推送验证（2026-09-27）

## 根因与修正

原 Router 按帧/用户提交同步查询订阅、写入视图；20 个做市策略下，8192 帧队列持续满载。
加入 pipeline 后，Lettuce 专用连接仍因缺少 commons-pool2 不断创建/关闭，因此明确启用最多 4 条连接的池。
最终处理流程：每轮最多 256 帧，批量查询路由；收齐提交后逐用户执行原原子 Lua；成功后按原序推送。
临时路由和命令集合仅属于当轮；不新建权威业务状态，不跨轮缓存订阅租约。

盘口采集每 100ms 轮询最多 8 个已订阅币对。gateway 的 `SubscriptionRegistry.publishDepth`
和 `DepthUpdate.between` 仍由首次 SNAPSHOT 转为价位绝对数量 DELTA，数量为零表示删除。
一个 DELTA 可以包含多个变化价位；不能把一条消息含多个价位等同于全量刷新。
前端 `useRealtime` 每 50ms 合并渲染，`TradePage` 按价位更新并校验连续性。

联调还复现了两个 Core 错误：普通下单账户准入拒绝晚于撮合完成时遗留活动发布上下文；
批量撤单把终态拒单继续送入活动撮合。分别在现有有序提交和批量撤单校验入口修正。
成交导出回放的回调异常现在向外传播，失败后不再继续处理并推进 checkpoint。
保留原数据库、账户资金、Archive 和运行目录，没有重建账本绕过异常。

## 自动测试

HotSpot Corretto JDK 27、Maven 3.9.16：
`mvn -pl surprising-realtime/surprising-realtime-provider -am package` 成功。
总计 1189 项，失败/错误 0，跳过 2（已有指标采样开关未启用）。其中 Core 944 项。
六产品线新增拒单回归先全部失败、修正后全部通过，校验后续批量下单、业务哈希和快照恢复。
Redis/Aeron 集成覆盖 20 币对、1024 公共帧、1024 用户提交、版本顺序、订阅隔离和过期租约。
回放集成覆盖异常传播及持久 checkpoint 字节不变。`git diff --check` 通过。

## 运行采样

同一套 20 策略和 8 单模拟吃单配置；未通过降低档位或停止做市伪装吞吐改善。
优化前 60 秒：入队丢弃增加 684673，20 币对总成交推送 963，5 币对没有盘口事件；
BTC 最大盘口间隔 24.67 秒。中间版本的无流量采样 `router-pooled.json` 不作为性能结论。
最终带成交负载 60 秒采样 `router-funded.json`：

- 20/20 币对都有成交和盘口，成交推送合计 67,462 笔，各币对 2,589–3,544 笔（均值约 43–59 笔/秒）。
- 各币对消息延迟 P95 为 137–151ms；入队丢弃增加 0、路由异常增加 0，节点传输丢弃增加 6。
- 采样前后队列为 2 / 42 帧，远低于容量；这不是队列峰值测量。
- 每币对仅 1 次初始 SNAPSHOT，后续 DELTA 序列无断档。盘口消息数 48–185 条；
  BTC 最大间隔 835ms，同时订阅 20 币对时 SOL 最长间隔仍达 6652ms，不能宣称全部币对恒定 300ms 更新。
- Redis 独立观察窗口连接总数 78310→78311，增加的 1 条为 redis-cli 检查连接；
  命令计数 13269325→16051491，未出现此前 pipeline 每轮新建连接的持续增长。
- 30 秒 JFR：router 采样 69 次，ThreadPark 合计约 12.92 秒；主要 CPU 样本来自
  Kafka Streams、可靠成交导出和 Lettuce。原始 JFR 只用于定位，不将不同时间市场负载的采样当严格基准。

以上是同配置运行观察；外部价格、订单变化和恢复状态不同，不能据此宣称精确吞吐提升倍数。

## 页面与交易

Chrome 1440×1000、390×844 检查，无 JS 异常，手机宽度 390/390 无横向溢出。
35 秒已登录 BTC 页面采样：BTC 15m 最近 120 根 K 线、指数价、标记价各 1 次 REST；
没有其他币对或其他周期的 candles 初始化。随后收到 2266 笔成交、196 条 depth、2 条 candles WS。
行情行按价位更新；报价平移会删除旧价位、建立新价位，因此不能用 30 秒后旧行是否仍存在判定全量重绘。
BTC 价格档距为 0.10，数量步长 0.01 BTC，盘口数量显示 0.01、0.06、0.12 等实际值。

做市运行中使用用户 1 API 覆盖 20/20 币对：市价买入 FILLED、reduce-only 平仓 FILLED、
GTX 挂单 ACCEPTED、撤单 CANCELED；测试用户各币对持仓为 0、冻结为 0。
首次恢复后没有成交的原因另查明为模拟吃单账户余额已支付手续费耗尽，拒单符合风控；
通过正常余额调整 API 一次性补充 900,000 USDT 测试预算，幂等编号
`local-taker-fee-budget-910001`，没有绕过手续费或修改余额校验。
暂停做市等待在途结束后，Core 权威核验：22 账户及 Treasury 合计
310000000000000 units，等于原 2,200,000 USDT 加本次 900,000 USDT，差额为 0；
20 币对全市场净持仓均为 0。已成功执行 Core SNAPSHOT 并恢复全部 20 策略。

六 JAR、PostgreSQL、Redis、Kafka 和前端保留运行；启动检查 20/20 通过。
入口仍为 `./scripts/local-perpetual.sh up`，页面 `http://127.0.0.1:5174/trade/usd-perpetual`。

## 范围

自动测试覆盖共享 Core 与实时模块；在线验证只针对 U 本位永续。
本次没有重新完整验证强平、ADL、资金费结算时点、交割和行权、高可用或长期压力。
不启动 wallet，不以行情视图替代 Core 资金核验。

## 产物与清理

代码提交 `a7ad8afc`（Core 拒单/撤单边界）、`3ef1c370`（实时 pipeline、连接池及回放错误传播）。
运行证据保存在 `~/.local/share/surprising-ex/perpetual-pmm-20/verification/` 的
`router-funded.json`、`router-funded-jfr-summary.json`、`realtime-ui.json`、桌面/手机截图、
`realtime-trading-verification.json`、`realtime-funds-final.log`。
测试用 Kafka/Redis/Aeron 随测试关闭；浏览器已退出。本轮原始 JFR 在汇总后删除，
旧回放错误洪泛 stdout 保留头尾异常片段后压缩到 64KiB，数据库和 Archive 不删除。
持续服务按用户要求保留，运行日志继续滚动；原始 JFR 路径仅作历史定位。
