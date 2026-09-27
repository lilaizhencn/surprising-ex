# 2026-09-27 订单成交值、测试数据重建与实时标签验证

## 业务变化

- 成交入口 `RuntimeDerivativeFillCalculator` / `RuntimeSpotMatchProcessor` 使用真实成交 ticks × steps 累计订单成交值。Account Lane 拥有两个 primitive 字段，`OrderChangeBuffer` 发布绝对值；查询、实时 ORDER 和快照恢复传播同一数据。128 位整数避免合法价格/数量乘积超过 signed long，十进制转换只在查询展示边界执行。
- `OrderRuntime` 和 `CoreOrderState.withCommitMetadata` 保留首次创建时间；成交、撤单更新更新时间。`OrderResponse` / `CoreOrderStateView` 提供 `executedValueTicks`、`averagePriceTicks`、`cumulativeFeeUnits`。
- `CoreStateQueryCodec` v6 / `TradingStateSnapshotCodec` v35 不兼容旧格式。用户明确授权清空本地测试数据后，停止六服务及本地依赖，删除本运行目录的交易状态、缓存、Kafka 和本地数据库，重新初始化 20 币对、做市账户、原测试账号。没有清理其他环境或用户代码。
- 本地初始化风险扫描预算 4096，保留开关与 25ms 间隔。此次真实持仓的风险推送 priceSequence 390、391、392 连续前进，浮盈亏同步变化。
- 前端订单表展示实际成交均价、成交/委托金额；最近订单动态有明确“本页面打开期间、最近 100 条”的范围说明。完整历史订单持久化接口目前未接通，前端不再请求空 SQL 投影并把它冒充完整历史。
- 浏览器标签标题复用当前行情状态，显示价格、币对、产品线；切换币对更新，离开交易页恢复标题。不新增轮询、订阅或价格历史缓存。

## 验证

HotSpot Amazon Corretto 27，Maven 3.9.16；开始/结束检查磁盘，约 318 / 324 GiB 可用。未启动 wallet。

| 范围 | 结果 |
| --- | --- |
| 六服务依赖、协议、订单、快照、实时相关 Maven package | 335 项，334 通过，1 条条件跳过；所有模块成功打包 |
| Core 现货/衍生品撮合、结算、风险、资金费、强平、期权/交割相关回归 | 183 项通过；与上一组存在重叠，不作为唯一测试总数 |
| 前端 | 85 项通过；lint 无 error/warning，188 个既有 info；build 成功，有 bundle 大小提示 |
| 20 币对真实 API | 每币对开多 5 steps、全部主动平仓、限价挂单、撤单均通过；验证均价 = 累计成交值 / 成交数量、手续费、剩余数量及撤单不改创建时间 |
| REST / 私有 WS | 买入、平仓、未成交挂单、撤单四种状态的均价、成交值、费用、成交/剩余数量一致；REST 初次 STALE 后等待新快照，未把旧快照当作新结果 |
| 资金核对 | 22 账户和资金库合计 3,010,000,000,000,000 USDT units（30,100,000 USDT），与初始注资一致，差额 0；20 币对净持仓和为 0；测试用户冻结为 0 |
| 60 秒 20 币对 WS | 每币对 324–436 笔成交、95–145 次深度消息，断档 0；最大深度消息间隔约 2.51 秒 |
| Chrome 开发/生产页面 | 12 秒内标签价格多次改变、盘口每次采样改变；DOGE 标题示例 0.09690；无 pageerror；390px 宽度无页面横向溢出 |
| 点击与导航 | 盘口价、最新成交价填入委托价格；站内离开交易页标题恢复 `Surprising EX` |
| 请求 | 当前币对价格指数初始化一次，图表默认 15m 初始化一次；标签更新不新增请求。24h 统计仍有独立 1m 数据初始化，打开列表时会加载各币对统计，不声称完全没有其他周期请求 |
| 运行状态 | Core、Gateway、Price、Realtime、Lifecycle、Maker 六进程运行，前端 5174，20 市场 readiness 通过 |

条件跳过为 `CoreOrderedOrderBatchTest.singleCancelFlushesMatchingPhaseStatisticsAndReleasesFunds`，需要开启每条命令的阶段统计日志；非本次行为修改。其他五产品线仅运行对应自动化测试，没有启动其真实服务；未做其他产品线真实强平、ADL、交割或行权。本次不声称覆盖完整历史订单存储、预计强平价展示或长时间内存压力测试。128 位字段为成交事实的必要状态，无新工厂、接口框架或 Lane 上 BigInteger 容器；未作吞吐提升结论。

## 证据与清理

本机证据：`~/.local/share/surprising-ex/perpetual-pmm-20/verification/order-execution-20260927/`。
包含测试日志、重建路径清单、20 币对下单结果、REST/WS 对比原始结果、资金核对、行情采样、页面截图和最终进程状态。
资金核对期间短暂停止模拟做市提交以取得一致账目，随后恢复全部 20 策略。
保留用户要求的六服务、前端及新数据；本轮开发服务器、Chrome、一次性订阅、查询进程停止后清理 `/tmp/ex-completion`，不删除原有 `/tmp/PmmFundsCheck.java` 等非本轮文件。
