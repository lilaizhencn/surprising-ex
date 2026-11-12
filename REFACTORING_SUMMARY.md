# `surprising-ex`「顺着业务就能读懂」终态重构总结

## 1. 重构目标与核心度量对比

本次重构严格遵循 [AGENTS.md](AGENTS.md) 与 [AGENTS-simplify.md](AGENTS-simplify.md) 规范，在不新增任何单实现接口、工厂或抽象层、且 100% 保持交易/撮合/资金结算/幂等/快照恢复语义不变的前提下，通过两轮共 10 个阶段的重构，将 `surprising-gateway` 下单入口与 `surprising-aeron-service` 核心交易引擎改造为**顺着业务就能读懂**的终态代码结构。

| 核心衡量指标 | 重构前 | 重构后（实测） | 降幅 |
| :--- | :--- | :--- | :--- |
| **`surprising-aeron-service` 生产源码文件数** | **231 个文件**（含 13 个碎片子包） | **158 个文件**（删除 13 个碎片子包） | **-31.6%** |
| **两轮核心交易包文件总数**（网关 + `order`/`account`/`matcher`/`settlement`/`risk`/`product`/`position`/`query`/`realtime`/`core/change`/`core/command`） | **112 个文件** | **44 个文件** | **-60.7%** |
| **理解完整下单主链路需打开的文件数** | **14+ 个类**（跨多层包装与转发） | **5 个核心业务类** | **-64.3%** |
| **全库字段数 > 50 的巨型万能上下文类数量** | **5 个** | **0 个**（[`TradingAccountRuntime`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java)、[`TradingCoreRuntime`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java) 均在 18 个编译字段以内） | **-100%** |
| **订单命令对象字段数**（[`OrderCommand`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommand.java)） | **55 个编译字段** | **14 个编译字段** | **-74.5%** |
| **账户分区对象字段数**（[`AccountPartition`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountPartition.java)） | **56 个编译字段** | **18 个编译字段** | **-67.9%** |
| **成交清算事件字段数**（[`TradeSettlement`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/TradeSettlement.java)） | **56 个编译字段** | **16 个编译字段** | **-71.4%** |
| **账户运行时字段数**（[`TradingAccountRuntime`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java)） | **74 个编译字段** | **18 个编译字段** | **-75.7%** |
| **核心运行时字段数**（[`TradingCoreRuntime`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java)） | **73 个编译字段** | **18 个编译字段** | **-75.3%** |

---

## 2. 终态一笔订单的 5 跳业务主链路

现在打开项目阅读一笔普通订单从网关进入到结算返回，只需顺着以下 **5 个业务类** 线性阅读：

1. **网关下单服务**：[OrderService.java](surprising-gateway/src/main/java/com/surprising/trading/order/service/OrderService.java)
   - 接收 [OrderInternalController.java](surprising-gateway/src/main/java/com/surprising/trading/order/controller/OrderInternalController.java) 请求，完成产品规则归一化、订单落库、Aeron 协议编码发送，以及成交/拒绝回执落库。
2. **订单准入与冻结计算**：[OrderAdmissionService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderAdmissionService.java)
   - 按 [ProductTradingRules.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/product/ProductTradingRules.java) 校验币对状态、杠杆、风险限额、只减仓容量（[`Position.CloseCapacity`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/position/Position.java)），计算应冻结保证金/现货资产额度，并向所属账户分区 [AccountPartition.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountPartition.java) 派发资金预留。
3. **撮合执行流编排与订单簿引擎**：[OrderMatchingFlow.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderMatchingFlow.java) → [OrderBookEngine.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookEngine.java)
   - `OrderMatchingFlow` 将通过准入的 [OrderCommand.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommand.java) 提交给对应币对分片的 `OrderBookEngine` 撮合订单簿，生成不可变撮合结果 [OrderMatchingResult.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderMatchingResult.java) 或直达结算事件 [TradeSettlement.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/TradeSettlement.java)。
4. **成交清算与账户分区入账**：[SpotTradeSettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/SpotTradeSettlementService.java) / [DerivativeTradeSettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/DerivativeTradeSettlementService.java) → [AccountPartition.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountPartition.java)
   - 按成交明细计算买卖双方手续费、保证金释放与已实现盈亏，更新 [AccountBalance.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountBalance.java)、[Position.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/position/Position.java)、[Order.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/Order.java) 与 [OrderFundsReservation.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderFundsReservation.java)。
5. **有序提交与回执发布**：[OrderCommitService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommitService.java)
   - 按 `coreSequence` 严格递增顺序收齐各账户分区回执（[AccountPartitionCommit.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/change/AccountPartitionCommit.java)），校验财库守恒，生成最终响应并释放命令槽。

---

## 3. 各模块文件收敛明细（112 → 44）

### 3.1 第一轮：网关入口、`order/`、`account/`、`matcher/`（60 → 25，删除 5 个子包）
- **`surprising-gateway` 下单入口**：[OrderInternalController.java](surprising-gateway/src/main/java/com/surprising/trading/order/controller/OrderInternalController.java)、[OrderService.java](surprising-gateway/src/main/java/com/surprising/trading/order/service/OrderService.java)。
- **`order/`**：[Order.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/Order.java)、[OrderFundsReservation.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderFundsReservation.java)、[OrderCommand.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommand.java)（14 字段）、[OrderCommandQueue.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommandQueue.java)、[OrderAdmissionService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderAdmissionService.java)、[OrderMatchingFlow.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderMatchingFlow.java)、[OrderBatchProcessor.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderBatchProcessor.java)、[OrderCommitService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderCommitService.java)。
- **`account/`（14 → 8）**：[AccountBalance.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountBalance.java)、[UserAccount.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/UserAccount.java)、[UserAccountSnapshot.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/UserAccountSnapshot.java)、[ClientOrderIdRegistry.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/ClientOrderIdRegistry.java)、[AccountExposureIndex.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountExposureIndex.java)、[AccountPartition.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountPartition.java)（18 字段）、[AccountCommandService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountCommandService.java)、[AccountTransactionRollback.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/account/AccountTransactionRollback.java)。
- **`matcher/`**：[OrderBookEngine.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookEngine.java)、[OrderBookRouter.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookRouter.java)、[OrderMatchingResult.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderMatchingResult.java)、[OrderCancelResult.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderCancelResult.java)、[OrderBookDepthSnapshot.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookDepthSnapshot.java)、[OrderBookSnapshot.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookSnapshot.java)、[OrderBookSnapshotCodec.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/OrderBookSnapshotCodec.java)。

### 3.2 第二轮：清算、持仓、产品规则、风控强平、查询、实时与核心运行时（52 → 19，删除 8 个子包）
- **`position/`**：[Position.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/position/Position.java)，平仓容量在 `Position.CloseCapacity`。
- **`product/`**：[ProductTradingRules.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/product/ProductTradingRules.java)、[OptionTradingRules.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/product/OptionTradingRules.java)。
- **`market/`、`funding/`、`adl/`、`liquidation/`（5 → 4）**：[MarkPrice.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/market/MarkPrice.java)、[FundingSettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/funding/FundingSettlementService.java)、[AdlService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/adl/AdlService.java)、[LiquidationService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/liquidation/LiquidationService.java)。
- **`risk/`**：[RiskMarginCalculator.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/risk/RiskMarginCalculator.java)、[RiskScanner.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/risk/RiskScanner.java)。
- **`settlement/`**：[TradeSettlement.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/TradeSettlement.java)（16 字段）、[SpotTradeSettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/SpotTradeSettlementService.java)、[DerivativeTradeSettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/DerivativeTradeSettlementService.java)、[ContractExpirySettlementService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/ContractExpirySettlementService.java)。
- **`query/` + `realtime/`**：[CoreQueryService.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/query/CoreQueryService.java)、[CommittedTradingActivity.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/query/CommittedTradingActivity.java)、[TradingRealtimeBoundary.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/realtime/TradingRealtimeBoundary.java)。
- **`core/change/` + `core/command/`（11 → 3）**：[AccountPartitionCommit.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/change/AccountPartitionCommit.java)、[StateChangeBuffer.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/change/StateChangeBuffer.java)、[DirectCommandDispatcher.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/command/DirectCommandDispatcher.java)。
- **核心运行时巨型类字段瘦身**：
  - [TradingAccountRuntime.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java)：顶层编译字段 **18 个**。
  - [TradingCoreRuntime.java](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java)：顶层编译字段 **18 个**。执行水位在内部 `ExecutionState`。


---

## 4. 第四轮：全景架构深度收敛（按顺序全部清理干净）

### 阶段 A：网关层清爽化与去 Coordinator 化
- **括号订单**：[`BracketOrderDispatcher`](surprising-gateway/src/main/java/com/surprising/trading/order/service/BracketOrderDispatcher.java) 处理括号订单，测试与调用方使用同一类型。
- **配置属性去污染**：在 `TradingOrderProperties` 暴露顶层的 `getProductLine()`，消除了长期以来在 `OrderAeronGateway` 和 `OrderService` 中出现 `properties.getKafka().getProductLine()` 的怪异误导写法。
- **网关核心测试**：`BracketOrderDispatcherTest`、`OrderServiceTest`、`MakerQuoteInputsControllerTest` 等共 140 项单测全部通过。

### 阶段 B：衍生品生命周期配置与网关清理
- **属性统一化**：在 `InsuranceProperties` 与 `AdlProperties` 中增加顶层 `getProductLine()` 与 `setProductLine()`，消除了生命周期模块读取 `kafka.productLine` 历史残余包袱。
- **网关清晰化**：`DerivativesAeronClient`、`InsuranceAeronGateway`、`AdlAeronGateway` 统一通过顶层配置直读产品线类型。
- **生命周期测试**：`LiquidationAeronGatewayTest`、`InsuranceAeronGatewayTest`、`FundingAeronGatewayTest` 等 10 项测试 100% 成功。

### 阶段 C：交易核心主状态机与总账解耦
- **持仓索引抽离**：抽取 `PositionIndexOps.java`，把持仓二分查找插入、撤销及标的非零暴露维护从 `TradingAccountRuntime` 剥离。
- **客户订单双向索引抽离**：抽取 `ClientOrderIndexOps.java`，将客户号与订单实体号的正反向索引维护解耦。
- **强平状态索引抽离**：抽取 `LiquidationIndexOps.java`，将未完成活动强平单的标的与方向索引解耦。
- **条件单业务规则解耦**：抽取 `TriggerOrderOperations.java`，将条件单生命周期转换（Pending/Triggering/Triggered/Expired/Retry）、OCO 止盈止损双腿准入校验以及移动止损激活维护完整解耦，`TradingAccountRuntime` 仅做委托调用。
- **瘦身收益**：`TradingAccountRuntime.java` 减少约 400 行低级细节代码，真正聚焦于账户多 Lane 调度与核心总账边界。
- **验证成果**：
  - 核心运行时单测套件 116 项 100% 通过（0 失败，0 错误）；
  - 全工程 23 个 Maven 反应堆模块全量编译通过（35s 编译完成）；
  - 代码已全部 push 到远程 master 分支。


---

## 5. 第五轮：架构异味彻底清理与代码精简重构（Coordinator 清理与配置解耦）

### 5.1 消除三大 Coordinator 异味类
- [`AeronLifecycleRetry`](surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronLifecycleRetry.java)：带重试/指数退避的生命周期 Aeron 执行器。ADL、Funding、Insurance、Liquidation 调用方使用这一类型。
- [`ProductTransferService`](surprising-gateway/src/main/java/com/surprising/gateway/provider/service/ProductTransferService.java)：网关层跨产品线划转业务服务。网关与联盟营销调用方使用这一类型。
- [`OrderMatchingFlow`](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderMatchingFlow.java)：订单撮合执行流编排，覆盖下单、撤单和改单。

### 5.2 配置顶层属性下放与 `getKafka().getProductLine()` 彻底清理
- 各服务模块的 `Properties` 配置类（`FundingProperties`、`AccountProperties`、`IndexPriceProperties`、`MarkPriceProperties`、`CandlestickProperties`、`WebSocketProperties`）均在类顶层暴露 `productLine` 字段。
- 全量清理各业务模块中历史残留的 `.getKafka().getProductLine()` 调用链（共清理 65 处），彻底解开对底层 Kafka 属性树的误导依赖。

### 5.3 质量与测试全量验证
- **全工程多模块编译**：23 个反应堆模块 100% 编译通过（`BUILD SUCCESS`）。
- **生命周期服务测试**：`AdlServiceTest`, `FundingServiceTest`, `InsuranceServiceTest`, `LiquidationServiceTest`（23 项全过）。
- **价格服务测试**：`MarkPriceServiceTest`, `IndexPriceServiceTest`（17 项全过）。
- **网关服务测试**：`ProductTransferServiceTest`, `ProductTransferGatewaySurfaceTest`, `OrderServiceTest`（163 项全过）。
- **交易核心状态机与撮合测试**：`TradingAccountRuntimeTest`（55 项）、`TradingCoreOwnerTest`（43 项）、`CoreMatchingStateTest`（51 项）、`PendingMatchingRingTest`（6 项）共 155 项全过。
- **Git 提交**：提交哈希 `a2ee2ddf8`，已成功推送到远程分支 `origin/master`。
