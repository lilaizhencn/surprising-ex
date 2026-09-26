# 本地盘口中断与 BBO 验证（2026-09-26）

## 撮合中断

本地核心反复出现 `another owner commit context is active`，调用链为
`OrderedCommitCoordinator.completeDispatchedMatcherSettlement` →
`MatchingPipelineProgress.submitDeferredMatchingAfterBatch` →
`OrderBatchExecutor.activateOrderBatch` → `TradingCoreRuntime.restoreMatchingCommitContext`。

单笔成交或撤单已完成账户结算、资金守恒检查及结果保存，却在释放本笔 fact context 前恢复下一批订单。
修复将本笔 context 的释放提前到下一批恢复之前；保留原有结算顺序和资金校验，不跳过保护性断言。
同时覆盖普通、异步成交和异步撤单三条完成路径。

验证使用 HotSpot Corretto JDK 27：核心服务 917 项测试，0 失败，2 项条件性跳过。
新增交错单笔、批量下单及批量撤单 30 轮回归，每轮检查预占清空、余额还原。
全量首次运行发现一条旧测试只匹配错误提示全文；现有提示已追加诊断字段，调整为匹配固定前缀，资金及状态断言不变。
本地已更新核心 JAR，原有数据保留，恢复结果另行追加。

## BBO 下单契约

`PlaceOrderRequest.bboPriceMode` 可选 `OPPONENT_1`、`OPPONENT_5`、`SAME_SIDE_1`、`SAME_SIDE_5`。
BBO 只允许 `LIMIT` 且 `priceTicks=0`。`OrderService.normalize` 读取当前产品线的 Core 盘口，
按买盘降序、卖盘升序的不同价格档位排序，确定一次性限价后沿用原有验证和资金冻结流程。
买入的同向价取买盘，对手价取卖盘；卖出相反。深度不足明确拒绝，不改用其他价格。
它不是持续跟价单，价格取样与订单进入撮合之间盘口可能变化。

网关定向测试 91 项通过，含六产品线、两个方向、四种模式及深度不足、参数冲突场景。
BBO 不改变 Core 下单协议、账户类型、资金模型或 Kafka topic。

## 双向止盈止损与返回值一致性

- 新增 `PLACE_TRIGGER_OCO_PAIR` 命令，复用触发单列表编码；不增加订单状态副本或新执行阶段。两条腿在同一账户 Lane 校验成功后统一写入，任一校验失败不写入另一条。
- 网关接受 `atomic=true` 的止盈/止损二元组，校验用户、币对、平仓方向、数量、保证金/持仓模式和价格先后关系；稳定命令 ID 保证重试不重复创建。当前来源为标记价。
- 六产品线验证创建、第二条腿失败的原子性、幂等、止盈/止损任一先触发、另一条取消、快照恢复。
- 排查出同步快速结算路径缺少 `materializeChangeAccumulators`，导致订单成功但响应偶尔为空；补齐与异步完成路径相同的结果物化。恢复测试连续 10 次运行。另修正三处测试对线程先后顺序的假定，保留业务状态、响应和资金断言。
- HotSpot JDK 27：`mvn -pl surprising-gateway -am test` 全部通过，核心 932（2 个诊断条件跳过），网关 609（35 个外部环境/显式启用测试跳过），协议 96，客户端 50，其余依赖模块也通过。
- BBO 真实 API：买卖两个方向各 4 个模式，8 笔 IOC 全部返回终态；对手价成交、同向价未成交撤销。请求返回的委托价来自服务端盘口。
- 前端 52 项测试、lint、构建通过；浏览器检查英文/中文、1440/390 宽度、全局无可见滚动条、BBO 选项、单向/双向提示、明暗主题和真实 K 线/量。

本轮未开启最新价/指数价触发，未宣称私有成交推送与大规模资金验收已通过。运行中服务与既有 Archive 为用户演示环境，继续保留；本轮临时测试输出待现场验证结束后清理。

### 真实 API 补充排障

- 用户确认单向/双向止盈止损仅用于已有仓位的平仓保护，保持 reduce-only。
- 本地首次 OCO 实测暴露生命周期批次没有推进触发扫描。`LiquidationService` 已改用
  `CONTINUE_RISK_SCAN` 推进风险及触发工作，随后查询执行强平；38 项 lifecycle 测试通过。
- 20 个高频做市账户各有约 100 个挂单时，原 64 工作单元扫描预算推进不足。本地通过既有管理 API
  将 `RISK_SCAN_CONTROL` 从版本 1 更新为版本 2，预算 4096、间隔 25ms、enabled=true。
  配置属于本地已复制日志中的权威状态；没有关闭风控或改变保证金规则。
- 大触发单 ID 实测可超过 `Long.MAX_VALUE / 2`。`TriggerOrderCommands.triggerChildOrderId`
  改为正奇数空间内的确定性映射和碰撞探测，避免乘二溢出；六产品两种 OCO 胜出方向定向测试通过。
- 完整回归曾暴露三个既有并发测试依赖后台线程速度（窗口数预期 2 实际 1，或准入事件已被收集）。
  测试补充 matcher/account Lane 等待栅栏，保留资金、状态哈希和恢复断言。
- 浏览器复核英文无中文残留，止盈止损问号可聚焦显示规则，全局滚动条隐藏但滚动保留。

- 升级切换时实测暴露账户回滚后的余额发布问题。`RuntimeAccountRollback.restoreLane` 原先只恢复
  Lane 余额，没有把恢复后的值写回同一回滚缓冲的 after-state；写前失败可能没有 after-state，
  写后失败则可能继续发布尝试写入的值。恢复后立即捕获实际 Lane 余额，供原发布边界使用。
  定向覆盖写前失败、写后恢复、异步冻结回滚，另跑六产品流水线回归，266 项无失败（1 条件跳过）。

- 20:02 的持续做市暴露账户线程直接写 Owner 变更缓冲：`reserveOrder → changedOrder → OwnerIndexedChanges.consolidate` 与 Owner 清空并发，造成越界退出。改为复用 LaneCommitDelta 交接，在 collectControlReservation 登记变更键。
  完整核心 935 项（2 条件跳过）及 realtime provider 47 项均无失败；新增确定性栅栏测试在旧实现下失败（账户任务完成前就看到了订单），修复后通过。
  验证日志：`/tmp/control-reservation-full-tests.log`、`/tmp/control-reservation-counterfactual.log`。

- 20:14–20:15 浏览器实测 15 分钟 K 线自动从 20:00 切到 20:15；新柱成交量依次 0、0.0001、0.0002、0.0003、0.0004，收盘价随逐笔成交变化。记录 `/tmp/chart-live-samples.json`。
- 20:13 公共 WS 15 秒采样（计数含各 1 次订阅确认）：盘口 76、逐笔 5、mark 15、index 15。15 分钟 candle 通道该短窗口只有订阅确认；前端新柱动态由真实逐笔驱动，不能据此声称历史 K 线无缺口。
- 第二次核心全量回归发现两处测试时序假设：normalizedSymbols 的窗口断言需显式 matcher 栅栏；Owner 快照测试需保留第一次 drain 已收到的快照帧。修正后 289 项定向通过（1 条件跳过），没有删除资金、快照恢复和零定时器断言。

- 恢复压力再次检出未来流水批次的 before-image 提前进入当前 Owner 回滚缓冲，前一条普通 PLACE 提交清理时遇到尚无 after-image 的余额条目。`registerPlaceBatchAdmission` 现在只登记待结算索引，既有事件持有资金前后值直到 `collectPlaceBatchAdmission` 的有序提交入口；删除 admissionCapturePrelude 特例，顺序批次只在队首启动。新增状态层用例在旧实现稳定失败（`/tmp/admission-boundary-counterfactual.log`），修复后通过。

### 后续真实交易验收（20:34 起）

- 核心完整回归 936 项（2 条件跳过）、行情 47 项无失败；恢复后四种 OCO 组合（多/空仓 × 止盈/止损胜出）真实 API 通过，胜出腿 TRIGGERED、另一腿 CANCELED，持仓和预占清空。证据：`surprising-gateway/target/live-qa/qa-1790426196963/oco.json`。
- 两用户真实成交资金核对通过：各 4 笔成交，按实际逐笔价格、数量、合约乘数与逐笔手续费独立计算余额，差额均为 0。证据：`target/live-qa/qa-1790426320429/report.json`。
- 私有通配订阅推送必须携带实际成交币对，而非 `*`；六产品线覆盖。网关 618 项（36 项环境/显式启用跳过）和行情 47 项通过。
- 第一轮 100 用户有 7 个订阅失败，未判通过。补充 receiver 错误日志与重连后，捕获 `DriverTimeoutException: keepalive age=1003ms > timeout=1000ms`。发送/接收端取消写死 1 秒，沿用 Aeron 默认 10 秒及其配置；客户端 52 项通过，含 1.5 秒心跳、真实 Driver 重启与分片测试。
- 更新期间一轮 100 用户全部完成交易，但前 30 个用户有启动期 WS 缺失，该轮失败（`/tmp/live-100-users-2.log`）。稳态重跑还检出盘口查询单槽冲突导致 HTTP 500（`/tmp/live-100-users-3.log`），不以重试掩盖该问题。
- 服务快照 recording 7、共识快照 recording 8 已有效落盘，term 27、logPosition 778692896。快照请求工具曾超时，最终以 recording-log 的有效条目确认。
- 浏览器再次验证 1440/390 宽度无横向溢出、英文无中文残留、滚动仍可用且滚动条隐藏。当前触发来源仍仅 MARK，最新价/指数价尚未开放。

- 盘口查询失败根因为 `MatcherCommandPipeline` 只有一个只读请求槽，并发查询被拒绝。改为 Owner → matcher 的有界 SPSC 请求队列，各自保留提交栅栏，消费移除、关闭拒绝未完成项，不缓存业务状态。定向 6 项、完整核心 937 项（2 条件跳过）通过，行情 47 项与价格 86 项回归通过。
- 该修复不等于行情读写分离。`MatchingMarketDataService` 的 HTTP 查询仍经 `OrderAeronGateway` 读取 Core；内部 BOOK 仍为前 50 档全量，`SubscriptionRegistry.publishDepth` 按连接基准生成 SNAPSHOT/DELTA。客户端 `previousSequence` 不连续时重新同步。因此不能声称查询不占用撮合线程，或每个币对仅维护一份独立可查询行情快照。

### 页面与做市补充（21:40）

- Router 另一个写死 1 秒的 Aeron 客户端已修正，真实 Driver 1.5 秒心跳集成测试通过，行情模块 47 项通过。更新后测试阶段 router dropped 保持启动基线 16677、failures=0。
- 第五轮 100 用户仍未整体通过：99 人完成，user 432 的 UNI 最小数量市价 IOC 平仓返回 CANCELED、成交 0。不能把 IOC 等同于保证成交。该测试账户遗留 1 步多仓已另发只减仓单平掉，返回 FILLED；该轮不记为资金/WS 全量验收通过。原始记录 `surprising-gateway/target/live-qa/qa-1790428857044`。
- 该轮 HTTP 下单耗时 p50=260.060ms、p99=2064.906ms；盘口查询 p50=243.502ms、p99=2397.354ms。这是六服务和前端同时运行的本机功能场景，不构成低延迟交易性能验收。
- 资产页曾强制等待六条产品线快照。本地仅启用 LINEAR_PERPETUAL，新增只读 `/api/v1/runtime` 暴露当前配置，前端按其订阅；网关 624 项（36 条件跳过）通过。另移除把 `/balances` 当前产品余额误当资金账户再次相加的逻辑。浏览器核对 user 1 可用 9999.97474791 USDT，按真实汇率展示约 9997.92 USD，只有一条账户记录。
- 做市删除 `trade.min-interval-ms` 配置、判断和 lastTradeTimes 状态，每个完成周期尝试 IOC；库存及数量校验保留。50 项 maker 测试通过，本地覆盖配置同步删除，20 策略 RUNNING。
- 修复历史 `13:30:00Z` 与实时 `13:30:00.000Z` 被视作不同 K 线的问题：API 时间统一，实时按毫秒时间戳合并同一周期，保留开高低并累加实际成交量。禁止以标记价刷新最新成交价，历史迟到不覆盖实时值。
- 前端 55 项、构建及 lint 通过（既有 107 条 info）。30 秒浏览器采样收到 141 次盘口、10 笔 BTC 成交、30 次标记价及 28 次指数价；30/30 次页面采样的 K 线收盘价与盘口中间成交价一致，量从 0.0175 增到 0.0185。实际成交频率仍受每轮铺单与接口耗时影响，未宣称达到高频吞吐目标。
- 本地真实 API 强平、全做市账户总资金守恒、所有历史 K 线无缺口、持续买卖各 50 档尚未完成验收；本轮核心六产品线回归不替代这些现场结论。六个服务与前端按用户要求保持运行，没有启动 wallet。

### 21:44 小规模现场复核

- 两用户 518、519 各 4 笔真实成交，独立核对手续费及已实现盈亏，余额差额均为 0；四种 OCO 组合全部通过，胜出腿触发、另一腿取消，持仓归零。Maven 两项现场测试通过，证据 `surprising-gateway/target/live-qa/qa-1790430237450/report.json`、`qa-1790430212566/oco.json`。该结果不替代百用户验收。

### 22:14 页面与频率复核

- 前端提交 `7f80080`、`2993c2f`：按单档数量渲染红/绿背景，160ms 过渡，实测 88 行均高 26px；蜡烛与量柱 29/29 时间戳一致。横轴统一本地时区，悬停 20:30 时上下成交量均 0.0291，移出恢复最新。删除合约副标题及 Realtime，Buy/Sell 顶部内边距 4px，桌面两侧底线实测 Y=125px。前端 56 测试、构建、lint 通过（107 既有 info）。
- 做市改为每策略独立报价线程，完成立即继续；测试吃单另有独立线程、周期锁及租约，复用普通 IOC 入口。取消 cycle-delay 配置，不增加资金状态副本；暂停、无租约或失败退让 100ms。51 项测试通过，含慢策略不阻塞其他策略、慢报价不阻塞吃单、重复启动与关闭中断。
- 现场仍未达标：20 对各 30 秒仅 8–9 笔成交，单秒最少 0 笔；有效档位也有低于每侧 50 的时段。证据 `/tmp/all-pairs-frequency.json`。不能把解除调度等待当作达到高频目标。
- JFR 前后各约 20 秒；后样本 SocketRead 701 条，主要指向 localhost/127.0.0.1。该样本与启动及本机其他负载并存，不能据此宣称性能提升。线程栈显示同步 Feign 等待，剩余瓶颈仍需分段测量。
- 22:01 两用户成交核账完成，但 OCO 胜出腿 60 秒内仍 PENDING；22:06 复测遇到 mark price unavailable，策略 DEGRADED，均判失败。此前 21:44 通过结论只代表当时窗口。lifecycle 更新客户端包后 38 模块测试通过；现场没有因此判通过。
- 22:09:16 BTC 指数帧明确为 INSUFFICIENT_SOURCES：配置 3 源、门槛 3，Bybit/OKX HEALTHY，Binance sourceTime=14:07:36.015Z、receivedAt=14:09:15.317511Z，判 STALE，导致指数及标记价不可用。没有降低来源数量门槛或放宽价格时效。连接检查新增全源报价过期时重连，87 项价格模块测试通过，含不断收旧帧仍重连、新鲜连接保持。
- 六服务、前端和用户演示 Archive 继续保留。临时 JFR 和线程采样已归纳入本节后清理；失败现场 JSON 暂留用于后续排障。本轮未通过每秒每币对至少 3 笔、持续 50 档以及完整资金/强平验收。

### 五源接入、短周期图表与 PMM（22:50）

- 用户授权初始化两个本地管理员后，经真实 `/api/v1/admin/approvals` 双人审批与 `/api/v1/admin/gateway/instrument-admin/upsert` 应用 20 个合约配置。保留每个合约原有阈值（BTC=3，其余=1）；增加 Kraken USD 20 对、Coinbase USD 19 对，TRX 不添加不存在的 Coinbase 币对。源时间戳与真实 USDT/USD 换算均保留，换算失败禁用该源。
- 22:47 全部 20 合约查询返回有效指数：11 HEALTHY、9 DEGRADED；BTC 5/5 HEALTHY。其他合约的部分 Coinbase ticker 因低成交频率过期，不伪造源时间。证据 `/tmp/index-source-coverage.json`。价格模块 88 测试通过。
- BTC 1m 120 根、5m 95 根，蜡烛和成交量逐根时间戳一致；最新价格长时间仅相差 0.01，不能判作已正常动态绘制。图表补充历史修正同步、币对重建、周期自动缩放、平价边框和 5m 控件。前端 57 测试、构建、lint 通过。
- maker 采用 Hummingbot PMM 外部价格源、分层/库存数量、相对价差/刷新容差、预算分配语义，仍走内部 RPC→gateway→Aeron。修复自身旧卖单锁住新买价、部分成交延长旧单年龄、价差范围与不吃单约束冲突，以及 OPEN_INTEREST_LIMIT_EXCEEDED 后反复补单。保留核心风险校验；预算不足按比例分配且先保留每档最低数量。maker 54 测试通过。
- 本机出现持续 NOT_CONNECTED，核心线程采样显示 Driver 分配/预触碰大量新缓冲区，业务 Owner 等待；客户端每 5 秒建连超时会重新分配。连接池握手期限独立为默认 30 秒，业务请求期限保持原值；客户端 53 测试通过，gateway 624（36 显式/环境跳过）及 lifecycle 38 测试通过。重启相关服务后仍需现场复验，不能用模块测试替代实际交易验收。
- 清理：已删除本轮明确归属的早期 JFR、SocketRead 导出及旧线程采样，共 10 个实际存在文件；用户演示运行目录、Archive 和资金数据均保留。新采样用于当前超时排障，尚未清理。

## 2026-09-26 23:20 PMM 批量模拟与审计修正

- core 于 22:51 保留 Archive 重启，22:55 恢复 leader；未重置资金或日志。网关/生命周期建连超时与请求超时已分开。
- maker PMM 外部价锚定后，BTC 实际成交已从旧 83877.50 附近移动至 83979 附近，前端最新柱与最新成交同时变化。1m/5m 的蜡烛与成交量时间戳对应已检查。
- 23:06 真实 API 测试：用户 528、529 各 4 笔执行，余额、手续费、损益核对差额 0；OCO 用户 527 触发订单 60 秒后仍 PENDING，不能宣称止盈止损全链路通过。风险扫描控制开关读回为 enabled=true，原因仍待定位。失败 QA 用户 520/527 的残留保护单和仓位尚未完成清理，不能删除对应记录冒充已清理。
- maker 模拟吃单改普通 MARKET/IOC 批量，首次审计因旧 SQL 约束拒绝 TRADE_EXECUTED/TRADE_NO_FILL。已应用 docs/validation/maker-trade-event-types-20260926.sql，仅更新事件类型约束。
- 核心批量成功回执可能没有已终结订单详情；maker 原判定误记为拒绝。现成功且缺少详情记 TRADE_SUBMITTED，不推断成交数；详细成功单可区分执行与零成交，真实拒绝保留原因。
- 23:17 前后无 Maven 编译并发的 30 秒公共 WS 采样：20 币对各 195～224 笔真实成交，总体明显增加，但逐秒存在空窗；末次盘口各边 33～50 档。未达到持续每秒 >=3 笔且双边 50 档要求。原始 /tmp/pmm-batch-frequency.log 与 /tmp/all-pairs-frequency.json 留作本轮证据。
- 影响面：maker 普通订单请求、审计 SQL；没有更改撮合/结算算法、资金协议和其他产品线。JDK 27 maker 57 项测试及依赖测试通过；未重跑其他五产品线完整端到端，未对外宣称生产高频能力。磁盘剩余约 418 GiB。
- 按用户要求保留六服务、前端和做市运行，保留持久化 Archive；不因诊断清理删除运行状态。失败测试报告保留用于后续排障。

### 23:21 部署后复查

- maker PID 38970，health UP；审计最近 20 秒 225 个 TRADE_SUBMITTED 批次/1800 张接受订单，未再将缺失终态详情误计为拒绝。maker 最终 package 含 57 项测试全通过，日志 /tmp/pmm-maker-verified-package.log。
- 最终 30 秒 WS：每币对 163～176 笔真实成交，最少逐秒仍为 0，末次深度各边 34～50，20 币对增量序号 gap 合计 0。证据 /tmp/pmm-final-frequency.log 与 /tmp/all-pairs-frequency.json；未达逐秒稳定与连续 50 档验收。
- BTC 1m 页面截图 /tmp/chart-one-minute.png 已目视确认蜡烛、成交量、价格标记；5m 为 102 根蜡烛/102 根成交量，时间戳 mismatch=[]，日志 /tmp/pmm-final-chart-five-minute.log。
- 六服务进程与前端均保留运行供查看。本次没有新增线程、框架或持仓副本；批次只在请求内持有订单列表，资金状态仍由核心唯一维护。
