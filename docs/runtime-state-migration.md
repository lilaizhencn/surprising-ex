# Runtime State 热路径迁移方案

## 目标

本方案解决 `LINEAR_PERPETUAL` 下单接收、保证金冻结和进入 exchange-core 前路径的结构性开销。目标不是把业务资金状态交给 exchange-core，而是借鉴其容器、线程和内存设计：数字 ID、primitive/hash 索引、单线程 owner、对象复用和批量快照。

当前基线：业务 Core 使用嵌套不可变 `PersistentTreeMap`。一笔下单会访问用户、余额、reservation、订单和 client-order 索引，并在多个 Map 上进行 AVL 查找与路径复制。exchange-core 使用 `LongObjectHashMap<UserProfile>`、`IntLongHashMap`、`IntObjectHashMap` 和按 symbol 分区的订单簿索引；其 owner 线程原地修改对象，快照时才序列化完整状态。

## 不变的边界

- `LINEAR_PERPETUAL` 的余额、冻结、订单元数据、持仓、风险、Treasury 和生命周期已由 owner-thread Runtime 原地提交；`TradingCoreState` 仅作为兼容读取/持久化视图。
- exchange-core 仍是唯一可执行订单簿；其 user/account/risk module 不成为业务资金来源。
- Exporter、Kafka、PostgreSQL 和 Valkey 不进入本阶段热路径。
- Runtime State 只允许 Core owner 线程写入；Future、Aeron callback 和 Provider 只能提交结果或查询。
- 撮合结果可以异步返回，但必须按 Cluster command sequence 有序 apply。

## 目标模型

```text
TradingRuntimeState
├── users: userId → UserRuntime
├── balances: (userId, assetId) → BalanceRuntime
├── positions: (userId, symbolId, positionSide) → PositionRuntime
├── orders: orderId → OrderRuntime
├── reservations: orderId → ReservationRuntime
├── clientOrders: (userId, clientOrderId) → orderId
└── instruments: symbolId → InstrumentRuntime
```

业务对象使用数字 ID：`assetId`、`symbolId`、`userId`、`orderId`。外部 symbol、asset 和 clientOrderId 只在入口或注册阶段转换一次。

## 阶段一：永续 PLACE_ORDER

### Runtime 类型

新增 `TradingRuntimeState`、`UserRuntime`、`BalanceRuntime`、`OrderRuntime`、`ReservationRuntime` 和 `RuntimeSnapshotBuilder`。初期保留旧 `TradingCoreState` 作为 Snapshot State，不删除旧模型。

当前已落地的第一批实现位于 `surprising-aeron-service` 的 `state` 包：

- `TradingRuntimeState` 绑定单一 owner 线程，并以 primitive map 保存用户、余额、订单、reservation 和 client-order 索引。
- `reserveOrder(...)` 在所有前置条件通过后才执行冻结、订单写入、reservation 写入和幂等索引写入；重复订单、重复 client key、未知用户、未知余额和余额不足均在任何 Runtime 实体写入前拒绝。
- 余额变更使用 `userId → assetId` 两级 primitive 索引；禁止使用可能碰撞的 `(userId, assetId)` 合成 `long` 作为资金裁决键。
- client-order 变更使用 `userId → clientKey → orderId` 两级索引；snapshot 使用 `ClientOrderKey(userId, clientKey)`，不把字符串或哈希值本身当作全局唯一身份。
- `TradingRuntimeSnapshot` 是独立的不可变快照，使用 `BalanceKey(userId, assetId)` 明确表达余额身份，并按数字 ID 排序复制；Runtime 的可变对象不会泄漏到快照。
- `RuntimeStateProjector` 用于启动恢复；`RuntimeStateDeltaApplier` 负责在线 transition 的变更键提交，`RuntimeStateMaterializer` 生成兼容读取视图，禁止每笔命令重新投影完整旧状态。
- `RuntimePlaceOrderDeltaApplier` 已实现首个增量 apply：只接受“订单从不存在到 OPEN、reservation 新增、余额 available/locked 等额变化”的合法差分；校验失败发生在 Runtime 写入之前。
- `CoreProbeState` 的 Runtime 在线模式默认对全部 `ProductLine` 开启；旧 reducer 生成候选 transition，Runtime 在 owner thread 执行增量提交、完整 parity 校验和 materialize。materialized `TradingCoreState` 是后续兼容读取与持久化的权威视图。
- `CoreProbeState` 持有唯一的持久 Runtime 实例及其 Core 游标。每次提交要求游标与当前 Core 完全一致；任何不一致、差分校验失败或 parity 失败都会阻断命令，不会继续使用可能过期的 Runtime 状态。

这批实现已替换 `CoreProbeState` 的在线状态提交边界。旧 reducer 仍负责生成候选 transition，Runtime delta apply 是在线提交门禁；每个成功 transition 均经完整 parity 和 materialize 后成为新的权威 Core 视图。

### 热路径

```text
decode command
→ resolve numeric IDs
→ lookup user/instrument/balance
→ validate order and deterministic margin
→ mutate available/locked balance
→ insert order/reservation/client index
→ append changed keys
→ create pending matching
→ submit exchange-core asynchronously
→ return PENDING
```

### 容器要求

- 数字 key 优先使用已有 exchange-core 依赖中的 Eclipse Collections primitive map。
- Runtime 不实现 `NavigableMap`；有序遍历只发生在 Snapshot Builder、风险扫描和恢复校验。
- 禁止在订单热路径调用 `stream()`、`TreeSet`、`TreeMap`、`UUID.randomUUID()` 和全量 `values()` 扫描。
- 每个 Runtime command 使用预分配或可复用的 `ChangedKeys` 收集器。

## 阶段二：撤单与拒单

普通永续撤单、批量撤单、替换/修改订单中的旧单撤销以及触发子单预占已接入 Runtime 差分提交：`RuntimeCancelOrderDeltaApplier` 只接受
`OPEN -> CANCELED`，在 Runtime 修改前校验用户归属、reservation 全量释放、available/locked
资金变化和 Runtime 当前游标，随后原子释放余额并保留终态订单及 client index。部分成交后的撤单同样校验已消费单位、剩余冻结与终态 reservation；拒单和未专门迁移的生命周期命令由通用 `RuntimeStateDeltaApplier` 提交。

进入阶段二的门槛是：阶段一的 snapshot 对照、失败不变更、重复请求幂等和资金守恒测试全部通过；不得以旁路结果替代 Runtime 的在线提交门禁。

## 阶段三：成交 apply

按 sequence 重排撮合结果，迁移成交扣减、手续费、持仓、open interest、冻结释放、强平和 ADL。Runtime 只接收 owner 线程调用，异步回调不得修改状态。

永续成交的差分契约必须同时覆盖：

- taker/maker 两侧订单的 `executedQuantitySteps`、`remainingQuantitySteps` 和 terminal status；
- reservation 的 `consumedUnits`、`releasedUnits` 和 remaining units；
- 结算资产 available/locked balance、已实现盈亏、释放保证金、追加保证金和手续费；
- position 的 signed quantity、entry price/value、realized PnL 和 position margin；
- fee treasury、insurance treasury 及其 deficit。

Runtime 已增加 position 与 treasury 的 owner-thread 结构并纳入投影。`RuntimePerpetualMatchProcessor`
可从撮合前状态独立执行原生 maker/taker perpetual fill，覆盖订单、余额、reservation、持仓和 treasury，
且其独立契约测试与 reducer parity 通过。它尚未接入真实异步撮合回调：预占命令与成交回调之间的用户 revision
生命周期尚未统一，直接提交会被 parity 门禁阻断。当前成交仍由 `RuntimeStateDeltaApplier` 提交，随后完整
materialize 为 `TradingCoreState`；这保证资金与状态一致，但不满足原生成交热路径迁移目标。

### 阶段三补充：永续资金费

`RuntimePerpetualFundingProcessor` 已在独立 Runtime 投影上实现永续资金费计算，并接入
`CoreProbeState` 的 `APPLY_FUNDING` Runtime 提交门禁。当前覆盖：

- settlementId 单调递增、instrument version、mark price 和 chunk cursor 校验；
- 同一用户 NET/LONG/SHORT position 的资金费汇总和逐腿 payment facts；
- 正向入账、负向扣款按 available cash 封顶，以及 insurance/deficit 净额调整；
- 多用户分片、nextCursorUserId、funding progress commandId 和完成后的 settlement marker；
- Snapshot 恢复后的 progress 续跑与完整 Runtime parity。

旧 `TradingCoreReducer.applyFundingWithFacts` 仍生成候选状态和事件 facts。原生 Runtime 在整批计算
全部成功后原地提交；任何计算、完整 Core 或 Snapshot 差异都会阻断命令，不允许部分提交。

## 阶段四：快照和恢复

`RuntimeSnapshotBuilder` 已按数字 ID 固定排序生成独立的 `TradingRuntimeSnapshot`，覆盖 user、balance、order、reservation、client-order index、position、mark price、risk snapshot、risk scan、nextLiquidationId、liquidation、fee/insurance/deficit treasury，以及 funding settlement/progress。快照与 Runtime 可变对象隔离，可用于验证 immutable、确定性、资金、持仓和 treasury。持久化沿用现有 `CoreStateSnapshotCodec`/`TradingStateSnapshotCodec` 编码边界：Runtime 在快照前 materialize 为 `TradingCoreState`，恢复后由 `RuntimeStateProjector` 重建 primitive/hash 索引并执行完整 parity；Runtime 可变对象不直接写入 wire format，避免把热路径容器布局固化为持久化协议。恢复校验比较 engine hash、book hash、business hash 和资金总额。

### 阶段四补充：永续风险扫描

`RuntimePerpetualRiskProcessor` 已独立复刻 `APPLY_MARK_PRICE` 和 `CONTINUE_RISK_SCAN` 的分页状态机，
并在旧 reducer 完成后、trigger order 扫描初始化前执行 Runtime parity。当前覆盖：

- mark price sequence 单调校验和最新 mark 原地更新；
- position、reservation、cross snapshot 三阶段游标及按工作条目计数的批次上限；
- isolated equity，以及同结算资产 cross portfolio 的 unrealized、maintenance、隔离保证金扣除；
- `PLANNED` liquidation 创建、刷新、恢复取消，非 `PLANNED` active 状态不覆盖；
- `nextLiquidationId`、完整 risk/trigger scan progress 和 Snapshot 恢复投影；
- 扫描中到达新 mark 时先完成旧游标轮次，再以最新 sequence 重启完整第二轮。

旧 `TradingCoreReducer` 负责生成候选状态。`CoreProbeState` 现在只在 Runtime 游标与 Core 状态失配时
执行恢复投影；连续 mark 和 continuation 直接复用同一个 owner-thread Runtime，原地更新 mark、risk
snapshot、scan、liquidation 和 `nextLiquidationId`。trigger scan 若只推进 trigger/scan 元数据，会把 scan
progress 增量同步回 Runtime；若触发子订单导致用户、订单或资金状态变化，则显式使 Runtime 游标失效，
下一条 Runtime 命令按恢复路径重建，禁止带着部分状态继续运行。

风险热路径不再扫描全部 liquidation：`TradingRuntimeState` 使用
`userId -> symbolId -> positionSide -> liquidationId` primitive 分层索引定位 active plan。position 和
reservation 翻页复用现有 `NavigableMap`，不再为每个 cursor step 创建临时 `TreeMap`。instrument 配置和
有序风险用户集合仍由 Core 的版本化 instrument 与 `PositionUserIndex` 提供；它们是只读输入，不发生
Runtime 全状态投影。

## 阶段五：其他产品线

Runtime owner、增量 delta apply、materialize/parity 和恢复投影已对全部 `ProductLine` 开放；`CoreProbeState`
不再把 Runtime 权威链路限制为 `LINEAR_PERPETUAL`。各产品线仍由现有 reducer 保持独立资金语义：现货资产冻结/成交，
币本位永续保证金/资金费/强平，交割到期结算，期权权利金/行权/到期失效。服务模块的产品线金融矩阵、生命周期、
触发器、撮合 continuation 和 Snapshot 恢复测试均在 Runtime 门禁开启下通过。

## 性能门禁

必须分别记录 `decode`、`lookup`、`riskValidation`、`marginCalculation`、`balanceFreeze`、`indexInsert`、`snapshot/export` 和 `matchingSubmit`。测试分为：单笔延迟、短 burst、持续吞吐、固定状态规模、增长状态规模和恢复后热路径。

目标不是只看单次峰值；任何 `100k/s` 声称都必须在无日志污染、固定 CPU 配置、明确 in-flight 上限、资金校验通过且持续时间达标的条件下成立。

## 失败与回滚

Runtime 与候选 `TradingCoreState` 必须保持 parity，但不能双重扣款。发现 hash、资金、订单状态或恢复差异时，阻断当前 transition；不得用 fallback 混合两套裁决逻辑。

### 当前实施状态

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| Runtime 基础容器 | 已完成 | primitive map、单线程 owner、两级余额索引 |
| 原子冻结入口 | 已完成 | `reserveOrder` 及重复请求、余额不足、溢出保护 |
| Runtime Snapshot | 已完成 | 不可变、排序，已覆盖余额、订单、reservation、position、mark/risk scan、liquidation、treasury 和 funding progress |
| PLACE_ORDER 增量 apply | 已完成 | `RuntimePlaceOrderDeltaApplier` 已具备成功和失败不变更测试 |
| CoreProbe Runtime 提交门禁 | 已完成 | 每个 transition 必须通过 Runtime delta、完整 parity 和 materialize；无可关闭的对照旁路 |
| Runtime 持久状态生命周期 | 已完成 | 唯一 Runtime 实例与 Core 游标强制一致；游标异常立即阻断 transition |
| 下单/撤单 Runtime 差分 | 已完成 | `LINEAR_PERPETUAL` 普通、批量、替换/修改和触发子单路径逐项校验；失败不变更 |
| 旧状态逐字段对照 | 已完成 | `RuntimeStateParityChecker` 覆盖 instrument、风险、持仓、订单、触发器、treasury、资金费进度和完整 Snapshot 字段 |
| 永续 Runtime 在线权威 | 已完成 | 下单/撤单使用专用 Runtime delta，资金费、风险和强平使用原生处理器；成交尚由通用 delta 提交，所有路径均完整 parity 与 materialize |
| 部分成交后的撤单 | 已完成 | 严格校验已成交/已消费单位、取消释放单位、剩余冻结和终态订单；已覆盖资金守恒与 parity 测试 |
| 成交差分契约 | 已完成 | 原生 perpetual match processor 覆盖订单、reservation、持仓、结算余额和 treasury，并有独立 parity 测试 |
| position/treasury Runtime 投影 | 已完成 | 已接入 owner-thread Runtime 和恢复投影，含资金/持仓字段校验基础 |
| 旧成交增量 applier | 已移除 | `RuntimeMatchDeltaApplier` 已移除；未迁移的成交由通用 `RuntimeStateDeltaApplier` 提交 |
| Runtime 原生永续 fill | 未完成在线接入 | 独立测试覆盖开仓、部分平仓、反向、reduce-only、maker/taker 多 match 和终态 reservation 释放；异步成交的用户 revision 契约待统一后才能接入生产 |
| Runtime 原生永续资金费 | 已完成 | 已覆盖零和、扣款封顶、insurance、分片游标、Snapshot 恢复和 CoreProbe Runtime parity |
| 永续资金费在线提交 | 已完成 | 资金费 transition 统一经过 Runtime delta apply，并由完整 parity 门禁保护 |
| Runtime 原生永续强平成交 | 已完成 | 已覆盖 cross/isolated、部分/全部平仓、手续费封顶、缺口、insurance、分片撤单游标和 CoreProbe Runtime parity |
| Runtime 原生永续强平保险解析 | 已完成 | 已覆盖完整/部分 insurance coverage 和残余 ADL_REQUIRED 状态 |
| Runtime 原生 ADL | 已完成 | 已覆盖目标持仓一致性、mark sequence、盈利容量、部分/全部减仓、保证金释放、deficit 覆盖和资金守恒 |
| 永续强平在线提交 | 已完成 | 强平、insurance、ADL transition 统一经过 Runtime delta apply，并由完整 parity 门禁保护 |
| 风险扫描原生迁移 | 已完成 parity | 已覆盖 mark、三阶段分页、cross/isolated、PLANNED 创建/刷新/取消、nextLiquidationId 和 CoreProbe Runtime parity |
| 风险扫描原地增量提交 | 已完成 | 连续 mark/continuation 复用持久 Runtime；trigger-only scan 增量同步，状态变化时显式失效恢复 |
| active liquidation Runtime 索引 | 已完成 | primitive 分层精确索引，创建、刷新、取消和恢复投影同步维护 |
| SPOT Runtime 权威与交易链路 | 已完成 | CoreProbe 下单/撤单/触发器/撮合 continuation 和 Snapshot 恢复通过 Runtime parity |
| INVERSE_PERPETUAL Runtime 权威与交易链路 | 已完成 | 共享 Runtime 容器，沿用币本位 reducer 资金语义并通过产品线金融矩阵 |
| DELIVERY Runtime 权威与结算链路 | 已完成 | 线性/币本位交割生命周期、分页结算、恢复和 treasury parity 通过 |
| OPTION Runtime 权威与行权链路 | 已完成 | 权利金、行权/到期状态和 Snapshot 恢复通过产品线金融矩阵与 Runtime parity |
