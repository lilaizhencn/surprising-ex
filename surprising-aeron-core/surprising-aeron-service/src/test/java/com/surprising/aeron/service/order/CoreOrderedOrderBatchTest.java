package com.surprising.aeron.service.order;

import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.core.AccountLaneQueryTestSupport;
import com.surprising.aeron.service.core.CoreFaults;
import com.surprising.aeron.service.core.PipelinedExecutionExtension;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.matcher.pipeline.MatcherCommandPipeline;
import com.surprising.aeron.service.matcher.pipeline.MatcherPipelineGroup;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommandType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 现货和 U 本位永续批量订单的综合回归场景：覆盖单项顺序、资金与预留、幂等、账户所有权、故障保留及部分恢复重放。JUnit 扩展把执行模式设为 PIPELINED，个别参数仅切换完成辅助方式。各测试注释只说明实际断言，故障状态中的账户事实不当作已提交终态。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreOrderedOrderBatchTest {

    /**
     * 先让只挂单因会成交而被拒，再撤该终态订单；检查单项报找不到订单、无预留残留、资金恢复且仍可快照。
     */
    @Test
    void cancelBatchRejectsTerminalOrderWithoutEnteringMatcherChunk() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, "BTC", 2, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(86_200, "cancel-rejected-maker", CoreOrderSide.SELL, 1_000, 1)))) ,1002));
            // 已经存在对手单时会被只挂单规则拒绝的新买单输入
            var rejected = new PlaceOrderCommand(86_201, "1", CoreOrderSide.BUY, 1000, 1,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTX, true, "cancel-rejected");
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(rejected)))));
            // 再次撤该终态订单返回的整批 Core 响应
            var result = drainBatch(state, command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 5,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(86_201))))));
            assertThat(TradingOrderBatchCodec.decodeResult(result.data()).items().getFirst().resultCode())
                    .isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            state.assertClusterCallbackComplete();
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(10_000);
            assertThat(state.snapshot()).isNotEmpty();
        }
    }

    /**
     * 单笔下单及撤单成功后，预留清空且报价资金全数可用。
     */
    @Test
    void singleCancelReleasesReservedFunds() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            assertThat(CoreTestCompletion.applyAsynchronously(state,
                    command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2,
                            TradingCommandCodec.encodePlaceOrder(place(86_101, "metrics-cancel", 1_000))))
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(state,
                    command(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 3,
                            TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(86_101))))
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits())
                    .isEqualTo(10_000);
        }
    }

    /**
     * 撤两张活动单及一张不存在订单，检查有序单项结果、资金解冻及旧单移除；同命令重放和快照恢复后重放都返回相同结果字节。
     */
    @Test
    void cancelBatchCommitsInItsSettlementTaskAndKeepsDuplicateAndSnapshotResults() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(86_001, "cancel-fused-1", 1_000),
                            place(86_002, "cancel-fused-2", 1_000))))));
            // 含自有、重复或缺失订单的待测撤单命令
            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(86_001), new CancelOrderCommand(86_002),
                            new CancelOrderCommand(86_003)))));
            // 整批响应解码得到的逐项结果
            CoreResponse result = drainBatch(state, cancel);
            // 按原输入下标排列的批量单项结果列表
            var items = TradingOrderBatchCodec.decodeResult(result.data()).items();
            assertThat(items.get(0).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(items.get(1).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(items.get(2).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(10_000);
            assertThat(state.tradingState().orders().get(86_001L)).isNull();
            assertThat(state.tradingState().orders().get(86_002L)).isNull();
            assertThat(state.apply(cancel).data()).isEqualTo(result.data());
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(restored.apply(cancel).data()).isEqualTo(result.data());
            }
        }
    }

    /**
     * 用余额刚够的原单检查涨价改单资金不足时原单保留，再降价改单复用已冻结资金；比较两种完成方式，异步辅助路径还要求不借走账户所有权，撤掉新单后资金全部恢复。
     * @param asynchronous 是否使用异步完成辅助入口，否则手动排空；线程执行模式仍由扩展设为 PIPELINED
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void spotAmendReusesLockedFundsAndRejectsUnaffordableIncreaseBeforeMatching(boolean asynchronous) throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 1_000, 1);
            applyBalance(state, 1002, "BTC", 1, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(85_000, "funds-maker", CoreOrderSide.SELL, 1_100, 1)))), 1002));
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(85_001, "funds-original", 1_000))))));

            // 继续准入前记录的账户所有权交接代数，完成后核对没有临时借走 Lane
            long handoffBefore = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
            // 资金不足而预期被拒绝的改单消息
            CoreMessage rejectedCommand = command(CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 5,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(85_001, 85_002, "unaffordable", 1_100L, 1L,
                                    CoreTimeInForce.GTC, false)))));
            // 资金不足的改单批次 Core 响应
            CoreResponse rejected = asynchronous ? CoreTestCompletion.applyAsynchronously(state, rejectedCommand)
                    : drainBatch(state, rejectedCommand);
            // 当前批量结果的一项，读取其状态、结果码、订单或成交明细
            var item = TradingOrderBatchCodec.decodeResult(rejected.data()).items().getFirst();
            assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(item.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            assertThat(item.executions()).isEmpty();
            assertThat(state.tradingState().orders().get(85_001L)).isNotNull();
            assertThat(state.tradingState().orders().get(85_000L)).isNotNull();
            assertThat(state.tradingState().orders().get(85_002L)).isNull();

            handoffBefore = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
            // 降价后能够复用原冻结资金的改单消息
            CoreMessage appliedCommand = command(CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 6,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(85_001, 85_003, "affordable", 900L, 1L,
                                    CoreTimeInForce.GTC, false)))));
            // 成功改单的 Core 终态响应
            CoreResponse applied = asynchronous ? CoreTestCompletion.applyAsynchronously(state, appliedCommand)
                    : drainBatch(state, appliedCommand);
            if (asynchronous) assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState()))
                    .as("amend preflight and reservation stay on the permanent Lane").isEqualTo(handoffBefore);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(applied, 1)).isEqualTo(-1);
            assertThat(state.tradingState().orders().get(85_001L)).isNull();
            // 当前要检查的资产可用与冻结余额
            var balance = state.tradingState().users().get(1001L).balances().get("USDT");
            assertThat(balance.availableUnits()).isEqualTo(100);
            assertThat(balance.lockedUnits()).isEqualTo(900);
            drainBatch(state, command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(85_003))))));
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(1_000);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 提交改单前故意把买方基础币余额改到最大值，让真实账户成交入账溢出；检查没有推进提交和发布水位或保存终态。再从故障前快照重放原命令，核对双方资金、无剩余订单及重复重放不二次结算。
     */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void spotAmendLaneFailureStopsTheBatchAndRecoversFromPriorSnapshotAndLog() throws Exception {
        // 本场景独立 Core 运行时，资源结束或 finally 中关闭
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000, 1);
            applyBalance(state, 1001, "BTC", 1, 2);
            applyBalance(state, 1002, "BTC", 1, 3);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(84_000, "fault-maker", CoreOrderSide.SELL, 1_100, 1)))), 1002));
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 5,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(84_001, "fault-original", 1_000))))));
            // 故意破坏账户余额前保存的恢复快照
            byte[] checkpoint = state.snapshot();
            // 故障注入前的已提交 Core 水位
            long committedBefore = state.committedCoreSequence();
            // 故障前的独立发布序号，失败不得错误前进
            long projectionBefore = state.realtimeExportSequence();
            // 当前待测的批量改单消息或其单项输入
            var amend = command(CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 6,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(84_001, 84_002, "fault-replacement", 1_100L, 1L,
                                    CoreTimeInForce.GTC, false)))));
            // 必须在提交批次之前注入故障，因为 Matcher 会直接发布给账户。
            // 若先等撮合结果再破坏余额，账户结算可能已经完成；这里让真实账户入账检查触发溢出。
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 当前运行时的资产、品种、客户号及持仓身份字典
            RuntimeIdentityRegistry identities = field(state, "identities");
            // 故障注入是否临时取得 Owner 账户访问权，finally 只释放本次取得的一层
            boolean acquired = runtime.accountLanesStarted() && !runtime.ownerLaneAccess();
            try {
                if (acquired) runtime.acquireOwnerLaneAccess();
                runtime.putBalance(new com.surprising.aeron.service.account.BalanceRuntime(
                        1001, identities.assetId("BTC"), Long.MAX_VALUE, 0));
                // 这里故意破坏测试余额，随后清变更跟踪。
                // 下一命令应在真实账户算术检查中失败，不能把这次注入当成业务余额调整。
                runtime.clearChangedKeys();
            } finally {
                if (acquired) runtime.releaseOwnerLaneAccess();
            }
            assertThat(state.apply(amend).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(amend.header().commandId());
            // 当前取得的同一 Matcher 事实，可能尚未完成账户结算
            var matching = awaitMatching(state, sequence);
            assertThat(matching.matcherEvents()).isNotEmpty();

            // 本轮捕获的执行失败，用于核对故障类型及未提交状态
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                    completeEventually(state, sequence, matching, 2_000, 6));
            assertThat(failure).isInstanceOf(
                    FatalMatchingDivergenceException.class)
                    .hasRootCauseInstanceOf(ArithmeticException.class);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(state.realtimeExportSequence()).isEqualTo(projectionBefore);
            assertThat(state.commandResults()).doesNotContainKey(amend.header().commandId());
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 7))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(state::snapshot).isInstanceOf(RuntimeException.class);

            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, checkpoint)) {
                // 当前已收到或由完成回调交出的 Core 响应
                CoreResponse response = drainBatch(restored, amend);
                assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 1)).isEqualTo(-1);
                // 完成阶段导出的业务状态，供余额、预留及订单断言读取
                var trading = restored.tradingState();
                assertThat(trading.users().get(1001L).balances().get("BTC").availableUnits()).isEqualTo(2);
                assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(8_900);
                assertThat(trading.users().get(1002L).balances().get("USDT").availableUnits()).isEqualTo(1_100);
                assertThat(CoreStateTestFixtures.totalUnits(trading.users().get(1002L).balances().get("BTC"))).isZero();
                for (long /* 当前需要核对资产或订单的参与账户用户编号 */ user : new long[]{1001, 1002}) {
                    assertThat(trading.users().get(user).reservations()).isEmpty();
                    assertThat(trading.users().get(user).balances().values()).allSatisfy(/* 当前要检查的资产可用与冻结余额 */ balance ->
                            assertThat(balance.lockedUnits()).isZero());
                }
                assertThat(trading.orders()).isEmpty();
                // 快照重放成功后的业务哈希，用于检查重复执行及再恢复没有改变状态
                long recoveredHash = trading.businessStateHash();
                drainBatch(restored, amend); // 重复重放不能让同一成交再次结算
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(recoveredHash);
                try (/* 已恢复状态再次快照并恢复出的 Core，用来检查结果稳定 */ TradingCoreRuntime again = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, restored.snapshot())) {
                    assertThat(again.tradingState().businessStateHash()).isEqualTo(recoveredHash);
                }
            }
        } finally {
            // 故障工作线程关闭时仍会抛错；测试清理也要继续停止其他工作线程。
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            org.assertj.core.api.Assertions.catchThrowable(state::close);
            if (runtime != null) {
                for (Object /* 本场景需要显式停止或放行的账户工作线程 */ worker : (Object[]) com.surprising.aeron.service.core.AccountLaneTestAccess.workers(runtime)) {
                    if (worker instanceof AutoCloseable /* 已经确认可关闭的工作线程对象，故障清理仍要停止其他线程 */ closeable) {
                        org.assertj.core.api.Assertions.catchThrowable(closeable::close);
                    }
                }
            }
        }
    }

    /**
     * 四笔买单共同吃掉一张卖单，检查双账户结算、终态回收并追加快照恢复哈希比较。
     */
    @Test
    void spotPipelinedBatchSettlesSharedMakerOncePerLaneAndReclaimsEveryTerminal() throws Exception {
        verifySharedMakerBatch(true);
    }

    /**
     * 执行相同共享 maker 场景，直接检查业务资金和订单终态，不追加快照恢复；这里不切换线程执行模式。
     */
    @Test
    void directSharedMakerBatchConservesFundsWithoutSnapshot() throws Exception {
        verifySharedMakerBatch(false);
    }

    /**
     * 临时使用两个账户 Lane，确认四项下单实际走流水批次，检查双方资产、全部成交订单及预留退休，并按参数决定是否再做恢复比较，最后还原原 Lane 配置。
     * @param recovery 是否在同一业务场景结束后追加快照恢复比较
     */
    private void verifySharedMakerBatch(boolean recovery) throws Exception {
        // 进入共享 maker 场景前的全局账户 Lane 配置，finally 按原值恢复
        String priorLanes = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", "2");
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, "BTC", 4, 2);
            // 其他用户先挂入的被动订单输入或其批量消息
            var maker = linearOrder(82_000, "shared-maker", CoreOrderSide.SELL, 1_000, 4);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(maker))), 1002));
            // 按输入顺序构造的下单列表
            var orders = List.of(place(82_001, "shared-a", 1_000), place(82_002, "shared-b", 1_000),
                    place(82_003, "shared-c", 1_000), place(82_004, "shared-d", 1_000));
            // 本场景要执行的原 Core 命令
            var message = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
            assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(message.header().commandId());
            assertThat(state.pendingMatching().get(sequence).orderBatch.pipelined).isTrue();
            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = drainBatchAfterFirst(state, message, sequence);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 4)).isEqualTo(-1);
            // 完成阶段导出的业务状态，供余额、预留及订单断言读取
            var trading = state.tradingState();
            assertThat(trading.users().get(1001L).balances().get("BTC").availableUnits()).isEqualTo(4);
            assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(6_000);
            assertThat(trading.users().get(1002L).balances().get("USDT").availableUnits()).isEqualTo(4_000);
            for (long /* 当前需要核对资产或订单的参与账户用户编号 */ user : new long[]{1001, 1002}) {
                assertThat(trading.users().get(user).reservations()).isEmpty();
                assertThat(trading.users().get(user).balances().values()).allSatisfy(/* 当前要检查的资产可用与冻结余额 */ balance ->
                        assertThat(balance.lockedUnits()).isZero());
            }
            for (/* 当前正在检查是否已退休的订单编号 */ long orderId = 82_000; orderId <= 82_004; orderId++) assertThat(trading.orders().get(orderId)).isNull();
            if (recovery) try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(trading.businessStateHash());
            }
        } finally {
            if (priorLanes == null) System.clearProperty("surprising.aeron.account-lanes");
            else System.setProperty("surprising.aeron.account-lanes", priorLanes);
        }
    }

    /**
     * 同批先卖出成交再用所得报价币买入挂单，检查后项能使用前项收益且账面及冻结准确；参数只切换完成辅助入口，不切换流水模式。
     * @param asynchronous 是否使用异步完成辅助入口，否则手动排空；线程执行模式仍由扩展设为 PIPELINED
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void spotBatchPreservesEarlierFillProceedsForLaterItemAdmission(boolean asynchronous) throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, "BTC", 1, 1);
            applyBalance(state, 1002, 2_000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(83_000, "proceeds-maker", 1_000)))), 1002));
            // 本批先成交以取得报价资金的卖单
            var sell = linearOrder(83_001, "sell-first", CoreOrderSide.SELL, 1_000, 1);
            // 本批随后使用前项所得挂入的买单
            var buy = place(83_002, "buy-with-proceeds", 1_000);
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(sell, buy))));
            // 继续准入前记录的账户所有权交接代数，完成后核对没有临时借走 Lane
            long handoffBefore = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = asynchronous ? CoreTestCompletion.applyAsynchronously(state, batch)
                    : drainBatch(state, batch);
            if (asynchronous) assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState()))
                    .as("sequential admission must not borrow Lane ownership").isEqualTo(handoffBefore);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 2)).isEqualTo(-1);
            // 完成阶段导出的业务状态，供余额、预留及订单断言读取
            var trading = state.tradingState();
            assertThat(trading.orders().get(83_000L)).isNull();
            assertThat(trading.orders().get(83_001L)).isNull();
            assertThat(trading.orders().get(83_002L).remainingQuantitySteps()).isOne();
            assertThat(trading.users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(1_000);
            assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isZero();
            assertThat(trading.users().get(1002L).balances().get("USDT").availableUnits()).isEqualTo(1_000);
            assertThat(trading.users().get(1002L).balances().get("BTC").availableUnits()).isOne();
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(trading.businessStateHash());
            }
        }
    }

    /**
     * 真实现货成交后用既有故障钩子破坏最终 Lane 位图，检查已发生账户变化保留而提交水位不前进；故障前快照重放后得到同样资金和挂单。
     */
    @Test
    void spotPipelinedFatalAfterFillPreservesFundsAndRecoversBySnapshotReplay() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, "BTC", 1, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(84_000, "fatal-maker", CoreOrderSide.SELL, 1_000, 1)))), 1002));
            // 故障前保存的快照字节，恢复后重放原消息而不重复使用故障状态
            byte[] recovery = state.snapshot();
            // 故障注入前的已提交 Core 水位
            long committedBefore = state.committedCoreSequence();
            // 本场景要执行的原 Core 命令
            var message = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(84_001, "fatal-taker", 1_000),
                            linearOrder(84_002, "fatal-resting", CoreOrderSide.BUY, 900, 1)))));
            assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(message.header().commandId());
            // 当前取得的同一 Matcher 事实，可能尚未完成账户结算
            var matching = awaitMatching(state, sequence);
            CoreFaults.afterSettlement(state, () -> {
            });
            // 本轮等待捕获的致命分歧异常，故障后仍须返回同一实例
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> completeEventually(state, sequence, matching, 2_000, 4));
            assertThat(divergence).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 当前运行时的资产、品种、客户号及持仓身份字典
            RuntimeIdentityRegistry identities = field(state, "identities");
            // 字典解析出的 USDT 资产编号
            int quote = identities.assetId("USDT");
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.balance(1001, quote).availableUnits())).isEqualTo(8_100);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.balance(1001, quote).lockedUnits())).isEqualTo(900);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1002, () -> runtime.balance(1002, quote).availableUnits())).isEqualTo(1_000);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(84_000))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(84_001))).isNull();
            assertThatThrownBy(() -> state.apply(message)).isSameAs(divergence);
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, recovery)) {
                assertThat(TradingOrderBatchCodec.firstNonAppliedItem(drainBatch(restored, message), 2))
                        .isEqualTo(-1);
                // 完成阶段导出的业务状态，供余额、预留及订单断言读取
                var trading = restored.tradingState();
                assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(8_100);
                assertThat(trading.users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(900);
                assertThat(trading.users().get(1002L).balances().get("USDT").availableUnits()).isEqualTo(1_000);
                assertThat(trading.users().get(1001L).balances().get("BTC").availableUnits()).isOne();
            }
        }
    }

    /**
     * 重复三十轮交替提交单笔及两项批次，再有界排空和撤单；检查每轮原发布上下文能释放、队列排空、预留清理且资金全部恢复。
     */
    @Test
    void alternatingSingleOrdersAndBatchesReleaseContextsBeforeTheNextBatchResumes() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 1_000_000);
            for (/* 交替单笔和批量场景的当前轮次 */ int cycle = 0; cycle < 30; cycle++) {
                // 当前轮测试订单编号起点，用相邻编号避免跨轮冲突
                long id = 190_000 + cycle * 3L;
                // 排在批次前后或单独检查路由的普通消息
                var single = command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2 + cycle * 3L,
                        TradingCommandCodec.encodePlaceOrder(place(id, "context-" + id, 1_000)));
                // 当前场景要推进的原批量消息
                var batch = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3 + cycle * 3L,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                place(id + 1, "context-" + (id + 1), 1_000),
                                place(id + 2, "context-" + (id + 2), 1_000)))));
                state.apply(single);
                state.apply(batch);
                // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (state.pendingMatching().size() != 0 && System.nanoTime() < deadline)
                    state.commits.commitReadyMatching(256, 2_000, 6, false, (/* 当前待完成命令的完整 Core 序号 */ sequence, /* 当前已收到或由完成回调交出的 Core 响应 */ response) -> {});
                assertThat(state.pendingMatching().size()).isZero();
                drainBatch(state, command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 4 + cycle * 3L,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                                new CancelOrderCommand(id), new CancelOrderCommand(id + 1), new CancelOrderCommand(id + 2))))));
                assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
                assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(1_000_000);
            }
        }
    }

    /**
     * 交错登记批次、延后普通单和全部拒绝的撤单批次，收集终态序号必须与原输入完全一致；检查延后计数及两种队列排空、正确挂单和恢复哈希。
     */
    @Test
    void deferredCommandsAndRejectedBatchAdvanceBySequenceAcrossBothQueueHeads() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 按原输入先后排列的不同批次及普通命令列表
            var commands = List.of(
                    command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                    place(82_001, "head-first-a", 1_000), place(82_002, "head-first-b", 1_000))))),
                    command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                            TradingCommandCodec.encodePlaceOrder(place(82_003, "head-deferred-a", 1_000))),
                    command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 4,
                            TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                                    new CancelOrderCommand(99_901), new CancelOrderCommand(99_902))))),
                    command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 5,
                            TradingCommandCodec.encodePlaceOrder(place(82_004, "head-deferred-b", 1_000))),
                    command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 6,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                    place(82_005, "head-last", 1_000))))));
            // 命令登记时记录的 Core 序号顺序，终态须完全一致
            var expected = new ArrayList<Long>();
            for (var /* 本场景要执行的原 Core 命令 */ message : commands) {
                assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                expected.add(state.matchingSequence(message.header().commandId()));
            }
            assertThat(state.pendingMatching().deferredCount()).isEqualTo(2);
            // 有序提交回调实际收集到的终态 Core 序号列表
            var completed = new ArrayList<Long>();
            // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (state.pendingMatching().size() != 0 && System.nanoTime() < deadline) {
                state.commits.commitReadyMatching(256, 2_000, 6, false,
                        (/* 当前待完成命令的完整 Core 序号 */ sequence, /* 当前已收到或由完成回调交出的 Core 响应 */ response) -> completed.add(sequence));
            }
            assertThat(completed).containsExactlyElementsOf(expected);
            assertThat(state.pendingMatching().deferredCount()).isZero();
            assertThat(state.batches().hasPendingBatches()).isFalse();
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(TradingOrderBatchCodec.decodeResult(
                    state.commandResults().get(commands.get(2).header().commandId()).responseData()).items())
                    .allSatisfy(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            for (/* 当前轮测试订单编号起点，用相邻编号避免跨轮冲突 */ long id = 82_001; id <= 82_005; id++) assertThat(state.tradingState().orders().get(id)).isNotNull();
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 两个成功下单批次之间夹全部拒绝的撤单批次，检查该批仍按中间序号发布单项拒绝；再调整余额和查询，确认后续命令及恢复状态正常。
     */
    @Test
    void deferredAllRejectedBatchStillPublishesItsTerminalBetweenAdjacentBatches() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 排在全拒绝撤单批次之前的成功下单消息
            CoreMessage first = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(81_001, "ordered-first-a", 1_000),
                            place(81_002, "ordered-first-b", 1_000)))));
            // 位于两个成功批次之间、全部项都找不到原单的撤单消息
            CoreMessage rejected = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(99_991), new CancelOrderCommand(99_992)))));
            // 在其他批次或普通单之后登记的最后一条命令
            CoreMessage last = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(81_003, "ordered-last-a", 1_000),
                            place(81_004, "ordered-last-b", 1_000)))));
            // 命令登记时记录的 Core 序号顺序，终态须完全一致
            var expected = new ArrayList<Long>();
            for (CoreMessage /* 本场景要执行的原 Core 命令 */ message : List.of(first, rejected, last)) {
                assertThat(state.apply(message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                expected.add(state.matchingSequence(message.header().commandId()));
            }
            // 提交回调实际返回的序号顺序
            var actual = new ArrayList<Long>();
            // 与实际序号同顺序保存的终态响应列表
            var responses = new ArrayList<CoreResponse>();
            // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (state.pendingMatching().size() != 0 && System.nanoTime() < deadline) {
                state.commits.commitReadyMatching(256, 2_000, 4, false, (/* 当前待完成命令的完整 Core 序号 */ sequence, /* 当前已收到或由完成回调交出的 Core 响应 */ response) -> {
                    actual.add(sequence);
                    responses.add(response);
                });
            }
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(TradingOrderBatchCodec.decodeResult(responses.get(1).data()).items())
                    .allSatisfy(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> {
                        assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED);
                        assertThat(item.resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
                    });
            applyBalance(state, 1001, 7, 5);
            // 后续用于确认读取仍正常的用户状态哈希查询
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_HASH_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 2_000, 5),
                    new byte[0]);
            assertThat(CoreTestCompletion.querySynchronously(state, query).status()).isEqualTo(ResponseStatus.OK);
            // 当前要检查的资产可用与冻结余额
            var balance = state.tradingState().users().get(1001L).balances().get("USDT");
            assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(100_007);
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 在撤单输入中插入重复和缺失订单，检查各项状态及下标仍按原顺序，实际只提交两个连续分片片段；最终余额回到下单前、无预留持仓残留且快照恢复一致。
     */
    @Test
    void coalescesSameMatcherCancellationsAndPreservesRejectedItemOrder() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 放入四张订单之前保存的账户余额，用于检查最后全部解冻后恢复
            var before = state.tradingState().users().get(1001L).balances();
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < 4; index++) {
                drainBatch(state, command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2 + index,
                        TradingCommandCodec.encodePlaceOrder(place(18_001 + index, "chunk-" + index, 1_000))));
            }
            // 当前 Core 使用的 Matcher 分片管线组
            MatcherPipelineGroup group = field(state, "matcherPipeline");
            // 反射读取管线组内部 shards 数组的字段
            var shardsField = MatcherPipelineGroup.class.getDeclaredField("shards");
            shardsField.setAccessible(true);
            // 实际 Matcher 分片工作队列数组
            var shards = (MatcherCommandPipeline[]) shardsField.get(group);
            // 撤单批次前分片零的提交位置，用差值检查合并片段数
            long submittedBefore = field(field(shards[0], "submittedPosition"), "value");
            // 含自有、重复或缺失订单的待测撤单命令
            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 6,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(18_001), new CancelOrderCommand(18_001),
                            new CancelOrderCommand(18_002),
                            new CancelOrderCommand(99_999),
                            new CancelOrderCommand(18_003), new CancelOrderCommand(18_004)))));
            assertThat(state.apply(cancel).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 回调捕获指定 Core 终态的单元素槽，等待循环读同一槽
            final CoreResponse[] terminal = {null};
            // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (terminal[0] == null && System.nanoTime() < deadline) {
                state.commits.commitReadyMatching(256, 2_000, 6, false, (/* 当前待完成命令的完整 Core 序号 */ sequence, /* 当前已收到或由完成回调交出的 Core 响应 */ response) -> terminal[0] = response);
            }
            assertThat(terminal[0]).isNotNull();
            // 按原输入下标排列的批量单项结果列表
            var items = TradingOrderBatchCodec.decodeResult(terminal[0].data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status).containsExactly(
                    ResponseStatus.APPLIED, ResponseStatus.REJECTED, ResponseStatus.APPLIED, ResponseStatus.REJECTED,
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED);
            assertThat(items.get(1).resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(items.get(3).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(items).extracting(CoreOrderBatchResult.Item::index).containsExactly(0, 1, 2, 3, 4, 5);
            long submittedAfter = field(field(shards[0], "submittedPosition"), "value");
            assertThat(submittedAfter - submittedBefore).isEqualTo(2);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(state.tradingState().users().get(1001L).balances()).isEqualTo(before);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).positions()).isEmpty();
            // 当前成功业务状态的恢复快照字节
            byte[] snapshot = state.snapshot();
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, snapshot)) {
                assertThat(restored.tradingState().users().get(1001L).balances()).isEqualTo(before);
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 八张活动单之间插入一个缺失订单，检查异步批次能越过该项继续后缀撤单，按原位保存拒绝，随后普通下单仍能完成。
     */
    @Test
    void asynchronousCancelBatchSubmitsLaterChunkAfterMissingOrder() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < 8; index++) {
                drainBatch(state, command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 2 + index,
                        TradingCommandCodec.encodePlaceOrder(
                                place(19_001 + index, "async-chunk-" + index, 1_000))));
            }
            // 按原位置插入缺失订单的撤单输入列表
            List<CancelOrderCommand> cancellations = new ArrayList<>();
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < 6; index++)
                cancellations.add(new CancelOrderCommand(19_001 + index));
            cancellations.add(new CancelOrderCommand(99_999));
            cancellations.add(new CancelOrderCommand(19_007));
            cancellations.add(new CancelOrderCommand(19_008));
            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = CoreTestCompletion.applyAsynchronously(state,
                    command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 10,
                            TradingOrderBatchCodec.encodeCancelOrderBatch(
                                    new CancelOrderBatchCommand(cancellations))));
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 按原输入下标排列的批量单项结果列表
            var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status).containsExactly(
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                    ResponseStatus.REJECTED, ResponseStatus.APPLIED, ResponseStatus.APPLIED);
            assertThat(items.get(6).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(CoreTestCompletion.applyAsynchronously(state,
                    command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 11,
                            TradingCommandCodec.encodePlaceOrder(place(19_009, "after-async-chunk", 1_000))))
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits())
                    .isEqualTo(99_000);
        }
    }

    /**
     * 连续提交同账户批次，分轮完成前批两项；检查后批没有提前给出完成引用，前后终态、水位、幂等字节及结果订单都按原批次归属。
     */
    @Test
    void isolatesOverlappingBatchesUntilTheActiveBatchCompletes() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 前置活动批次的原命令 UUID
            UUID batchId = UUID.randomUUID();
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, batchId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_001, "batch-9001", 1_000),
                            place(9_002, "batch-9002", 1_000)))));
            // 排在前置批次之后的命令 UUID
            UUID laterId = UUID.randomUUID();
            // 需要等前置批次完成的后续批次或普通消息
            CoreMessage later = command(CoreMessageType.PLACE_ORDER_BATCH, laterId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_003, "later-9003", 1_000)))));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.apply(later).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 前置活动批次对应的 Core 序号
            long batchSequence = state.matchingSequence(batchId);
            // 第一条后续命令对应的 Core 序号
            long laterSequence = state.matchingSequence(laterId);
            // 当前批次先取得的 Matcher 事实
            var first = awaitMatching(state, batchSequence);
            assertThat(state.commits.completeMatching(batchSequence, first, 2_000, 3)).isNull();
            // 同一前置批次推进到下一项后取得的 Matcher 事实
            var second = awaitMatching(state, batchSequence);

            assertThat(state.pendingMatching().required(laterSequence).hasMatchingCompletion()).isFalse();

            // 前置批次完成时的终态响应
            CoreResponse batchResponse = completeEventually(state, batchSequence, second, 2_001, 4);
            assertThat(batchResponse).isNotNull();
            // 后续批次取得的 Matcher 事实
            var laterResult = awaitMatching(state, laterSequence);
            // 第一条后续命令的终态响应
            CoreResponse laterResponse = completeEventually(state, laterSequence, laterResult, 2_002, 5);
            assertThat(laterResponse).isNotNull();
            assertThat(batchResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(laterResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(batchResponse.appliedCommandCount()).isLessThan(laterResponse.appliedCommandCount());
            assertThat(state.commandResults().get(batchId).responseData()).containsExactly(batchResponse.data());
            assertThat(state.commandResults().get(laterId).responseData()).containsExactly(laterResponse.data());
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(TradingOrderBatchCodec.decodeResult(batchResponse.data()).items())
                    .extracting(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> item.order().orderId())
                    .containsExactly(9_001L, 9_002L);
            assertThat(TradingOrderBatchCodec.decodeResult(laterResponse.data()).items())
                    .extracting(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> item.order().orderId())
                    .containsExactly(9_003L);
        }
    }

    /**
     * 活动批次后再排两个普通单，检查在途重复只回原序号、过期来源被拒，未退休项没有终态缓存；按批次及两个普通单顺序完成后核对结果和订单。
     */
    @Test
    void defersSinglePlaceCompletionUntilTheActiveBatchCompletes() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 前置活动批次的原命令 UUID
            UUID batchId = UUID.randomUUID();
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, batchId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(9_101, "batch-9101", 1_000)))));
            // 排在前置批次之后的命令 UUID
            UUID laterId = UUID.randomUUID();
            // 需要等前置批次完成的后续批次或普通消息
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, laterId, 3,
                    TradingCommandCodec.encodePlaceOrder(place(9_102, "later-9102", 1_000)));
            // 第二条延后普通命令的原 UUID
            UUID lastId = UUID.randomUUID();
            // 在其他批次或普通单之后登记的最后一条命令
            CoreMessage last = command(CoreMessageType.PLACE_ORDER, lastId, 4,
                    TradingCommandCodec.encodePlaceOrder(place(9_103, "last-9103", 1_000)));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 第一条普通单登记后的待处理响应
            CoreResponse deferred = state.apply(later);
            // 第二条普通单登记后的待处理响应，当前用例没有再读取这个局部值
            CoreResponse lastDeferred = state.apply(last);
            // 用相同原命令再次请求得到的重复响应
            CoreResponse duplicate = state.apply(later);
            assertThat(state.apply(command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 4,
                    TradingCommandCodec.encodePlaceOrder(place(9_104, "stale-9104", 1_000)))).resultCode())
                    .isEqualTo(CoreResultCode.STALE_SOURCE_SEQUENCE);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.appliedCommandCount()).isEqualTo(deferred.appliedCommandCount());
            assertThat(state.pendingMatching().snapshot()).containsKeys(
                    state.matchingSequence(laterId), state.matchingSequence(lastId));
            assertThat(state.commandResults()).doesNotContainKey(laterId);
            assertThat(state.commandResults()).doesNotContainKey(lastId);

            // 前置活动批次对应的 Core 序号
            long batchSequence = state.matchingSequence(batchId);
            // 前置活动批次已有的 Matcher 事实
            var batchMatching = awaitMatching(state, batchSequence);
            // 前置批次完成时的终态响应
            CoreResponse batchResponse = completeEventually(state, batchSequence, batchMatching, 2_000, 4);
            assertThat(batchResponse).isNotNull();
            assertThat(state.pendingMatching().snapshot()).containsKeys(
                    state.matchingSequence(laterId), state.matchingSequence(lastId));
            assertThat(batchResponse.appliedCommandCount()).isEqualTo(batchSequence);
            assertThat(state.commandResults().get(batchId).responseData()).containsExactly(batchResponse.data());
            assertThat(state.commandResults()).doesNotContainKey(laterId);
            assertThat(state.commandResults()).doesNotContainKey(lastId);

            // 第一条后续命令对应的 Core 序号
            long laterSequence = state.matchingSequence(laterId);
            // 第一条后续普通单已有的 Matcher 事实
            var laterMatching = awaitMatching(state, laterSequence);
            // 第一条后续命令的终态响应
            CoreResponse laterResponse = completeEventually(state, laterSequence, laterMatching, 2_002, 6);
            assertThat(laterResponse).isNotNull();
            assertThat(laterResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            // 最后一条延后普通单的 Core 序号
            long lastSequence = state.matchingSequence(lastId);
            // 最后一条普通单已有的 Matcher 事实
            var lastMatching = awaitMatching(state, lastSequence);
            // 最后一条普通单的终态响应
            CoreResponse lastResponse = completeEventually(state, lastSequence, lastMatching, 2_003, 7);
            assertThat(lastResponse).isNotNull();
            assertThat(lastResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(batchResponse.appliedCommandCount()).isLessThan(laterResponse.appliedCommandCount());
            assertThat(laterResponse.appliedCommandCount()).isLessThan(lastResponse.appliedCommandCount());
            assertThat(state.commandResults().get(laterId).responseData()).containsExactly(laterResponse.data());
            assertThat(state.commandResults().get(lastId).responseData()).containsExactly(lastResponse.data());
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrder(9_101L, 9_102L, 9_103L);
        }
    }

    /**
     * 按协议允许的最大项数下单，检查项下标、订单集合及终态缓存；同指纹重放返回原结果，同 UUID 不同载荷报幂等冲突且业务状态不变。
     */
    @Test
    void processesMaximumBatchesInInputOrder() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, Math.multiplyExact(PlaceOrderBatchCommand.MAX_ORDERS, 1_000L));
            // 最大项数原批次 UUID，重复与冲突请求继续使用同一值
            UUID commandId = UUID.randomUUID();
            // 按输入顺序构造的下单列表
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < PlaceOrderBatchCommand.MAX_ORDERS; index++) {
                orders.add(place(10_000 + index, "batch-place-" + index, 1_000));
            }
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));

            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = drainBatch(state, batch);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            // 整批响应解码得到的逐项结果
            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(response.data());
            assertThat(result.items()).hasSize(PlaceOrderBatchCommand.MAX_ORDERS)
                    .extracting(CoreOrderBatchResult.Item::index)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, orders.size())
                            .boxed().toList());
            assertThat(result.items()).allMatch(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> item.status() == ResponseStatus.APPLIED);
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrderElementsOf(orders.stream()
                            .map(PlaceOrderCommand::orderId).toList());
            assertThat(state.commandResults().get(commandId).responseData()).containsExactly(response.data());
            assertThat(state.commandResults().get(commandId).appliedCommandCount())
                    .isEqualTo(response.appliedCommandCount());

            // 用原消息重放返回的重复终态响应
            CoreResponse replay = state.apply(batch);
            assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(replay.data()).containsExactly(response.data());
            // 批次完成后导出的业务状态，幂等冲突不能改变它
            var stateAfterBatch = state.tradingState();
            // 复用原 UUID 但更换载荷得到的幂等冲突响应
            CoreResponse conflict = state.apply(command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(99_999, "changed-batch", 1_000))))));
            assertThat(conflict.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(conflict.resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(state.tradingState()).isEqualTo(stateAfterBatch);
            assertThat(state.tradingState().orders().keySet())
                    .containsExactlyInAnyOrderElementsOf(orders.stream()
                            .map(PlaceOrderCommand::orderId).toList());
        }
    }

    /**
     * 一项吃掉其他用户挂单、一项继续挂单，检查成交明细、活跃单与终态身份、双方资产及报价币加财库余额守恒；重复和恢复重放不能再变资金。费用和预留参数由下方辅助方法的实际实现决定。
     */
    @Test
    void conservesFundsWhenABatchMatchesAnotherUser() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 2_000_000);
            applyBalance(state, 1002, "BTC", 2_000, 2);
            // 本批先成交以取得报价资金的卖单
            PlaceOrderCommand sell = spotOrder(10_501, "batch-maker-sell", CoreOrderSide.SELL,
                    1_000, 1_000, "BTC", 1_000, 200, 500);
            // 被动方先挂入订单的批量消息
            CoreMessage makerBatch = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(sell))), 1002);
            assertThat(drainBatch(state, makerBatch).status()).isEqualTo(ResponseStatus.APPLIED);

            // 会立即吃掉已有卖单的买单
            PlaceOrderCommand crossingBuy = spotOrder(10_502, "batch-crossing-buy", CoreOrderSide.BUY,
                    1_000, 1_000, "USDT", 1_000_500, 200, 500);
            // 当前价格不会成交而继续挂在订单簿的买单
            PlaceOrderCommand restingBuy = spotOrder(10_503, "batch-resting-buy", CoreOrderSide.BUY,
                    900, 100, "USDT", 90_045, 200, 500);
            // 本次主动方两项批次的原 UUID
            UUID takerBatchId = UUID.randomUUID();
            // 一项成交一项挂单的主动方批量消息
            CoreMessage takerBatch = command(CoreMessageType.PLACE_ORDER_BATCH, takerBatchId, 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(crossingBuy, restingBuy))), 1001);

            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = drainBatch(state, takerBatch);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            // 按原输入下标排列的批量单项结果列表
            var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
            assertThat(items).allSatisfy(/* 当前批量结果的一项，读取其状态、结果码、订单或成交明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(items.getFirst().order()).isNull();
            assertThat(items.get(1).order().status()).isEqualTo("OPEN");
            assertThat(state.terminalRetention().containsOrder(10_502, 1001, "batch-crossing-buy")).isTrue();
            assertThat(state.terminalRetention().containsOrder(10_501, 1002, "batch-maker-sell")).isTrue();
            assertThat(items.getFirst().executions()).hasSize(1);
            assertThat(items.get(1).executions()).isEmpty();
            assertThat(state.tradingState().orders().keySet()).containsExactly(10_503L);
            assertThat(CoreStateTestFixtures.totalUnits(state.tradingState().users().get(1001L), "BTC")).isEqualTo(1_000);
            assertThat(CoreStateTestFixtures.totalUnits(state.tradingState().users().get(1002L), "BTC")).isEqualTo(1_000);
            assertThat(CoreStateTestFixtures.totalUnits(state.tradingState().users().get(1001L), "USDT")
                    + CoreStateTestFixtures.totalUnits(state.tradingState().users().get(1002L), "USDT")
                    + state.tradingState().treasuryState().feeBalances().getOrDefault("USDT", 0L))
                    .isEqualTo(2_000_000);
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(90_000);
            assertThat(state.tradingState().users().get(1001L).reservations()).containsOnlyKeys(10_503L);
            assertThat(state.tradingState().users().get(1002L).reservations()).isEmpty();
            // 成功批次后的业务状态哈希，重复和恢复均须保持
            long businessHash = state.tradingState().businessStateHash();
            // 用相同原命令再次请求得到的重复响应
            CoreResponse duplicate = drainBatch(state, takerBatch);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.data()).containsExactly(response.data());
            assertThat(state.tradingState().businessStateHash()).isEqualTo(businessHash);
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(businessHash);
                assertThat(restored.tradingState().orders().keySet()).containsExactly(10_503L);
                assertThat(restored.terminalRetention().containsOrder(10_502, 1001, "batch-crossing-buy")).isTrue();
                assertThat(restored.terminalRetention().containsOrder(10_501, 1002, "batch-maker-sell")).isTrue();
                // 用原消息重放返回的重复终态响应
                CoreResponse replay = drainBatch(restored, takerBatch);
                assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(replay.data()).containsExactly(response.data());
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(businessHash);
            }
        }
    }

    /**
     * 注册带名义价值档位的 U 本位永续品种，同批第二项必须看见首项占用并因超限拒绝；检查可用加冻结金额守恒，只有第一项保留预留。
     */
    @Test
    void perpetualBatchAdmissionIncludesEarlierItemsAndConservesReservedFunds() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 本测试注册的现货或 U 本位永续品种配置
            RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                    "1", "BTC", "USDT", "USDT", 1, 1, 1,
                    new CoreInstrumentRiskLimits(
                            100_000, 10_000_000, 1_500, 0, 1_500,
                            List.of(new CoreRiskLimitBracket(1_500,
                            10_000_000, 100_000, 50_000))),
                    0, 0, 0, -1, 0);
            assertThat(state.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.REGISTER_INSTRUMENT,
                    UUID.randomUUID(), 1, TradingCommandCodec.encodeRegisterInstrument(instrument, ProductLine.LINEAR_PERPETUAL))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), 2, TradingCommandCodec.encodeApplyMarkPrice(
                            new ApplyMarkPriceCommand("1", 1_000, 1, 1_000), ProductLine.LINEAR_PERPETUAL))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            // 初始化永续账户报价币余额的原消息
            var adjust = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), 3, TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", 1_000_000)));
            // 余额控制命令完成响应，未齐时继续轮询原槽
            var adjustResponse = state.apply(adjust);
            while (adjustResponse == null && state.directCommand.active()) {
                adjustResponse = state.directCommand.poll();
                if (adjustResponse == null) Thread.onSpinWait();
            }
            assertThat(adjustResponse.status()).isEqualTo(ResponseStatus.APPLIED);
            // 按当前名义价值限额可成功准入的第一张永续订单
            PlaceOrderCommand first = new PlaceOrderCommand(10_601, "1",
                    CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-first");
            // 第二项新增委托，合并首项占用后应超过品种限额
            PlaceOrderCommand exceedsBatchLimit = new PlaceOrderCommand(10_602, "1",
                    CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-second");
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(first, exceedsBatchLimit))));

            // 整批响应解码得到的逐项结果
            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(drainBatch(state, batch).data());

            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(result.items().get(1).resultCode())
                    .isEqualTo(CoreResultCode.POSITION_NOTIONAL_LIMIT_EXCEEDED);
            // 当前要检查的资产可用与冻结余额
            var balance = state.tradingState().users().get(1001L).balances().get("USDT");
            assertThat(Math.addExact(balance.availableUnits(), balance.lockedUnits())).isEqualTo(1_000_000);
            assertThat(state.tradingState().users().get(1001L).reservations()).containsOnlyKeys(10_601L);
        }
    }

    /**
     * 同批两项使用同一客户单号，整批准入失败后回到逐项路径；检查首项成功、次项重复客户号拒绝、仅首项冻结且恢复哈希一致。
     */
    @Test
    void laneBatchRejectionRollsBackProvisionalClientAndFundsBeforeOrderedItemsResume() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyLinearPerpetualInstrument(state);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1001, 1_000_000, 1);
            // 当前场景要推进的原批量消息
            var batch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 2, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(
                            List.of(linearOrder(19_001, "same-client", CoreOrderSide.BUY, 500, 1),
                                    linearOrder(19_002, "same-client", CoreOrderSide.BUY, 500, 1)))), 1001);
            // 整批响应解码得到的逐项结果
            var result = TradingOrderBatchCodec.decodeResult(drainBatch(state, batch).data());
            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(result.items().get(1).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
            // 完成后导出的请求用户状态，检查其预留和总资金
            var user = state.tradingState().users().get(1001L);
            assertThat(user.reservations()).containsOnlyKeys(19_001L);
            assertThat(CoreStateTestFixtures.totalUnits(user.balances().get("USDT"))).isEqualTo(1_000_000);
            assertThat(state.pendingMatching().size()).isZero();
            // 当前成功业务状态的恢复快照字节
            byte[] snapshot = state.snapshot();
            try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 先检查普通重复订单号只拒绝该项而保留前后成功项，再在另一批已准入并交出第二项后注入致命证据；检查故障持续、前项事实和预留保留，终态及提交水位没有错误前进。
     */
    @Test
    void keepsPriorItemsButFailsStickyAfterMatcherDivergence() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 包含成功首项、重复订单号中项及成功末项的下单批次输入
            PlaceOrderBatchCommand command = new PlaceOrderBatchCommand(List.of(
                    place(11_001, "batch-first", 1_000),
                    place(11_001, "batch-duplicate", 1_000),
                    place(11_003, "batch-third", 1_000)));
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(command));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(commandId);
            // 当前顺序批次第一项已经取得的 Matcher 事实
            var firstResult = awaitMatching(state, sequence);
            // 第一次尝试推进首项时的可空整批响应，仍有后项时应为空
            CoreResponse firstCompletion = state.commits.completeMatching(sequence, firstResult, 2_000, 3);
            assertThat(firstCompletion).isNull();
            // 本轮已完成响应、序号列表或最后取得的终态，具体角色见所在方法
            CoreResponse completed = drainBatchAfterFirst(state, batch, sequence);
            // 整批响应解码得到的逐项结果
            CoreOrderBatchResult result = TradingOrderBatchCodec.decodeResult(completed.data());
            assertThat(result.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED, ResponseStatus.APPLIED);
            assertThat(result.items().get(1).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
            assertThat(state.tradingState().orders().get(11_001L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().orders().get(11_003L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);

            // 随后要注入致命事实的原批次 UUID
            UUID fatalId = UUID.randomUUID();
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 当前运行时的资产、品种、客户号及持仓身份字典
            RuntimeIdentityRegistry identities = field(state, "identities");
            // USDT 资产编号，供账户合法读取及预留资产断言
            int quoteAssetId = identities.assetId("USDT");
            // 进入故障批次前复制的各逻辑分片已应用序号
            long[] matcherBeforeFatal = ((long[]) field(state.commits, "appliedMatcherSequences")).clone();
            // 故障批次前的已提交 Core 水位
            long committedBeforeFatal = state.committedCoreSequence();
            // 后项重复订单号会选择逐项准入和部分成功路径。
            // 流水整批故障另有用例，它没有第二次逐项 Matcher 回调。
            // 已准备在后续完成阶段注入故障的批量消息
            CoreMessage fatalBatch = command(CoreMessageType.PLACE_ORDER_BATCH, fatalId, 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(11_004, "fatal-fourth", 1_000),
                            place(11_005, "fatal-fifth", 1_000),
                            place(11_004, "fatal-duplicate", 1_000)))));
            assertThat(state.apply(fatalBatch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 故障批次登记后共用的 Core 序号
            long fatalSequence = state.matchingSequence(fatalId);
            // 故障批次第一项已经成功撮合的事实
            var acceptedFirst = awaitMatching(state, fatalSequence);
            // 当前命令原槽持有的批次上下文，等待首项游标实际前进
            OrderBatchPending activeBatch = state.batches().batch(fatalSequence);
            // 首项账户和提交推进的有界等待截止点
            long firstDeadline = System.nanoTime() + 5_000_000_000L;
            while (activeBatch.nextIndex == 0) {
                assertThat(System.nanoTime()).isLessThan(firstDeadline);
                state.commits.advanceMatchingProgress(fatalSequence);
                assertThat(state.commits.completeMatching(fatalSequence, acceptedFirst, 2_900, 4)).isNull();
                Thread.onSpinWait();
            }
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(11_004))).isNotNull();
            // 等第二项已经准入并交给 Matcher 后再注入分歧。
            // 过早标记故障可能挡住该项预留，使后面的断言失去场景前提。
            assertThat(awaitMatching(state, fatalSequence).accepted()).isTrue();
            // 故障批次仍占用的原命令槽，用于检查没有残留可再次消费的完成引用
            CommandSlot claimedContext = state.pendingMatching().required(fatalSequence);
            // 故障批次首项推进后复制的分片水位，后续故障不能错误覆盖
            long[] matcherAfterFirst = ((long[]) field(state.commits, "appliedMatcherSequences")).clone();
            assertThat(matcherAfterFirst).isNotEqualTo(matcherBeforeFatal);
            // 测试构造的 EXCHANGE_CORE_FAILURE 结果，用于持续故障断言
            var fatal = new com.surprising.aeron.service.matcher.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");
            // 本轮等待捕获的致命分歧异常，故障后仍须返回同一实例
            Throwable divergence = null;
            // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (divergence == null) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                state.commits.advanceMatchingProgress(fatalSequence);
                divergence = org.assertj.core.api.Assertions.catchThrowable(
                        () -> state.commits.completeMatching(fatalSequence, fatal, 3_000, 5));
                Thread.onSpinWait();
            }
            assertThat(divergence).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 4)))
                    .isSameAs(divergence);
            assertThat(CoreTestCompletion.takeMatchingResult(state, fatalSequence)).isNull();
            assertThat(claimedContext.hasMatchingCompletion()).isFalse();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(11_004))).isNotNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_004))).isNotNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_005))).isNotNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_004).assetId())).isEqualTo(quoteAssetId);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_005).assetId())).isEqualTo(quoteAssetId);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_004).totalReservedUnits())).isEqualTo(1_000L);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(11_005).totalReservedUnits())).isEqualTo(1_000L);
            assertThat(state.commandResults()).doesNotContainKey(fatalId);
            assertThat(state.pendingMatching().get(fatalSequence)).isNotNull();
            assertThat(state.pendingMatching().size()).isOne();
            assertThat(state.matchingSequence(fatalId)).isEqualTo(fatalSequence);
            assertThat(state.snapshotHasPendingCommands()).isTrue();
            assertThat((long[]) field(state.commits, "appliedMatcherSequences")).containsExactly(matcherAfterFirst);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBeforeFatal);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> identities.findClientKeyValue(1001, "fatal-fourth"))).isNotZero();
        }
    }

    /**
     * 只为测试反射读取目标类直接声明的字段，允许访问私有值并按调用方类型转换；反射及转换错误向测试传播。
     * @param <T> 调用方期望的字段返回类型，读取后作未检查转换
     * @param target 要反射读取字段或调用方法的目标对象
     * @param name 目标类直接声明的字段名或方法名
     */
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        // 目标类直接声明的反射字段，设置可访问后读取
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    /**
     * 按用途收集财库各类余额、结算和游标查询结果，便于测试观察；只整理返回值，不是持久化恢复快照。
     * @param runtime 要观察财库返回值的账户运行时
     */
    private static Map<String, Object> treasurySnapshot(TradingAccountRuntime runtime) {
        // 当前运行时财库查询入口
        var treasury = runtime.treasury();
        return Map.ofEntries(
                Map.entry("fees", treasury.feeBalances()),
                Map.entry("insurance", treasury.insuranceBalances()),
                Map.entry("deficits", treasury.insuranceDeficits()),
                Map.entry("liquidationFees", treasury.liquidationFeeBalances()),
                Map.entry("fundingResiduals", treasury.fundingResidualBalances()),
                Map.entry("roundingResiduals", treasury.roundingResidualBalances()),
                Map.entry("clearingPnl", treasury.clearingPnlBalances()),
                Map.entry("fundingSettlements", treasury.fundingSettlements()),
                Map.entry("fundingProgress", treasury.fundingProgresses()),
                Map.entry("lifecycleSettlements", treasury.lifecycleSettlements()),
                Map.entry("lifecycleProgress", treasury.lifecycleProgresses()));
    }

    /**
     * 按方法名及精确参数类型反射调用目标类的声明方法，允许访问私有方法；不吞反射或被调用方法异常。
     * @param target 要反射读取字段或调用方法的目标对象
     * @param name 目标类直接声明的字段名或方法名
     * @param parameterTypes 要调用方法的精确参数类型数组
     * @param arguments 传给反射方法的各个实参
     */
    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        // 按精确参数列表找到并允许访问的反射方法
        var method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    /**
     * 按业务名称收集持仓用户、OI、触发、算法、强平、定时撤单、活动订单及 ADL 索引的浅层观察值。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     */
    private static Map<String, Map<String, Object>> allIndexSnapshots(TradingCoreRuntime state) throws Exception {
        return Map.ofEntries(
                Map.entry("position-user", indexSnapshot(field(state, "positionUserIndex"))),
                Map.entry("open-interest", indexSnapshot(field(state, "openInterestIndex"))),
                Map.entry("trigger", indexSnapshot(field(state, "triggerOrderIndex"))),
                Map.entry("algo", indexSnapshot(field(state, "algoOrderIndex"))),
                Map.entry("liquidation", indexSnapshot(field(state, "liquidationIndex"))),
                Map.entry("timer", indexSnapshot(field(state, "cancelAllAfterIndex"))),
                Map.entry("active-order", indexSnapshot(field(state, "activeOrderIndex"))),
                Map.entry("adl-position", indexSnapshot(field(state, "adlPositionIndex"))));
    }

    /**
     * 反射读取索引实例字段，跳过静态字段、身份字典及分页暂存；Map、Set、List 浅复制，其他对象沿用引用，不能当作任意深拷贝或恢复快照。
     * @param index 要作浅层字段观察的派生索引对象
     */
    private static Map<String, Object> indexSnapshot(Object index) throws Exception {
        // 当前成功业务状态的恢复快照字节
        java.util.TreeMap<String, Object> snapshot = new java.util.TreeMap<>();
        for (var /* 当前要浅观察的索引声明字段 */ value : index.getClass().getDeclaredFields()) {
            // 分页暂存不是业务索引事实，且可能首次查询才分配，观察索引时跳过它。
            if (Modifier.isStatic(value.getModifiers()) || value.getName().equals("identities")
                    || value.getName().equals("pageScratch")) continue;
            value.setAccessible(true);
            // 从当前字段取出的现有对象，按实际容器类型决定浅复制或恢复方式
            Object current = value.get(index);
            if (current instanceof Map<?, ?> /* 需要复制顶层条目的 Map，不复制条目内部对象 */ map) current = new java.util.LinkedHashMap<>(map);
            else if (current instanceof java.util.Set<?> /* 需要复制顶层元素的 Set，不复制元素内部对象 */ set) current = new java.util.LinkedHashSet<>(set);
            else if (current instanceof java.util.List<?> /* 需要保存顶层不可变元素列表的 List */ list) current = List.copyOf(list);
            snapshot.put(value.getName(), current);
        }
        return Map.copyOf(snapshot);
    }

    /**
     * 先改单再撤单，每批都混入缺失原单；检查成功及拒绝保留原项顺序，旧单移除、新单可撤且预留最终清空。
     */
    @Test
    void executesCancelAndAmendBatchesWithOrderedPerItemOutcomes() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);

            // 给改单和撤单提供原活动订单的前置下单批次
            CoreMessage place = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(12_001, "cancel-amend-source", 1_000)))));
            assertThat(drainBatch(state, place).status()).isEqualTo(ResponseStatus.APPLIED);

            // 当前待测的批量改单消息或其单项输入
            CoreMessage amend = command(CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(12_001, 12_002, "amended", 1_100L, 2L,
                                    CoreTimeInForce.GTC, false),
                            new AmendOrderCommand(19_999, 12_003, "missing", 1_200L, 1L,
                                    CoreTimeInForce.GTC, false)))));
            // 改单批次终态解码后的单项结果
            CoreOrderBatchResult amended = TradingOrderBatchCodec.decodeResult(drainBatch(state, amend).data());
            assertThat(amended.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(amended.items().get(1).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().orders().get(12_001L)).isNull();
            assertThat(state.tradingState().orders().get(12_002L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);

            // 含自有、重复或缺失订单的待测撤单命令
            CoreMessage cancel = command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(12_002), new CancelOrderCommand(19_999)))));
            // 撤单批次终态解码后的单项结果
            CoreOrderBatchResult canceled = TradingOrderBatchCodec.decodeResult(drainBatch(state, cancel).data());
            assertThat(canceled.items()).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(canceled.items().get(1).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            assertThat(state.tradingState().orders().get(12_002L)).isNull();
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
        }
    }

    /**
     * 先阻塞真实 Matcher，再向当前直达事件写入旧单已撤、新单未找到的部分替换事实；检查报致命恢复故障、后项不执行、不保存业务拒绝终态，最后释放门闩并停止工作线程。
     */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void amendPartialMatcherFailureMustFailStickyBeforeRecordingBusinessRejection() throws Exception {
        // 本场景独立 Core 运行时，资源结束或 finally 中关闭
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        // 阻塞工作线程的放行门闩，finally 必须放行
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            // 给改单和撤单提供原活动订单的前置下单批次
            CoreMessage place = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(12_101, "partial-amend-source", 1_000)))));
            assertThat(drainBatch(state, place).status()).isEqualTo(ResponseStatus.APPLIED);

            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 当前待测的批量改单消息或其单项输入
            CoreMessage amend = command(CoreMessageType.AMEND_ORDER_BATCH, commandId, 3,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(12_101, 12_102, "partial-amend-replacement", 1_100L, 2L,
                                    CoreTimeInForce.GTC, false),
                            new AmendOrderCommand(12_101, 12_103, "must-not-run", 1_200L, 1L,
                                    CoreTimeInForce.GTC, false)))));

            // 确认目标工作线程已经到达阻塞点的门闩
            var entered = new java.util.concurrent.CountDownLatch(1);
            state.matcherPipeline().readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("matcher gate timeout");
                } catch (/* 等待 Matcher 门闩被中断的异常，恢复中断标记后交测试失败 */ InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(state.apply(amend).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(commandId);
            // 在真实 Matcher 到账户事件的边界注入。
            // 一旦命令已有直达结算事件，Owner 另传的令牌就不是该路径的权威事实。
            // 等待真实改单准入及直达事件建立的两秒截止点
            long admissionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (state.pendingMatching().get(sequence).orderBatch.itemSettlementEvent == null) {
                // 当前仍在队列中的批次命令槽
                var pending = state.pendingMatching().get(sequence);
                if (!pending.orderBatch.activated())
                    state.batches().activateOrderBatch(pending.orderBatch, pending, true);
                state.runtimeState().assertAccountLanesHealthy();
                if (System.nanoTime() >= admissionDeadline) throw new AssertionError("amend admission timeout");
                Thread.onSpinWait();
            }
            // 已经派发并等待准入或结算的真实事件
            var event = state.pendingMatching().get(sequence).orderBatch.itemSettlementEvent;
            assertThat(event).isNotNull();
            // 注入的原生旧单撤销成功事实
            var nativeCancellation = nativeResult(CommandResultCode.SUCCESS, 12_101);
            // 注入的原生新单放入失败事实，和旧单成功构成部分前缀
            var nativePlacement = nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID, 12_102);
            event.publishDirectNativeReplacement(nativeCancellation, nativePlacement, sequence,
                    commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                    12_102, 2, 0);
            // 本轮已完成响应、序号列表或最后取得的终态，具体角色见所在方法
            CoreResponse completed = null;
            // 本轮等待捕获的致命分歧异常，故障后仍须返回同一实例
            Throwable divergence = null;
            try {
                completed = completeEventually(state, sequence, event, 2_000, 4);
            } catch (/* 完成入口抛出的部分撮合故障，转存给外层断言 */ Throwable t) {
                divergence = t;
            }

            assertThat(divergence).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 1001, () -> state.runtimeState().order(12_101)).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);
            assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 1001, () -> state.runtimeState().order(12_102))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 1001, () -> state.runtimeState().order(12_103))).isNull();
            assertThat(state.pendingMatching().size()).isEqualTo(1);
            assertThat(state.commandResults()).doesNotContainKey(commandId);
            assertThat(state.pendingMatching().get(sequence)).isNotNull();
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 4)))
                    .isSameAs(divergence);
        } finally {
            release.countDown();
            org.assertj.core.api.Assertions.catchThrowable(state::close);
            for (Object /* 本场景需要显式停止或放行的账户工作线程 */ worker : (Object[]) com.surprising.aeron.service.core.AccountLaneTestAccess.workers(state.runtimeState())) {
                if (worker instanceof AutoCloseable /* 已经确认可关闭的工作线程对象，故障清理仍要停止其他线程 */ closeable)
                    org.assertj.core.api.Assertions.catchThrowable(closeable::close);
            }
        }
    }

    /**
     * 两个用户在不同 Lane 挂单，用对方已占订单号作替换号；检查准入以全局重复订单号拒绝、没有执行明细，两张原单和各自冻结均保留。
     */
    @Test
    void amendBatchRejectsReplacementIdOnAnotherLaneBeforeCancelingOriginal() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 10_000);
            applyBalance(state, 1002, 10_000, 2);
            assertThat(state.runtimeState().topology().accountLaneId(1001))
                    .isNotEqualTo(state.runtimeState().topology().accountLaneId(1002));
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < 2; index++) {
                // 当前轮给另一用户创建的订单编号
                // 当前需要核对资产或订单的参与账户用户编号
                long user = 1001 + index, order = 13_211 + index;
                assertThat(drainBatch(state, command(
                        CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3 + index,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                place(order, "cross-lane-" + user, 1_000)))), user)).status())
                        .isEqualTo(ResponseStatus.APPLIED);
            }
            // 当前已收到或由完成回调交出的 Core 响应
            var response = CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.AMEND_ORDER_BATCH,
                    UUID.randomUUID(), 5, TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(13_211, 13_212, "occupied", 900L, 1L, CoreTimeInForce.GTC, false))))));
            // 当前批量结果的一项，读取其状态、结果码、订单或成交明细
            var item = TradingOrderBatchCodec.decodeResult(response.data()).items().getFirst();
            assertThat(item.resultCode()).isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
            assertThat(item.executions()).isEmpty();
            assertThat(state.tradingState().orders().get(13_211L).userId()).isEqualTo(1001);
            assertThat(state.tradingState().orders().get(13_212L).userId()).isEqualTo(1002);
            for (long /* 当前需要核对资产或订单的参与账户用户编号 */ user : new long[]{1001, 1002}) {
                // 当前要检查的资产可用与冻结余额
                var balance = state.tradingState().users().get(user).balances().get("USDT");
                assertThat(balance.availableUnits()).isEqualTo(9_000);
                assertThat(balance.lockedUnits()).isEqualTo(1_000);
            }
        }
    }

    /**
     * 给各账户订单表安装 Owner 读取守卫，再做链式两次成功改单和一次资金不足拒绝；检查最终响应同时表达已退休项、现存新单及拒绝沿用订单，再移除守卫核对余额。
     */
    @Test
    void finalAmendBatchResolvesChainedAndRejectedItemsWithoutOwnerOrderReads() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 1_000);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_221, "final-original", 1_000))))));
            state.activate();
            // 当前运行时各账户 Lane 对象数组
            Object[] lanes = field(state.runtimeState(), "accountLanes");
            // 安装守卫前保存的原账户表引用，finally 按对应位置恢复
            Object[] original = new Object[lanes.length];
            try {
                for (/* 当前账户 Lane 数组下标 */ int lane = 0; lane < lanes.length; lane++) {
                    // 当前被安装守卫或恢复的账户表声明字段
                    var table = lanes[lane].getClass().getDeclaredField("orders"); table.setAccessible(true);
                    original[lane] = table.get(lanes[lane]);
                    table.set(lanes[lane], ownerReadGuard(original[lane], Thread.currentThread()));
                }
                // 当前已收到或由完成回调交出的 Core 响应
                var response = CoreTestCompletion.applyAsynchronously(state, command(
                        CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 3,
                        TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                                new AmendOrderCommand(13_221, 13_222, "final-first", 900L, 1L, CoreTimeInForce.GTC, false),
                                new AmendOrderCommand(13_222, 13_223, "final-second", 800L, 1L, CoreTimeInForce.GTC, false),
                                new AmendOrderCommand(13_223, 13_224, "final-rejected", 1_100L, 1L, CoreTimeInForce.GTC, false))))));
                // 按原输入下标排列的批量单项结果列表
                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                assertThat(items).hasSize(3);
                assertThat(items.get(0).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(items.get(1).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(items.get(2).resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(items.get(0).order()).isNull();
                assertThat(items.get(1).order().orderId()).isEqualTo(13_223);
                assertThat(items.get(2).order().orderId()).isEqualTo(13_223);
                // 本场景预期被拒绝的输入、Core 响应或批次，具体角色见所在方法
                var rejected = CoreTestCompletion.applyAsynchronously(state, command(
                        CoreMessageType.AMEND_ORDER_BATCH, UUID.randomUUID(), 4,
                        TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                                new AmendOrderCommand(13_223, 13_225, "all-rejected", 1_100L, 1L, CoreTimeInForce.GTC, false))))));
                // 只含资金不足结果的改单批次首项
                var rejectedItem = TradingOrderBatchCodec.decodeResult(rejected.data()).items().getFirst();
                assertThat(rejectedItem.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(rejectedItem.order().orderId()).isEqualTo(13_223);
            } finally {
                for (/* 当前账户 Lane 数组下标 */ int lane = 0; lane < lanes.length; lane++) {
                    if (original[lane] == null) continue;
                    // 当前被安装守卫或恢复的账户表声明字段
                    var table = lanes[lane].getClass().getDeclaredField("orders"); table.setAccessible(true);
                    // 需要复制数据的现有原语账户表
                    @SuppressWarnings("unchecked") var source =
                            (org.agrona.collections.Long2ObjectHashMap<Object>) table.get(lanes[lane]);
                    // 要恢复内容的原账户表，先清旧内容再放回守卫期间的新值
                    @SuppressWarnings("unchecked") var target =
                            (org.agrona.collections.Long2ObjectHashMap<Object>) original[lane];
                    target.clear(); target.putAll(source); table.set(lanes[lane], target);
                }
            }
            // 当前要检查的资产可用与冻结余额
            var balance = state.tradingState().users().get(1001L).balances().get("USDT");
            assertThat(balance.availableUnits()).isEqualTo(200);
            assertThat(balance.lockedUnits()).isEqualTo(800);
        }
    }

    /**
     * 先成交卖单再挂买单，检查两项成功、账户所有权交接代数未变，以及双方可用、冻结、预留和订单终态。
     */
    @Test
    void sequentialPlaceUsesEarlierFillProceedsOnPermanentLanes() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, "BTC", 1, 1);
            applyBalance(state, 1002, 2_000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(98_300, "proceeds-maker", CoreOrderSide.BUY, 1_000, 1)))), 1002));
            state.activate();
            // 执行本场景前的账户所有权交接代数，后续准入和结算不应增加
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
            // 当前已收到或由完成回调交出的 Core 响应
            var response = CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(98_301, "sell-for-proceeds", CoreOrderSide.SELL, 1_000, 1),
                            linearOrder(98_302, "buy-with-proceeds", CoreOrderSide.BUY, 1_000, 1))))));
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(response, 2)).isEqualTo(-1);
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState())).isEqualTo(handoff);
            // 完成阶段导出的业务状态，供余额、预留及订单断言读取
            var trading = state.tradingState();
            assertThat(trading.orders().get(98_300L)).isNull();
            assertThat(trading.orders().get(98_301L)).isNull();
            assertThat(trading.orders().get(98_302L).remainingQuantitySteps()).isOne();
            assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isZero();
            assertThat(trading.users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(1_000);
            assertThat(trading.users().get(1001L).reservations()).containsOnlyKeys(98_302L);
            assertThat(trading.users().get(1002L).balances().get("USDT").availableUnits()).isEqualTo(1_000);
            assertThat(trading.users().get(1002L).balances().get("BTC").availableUnits()).isOne();
        }
    }

    /**
     * 暂用两个账户 Lane，给订单、预留、用户、持仓和余额表安装只禁止 Owner get 的守卫；检查成功、资金拒绝、跨账户重复号及客户键清理，最后复制守卫中的新值回原表并还原全局配置。
     */
    @Test
    void sequentialPlaceBatchAdmitsAndRejectsFromLanesWithoutOwnerAccountReads() throws Exception {
        // 进入场景前的全局账户 Lane 配置，finally 恢复
        String previous = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", "2");
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 1_000, 1);
            applyBalance(state, 1002, 2_000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 3, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(linearOrder(98_200, "other-account", CoreOrderSide.BUY, 2_000, 1)))), 1002));
            state.activate();
            // 当前运行时各账户 Lane 对象数组
            Object[] lanes = field(state.runtimeState(), "accountLanes");
            // 需要安装读取守卫的订单、预留、用户、持仓及余额表字段名
            String[] names = {"orders", "reservations", "users", "positions", "balances"};
            // 按账户 Lane 与字段下标保存的原始表引用二维数组
            Object[][] originals = new Object[lanes.length][names.length];
            try {
                for (/* 当前账户 Lane 数组下标 */ int lane = 0; lane < lanes.length; lane++) {
                    for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < names.length; index++) {
                        // 当前被安装守卫或恢复的账户表声明字段
                        var table = lanes[lane].getClass().getDeclaredField(names[index]);
                        table.setAccessible(true);
                        originals[lane][index] = table.get(lanes[lane]);
                        table.set(lanes[lane], ownerReadGuard(originals[lane][index], Thread.currentThread()));
                    }
                }
                // 执行本场景前的账户所有权交接代数，后续准入和结算不应增加
                long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
                // 当前已收到或由完成回调交出的 Core 响应
                var response = CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.PLACE_ORDER_BATCH,
                        UUID.randomUUID(), 4, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                linearOrder(98_201, "lane-first", CoreOrderSide.BUY, 900, 1),
                                linearOrder(98_202, "lane-unaffordable", CoreOrderSide.BUY, 200, 1),
                                linearOrder(98_203, "lane-last", CoreOrderSide.BUY, 100, 1))))));
                // 按原输入下标排列的批量单项结果列表
                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                assertThat(items).as("sequential Lane outcomes: %s", items).extracting(CoreOrderBatchResult.Item::status)
                        .containsExactly(ResponseStatus.APPLIED, ResponseStatus.REJECTED, ResponseStatus.APPLIED);
                assertThat(items.get(1).resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(items.get(0).order().orderId()).isEqualTo(98_201);
                assertThat(items.get(2).order().orderId()).isEqualTo(98_203);
                // 本场景预期被拒绝的输入、Core 响应或批次，具体角色见所在方法
                var rejected = CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.PLACE_ORDER_BATCH,
                        UUID.randomUUID(), 5, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                linearOrder(98_200, "duplicate-other-account", CoreOrderSide.BUY, 1, 1),
                                linearOrder(98_204, "all-rejected", CoreOrderSide.BUY, 1, 1))))));
                assertThat(TradingOrderBatchCodec.decodeResult(rejected.data()).items())
                        .extracting(CoreOrderBatchResult.Item::resultCode)
                        .containsExactly(CoreResultCode.DUPLICATE_ORDER_ID, CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState())).isEqualTo(handoff);
            } finally {
                for (/* 当前账户 Lane 数组下标 */ int lane = 0; lane < lanes.length; lane++) {
                    for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < names.length; index++) {
                        // 安装守卫前保存的原账户表引用，finally 按对应位置恢复
                        Object original = originals[lane][index];
                        if (original == null) continue;
                        // 当前被安装守卫或恢复的账户表声明字段
                        var table = lanes[lane].getClass().getDeclaredField(names[index]);
                        table.setAccessible(true);
                        // 从当前字段取出的现有对象，按实际容器类型决定浅复制或恢复方式
                        Object current = table.get(lanes[lane]);
                        if (original instanceof org.agrona.collections.Long2ObjectHashMap<?>) {
                            // 要恢复内容的原账户表，先清旧内容再放回守卫期间的新值
                            @SuppressWarnings("unchecked") var target = (org.agrona.collections.Long2ObjectHashMap<Object>) original;
                            // 需要复制数据的现有原语账户表
                            @SuppressWarnings("unchecked") var source = (org.agrona.collections.Long2ObjectHashMap<Object>) current;
                            target.clear(); target.putAll(source);
                        } else {
                            // 要恢复内容的原账户表，先清旧内容再放回守卫期间的新值
                            @SuppressWarnings("unchecked") var target = (org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Object>) original;
                            // 需要复制数据的现有原语账户表
                            @SuppressWarnings("unchecked") var source = (org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Object>) current;
                            target.clear(); target.putAll(source);
                        }
                        table.set(lanes[lane], original);
                    }
                }
            }
            // 完成阶段导出的业务状态，供余额、预留及订单断言读取
            var trading = state.tradingState();
            assertThat(trading.users().get(1001L).balances().get("USDT").availableUnits()).isZero();
            assertThat(trading.users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(1_000);
            assertThat(trading.users().get(1001L).reservations()).containsOnlyKeys(98_201L, 98_203L);
            assertThat(trading.orders().get(98_200L).userId()).isEqualTo(1002);
            // 账户线程完成拒绝客户号查询后回传的两项键值 Future
            var clientKeys = new java.util.concurrent.CompletableFuture<long[]>();
            // 当前运行时账户工作线程数组
            Object[] workers = com.surprising.aeron.service.core.AccountLaneTestAccess.workers(state.runtimeState());
            // 用户一千零一固定路由到的账户 Lane 编号
            int laneId = state.runtimeState().topology().accountLaneId(1001);
            ((com.surprising.aeron.bootstrap.account.SettlementLaneWorker) workers[laneId]).submit(/* 在用户所属账户线程收到的 Lane，用于查询被拒订单的客户身份是否已释放 */ lane ->
                    clientKeys.complete(new long[]{
                            state.identities().findClientKeyValueInLane(lane, 1001, "lane-unaffordable"),
                            state.identities().findClientKeyValueInLane(lane, 1001, "all-rejected")}));
            assertThat(clientKeys.get(3, java.util.concurrent.TimeUnit.SECONDS)).containsExactly(0, 0);
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.account-lanes");
            else System.setProperty("surprising.aeron.account-lanes", previous);
        }
    }

    /**
     * 阻塞所属账户并安装表读取守卫，手动准备改单准入，检查未完成事件不能回收且其他 Lane 仍工作；放行后收成功结果再归池，用同一事件检查资金不足拒绝，确认原单冻结仍在。
     */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void amendBatchAdmissionReadsOnlyItsLaneAndRetainsTheEventUntilCollected() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 1_000);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_201, "lane-amend-original", 1_000))))));
            state.activate();
            // 当前运行时各账户 Lane 对象数组
            Object[] lanes = field(state.runtimeState(), "accountLanes");
            // 当前运行时账户工作线程数组
            Object[] workers = com.surprising.aeron.service.core.AccountLaneTestAccess.workers(state.runtimeState());
            // 用户一千零一固定路由到的账户 Lane 编号
            int laneId = state.runtimeState().topology().accountLaneId(1001);
            // 本场景需要显式停止或放行的账户工作线程
            var worker = (com.surprising.aeron.bootstrap.account.SettlementLaneWorker) workers[laneId];
            // 确认目标工作线程已经到达阻塞点的门闩
            var entered = new java.util.concurrent.CountDownLatch(1);
            // 阻塞工作线程的放行门闩，finally 必须放行
            var release = new java.util.concurrent.CountDownLatch(1);
            worker.submit(/* 目标账户工作线程传入的 Lane；这里只阻塞线程，不读取它 */ lane -> {
                entered.countDown();
                try {
                    if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out");
                } catch (/* 阻塞账户门闩等待被中断的错误，按测试原实现转为断言失败 */ InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 该用户独占的实际账户 Lane 对象
            var accountLane = lanes[laneId];
            // 需要安装读取守卫的订单、预留、用户、持仓及余额表字段名
            String[] names = {"orders", "reservations", "users", "positions", "balances"};
            // 安装守卫前保存的原账户表引用，finally 按对应位置恢复
            Object[] original = new Object[names.length];
            // 不允许跨线程读取账户表的当前测试 Owner 线程
            Thread ownerThread = Thread.currentThread();
            try {
                for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < names.length; index++) {
                    // 当前被安装守卫或恢复的账户表声明字段
                    var table = accountLane.getClass().getDeclaredField(names[index]); table.setAccessible(true);
                    original[index] = table.get(accountLane);
                    table.set(accountLane, ownerReadGuard(original[index], ownerThread));
                }
                // 当前场景要推进的原批量消息
                var batch = state.batches().acquireOrderBatchPending(
                        com.surprising.aeron.service.order.command.OrderBatchKind.AMEND,
                        1, 2_000, 3);
                batch.sequence = 1_000;
                // 当前待测的批量改单消息或其单项输入
                var amend = new AmendOrderCommand(13_201, 13_202, "lane-amend", 900L, 1L, CoreTimeInForce.GTC, false);
                batch.addItem(13_202, 13_201, 13_202, amend);
                state.batches().prepareOrderBatchItem(batch, batch.items.getFirst(), 1001, UUID.randomUUID());
                // 已经派发并等待准入或结算的真实事件
                var event = batch.replacementAdmissionEvent;
                assertThat(event).isNotNull();
                assertThat(event.complete()).isFalse();
                assertThatThrownBy(() -> state.runtimeState().releaseReplacementAdmission(event))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
                // 确认另一账户 Lane 未被目标 Lane 阻塞的完成门闩
                var independent = new java.util.concurrent.CountDownLatch(1);
                ((com.surprising.aeron.bootstrap.account.SettlementLaneWorker) workers[(laneId + 1) % lanes.length])
                        .submit(/* 另一账户线程传入的 Lane；这里只报告门闩到达，不读取它 */ lane -> independent.countDown());
                assertThat(independent.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release.countDown();
                awaitBatchAmendAdmission(state, event);
                assertThat(event.rejection()).isNull();
                assertThat(event.admission().requiredReservationUnits()).isEqualTo(900);
                state.batches().releaseOrderBatchPending(batch);
                assertThat(event.complete()).isFalse();

                // 本场景预期被拒绝的输入、Core 响应或批次，具体角色见所在方法
                var rejected = state.batches().acquireOrderBatchPending(
                        com.surprising.aeron.service.order.command.OrderBatchKind.AMEND,
                        1, 2_001, 4);
                rejected.sequence = 1_001;
                rejected.addItem(13_203, 13_201, 13_203, new AmendOrderCommand(
                        13_201, 13_203, "unaffordable-lane", 1_100L, 1L, CoreTimeInForce.GTC, false));
                state.batches().prepareOrderBatchItem(rejected, rejected.items.getFirst(), 1001, UUID.randomUUID());
                assertThat(rejected.replacementAdmissionEvent).isSameAs(event);
                awaitBatchAmendAdmission(state, event);
                assertThat(event.rejection()).isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                        .hasMessageContaining("insufficient");
                state.batches().releaseOrderBatchPending(rejected);
            } finally {
                release.countDown();
                for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < names.length; index++) {
                    if (original[index] == null) continue;
                    // 当前被安装守卫或恢复的账户表声明字段
                    var table = accountLane.getClass().getDeclaredField(names[index]); table.setAccessible(true);
                    table.set(accountLane, original[index]);
                }
            }
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").lockedUnits()).isEqualTo(1_000);
            assertThat(state.tradingState().orders().get(13_201L)).isNotNull();
            assertThat(state.tradingState().orders().get(13_202L)).isNull();
        }
    }

    /**
     * 在两秒内等待替换准入事件完成，每轮检查账户线程健康并让出自旋提示；超时抛断言，不收集或回收该事件。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param event 本场景已经派发的替换准入事件，等待完成但不在辅助方法内回收
     */
    private static void awaitBatchAmendAdmission(TradingCoreRuntime state,
            com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent event) {
        // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!event.complete()) {
            state.runtimeState().assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("batch amend admission timeout");
            Thread.onSpinWait();
        }
    }

    /**
     * 按两种现有原语表类型复制内容，返回只禁止指定 Owner 线程 get 的测试替身；其他线程仍可按原表接口访问，并非所有读操作的通用代理。
     * @param original 安装读取守卫前的账户原语表
     * @param ownerThread 不允许通过 get 读取账户表的指定 Owner 线程
     */
    @SuppressWarnings("unchecked")
    private static Object ownerReadGuard(Object original, Thread ownerThread) {
        if (original instanceof org.agrona.collections.Long2ObjectHashMap<?> /* 需要复制数据的现有原语账户表 */ source) {
            // 复制原表内容并禁止 Owner get 的账户表测试替身
            var guard = /* 复制原 Agrona 账户表内容的测试替身，只阻止指定 Owner 线程调用 get，其他线程沿用父表实现。 */ new org.agrona.collections.Long2ObjectHashMap<Object>() {
                /**
                 * 指定 Owner 线程查询 Agrona 账户表就抛断言，其他线程交父表读取。
                 * @param key 当前原语表 get 请求的键
                 */
                @Override public Object get(long key) {
                    if (Thread.currentThread() == ownerThread) throw new AssertionError("Owner read Account Lane table");
                    return super.get(key);
                }
            };
            guard.putAll((org.agrona.collections.Long2ObjectHashMap<Object>) source);
            return guard;
        }
        // 复制原表内容并禁止 Owner get 的账户表测试替身
        var guard = /* 复制原 Eclipse 原语账户表的测试替身，只阻止指定 Owner 线程调用 get。 */ new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Object>() {
            /**
             * 指定 Owner 线程查询 Eclipse 原语账户表就抛断言，其他线程交父表读取。
             * @param key 当前原语表 get 请求的键
             */
            @Override public Object get(long key) {
                if (Thread.currentThread() == ownerThread) throw new AssertionError("Owner read Account Lane table");
                return super.get(key);
            }
        };
        guard.putAll((org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Object>) original);
        return guard;
    }

    /**
     * 把各 Lane 订单表临时换成任意 get 都抛错的空表，只运行身份和路由准备检查；自有、外部及缺失订单按既有路由判定，恢复原表后确认状态及水位未变，再正常撤掉自有单。
     */
    @Test
    void cancelIdentityAndPreparationNeverReadLaneOrderTables() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            applyBalance(state, 1002, 100_000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_101, "route-own", 1_000)))), 1001));
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_102, "route-foreign", 1_000)))), 1002));
            // 本次待测操作前导出的业务状态，用于检查入口拒绝没有改动
            TradingCoreState before = state.tradingState();
            // 入口校验之前的应用命令计数，拒绝后应保持不变
            long appliedBefore = state.appliedCommandCount();
            // 当前运行时各账户 Lane 对象数组
            Object[] lanes = field(state.runtimeState(), "accountLanes");
            // 账户 Lane 中订单表字段的反射句柄
            var ordersField = lanes[0].getClass().getDeclaredField("orders");
            ordersField.setAccessible(true);
            // 临时替换订单表之前保存的各 Lane 原表引用
            Object[] originalTables = new Object[lanes.length];
            for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < lanes.length; index++) {
                originalTables[index] = ordersField.get(lanes[index]);
                // 入口到达时账户线程可能正在修改或扩容订单表。
                // 测试让任何直接查询都失败，要求路由使用 Owner 已有活动索引。
                ordersField.set(lanes[index], /* 所有 get 都抛断言的临时空订单表，当前入口只验证路由不执行账户业务，finally 会恢复各 Lane 原表。 */ new org.agrona.collections.Long2ObjectHashMap<
                        com.surprising.aeron.service.order.state.OrderRuntime>() {
                    /**
                     * 任何订单表读取都抛断言，证明当前入口准备路径使用 Owner 活动路由而未调用此表。
                     * @param key 故意禁止的订单表查询键；测试替身不读取它而直接抛断言
                     */
                    @Override public com.surprising.aeron.service.order.state.OrderRuntime get(long key) {
                        throw new AssertionError("Owner read a Lane order table");
                    }
                });
            }
            try {
                // 排在批次前后或单独检查路由的普通消息
                var single = command(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 5,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(13_101)), 1001);
                // 当前普通撤单消息的类型化解码输入
                var decoded = com.surprising.aeron.service.order.command.DecodedMatchingCommand.decode(single);
                state.admissions().validatePendingCancel(single, decoded);
                assertThat(state.admissions().matchingSymbol(CommandSlot.Operation.CANCEL, decoded))
                        .isEqualTo("1");
                // 只用于查原单路由的普通改单消息
                var amendSingle = command(CoreMessageType.AMEND_ORDER, UUID.randomUUID(), 5,
                        TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(
                                13_101, 13_103, "route-amend", 900L, 1L, CoreTimeInForce.GTC, false)), 1001);
                assertThat(state.admissions().matchingSymbol(CommandSlot.Operation.AMEND,
                        com.surprising.aeron.service.order.command.DecodedMatchingCommand.decode(amendSingle)))
                        .isEqualTo("1");
                // 只用于查原单路由的普通替换消息
                var replaceSingle = command(CoreMessageType.REPLACE_ORDER, UUID.randomUUID(), 5,
                        TradingCommandCodec.encodeReplaceOrder(new com.surprising.aeron.protocol.ReplaceOrderCommand(
                                13_101, place(13_103, "route-replace", 900))), 1001);
                assertThat(state.admissions().matchingSymbol(CommandSlot.Operation.REPLACE,
                        com.surprising.aeron.service.order.command.DecodedMatchingCommand.decode(replaceSingle)))
                        .isEqualTo("1");
                // 请求用户尝试撤对方订单的普通消息
                var foreignSingle = command(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 5,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(13_102)), 1001);
                assertThatThrownBy(() -> state.admissions().validatePendingCancel(foreignSingle,
                        com.surprising.aeron.service.order.command.DecodedMatchingCommand.decode(foreignSingle)))
                        .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                        .hasMessageContaining("another user");
                for (/* 重复借用和归还批次的轮数 */ int round = 0; round < 4; round++) {
                    // 只含自有订单的临时撤单批次
                    var own = state.batches().acquireOrderBatchPending(
                            com.surprising.aeron.service.order.command.OrderBatchKind.CANCEL,
                            1, 2_000, 5);
                    own.addItem(13_101, 0, 0, new CancelOrderCommand(13_101));
                    state.batches().validateOrderBatchIdentity(own, 1001);
                    state.batches().prepareOrderBatchItem(own, own.items.getFirst(), 1001, UUID.randomUUID());
                    state.batches().releaseOrderBatchPending(own);

                    // 混入其他用户订单的临时撤单批次
                    var foreign = state.batches().acquireOrderBatchPending(
                            com.surprising.aeron.service.order.command.OrderBatchKind.CANCEL,
                            2, 2_000, 5);
                    foreign.addItem(13_101, 0, 0, new CancelOrderCommand(13_101));
                    foreign.addItem(13_102, 0, 0, new CancelOrderCommand(13_102));
                    assertThatThrownBy(() -> state.batches().validateOrderBatchIdentity(foreign, 1001))
                            .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                            .hasMessageContaining("another user's order");
                    state.batches().releaseOrderBatchPending(foreign);

                    // 当前待测的批量改单消息或其单项输入
                    var amend = state.batches().acquireOrderBatchPending(
                            com.surprising.aeron.service.order.command.OrderBatchKind.AMEND,
                            1, 2_000, 5);
                    amend.addItem(13_103, 13_102, 13_103, new Object());
                    assertThatThrownBy(() -> state.batches().validateOrderBatchIdentity(amend, 1001))
                            .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                            .hasMessageContaining("another user's order");
                    state.batches().releaseOrderBatchPending(amend);

                    // 只含缺失订单的临时撤单批次
                    var missing = state.batches().acquireOrderBatchPending(
                            com.surprising.aeron.service.order.command.OrderBatchKind.CANCEL,
                            1, 2_000, 5);
                    missing.addItem(999_999, 0, 0, new CancelOrderCommand(999_999));
                    state.batches().validateOrderBatchIdentity(missing, 1001);
                    assertThatThrownBy(() -> state.batches().prepareOrderBatchItem(
                            missing, missing.items.getFirst(), 1001, UUID.randomUUID()))
                            .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                            .hasMessageContaining("active order");
                    state.batches().releaseOrderBatchPending(missing);
                }
            } finally {
                for (/* 当前订单项、分片或账户表的遍历下标 */ int index = 0; index < lanes.length; index++)
                    ordersField.set(lanes[index], originalTables[index]);
            }
            assertThat(state.tradingState()).isEqualTo(before);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBefore);
            // 当前已收到或由完成回调交出的 Core 响应
            var response = drainBatch(state, command(CoreMessageType.CANCEL_ORDER_BATCH, UUID.randomUUID(), 5,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(13_101)))), 1001));
            assertThat(TradingOrderBatchCodec.decodeResult(response.data()).items().getFirst().status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users().get(1001L).reservations()).isEmpty();
            assertThat(state.tradingState().users().get(1001L).balances().get("USDT").availableUnits()).isEqualTo(100_000);
            assertThat(state.tradingState().orders().get(13_102L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);
        }
    }

    /**
     * 同一撤单批次混入他人订单，检查整批入口归属拒绝，业务状态、哈希及应用计数不变，未占待槽或保存终态，两张原单仍活动。
     */
    @Test
    void rejectsMixedUserCancelBatchBeforeAnyMutation() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 100_000);
            applyBalance(state, 1002, 100_000, 2);
            // 请求用户先挂入自有单的消息
            CoreMessage ownPlace = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_001, "mixed-user-own", 1_000)))), 1001);
            // 另一用户先挂入外部单的消息
            CoreMessage foreignPlace = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(13_002, "mixed-user-foreign", 1_000)))), 1002);
            assertThat(drainBatch(state, ownPlace).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(drainBatch(state, foreignPlace).status()).isEqualTo(ResponseStatus.APPLIED);

            // 本次待测操作前导出的业务状态，用于检查入口拒绝没有改动
            TradingCoreState before = state.tradingState();
            // 入口校验之前的应用命令计数，拒绝后应保持不变
            long appliedBefore = state.appliedCommandCount();
            // 入口校验前包含运行元数据的状态哈希
            long stateHashBefore = state.stateHash();
            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 同批混入自有及他人订单的非法请求
            CoreMessage mixed = command(CoreMessageType.CANCEL_ORDER_BATCH, commandId, 5,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(13_001), new CancelOrderCommand(13_002)))), 1001);

            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = state.apply(mixed);

            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.ORDER_OWNER_MISMATCH);
            assertThat(state.tradingState()).isEqualTo(before);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBefore);
            assertThat(state.stateHash()).isEqualTo(stateHashBefore);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(state.commandResults()).doesNotContainKey(commandId);
            assertThat(state.tradingState().orders().get(13_001L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);
            assertThat(state.tradingState().orders().get(13_002L).status())
                    .isEqualTo(com.surprising.aeron.model.CoreOrderStatus.OPEN);
        }
    }

    /**
     * 等顺序批次第一项账户事实可见后关闭，检查第一单和冻结变化保留而第二单尚无活动订单；关闭清在途标记，不撤销已观察事实。
     */
    @Test
    void closeDoesNotRollBackAnInterruptedBatchAfterObservedMatcherFact() throws Exception {
        // 本场景独立 Core 运行时，资源结束或 finally 中关闭
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        applySpotInstrument(state);
        applyBalance(state, 1001, 20_000);
        // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
        TradingAccountRuntime runtime = field(state, "runtimeState");
        // 当前运行时的资产、品种、客户号及持仓身份字典
        RuntimeIdentityRegistry identities = field(state, "identities");
        // USDT 资产编号，供账户合法读取及预留资产断言
        int quoteAssetId = identities.assetId("USDT");
        // 中断批次开始前的可用报价币数量
        long availableBefore = AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.balance(1001, quoteAssetId).availableUnits());
        // 中断批次开始前的冻结报价币数量
        long lockedBefore = AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.balance(1001, quoteAssetId).lockedUnits());
        // 执行当前批次前的运行时业务修订
        long revisionBefore = runtime.revision();
        // 批次开始前的持仓身份检查点，现货场景关闭后不应改变
        long identityBefore = identities.positionCheckpoint();
        // 当前需要检查终态缓存及重复请求的原 UUID
        UUID commandId = UUID.randomUUID();
        // 当前场景要推进的原批量消息
        CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                        place(15_001, "close-first", 1_000),
                        place(15_002, "close-second", 1_000),
                        place(15_001, "close-duplicate", 1_000)))));

        assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        // 当前待完成命令的完整 Core 序号
        long sequence = state.matchingSequence(commandId);
        // 当前顺序批次第一项已经取得的 Matcher 事实
        var firstResult = awaitMatching(state, sequence);
        // 当前命令原槽持有的批次上下文，等待首项游标实际前进
        OrderBatchPending activeBatch = state.batches().batch(sequence);
        // 首项账户和提交推进的有界等待截止点
        long firstDeadline = System.nanoTime() + 5_000_000_000L;
        while (activeBatch.nextIndex == 0) {
            assertThat(System.nanoTime()).isLessThan(firstDeadline);
            state.commits.advanceMatchingProgress(sequence);
            assertThat(state.commits.completeMatching(sequence, firstResult, 2_000, 3)).isNull();
            Thread.onSpinWait();
        }
        assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(15_001))).isNotNull();
        assertThat(state.snapshotHasPendingCommands()).isTrue();

        state.close();

        assertThat(runtime.order(15_001)).isNotNull();
        assertThat(runtime.order(15_002)).isNull();
        assertThat(runtime.balance(1001, quoteAssetId).availableUnits()).isLessThan(availableBefore);
        assertThat(runtime.balance(1001, quoteAssetId).lockedUnits()).isGreaterThan(lockedBefore);
        assertThat(runtime.revision()).isGreaterThan(revisionBefore);
        assertThat(identities.positionCheckpoint()).isEqualTo(identityBefore);
        assertThat(identities.findClientKeyValue(1001, "close-first")).isNotZero();
        assertThat(state.snapshotHasPendingCommands()).isFalse();
    }

    /**
     * 批次已经准入并取得撮合事实时直接尝试改标记价，检查批次作用域拒绝越界状态修改，标记价仍未写入。
     */
    @Test
    void rejectsUnsupportedRuntimeDomainBeforeItsFirstMutationDuringBatch() throws Exception {
        // 本场景独立 Core 运行时，资源结束或 finally 中关闭
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            applySpotInstrument(state);
            applyBalance(state, 1001, 20_000);
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 当前运行时的资产、品种、客户号及持仓身份字典
            RuntimeIdentityRegistry identities = field(state, "identities");
            // 品种一的永久编号
            int symbolId = identities.symbolId("1");
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(15_101, "guard-first", 1_000),
                            place(15_102, "guard-second", 1_000),
                            place(15_101, "guard-duplicate", 1_000)))));
            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 即使只有一项，账户准入也先于 Owner 批次提交作用域；先等真实撮合事实再检查越界修改。
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(batch.header().commandId());
            awaitMatching(state, sequence);

            assertThatThrownBy(() -> runtime.putMarkPrice(new MarkPriceRuntime(
                    symbolId, runtime.instrument("1"), 50_000, 2, 2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order batch cannot mutate mark-price state");

            assertThat(runtime.markPrice(symbolId)).isNull();
        } finally {
            state.close();
        }
    }

    /**
     * 用测试钩子破坏最终参与账户位图，检查报致命故障且已应用订单和修订保留；未完成工作阻止状态哈希、发布与提交水位不前进，后续命令持续返回同一故障。
     */
    @Test
    void finalLaneMaskFailureStopsWithoutRollingBackObservedMatcherFact() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 20_000);
            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 执行当前批次前的运行时业务修订
            long revisionBefore = runtime.revision();
            // 故障前的独立发布序号，失败不得错误前进
            long projectionBefore = state.realtimeExportSequence();
            // 故障注入前的已提交 Core 水位
            long committedBefore = state.committedCoreSequence();
            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(15_201, "lane-mask-fault", 1_000)))));

            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            CoreFaults.laneMask(state, 0);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(commandId);
            // 本轮捕获的执行失败，用于核对故障类型及未提交状态
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> completeEventually(state,
                    sequence, awaitMatching(state, sequence), 2_000, 3));

            assertThat(failure).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(15_201))).isNotNull();
            assertThat(runtime.revision()).isGreaterThan(revisionBefore);
            assertThatThrownBy(state::stateHash)
                    .hasMessageContaining("unfinished reservation or patch work");
            assertThatThrownBy(() -> com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(
                    state.snapshotTradingState()))
                    .hasMessageContaining("unfinished reservation or patch work");
            assertThat(state.realtimeExportSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThatThrownBy(() -> state.apply(probe(UUID.randomUUID(), 3))).isSameAs(failure);
        }
    }

    /**
     * 真实永续成交后先断言双方持仓，再翻转最终 Lane 位图制造故障；检查持仓及身份分配保留、已成交订单和预留退休，但提交发布水位不前进并拒绝继续写。此用例不实际执行快照重放。
     */
    @Test
    void pipelinedFatalAfterRealFillKeepsAppliedLaneStateForSnapshotLogRecovery() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyLinearPerpetualInstrument(state);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1001, 1_000_000, 1);
            applyBalance(state, ProductLine.LINEAR_PERPETUAL, 1002, 1_000_000, 2);
            // 其他用户先挂入的被动订单输入或其批量消息
            CoreMessage maker = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    UUID.randomUUID(), 3, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(linearOrder(
                                    15_301, "position-maker", CoreOrderSide.SELL, 1_000, 1)))), 1002);
            assertThat(drainBatch(state, maker).status()).isEqualTo(ResponseStatus.APPLIED);

            // 当前 Core 内的账户权威运行时，故障后通过合法 Lane 读取检查
            TradingAccountRuntime runtime = field(state, "runtimeState");
            // 当前运行时的资产、品种、客户号及持仓身份字典
            RuntimeIdentityRegistry identities = field(state, "identities");
            // 故障前持仓身份分配检查点，实际成交后的新身份不得回滚。
            long positionCheckpointBefore = identities.positionCheckpoint();
            // 故障前的独立发布序号，失败不得错误前进
            long projectionBefore = state.realtimeExportSequence();
            // 故障注入前的已提交 Core 水位
            long committedBefore = state.committedCoreSequence();
            // 随后要注入致命事实的原批次 UUID
            UUID fatalId = UUID.randomUUID();
            // 已准备在后续完成阶段注入故障的批量消息
            CoreMessage fatalBatch = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    fatalId, 4, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            linearOrder(15_302, "position-taker", CoreOrderSide.BUY, 1_000, 1),
                            linearOrder(15_303, "position-resting", CoreOrderSide.BUY, 900, 1)))), 1001);

            assertThat(state.apply(fatalBatch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(fatalId);
            // 当前批次先取得的 Matcher 事实
            var first = awaitMatching(state, sequence);
            assertThat(first.matcherEvents()).as(first.resultCode()).isNotEmpty();
            CoreFaults.afterSettlement(state, () -> {
                // 主动方用户在品种一的永久持仓身份
                Long takerPositionKey = identities.preparedPositionKey(1001, "1");
                // 被动方用户在品种一的永久持仓身份
                Long makerPositionKey = identities.preparedPositionKey(1002, "1");
                assertThat(takerPositionKey).isNotNull();
                assertThat(makerPositionKey).isNotNull();
                assertThat(runtime.position(takerPositionKey).signedQuantitySteps()).isEqualTo(1);
                assertThat(runtime.position(makerPositionKey).signedQuantitySteps()).isEqualTo(-1);
            });
            // 本轮等待捕获的致命分歧异常，故障后仍须返回同一实例
            Throwable divergence = org.assertj.core.api.Assertions.catchThrowable(
                    () -> completeEventually(state, sequence, first, 3_000, 3));

            assertThat(divergence).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            // 致命故障出现后实际已经观察到的分片证据水位
            long[] observedSequences = ((long[]) field(state.commits, "appliedMatcherSequences")).clone();
            assertThat(java.util.Arrays.stream(observedSequences).anyMatch(/* 当前被检查是否大于零的已观察 Matcher 进度值 */ value -> value > 0)).isTrue();
            // 主动方用户在品种一的永久持仓身份
            Long takerPositionKey = identities.preparedPositionKey(1001, "1");
            // 被动方用户在品种一的永久持仓身份
            Long makerPositionKey = identities.preparedPositionKey(1002, "1");
            assertThat(takerPositionKey).isNotNull();
            assertThat(makerPositionKey).isNotNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.position(takerPositionKey).signedQuantitySteps())).isEqualTo(1);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1002, () -> runtime.position(makerPositionKey).signedQuantitySteps())).isEqualTo(-1);
            assertThat(identities.positionCheckpoint()).isGreaterThan(positionCheckpointBefore);
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(15_301))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.order(15_302))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(15_301))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 1001, () -> runtime.reservation(15_302))).isNull();
            assertThat(state.realtimeExportSequence()).isEqualTo(projectionBefore);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);
            assertThat(CoreTestCompletion.takeMatchingResult(state, sequence)).isNull();
            assertThatThrownBy(() -> state.apply(command(ProductLine.LINEAR_PERPETUAL,
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 5,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)), 1001))).isSameAs(divergence);
        }
    }

    /**
     * 顺序批次首项已应用后注入致命结果，保存实际故障边界的分片水位；关闭后待槽及完成引用清空，但已经观察到的 Matcher 序号保持不变。
     */
    @Test
    void fatalTeardownReleasesAdmissionWithoutErasingMatcherForensics() throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, 20_000);
            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 当前场景要推进的原批量消息
            CoreMessage batch = command(CoreMessageType.PLACE_ORDER_BATCH, commandId, 2,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(15_401, "fatal-close-first", 1_000),
                            place(15_402, "fatal-close-second", 1_000),
                            place(15_401, "fatal-close-duplicate", 1_000)))));
            assertThat(state.apply(batch).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 当前待完成命令的完整 Core 序号
            long sequence = state.matchingSequence(commandId);
            // 当前顺序批次第一项已经取得的 Matcher 事实
            var firstResult = awaitMatching(state, sequence);
            // 当前命令原槽持有的批次上下文，等待首项游标实际前进
            OrderBatchPending activeBatch = state.batches().batch(sequence);
            // 首项账户和提交推进的有界等待截止点
            long firstDeadline = System.nanoTime() + 5_000_000_000L;
            while (activeBatch.nextIndex == 0) {
                assertThat(System.nanoTime()).isLessThan(firstDeadline);
                // 账本派发尚在追赶时保留同一撮合事实，像正常完成推进入口一样继续尝试。
                state.commits.advanceMatchingProgress(sequence);
                assertThat(state.commits.completeMatching(sequence, firstResult, 2_000, 3)).isNull();
                Thread.onSpinWait();
            }
            // 首项完成后复制的分片序号，供故障边界作不倒退比较
            long[] matcherSequences = ((long[]) field(state.commits, "appliedMatcherSequences")).clone();
            // 测试构造的致命原生完成结果
            var failedResult = new com.surprising.aeron.service.matcher.CoreMatchingResult(false, "EXCHANGE_CORE_FAILURE");
            // 本轮等待捕获的致命分歧异常，故障后仍须返回同一实例
            Throwable divergence = null;
            // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (divergence == null) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                state.commits.advanceMatchingProgress(sequence);
                divergence = org.assertj.core.api.Assertions.catchThrowable(() ->
                        state.commits.completeMatching(sequence, failedResult, 2_001, 4));
                Thread.onSpinWait();
            }
            assertThat(divergence).isInstanceOf(
                    FatalMatchingDivergenceException.class);

            // 账本追赶期间，下一条有效撮合事实也可能先到。
            // 关闭时保留实际故障边界已经观察到的全部分片水位。
            // 实际致命故障边界保存的分片水位，关闭后必须逐项保持
            long[] fatalMatcherSequences = ((long[]) field(state.commits, "appliedMatcherSequences")).clone();
            for (/* 当前比较的进度数组下标，包含控制分片位置 */ int shard = 0; shard < matcherSequences.length; shard++)
                assertThat(fatalMatcherSequences[shard]).isGreaterThanOrEqualTo(matcherSequences[shard]);

            state.close();

            assertThat(state.pendingMatching().size()).isZero();
            assertThat(state.snapshotHasPendingCommands()).isFalse();
            assertThat((long[]) field(state.commits, "appliedMatcherSequences")).containsExactly(fatalMatcherSequences);
            assertThat(CoreTestCompletion.takeMatchingResult(state, sequence)).isNull();
        }
    }

    /**
     * 向现货 Core 传 U 本位永续消息，要求产品线不匹配即拒绝，状态、哈希、计数和待队列不变，也不保存命令终态。
     */
    @Test
    void rejectsProductLineMismatchBeforeAnyBatchMutation() {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            // 本次待测操作前导出的业务状态，用于检查入口拒绝没有改动
            TradingCoreState before = state.tradingState();
            // 入口校验之前的应用命令计数，拒绝后应保持不变
            long appliedBefore = state.appliedCommandCount();
            // 入口校验前包含运行元数据的状态哈希
            long stateHashBefore = state.stateHash();
            // 当前需要检查终态缓存及重复请求的原 UUID
            UUID commandId = UUID.randomUUID();
            // 把永续产品消息故意传给现货 Core 的原命令
            CoreMessage crossLine = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.PLACE_ORDER_BATCH,
                    commandId, 2, TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(14_001, "cross-line", 1_000)))));

            // 当前已收到或由完成回调交出的 Core 响应
            CoreResponse response = state.apply(crossLine);

            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_MISMATCH);
            assertThat(state.tradingState()).isEqualTo(before);
            assertThat(state.appliedCommandCount()).isEqualTo(appliedBefore);
            assertThat(state.stateHash()).isEqualTo(stateHashBefore);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(state.commandResults()).doesNotContainKey(commandId);
        }
    }

    /**
     * 依次检查 Matcher 只挂单拒绝和账户资金不足拒绝的清理，再追加快照恢复比较。
     */
    @Test
    void sequentialNativeAndAdmissionRejectionsReleaseFundsWithoutLaneHandoff() throws Exception {
        sequentialNativeAndAdmissionRejectionsReleaseFunds(true);
    }

    /**
     * 检查相同两类单项拒绝的资金与客户作用域清理，不追加快照恢复。
     */
    @Test
    void sequentialNativeAndAdmissionRejectionsReleaseFundsWithoutRecovery() throws Exception {
        sequentialNativeAndAdmissionRejectionsReleaseFunds(false);
    }

    /**
     * 让卖出只挂单被 Matcher 拒绝，随后买入因没有报价资金被账户拒绝；检查原基础币全可用、无预留、其他用户挂单保留且不借走账户所有权，按参数决定恢复比较。
     * @param recovery 是否在同一业务场景结束后追加快照恢复比较
     */
    private void sequentialNativeAndAdmissionRejectionsReleaseFunds(boolean recovery) throws Exception {
        try (/* 本场景独立 Core 运行时，资源结束或 finally 中关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applySpotInstrument(state);
            applyBalance(state, 1001, "BTC", 1, 1);
            applyBalance(state, 1002, 2000, 2);
            drainBatch(state, command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 3,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(97_000, "reject-maker", 1000)))), 1002));
            // 当前卖出只挂单，因会吃掉挂单而应被 Matcher 拒绝
            var postOnly = new PlaceOrderCommand(97_001, "1", CoreOrderSide.SELL, 1000, 1, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTX,
                    true, "reject-post-only");
            // 本场景要执行的原 Core 命令
            var message = command(CoreMessageType.PLACE_ORDER_BATCH, UUID.randomUUID(), 4,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            postOnly, place(97_002, "reject-funds", 1000)))));
            // 执行本场景前的账户所有权交接代数，后续准入和结算不应增加
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState());
            // 两项均拒绝但整批已完成的 Core 响应
            var result = CoreTestCompletion.applyAsynchronously(state, message);
            // 按原输入下标排列的批量单项结果列表
            var items = TradingOrderBatchCodec.decodeResult(result.data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status)
                    .containsExactly(ResponseStatus.REJECTED, ResponseStatus.REJECTED);
            assertThat(items).extracting(CoreOrderBatchResult.Item::resultCode)
                    .containsExactly(CoreResultCode.MATCHING_REJECTED, CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(state.runtimeState())).isEqualTo(handoff);
            // 两种拒绝全部完成后导出的请求用户状态
            var user = state.tradingState().users().get(1001L);
            assertThat(user.reservations()).isEmpty();
            assertThat(user.balances().get("BTC").availableUnits()).isOne();
            assertThat(user.balances().get("BTC").lockedUnits()).isZero();
            assertThat(state.tradingState().orders().get(97_000L)).isNotNull();
            if (recovery) try (/* 由场景保存的快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 提交命令，非待撮合响应直接返回；待处理时取得原序号，再通过共同有序提交辅助入口等终态。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param batch 已提交或待提交的整批原命令
     */
    private static CoreResponse drainBatch(TradingCoreRuntime state, CoreMessage batch) {
        // 首次提交当前批次得到的响应，非 MATCHING_PENDING 时直接返回
        CoreResponse initial = state.apply(batch);
        if (initial.resultCode() != CoreResultCode.MATCHING_PENDING) return initial;
        // 当前待完成命令的完整 Core 序号
        long sequence = state.matchingSequence(batch.header().commandId());
        return drainBatchAfterFirst(state, batch, sequence);
    }

    /**
     * 最多五秒推进有序提交，每轮尝试预算二百五十六，仅保存指定序号的终态；使用原消息提交时间和来源序号作为本测试推进元数据，超时断言失败。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param batch 已提交或待提交的整批原命令
     * @param sequence 指定命令完整 Core 序号
     */
    private static CoreResponse drainBatchAfterFirst(TradingCoreRuntime state, CoreMessage batch, long sequence) {
        // 回调捕获指定 Core 终态的单元素槽，等待循环读同一槽
        CoreResponse[] terminal = {null};
        // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (terminal[0] == null && System.nanoTime() - deadline < 0) {
            state.commits.commitReadyMatching(256, batch.header().submittedAtEpochMillis(),
                    batch.header().sourceSequence(), false, (/* 有序提交回调已经完成的命令 Core 序号 */ completedSequence, /* 当前已收到或由完成回调交出的 Core 响应 */ response) -> {
                        if (completedSequence == sequence) terminal[0] = response;
                    });
        }
        assertThat(terminal[0]).as("batch must reach a terminal response").isNotNull();
        return terminal[0];
    }

    /**
     * 最多五秒反复用同一 Matcher 事实尝试完成指定命令，账户或财务未齐时继续自旋；始终未完成则抛断言，业务故障直接传播。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param sequence 指定命令完整 Core 序号
     * @param matching 重复尝试提交时保持使用的同一 Matcher 事实
     * @param clusterTimestamp 本测试固定传给完成入口的业务时间
     * @param clusterPosition 本测试固定传给完成入口的日志位置
     */
    private static CoreResponse completeEventually(
            TradingCoreRuntime state, long sequence,
            com.surprising.aeron.service.matcher.MatchingResult matching,
            long clusterTimestamp, long clusterPosition) {
        // 本轮已完成响应、序号列表或最后取得的终态，具体角色见所在方法
        CoreResponse completed = null;
        // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.commits.completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
            if (completed == null) Thread.onSpinWait();
        }
        if (completed == null) throw new AssertionError("account lane settlement did not complete");
        return completed;
    }

    /**
     * 构造指定结果码和订单编号的原生 Matcher 测试值，其他命令、价格和数量固定，事件及盘口为空，用于替换部分事实注入。
     * @param code 要模拟的原生 Matcher 结果码
     * @param orderId 要构造或注入的订单编号
     */
    private static MatcherResult nativeResult(CommandResultCode code, long orderId) {
        return new MatcherResult(1, OrderCommandType.PLACE_ORDER, orderId, 1,
                1_000, 1, 1_000, OrderAction.BID, OrderType.GTC, 1001, 1_000, 0,
                code, List.of(), new MatcherResult.MarketData(List.of(), List.of(), 0, 0));
    }

    /**
     * 最多五秒激活未开始的队首批次并排空已有完成通知，取得指定序号 Matcher 事实；取到事实不表示账户结算已经完成，超时断言失败。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param sequence 指定命令完整 Core 序号
     */
    private static com.surprising.aeron.service.matcher.MatchingResult awaitMatching(
            TradingCoreRuntime state, long sequence) {
        // 当前取得的同一 Matcher 事实，可能尚未完成账户结算
        com.surprising.aeron.service.matcher.MatchingResult matching = null;
        // 本段等待的 nanoTime 截止点，超时会停止轮询并断言
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            // 当前全局队首命令槽，未激活批次由等待辅助入口推进
            CommandSlot head = state.pendingMatching().get(state.pendingMatching().firstSequence());
            if (head != null && head.orderBatch != null && !head.orderBatch.activated())
                state.batches().activateOrderBatch(head.orderBatch, head, true);
            state.matchingProgress.drainMatchingCompletions();
            matching = CoreTestCompletion.takeMatchingResult(state, sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).isNotNull();
        return matching;
    }

    /**
     * 构造品种一、价格一千、数量一的限价买单，其他规则固定；reservedUnits 是当前未读取的辅助参数，实际预留由业务准入计算。
     * @param orderId 要构造或注入的订单编号
     * @param clientOrderId 该测试订单客户端号
     * @param reservedUnits 当前辅助方法不读取此值，实际资金预留由业务准入计算
     */
    private static PlaceOrderCommand place(long orderId, String clientOrderId, long reservedUnits) {
        return new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientOrderId);
    }

    /**
     * 构造指定方向、价格及数量的品种一限价单；当前实现不读取预留资产、预留金额和传入费率，实际规则仍来自 Core 配置和准入。
     * @param orderId 要构造或注入的订单编号
     * @param clientOrderId 该测试订单客户端号
     * @param side 测试订单买卖方向
     * @param priceTicks 委托价格整数刻度
     * @param quantitySteps 委托数量整数步数
     * @param reservationAsset 当前构造方法不读取此资产参数，实际冻结资产由产品规则决定
     * @param reservedUnits 当前辅助方法不读取此值，实际资金预留由业务准入计算
     * @param makerFeeRatePpm 当前构造方法不读取此挂单费率，实际费率来自 Core 配置
     * @param takerFeeRatePpm 当前构造方法不读取此吃单费率，实际费率来自 Core 配置
     */
    private static PlaceOrderCommand spotOrder(long orderId, String clientOrderId, CoreOrderSide side,
                                                long priceTicks, long quantitySteps, String reservationAsset,
                                                long reservedUnits, long makerFeeRatePpm, long takerFeeRatePpm) {
        return new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientOrderId);
    }

    /**
     * 通过异步完成入口注册本测试的现货品种一，并要求成功。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     */
    private static void applySpotInstrument(TradingCoreRuntime state) {
        // 本测试注册的现货或 U 本位永续品种配置
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0);
        assertThat(CoreTestCompletion.applyAsynchronously(state, new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.REGISTER_INSTRUMENT, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 9, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument, ProductLine.SPOT))).status())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 注册带名义价值限制的 U 本位永续品种一，再按独立价格来源写初始标记价，两个命令都要求成功。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     */
    private static void applyLinearPerpetualInstrument(TradingCoreRuntime state) {
        // 本测试注册的现货或 U 本位永续品种配置
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, 1_500, 0, 1_500,
                        List.of(new CoreRiskLimitBracket(1_500,
                        10_000_000, 100_000, 50_000))),
                0, 0, 0, -1, 0);
        assertThat(CoreTestCompletion.applyAsynchronously(state, new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                9, 1, 0, 1_000, 1), TradingCommandCodec.encodeRegisterInstrument(instrument, ProductLine.LINEAR_PERPETUAL))).status())
                .isEqualTo(ResponseStatus.APPLIED);
        assertThat(CoreTestCompletion.applyAsynchronously(state, new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.KAFKA_INPUT_BRIDGE,
                89, 1, 0, 1_000, 2), TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("1", 1_000, 1, 1_000), ProductLine.LINEAR_PERPETUAL))).status())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 构造指定方向、价量和客户号的品种一限价 GTC 订单；本测试也把该构造入口用于现货场景，不在这里计算保证金。
     * @param orderId 要构造或注入的订单编号
     * @param clientOrderId 该测试订单客户端号
     * @param side 测试订单买卖方向
     * @param priceTicks 委托价格整数刻度
     * @param quantitySteps 委托数量整数步数
     */
    private static PlaceOrderCommand linearOrder(long orderId, String clientOrderId,
                                                  CoreOrderSide side, long priceTicks,
                                                  long quantitySteps) {
        return new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, clientOrderId);
    }

    /**
     * 按来源序号一调整现货用户 USDT 余额，具体完成委托下一入口。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param userId 业务命令所属测试用户
     * @param units 余额调整的最小单位数量
     */
    private static void applyBalance(TradingCoreRuntime state, long userId, long units) {
        applyBalance(state, userId, units, 1);
    }

    /**
     * 按给定来源序号调整现货用户 USDT 余额。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param userId 业务命令所属测试用户
     * @param units 余额调整的最小单位数量
     * @param sourceSequence 本测试来源的递增命令序号
     */
    private static void applyBalance(TradingCoreRuntime state, long userId, long units, long sourceSequence) {
        applyBalance(state, userId, "USDT", units, sourceSequence);
    }

    /**
     * 提交指定现货用户及资产的余额调整，用异步完成入口等业务成功。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param userId 业务命令所属测试用户
     * @param asset 要调整的资产名称
     * @param units 余额调整的最小单位数量
     * @param sourceSequence 本测试来源的递增命令序号
     */
    private static void applyBalance(TradingCoreRuntime state, long userId, String asset, long units,
                                     long sourceSequence) {
        assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), sourceSequence,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)), userId))
                .status()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 提交指定产品线用户的 USDT 余额调整，用异步完成入口等成功。
     * @param state 当前场景要推进或读取的测试 Core 运行时
     * @param productLine 消息所属的独立产品线
     * @param userId 业务命令所属测试用户
     * @param units 余额调整的最小单位数量
     * @param sourceSequence 本测试来源的递增命令序号
     */
    private static void applyBalance(TradingCoreRuntime state, ProductLine productLine,
                                     long userId, long units, long sourceSequence) {
        assertThat(CoreTestCompletion.applyAsynchronously(state, command(productLine, CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                sourceSequence, TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand("USDT", units)), userId)).status())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 构造默认现货用户一千零一的测试消息，委托带用户入口。
     * @param type 原 Core 消息类型
     * @param commandId 原命令 UUID，幂等重放保持不变
     * @param sourceSequence 本测试来源的递增命令序号
     * @param payload 已经编码的命令载荷
     */
    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence, byte[] payload) {
        return command(type, commandId, sourceSequence, payload, 1001);
    }

    /**
     * 为指定用户构造现货测试消息，委托完整消息入口。
     * @param type 原 Core 消息类型
     * @param commandId 原命令 UUID，幂等重放保持不变
     * @param sourceSequence 本测试来源的递增命令序号
     * @param payload 已经编码的命令载荷
     * @param userId 业务命令所属测试用户
     */
    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence,
                                       byte[] payload, long userId) {
        return command(ProductLine.SPOT, type, commandId, sourceSequence, payload, userId);
    }

    /**
     * 为指定产品线构造默认用户一千零一的测试消息。
     * @param productLine 消息所属的独立产品线
     * @param type 原 Core 消息类型
     * @param commandId 原命令 UUID，幂等重放保持不变
     * @param sourceSequence 本测试来源的递增命令序号
     * @param payload 已经编码的命令载荷
     */
    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload) {
        return command(productLine, type, commandId, sourceSequence, payload, 1001);
    }

    /**
     * 构造 Gateway 来源七十七的消息，固定业务时间一千，使用给定来源序号同时作为测试日志位置，再附上原载荷。
     * @param productLine 消息所属的独立产品线
     * @param type 原 Core 消息类型
     * @param commandId 原命令 UUID，幂等重放保持不变
     * @param sourceSequence 本测试来源的递增命令序号
     * @param payload 已经编码的命令载荷
     * @param userId 业务命令所属测试用户
     */
    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload, long userId) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.GATEWAY, 77, sourceSequence, userId, 1_000, sourceSequence), payload);
    }

    /**
     * 构造默认现货用户增加一个 USDT 最小单位的命令，只用于检查故障后是否禁止继续写入。
     * @param commandId 原命令 UUID，幂等重放保持不变
     * @param sequence 故障后探测命令使用的来源序号及测试日志位置
     */
    private static CoreMessage probe(UUID commandId, long sequence) {
        return command(CoreMessageType.ADJUST_BALANCE, commandId, sequence,
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
