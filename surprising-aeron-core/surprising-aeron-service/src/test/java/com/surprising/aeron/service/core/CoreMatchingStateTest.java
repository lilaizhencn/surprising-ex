package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.support.metrics.CoreLaneMetrics;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六产品线核心撮合与账户结算测试，覆盖准入、在途幂等、部分成交、自动撤单、费用、持仓与内存原生快照恢复。固定金额断言和双状态/哈希对照各自说明覆盖；不启动真实 Cluster。多个订单辅助参数没有进入实际载荷，注释按当前代码明确标出，避免把调用字面值当作输入覆盖。
 */
class CoreMatchingStateTest {

    /**
     * 按本样例整数金额口径构造五百万名义额，在足额资金和深度下完成 U 本位永续/交割开仓，核对预留、双边持仓及总额；调整到五倍杠杆后核对保证金，并以调整前后内存快照重放对照。不据此推断生产账户限额。
     * @param product 本用例参数化的 U 本位永续或交割产品线
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY"})
    void fiveMillionNotionalCanFormPositionsWhenTestCapitalAndDepthAreAvailable(ProductLine product) {
        // 本样例按一亿倍整数口径构造金额，使用测试注册配置、注入资金和深度；不据此推断生产账户限额。
        // 按样例一亿倍整数口径构造的名义金额，供数量与资金量参照。
        long notional = 5_000_000L * 100_000_000L;
        // 由本样例名义额除价格得到的订单数量。
        long quantity = notional / 100;
        // 每个用户实际注入的初始结算资产金额。
        long capital = 1_000_000L * 100_000_000L;
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(product)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", capital)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", capital)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, quantity, ReservationKind.DERIVATIVE_MARGIN, "USDT", notional));
            apply(state, 4, 11, CoreMessageType.AMEND_ORDER,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(101, 102, "maker-refreshed",
                            100L, quantity, CoreTimeInForce.GTX, true)));
            assertThat(state.tradingState().order(102).remainingQuantitySteps()).isEqualTo(quantity);
            assertThat(state.tradingState().user(11).balances().get("USDT").lockedUnits())
                    // 本样例准入按价格带上界一百零一及十倍杠杆计算预留。
                    .isEqualTo(quantity * 101 / 10);
            // 大额市价开仓完成后的响应。
            var filled = apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(201, CoreOrderSide.BUY, 0, quantity, ReservationKind.DERIVATIVE_MARGIN, "USDT", notional,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 101, false));
            assertThat(orderIn(filled, 201).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().user(11).positions().get("1").signedQuantitySteps()).isEqualTo(-quantity);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(quantity);
            assertThat(total(state, "USDT")).isEqualTo(capital * 2);
            // 调整杠杆前保存的内存快照字节。
            byte[] beforeLeverage = state.snapshot();
            // 把全仓杠杆改为五倍的编码命令。
            byte[] leverage = TradingCommandCodec.encodeUpdateLeverage(
                    new com.surprising.aeron.protocol.UpdateLeverageCommand("1",
                            com.surprising.aeron.protocol.CoreMarginMode.CROSS, 5_000_000, true));
            apply(state, 6, 11, CoreMessageType.UPDATE_LEVERAGE, leverage);
            assertThat(state.tradingState().user(11).positions().get("1").positionMarginUnits()).isEqualTo(capital);
            assertThat(state.tradingState().user(11).balances().get("USDT").availableUnits()).isZero();
            assertThat(total(state, "USDT")).isEqualTo(capital * 2);
            try (/* 从调整前快照恢复、重放同一杠杆命令的对照实例。 */ var replay = TradingCoreRuntime.fromSnapshot(product, beforeLeverage)) {
                apply(replay, 6, 11, CoreMessageType.UPDATE_LEVERAGE, leverage);
                assertThat(replay.tradingState()).isEqualTo(state.tradingState());
            }
            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
            }
        }
    }

    /**
     * 两档卖单前提交买三份的市价单，核对实际只成交一份、第二档仍有两份，资金总额及内存快照/Matcher 哈希一致。辅助参数 matchingPriceTicks 未写入请求，不能单凭传入的一百零一证明显式保护价覆盖。
     * @param product 本用例参数化的 U 本位永续或交割产品线
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY"})
    void marketPriceBandProducesOnlyFundedPositionsAndSurvivesSnapshot(ProductLine product) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(product)) {
            applyInstrument(state);
            for (int /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new int[]{11, 22}) {
                apply(state, user == 11 ? 1 : 2, user, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            }
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(102, CoreOrderSide.SELL, 101, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 202));
            // 执行当前市价单之前按本测试口径计算的资产总量。
            long before = total(state, "USDT");
            // 市价买单完成后包含实际成交数量的命令响应。
            var result = apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(201, CoreOrderSide.BUY, 0, 3, ReservationKind.DERIVATIVE_MARGIN, "USDT", 303,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 101, false));
            assertThat(orderIn(result, 201).executedQuantitySteps()).isEqualTo(1);
            assertThat(state.tradingState().order(102).remainingQuantitySteps()).isEqualTo(2);
            assertThat(total(state, "USDT")).isEqualTo(before);
            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                assertThat(awaitMatchingHash(restored)).isEqualTo(awaitMatchingHash(state));
            }
        }
    }

    /**
     * 先确认通知探测为空，再反射向账户 Lane 故障槽注入异常，要求下一次探测原样抛出该异常，finally 清空测试故障。
     */
    @Test
    void emptyNotificationProbeStillDetectsAFailedLaneWithoutACompletion() throws Exception {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            var signal = AccountLaneTestAccess.failure(state.runtimeState());
            // 本用例注入且希望下一次通知探测原样抛出的异常。
            var failure = new IllegalStateException("injected lane failure before completion publication");
            // 比当前纳秒时间晚一秒的测试探测时刻，随后再人为推进探测时间。
            long now = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            assertThat(state.matchingProgress.hasNotifications(now)).isFalse();
            signal.compareAndSet(null, failure);
            try {
                assertThatThrownBy(() -> state.matchingProgress.hasNotifications(now + 1_000_000)).isSameAs(failure);
            } finally {
                signal.set(null);
            }
        }
    }

    /**
     * 先提交正常在途订单，再提交因标记价过期而同步拒绝的订单，完成前缀后显式收集拒绝回执，要求收集一条且在途队列清空。
     */
    @Test
    void inFlightSynchronousRejectionIsPublishedAsReadyAndDoesNotBlockThePendingHead() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            // 先进入在途队列的正常订单命令。
            CoreMessage first = message(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
            assertThat(submit(state, first).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);

            // 在旧标记价下提交、预期同步拒绝的后续命令。
            CoreMessage stale = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                    UUID.randomUUID(), state.productLine(), CommandSource.GATEWAY, 77, 4, 22,
                    6_001, 4), place(202, CoreOrderSide.BUY, 100, 1,
                    ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
            assertThat(submit(state, stale).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);

            // 在途正常前缀命令最终完成响应。
            CoreResponse firstCompleted = CoreTestCompletion.completeMatchingSynchronously(state,
                    state.matchingSequence(first.header().commandId()), 6_001, 4);
            assertThat(firstCompleted).isNotNull();
            // 保存一次提交回调响应的单元素数组。
            CoreResponse[] rejected = new CoreResponse[1];
            // 本次显式收集到的终态响应条数，应为一。
            int completed = state.commits.commitReadyMatching(1, 6_001, 4, true,
                    (/* 提交回调附带的命令序号，此断言只取响应而不读取该参数。 */ sequence, /* 当前准入、回调或最终命令响应。 */ response) -> rejected[0] = response);

            assertThat(completed).isOne();
            assertThat(rejected[0]).isNotNull();
            assertThat(rejected[0].resultCode()).isEqualTo(CoreResultCode.STALE_MARK_PRICE);
            assertThat(state.pendingMatching().size()).isZero();
        }
    }

    /**
     * 相同在途命令重发返回 DUPLICATE 且仍 MATCHING_PENDING，相同 UUID 不同载荷返回冲突；在途期间不写终态结果表，显式完成后才出现已提交结果。
     */
    @Test
    void inFlightIdempotencyUsesPendingIndexWithoutPollutingTerminalResultLedger() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 7, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            // 本次在途幂等场景的原始订单命令。
            CoreMessage command = message(state, 2, 7, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.BUY, 100, 1,
                            ReservationKind.SPOT_ASSET, "USDT", 100));

            // 命令尚未完成撮合与账户提交时的在途响应。
            CoreResponse pending = submit(state, command);
            // 原命令 UUID 与载荷再次发送时返回的重复响应。
            CoreResponse duplicate = submit(state, command);
            // 沿用原请求头却改成另一订单载荷的冲突命令。
            CoreMessage conflicting = new CoreMessage(command.header(),
                    place(102, CoreOrderSide.BUY, 100, 1,
                            ReservationKind.SPOT_ASSET, "USDT", 100));
            // 同身份不同载荷的幂等冲突响应。
            CoreResponse conflict = submit(state, conflicting);

            assertThat(state.commandResults()).doesNotContainKey(command.header().commandId());
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.OK);
            assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(duplicate.appliedCommandCount()).isEqualTo(pending.appliedCommandCount());
            assertThat(conflict.resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);

            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, pending, command);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.commandResults()).containsKey(command.header().commandId());
        }
    }

    /**
     * 不同账户 Lane 的 IOC 买五份实际成交两份，在途时不推进已提交 Core 序号；完成后未用计价资金释放、买到两份 BTC，客户编号终态保留，两侧应用/提交指标达到同一投影序号。
     */
    @Test
    void crossLaneIocPartialFillCommitsBothOwnersAndReleasesUnusedFunds() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 7, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 9, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            apply(state, 3, 7, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.SPOT_ASSET, "BTC", 2));
            // 跨两个账户 Lane、买五份但只可成交两份的 IOC 命令。
            CoreMessage crossing = message(state, 4, 9, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 5, ReservationKind.SPOT_ASSET, "USDT", 500,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false));
            // 提交 IOC 前的已提交 Core 命令水位。
            long committedBefore = state.committedCoreSequence();
            // 命令尚未完成撮合与账户提交时的在途响应。
            CoreResponse pending = submit(state, crossing);

            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(state.committedCoreSequence()).isEqualTo(committedBefore);

            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, pending, crossing);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);

            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.terminalRetention().containsOrder(202, 9, "client-202")).isTrue();
            // 终态客户编号尚保留时尝试复用该编号的新订单命令。
            CoreMessage duplicateClientId = message(state, 5, 9, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                            203, "1", CoreOrderSide.BUY, 100, 1, false,
                            com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                            com.surprising.aeron.protocol.CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, false, "client-202")));
            assertThat(submit(state, duplicateClientId).status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().user(9).balances().get("USDT").availableUnits()).isEqualTo(300);
            assertThat(state.tradingState().user(9).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().user(9).totalUnits("BTC")).isEqualTo(2);
            // 挂单用户按当前拓扑路由到的账户 Lane。
            int makerLane = state.runtimeState.topology().accountLaneId(7);
            // 主动用户按当前拓扑路由到的另一账户 Lane。
            int takerLane = state.runtimeState.topology().accountLaneId(9);
            assertThat(makerLane).isNotEqualTo(takerLane);
            // 完成后读取的 Lane 应用与提交水位指标。
            CoreLaneMetrics metrics = state.laneMetrics();
            assertThat(metrics.accountLaneAppliedSequences()[makerLane]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneCommittedSequences()[makerLane]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneAppliedSequences()[takerLane]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(metrics.accountLaneCommittedSequences()[takerLane]).isEqualTo(state.snapshotProjectionSequence());
            assertThat(state.tradingState().orders().values())
                    .noneMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN);
        }
    }

    /**
     * 会吃单的现货 GTX 请求被拒后保留 REJECTED 订单记录，并释放买方全部冻结；原卖单仍为唯一 OPEN。不能把方法名中的 no order 理解为拒单记录也被删除。
     */
    @Test
    void postOnlyRejectionLeavesNoOrderOrReservedFunds() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.SPOT_ASSET, "BTC", 2));

            // 会与现有挂单相交、预期拒绝的 GTX 命令。
            CoreMessage crossingPostOnly = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 1, ReservationKind.SPOT_ASSET, "USDT", 100,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTX, 100, true));
            // 命令尚未完成撮合与账户提交时的在途响应。
            CoreResponse pending = submit(state, crossingPostOnly);
            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, pending, crossingPostOnly);
            assertThat(completed.status()).isEqualTo(ResponseStatus.REJECTED);

            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.REJECTED);
            assertThat(state.tradingState().user(22).balances().get("USDT").availableUnits()).isEqualTo(500);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().orders().values())
                    .filteredOn(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN)
                    .extracting(CoreOrderState::orderId)
                    .containsExactly(101L);
        }
    }

    /**
     * 完成一笔未成交的现货挂单后，核对回执带 OPEN 状态及剩余两份、无成交事实，账户冻结二百且当前订单仍 OPEN；本用例没有在中途观察未完成状态导出。
     */
    @Test
    void finalMatcherFactCarriesTheRestingOrderWithoutExportingPendingState() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            // 要挂两份买单、最终应保持 OPEN 的原命令帧。
            CoreMessage order = message(state, 2, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));

            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, submit(state, order), order);

            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            // 当前最终回执解码得到的命令订单及执行结果。
            var result = CoreCommandResultCodec.decode(completed.data());
            assertThat(orderIn(completed, 202).status()).isEqualTo("OPEN");
            assertThat(orderIn(completed, 202).remainingQuantitySteps()).isEqualTo(2);
            assertThat(result.executions()).isEmpty();
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isEqualTo(200);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.OPEN);
        }
    }

    /**
     * 现货市价 IOC 成交后核对回执价一百、FILLED 终态、运行时订单已退休且无冻结或 OPEN 单；辅助 matchingPriceTicks 参数没有单独编码。
     */
    @Test
    void marketOrderUsesProtectionPriceAndNeverRestsOnBook() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 101)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1, ReservationKind.SPOT_ASSET, "BTC", 1));
            // 当前第四条业务命令完成后的回执，供读取已退休订单终态。
            CoreResponse receipt4 = apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 1, ReservationKind.SPOT_ASSET, "USDT", 100,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 100, false));

            // 本现货回执保留成交保护边界；市价 IOC 在成交或撤量后不会继续挂簿。
            assertThat(orderIn(receipt4, 202).priceTicks()).isEqualTo(100);
            assertThat(orderIn(receipt4, 202).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().orders().values())
                    .noneMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN);
        }
    }

    /**
     * 成功现货部分成交后核对双方 BTC/USDT、挂单剩余、主动单退休及原生哈希变化；快照恢复后继续吃完挂单。这里只验证成功完成后的状态，没有中途故障注入来证明原子回滚。
     */
    @Test
    void spotMatchUpdatesBothUsersFundsOrdersAndNativeMatcherAtomically() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, ReservationKind.SPOT_ASSET, "BTC", 5));
            // 主动成交前原生挂单订单簿的哈希。
            int restingBookHash = awaitMatchingHash(state);
            // 当前第四条业务命令完成后的回执，供读取已退休订单终态。
            CoreResponse receipt4 = apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 3, ReservationKind.SPOT_ASSET, "USDT", 300));

            assertThat(state.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(101).remainingQuantitySteps()).isEqualTo(2);
            assertThat(orderIn(receipt4, 202).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().user(11).totalUnits("BTC")).isEqualTo(7);
            assertThat(state.tradingState().user(11).totalUnits("USDT")).isEqualTo(300);
            assertThat(state.tradingState().user(22).totalUnits("BTC")).isEqualTo(3);
            assertThat(state.tradingState().user(22).totalUnits("USDT")).isEqualTo(700);
            assertThat(state.tradingState().user(11).totalUnits("BTC")
                    + state.tradingState().user(22).totalUnits("BTC")).isEqualTo(10);
            assertThat(state.tradingState().user(11).totalUnits("USDT")
                    + state.tradingState().user(22).totalUnits("USDT")).isEqualTo(1_000);
            // 成功成交后原生订单簿的哈希。
            int matchedBookHash = awaitMatchingHash(state);
            assertThat(matchedBookHash).isNotEqualTo(restingBookHash).isNotZero();

            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                assertThat(awaitMatchingHash(restored)).isEqualTo(matchedBookHash);
                apply(restored, 5, 22, CoreMessageType.PLACE_ORDER,
                        place(203, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));
                assertThat(restored.terminalRetention().containsOrder(101, 11, "")).isTrue();
                assertThat(restored.tradingState().order(101)).isNull();
                assertThat(restored.tradingState().orders().values())
                        .noneMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN);
            }
        }
    }

    /**
     * 六产品线分别构造同用户反向相交单，要求先撤原挂单、新单不自成交而继续 OPEN；核对旧预留释放、资产总额及衍生品没有生成自成交持仓。
     * @param productLine 本用例当前独立产品线
     */
    @ParameterizedTest
    @MethodSource("allProductLines")
    void sameUserCrossCancelsTheRestingOrderBeforeSubmittingTheTaker(ProductLine productLine) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            applyInstrument(state);
            // 按产品选择的测试预留用途参照，辅助编码器不把它写成请求字段。
            ReservationKind reservationKind = productLine == ProductLine.SPOT
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            // 本场景卖方初始入账与预留参照使用的资产。
            String sellerAsset = productLine == ProductLine.SPOT ? "BTC" : settleAsset(productLine);
            // 本场景买方初始入账与预留参照使用的资产。
            String buyerAsset = productLine == ProductLine.SPOT ? "USDT" : settleAsset(productLine);
            // 同用户自成交场景实际注入的卖方资产金额。
            long initialSellerUnits = productLine == ProductLine.SPOT ? 10 : 20_000;
            // 现货同用户场景另行注入的计价资产金额，衍生品沿用同一结算钱包。
            long initialBuyerUnits = productLine == ProductLine.SPOT ? 1_000 : 20_000;
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand(sellerAsset, initialSellerUnits)));
            if (productLine == ProductLine.SPOT) {
                apply(state, 2, 11, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(
                                new BalanceAdjustmentCommand(buyerAsset, initialBuyerUnits)));
            }
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000));

            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 3, reservationKind, buyerAsset,
                            productLine == ProductLine.SPOT ? 300 : 1_000));

            assertThat(state.tradingState().order(101)).isNull();
            assertThat(state.terminalRetention().containsOrder(101, 11, "")).isTrue();
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(202).executedQuantitySteps()).isZero();
            if (productLine == ProductLine.SPOT) {
                assertThat(state.tradingState().user(11).balances().get("BTC").availableUnits()).isEqualTo(10);
                assertThat(state.tradingState().user(11).balances().get("BTC").lockedUnits()).isZero();
                assertThat(state.tradingState().user(11).balances().get("USDT").availableUnits()).isEqualTo(700);
                assertThat(state.tradingState().user(11).balances().get("USDT").lockedUnits()).isEqualTo(300);
            } else {
                // 当前衍生品用户在结算资产上的可用及冻结余额。
                var balance = state.tradingState().user(11).balances().get(buyerAsset);
                assertThat(balance.lockedUnits()).isPositive();
                assertThat(Math.addExact(balance.availableUnits(), balance.lockedUnits())).isEqualTo(20_000);
                assertThat(state.tradingState().user(11).positions()).isEmpty();
            }
        }
    }

    /**
     * 在四条非期权衍生品线上成交后，核对无 OPEN 订单、若仍有订单须为 FILLED，双方形成等量反向持仓且保证金冻结为正。
     * @param productLine 本用例当前独立产品线
     */
    @ParameterizedTest
    @MethodSource("derivativeLines")
    void nonOptionDerivativeLinesCreatePositionMarginFromMatchedReservations(ProductLine productLine) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            applyInstrument(state);
            // 本产品实际使用的结算资产名称。
            String settleAsset = settleAsset(productLine);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, settleAsset, 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, settleAsset, 200));

            assertThat(state.tradingState().orders().values())
                    .allMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.FILLED);
            assertThat(state.tradingState().orders().values())
                    .noneMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN);
            assertThat(state.tradingState().user(11).positions().get("1").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().user(11).balances().get(settleAsset).lockedUnits()).isPositive();
            assertThat(state.tradingState().user(22).balances().get(settleAsset).lockedUnits()).isPositive();
        }
    }

    /**
     * 八个 Maker 与一个 Taker 成交，显式等到最终响应后核对 Taker 八份、各 Maker 负一份及总额一万八千。未阻塞某个 Lane 来直接观察提前发布，因此最终断言不单独证明中途发布栅栏。
     */
    @Test
    void multiMakerPerpetualSettlementPublishesOnlyAfterTheUnifiedLaneBarrier() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            // 构造多 Maker 前置输入时递增的源序号，或等待撮合所用的在途序号。
            long sequence = 1;
            for (/* 本场景八个挂单用户中的当前用户编号。 */ long makerId = 11; makerId < 19; makerId++) {
                apply(state, sequence++, makerId, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(
                                new BalanceAdjustmentCommand("USDT", 2_000)));
                apply(state, sequence++, makerId, CoreMessageType.PLACE_ORDER,
                        place(1_000 + makerId, CoreOrderSide.SELL, 100, 1,
                                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100));
            }
            apply(state, sequence++, 99, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", 2_000)));
            // 要与八个 Maker 一次成交的主动订单命令。
            CoreMessage taker = message(state, sequence, 99, CoreMessageType.PLACE_ORDER,
                    place(2_000, CoreOrderSide.BUY, 100, 8,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 800));
            // 命令尚未完成撮合与账户提交时的在途响应。
            CoreResponse pending = submit(state, taker);
            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            // 主动订单在原命令窗口中的在途撮合序号。
            long matchingSequence = state.matchingSequence(taker.header().commandId());
            // 已经由 Matcher 发布、仍待账户结算的原撮合事实。
            var matching = CoreTestCompletion.awaitMatchingResult(state, matchingSequence);
            assertThat(matching).isNotNull();
            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = completeUntilTerminalOrFailure(state, matchingSequence, matching,
                    taker.header().submittedAtEpochMillis(), taker.header().sourceSequence());
            assertThat(completed).isNotNull();
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(99).positions()).isNotEmpty();
            for (/* 本场景八个挂单用户中的当前用户编号。 */ long makerId = 11; makerId < 19; makerId++) {
                assertThat(state.tradingState().user(makerId).positions()).isNotEmpty();
            }
            assertThat(state.tradingState().user(99).positions().get("1").signedQuantitySteps())
                    .isEqualTo(8);
            for (/* 本场景八个挂单用户中的当前用户编号。 */ long makerId = 11; makerId < 19; makerId++) {
                assertThat(state.tradingState().user(makerId).positions().get("1").signedQuantitySteps())
                        .isEqualTo(-1);
            }
            assertThat(total(state, "USDT")).isEqualTo(18_000);
        }
    }

    /**
     * 期权两份成交后核对两订单终态保留并退休，双方持仓正负二，卖方总额一千二、买方八百，验证本样例权利金转移。
     */
    @Test
    void optionFillTransfersPremiumAndCreatesBuyerSellerPositions() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.OPTION)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 300));
            // 当前第四条业务命令完成后的回执，供读取已退休订单终态。
            CoreResponse receipt4 = apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            assertThat(state.terminalRetention().containsOrder(101, 11, "")).isTrue();
            assertThat(state.tradingState().order(101)).isNull();
            assertThat(orderIn(receipt4, 202).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().user(11).positions().get("1").signedQuantitySteps()).isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(11).totalUnits("USDT")).isEqualTo(1_200);
            assertThat(state.tradingState().user(22).totalUnits("USDT")).isEqualTo(800);
        }
    }

    /**
     * 先开等量反向仓，过量只减仓单须按容量不足拒绝，再以三份反向单平掉两份并反开一份，核对方向、开仓价一百一十及测试资金总额。
     */
    @Test
    void derivativeCloseReverseAndReduceOnlyCapacityAreAuthoritative() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            // 数量超过当前可平持仓的只减仓请求。
            CoreMessage tooLarge = message(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 3, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            assertThat(drainMatching(state, submit(state, tooLarge), tooLarge).resultCode().name())
                    .isEqualTo("REDUCE_ONLY_CAPACITY_EXCEEDED");

            apply(state, 6, 11, CoreMessageType.PLACE_ORDER,
                    place(301, CoreOrderSide.BUY, 110, 3,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 40));
            apply(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    place(302, CoreOrderSide.SELL, 110, 3,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 40));

            assertThat(state.tradingState().user(11).positions().get("1").signedQuantitySteps()).isEqualTo(1);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(-1);
            assertThat(state.tradingState().user(11).positions().get("1").entryPriceTicks()).isEqualTo(110);
            assertThat(state.tradingState().user(22).positions().get("1").entryPriceTicks()).isEqualTo(110);
            // 当前样例已合计的单资产资金量。
            long total = state.tradingState().user(11).totalUnits("USDT")
                    + state.tradingState().user(22).totalUnits("USDT")
                    + state.tradingState().treasuryState().insuranceBalances().getOrDefault("USDT", 0L);
            assertThat(total).isEqualTo(4_000);
        }
    }

    /**
     * 普通平仓方向挂单保持原两份持仓不变，新预留实际大于一。辅助器不编码 reservedUnits，验证的是核心自行计算预留，不能说核心覆盖了客户请求中的一单位预留字段。
     */
    @Test
    void coreDerivesTheFullDerivativeCloseReservation() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));

            // 普通平仓方向请求，可能保持挂单或以市价实际平仓。
            CoreMessage close = message(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));

            // 当前准入、回调或最终命令响应。
            CoreResponse response = drainMatching(state, submit(state, close), close);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().user(22).reservations().get(203L).reservedUnits()).isGreaterThan(1);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps())
                    .isEqualTo(2);
        }
    }

    /**
     * 分别走异步辅助入口和显式收尾，要求普通平仓挂单优先撤销最新冲突只减仓单，较早只减仓单仍开着；随后用 IOC 买完可平数量，核对旧/新挂单全部退休且原持仓归零。
     * @param asynchronous 是否用异步完成辅助入口，false 则使用 submit 加 drainMatching 显式完成
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void normalCloseCancelsNewestConflictingReduceOnlyOrderBeforeMatcherSubmission(boolean asynchronous) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            apply(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(204, CoreOrderSide.SELL, 120, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));

            // 普通平仓方向请求，可能保持挂单或以市价实际平仓。
            var close = message(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    place(205, CoreOrderSide.SELL, 130, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            // 当前准入、回调或最终命令响应。
            var response = asynchronous ? CoreTestCompletion.applyAsynchronously(state, close)
                    : drainMatching(state, submit(state, close), close);
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);

            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.terminalRetention().containsOrder(204, 22, "")).isTrue();
            assertThat(state.tradingState().order(204)).isNull();
            assertThat(state.tradingState().order(205).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(22).reservations()).doesNotContainKey(204L);
            // 随后 IOC 买入两份的回执，用来核对旧只减仓与新平仓挂单均已结束。
            CoreResponse receipt8 = apply(state, 8, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(301, CoreOrderSide.BUY, 130, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 130, false, 0, 0));
            assertThat(orderIn(receipt8, 301).status()).isEqualTo("FILLED");
            assertThat(orderIn(receipt8, 301).executedQuantitySteps()).isEqualTo(2);
            assertThat(state.terminalRetention().containsOrder(203, 22, "")).isTrue();
            assertThat(state.tradingState().order(203)).isNull();
            assertThat(state.terminalRetention().containsOrder(204, 22, "")).isTrue();
            assertThat(state.tradingState().order(204)).isNull();
            assertThat(state.terminalRetention().containsOrder(205, 22, "")).isTrue();
            assertThat(state.tradingState().order(205)).isNull();
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isZero();
        }
    }

    /**
     * 超大普通平仓方向请求因可用不足拒绝时，要求原冲突订单和余额完整保留、不增加新预留；同请求重发仍返回重复拒绝。
     */
    @Test
    void rejectedLaneCloseAdmissionKeepsConflictingOrdersAndFunds() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            for (long /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new long[]{11, 22})
                apply(state, user == 11 ? 1 : 2, user, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 110, 2, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            // 拒绝准入之前保存的用户余额表参照。
            var balances = state.tradingState().user(22).balances();
            // 当前准入、自动撤单或 GTX 失败场景的待测请求。
            var request = message(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(204, CoreOrderSide.SELL, 130, 1_000_000, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            // 当前拒绝命令的终态响应。
            var rejected = CoreTestCompletion.applyAsynchronously(state, request);
            assertThat(rejected.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            assertThat(state.tradingState().order(203).remainingQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().order(204)).isNull();
            assertThat(state.tradingState().user(22).balances()).isEqualTo(balances);
            assertThat(state.tradingState().user(22).reservations()).containsKey(203L).doesNotContainKey(204L);
            assertThat(total(state, "USDT")).isEqualTo(4_000);
            // 原命令 UUID 与载荷再次发送时返回的重复响应。
            var duplicate = CoreTestCompletion.applyAsynchronously(state, request);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        }
    }

    /**
     * 分别走两种完成入口，核对平仓容量冲突单和自己的相交单都被撤销，新单不自成交、持仓不变、只留新预留且无致命错误；没有重发原请求，未独立验证重复回放恰好一次。
     * @param asynchronous 是否用异步完成辅助入口，false 则使用 submit 加 drainMatching 显式完成
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void laneCloseAndOwnerSelfTradeCancellationsAreAppliedOnce(boolean asynchronous) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            for (long /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new long[]{11, 22})
                apply(state, user == 11 ? 1 : 2, user, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 130, 2, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            apply(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(301, CoreOrderSide.BUY, 110, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            // 当前准入、自动撤单或 GTX 失败场景的待测请求。
            var request = message(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    place(204, CoreOrderSide.SELL, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            // 当前准入、回调或最终命令响应。
            var response = asynchronous ? CoreTestCompletion.applyAsynchronously(state, request)
                    : drainMatching(state, submit(state, request), request);
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().order(203)).isNull();
            assertThat(state.tradingState().order(301)).isNull();
            assertThat(state.tradingState().order(204).executedQuantitySteps()).isZero();
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(22).reservations()).doesNotContainKeys(203L, 301L).containsKey(204L);
            assertThat(total(state, "USDT")).isEqualTo(4_000);
            assertThat(state.fatalFailure).isNull();
        }
    }

    /**
     * 普通平仓 GTX 放新失败时，已获 Lane 授权的冲突只减仓单仍撤销，另一用户挂单保持不变，新单留 REJECTED 记录且无预留，资金和原持仓不变。
     */
    @Test
    void postOnlyCloseFailureCommitsOnlyLaneAuthorizedCancellations() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            for (long /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new long[]{11, 22})
                apply(state, user == 11 ? 1 : 2, user, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(203, CoreOrderSide.SELL, 130, 2, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1));
            apply(state, 6, 11, CoreMessageType.PLACE_ORDER,
                    place(301, CoreOrderSide.BUY, 110, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            // 当前准入、自动撤单或 GTX 失败场景的待测请求。
            var request = message(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(204, CoreOrderSide.SELL, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTX, 110, true, 0, 0));
            // 当前拒绝命令的终态响应。
            var rejected = CoreTestCompletion.applyAsynchronously(state, request);
            assertThat(rejected.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(state.tradingState().order(203)).isNull();
            assertThat(state.tradingState().order(204).status()).isEqualTo(CoreOrderStatus.REJECTED);
            assertThat(state.tradingState().order(301).remainingQuantitySteps()).isEqualTo(1);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().user(22).reservations()).doesNotContainKeys(203L, 204L);
            assertThat(total(state, "USDT")).isEqualTo(4_000);
            assertThat(state.fatalFailure).isNull();
        }
    }

    /**
     * 同用户两笔相交卖单被撤销，另一用户不相交卖单保留，新买单零成交；核对两用户总额与空持仓，并在内存快照恢复后比较业务哈希。
     */
    @Test
    void selfTradeScanCancelsOnlyCrossingOwnOrdersAndRestoresWithoutTradingWithSelf() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            for (long /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new long[]{11, 22}) {
                apply(state, user == 11 ? 1 : 2, user, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            }
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(303, CoreOrderSide.SELL, 105, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.SELL, 110, 1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            apply(state, 6, 11, CoreMessageType.PLACE_ORDER,
                    place(400, CoreOrderSide.BUY, 105, 2, ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            assertThat(state.tradingState().order(101)).isNull();
            assertThat(state.tradingState().order(303)).isNull();
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(400).executedQuantitySteps()).isZero();
            assertThat(state.tradingState().user(11).reservations()).doesNotContainKeys(101L, 303L);
            for (long /* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user : new long[]{11, 22}) {
                assertThat(state.tradingState().user(user).balances().get("USDT").totalUnits()).isEqualTo(2_000);
                assertThat(state.tradingState().user(user).positions()).isEmpty();
            }
            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }

    /**
     * 通过币对注册配置指定负 Maker 费率和正 Taker 费率，成交后核对双边仓位、终态退休、财库净手续费十及测试资金守恒。订单辅助器的费率传参并未进入订单载荷。
     */
    @Test
    void linearPerpetualMatchConservesFundsWithMakerTakerFees() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state, -50_000, 100_000);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, -50_000, 0));

            // 当前带费成交或 GTX 请求之前的单资产资金总额。
            long fundsBefore = total(state, "USDT");
            // 当前第四条业务命令完成后的回执，供读取已退休订单终态。
            CoreResponse receipt4 = apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false, 0, 100_000));

            assertThat(state.terminalRetention().containsOrder(101, 11, "")).isTrue();
            assertThat(state.tradingState().order(101)).isNull();
            assertThat(orderIn(receipt4, 202).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().user(11).positions().get("1").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(state.tradingState().treasuryState().feeBalances()).containsEntry("USDT", 10L);
            assertThat(total(state, "USDT")).isEqualTo(fundsBefore);
        }
    }

    /**
     * 先开多仓并挂一百一十买单，再市价卖出主动平仓，核对完全成交、持仓和冻结归零、预留释放及总额不变；辅助参数九十未编码，不能据该字面值证明显式卖出保护价场景。
     */
    @Test
    void linearPerpetualActiveCloseAcceptsBetterFillThanSellMarketProtectionPrice() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 33, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, 0, 100_000));
            apply(state, 5, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 0, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 100, false, 0, 100_000));
            apply(state, 6, 33, CoreMessageType.PLACE_ORDER,
                    placeWithFees(303, CoreOrderSide.BUY, 110, 1, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 110, false, 0, 100_000));

            // 主动平仓之前的单资产资金总额。
            long fundsBeforeClose = total(state, "USDT");
            // 第七条继续成交或主动平仓命令的终态回执。
            CoreResponse receipt7 = apply(state, 7, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(204, CoreOrderSide.SELL, 0, 1, true,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000,
                            CoreOrderType.MARKET, CoreTimeInForce.IOC, 90, false, 0, 100_000));

            assertThat(orderIn(receipt7, 204).status()).isEqualTo("FILLED");
            assertThat(orderIn(receipt7, 204).executedQuantitySteps()).isEqualTo(1);
            assertThat(state.tradingState().order(204)).isNull();
            assertThat(state.tradingState().user(22).positions().get("1").signedQuantitySteps()).isZero();
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.tradingState().user(22).reservations()).doesNotContainKey(204L);
            assertThat(total(state, "USDT")).isEqualTo(fundsBeforeClose);
        }
    }

    /**
     * 六产品线使用注册的非零 Maker/Taker 费率成交，要求终态退休、财库手续费增加且当前结算资产测试总额不变。
     * @param productLine 本用例当前独立产品线
     */
    @ParameterizedTest
    @MethodSource("allProductLines")
    void everyProductLineMatchConservesSettlementFundsWithNonzeroFees(ProductLine productLine) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            applyInstrument(state, 100_000, 200_000);
            // 六产品线手续费守恒场景当前使用的结算资产名称。
            String settlementAsset = settleAsset(productLine);
            // 当前是否现货，决定实际入账和预留参照资产。
            boolean spot = productLine == ProductLine.SPOT;
            // 按产品选择的测试预留用途参照，辅助编码器不把它写成请求字段。
            ReservationKind reservationKind = spot
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            // 本场景卖方初始入账与预留参照使用的资产。
            String sellerAsset = spot ? "BTC" : settlementAsset;
            // 本场景买方初始入账与预留参照使用的资产。
            String buyerAsset = spot ? "USDT" : settlementAsset;
            // 当前买方实际注入的初始结算资产金额。
            long wallet = spot ? 1_000 : 20_000;
            // 传给辅助器但不被编码的卖方预留参照数。
            long sellerReservation = spot ? 2 : 10_000;
            // 传给辅助器但不被编码的买方预留参照数。
            long buyerReservation = spot ? 240 : 10_000;
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(
                            sellerAsset, spot ? 2 : wallet)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(buyerAsset, wallet)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false, reservationKind,
                            sellerAsset, sellerReservation, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false,
                            100_000, 200_000));
            // 当前带费成交或 GTX 请求之前的单资产资金总额。
            long fundsBefore = total(state, settlementAsset);
            // 本次成交前财库手续费子账余额。
            long feeBefore = state.tradingState().treasuryState().feeBalances()
                    .getOrDefault(settlementAsset, 0L);
            // 当前第四条业务命令完成后的回执，供读取已退休订单终态。
            CoreResponse receipt4 = apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false, reservationKind,
                            buyerAsset, buyerReservation, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                            100, false, 100_000, 200_000));

            assertThat(state.terminalRetention().containsOrder(101, 11, "")).isTrue();
            assertThat(state.tradingState().order(101)).isNull();
            assertThat(orderIn(receipt4, 202).status()).isEqualTo("FILLED");
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().treasuryState().feeBalances().getOrDefault(settlementAsset, 0L))
                    .isGreaterThan(feeBefore);
            assertThat(total(state, settlementAsset)).isEqualTo(fundsBefore);
        }
    }

    /**
     * 现货成交两份后核对财库手续费六十、卖方收到一百八十 USDT、买方计价总额归零，USDT 总额二百四十和 BTC 总额二均保持。
     */
    @Test
    void spotMatchCommitsTheChangedTreasuryBalance() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state, 100_000, 200_000);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 240)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    placeWithFees(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.SPOT_ASSET, "BTC", 2,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false, 100_000, 200_000));
            // 带费现货买单的原始命令帧。
            CoreMessage buyer = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    placeWithFees(202, CoreOrderSide.BUY, 100, 2, false,
                            ReservationKind.SPOT_ASSET, "USDT", 240,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, false, 100_000, 200_000));

            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, submit(state, buyer), buyer);
            assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(orderIn(completed, 202).status()).isEqualTo("FILLED");
            assertThat(orderIn(completed, 202).executedQuantitySteps()).isEqualTo(2);
            assertThat(state.tradingState().treasuryState().feeBalances()).containsEntry("USDT", 60L);
            assertThat(total(state, "USDT")).isEqualTo(240);
            assertThat(total(state, "BTC")).isEqualTo(2);
            assertThat(state.tradingState().user(11).totalUnits("USDT")).isEqualTo(180);
            assertThat(state.tradingState().user(22).totalUnits("USDT")).isZero();
        }
    }

    /**
     * U 本位 GTX 吃单请求被拒，要求 REJECTED 记录保留、买方冻结清零和测试资金总额不变。
     */
    @Test
    void linearPerpetualPostOnlyRejectionPreservesFundsAndReservations() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 2, false,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200));
            // 当前带费成交或 GTX 请求之前的单资产资金总额。
            long fundsBefore = total(state, "USDT");

            // 会与现有挂单相交、预期拒绝的 GTX 命令。
            CoreMessage crossingPostOnly = message(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2,
                            ReservationKind.DERIVATIVE_MARGIN, "USDT", 200,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTX, 100, true));
            // 命令尚未完成撮合与账户提交时的在途响应。
            CoreResponse pending = submit(state, crossingPostOnly);
            // 本命令最终完成响应，尚未完成时可为空。
            CoreResponse completed = drainMatching(state, pending, crossingPostOnly);

            assertThat(completed.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().order(202).status()).isEqualTo(CoreOrderStatus.REJECTED);
            assertThat(state.tradingState().user(22).balances().get("USDT").lockedUnits()).isZero();
            assertThat(total(state, "USDT")).isEqualTo(fundsBefore);
        }
    }

    /**
     * 把旧买单改价替换成可成交新单，核对三个相关订单都退休并保留终态，快照恢复后订单表为空；本用例未布置 FIFO 竞争，也未比较恢复 Matcher 哈希。
     */
    @Test
    void replaceLosesPriorityCanMatchAndRestoresToSameExchangeCoreHash() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10)));
            apply(state, 2, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 3, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 110, 2, ReservationKind.SPOT_ASSET, "BTC", 2));
            apply(state, 4, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));
            apply(state, 5, 22, CoreMessageType.REPLACE_ORDER,
                    TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(202,
                            new PlaceOrderCommand(203, "1", CoreOrderSide.BUY, 110, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""))));

            assertThat(state.tradingState().orders().values())
                    .noneMatch(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.status() == CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(101)).isNull();
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.tradingState().order(203)).isNull();
            assertThat(state.terminalRetention().containsOrder(101, 11, "")).isTrue();
            assertThat(state.terminalRetention().containsOrder(202, 22, "")).isTrue();
            assertThat(state.terminalRetention().containsOrder(203, 22, "")).isTrue();
            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.tradingState().orders()).isEmpty();
            }
        }
    }

    /**
     * 六产品线同价两卖单中先成交首单一份，快照恢复后核对交易状态和 Matcher 哈希，再买四份必须先吃完原首单，第二卖单仍完整五份，验证恢复后的同价 FIFO。
     * @param productLine 本用例当前独立产品线
     */
    @ParameterizedTest
    @MethodSource("allProductLines")
    void preservesFifoAcrossNativeSnapshotWithoutCoreBookState(ProductLine productLine) {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            applyInstrument(state);
            // 按产品选择的测试预留用途参照，辅助编码器不把它写成请求字段。
            ReservationKind reservationKind = productLine == ProductLine.SPOT
                    ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
            // 本场景卖方初始入账与预留参照使用的资产。
            String sellerAsset = productLine == ProductLine.SPOT ? "BTC" : settleAsset(productLine);
            // 本场景买方初始入账与预留参照使用的资产。
            String buyerAsset = productLine == ProductLine.SPOT ? "USDT" : settleAsset(productLine);
            apply(state, 1, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(sellerAsset, 2_000)));
            apply(state, 2, 12, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(sellerAsset, 2_000)));
            apply(state, 3, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(buyerAsset, 2_000)));
            apply(state, 4, 11, CoreMessageType.PLACE_ORDER,
                    place(101, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000));
            apply(state, 5, 12, CoreMessageType.PLACE_ORDER,
                    place(102, CoreOrderSide.SELL, 100, 5, reservationKind, sellerAsset,
                            productLine == ProductLine.SPOT ? 5 : 1_000,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, false));
            apply(state, 6, 22, CoreMessageType.PLACE_ORDER,
                    place(201, CoreOrderSide.BUY, 100, 1, reservationKind, buyerAsset, 100));

            // 保留同价挂单 FIFO 前置状态的内存快照字节。
            byte[] snapshot = state.snapshot();
            try (/* 从当前内存快照恢复、在作用域结束时关闭的运行时。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
                assertThat(restored.tradingState()).isEqualTo(state.tradingState());
                var originalIdentities = state.identities();
                var restoredIdentities = restored.identities();
                long client = AccountLaneQueryTestSupport.read(state.runtimeState(), 12,
                        () -> originalIdentities.findClientKeyValue(12, "client-102"));
                assertThat(client).isPositive();
                assertThat(AccountLaneQueryTestSupport.read(restored.runtimeState(), 12,
                        () -> restoredIdentities.findClientKeyValue(12, "client-102"))).isEqualTo(client);
                assertThat(AccountLaneQueryTestSupport.read(restored.runtimeState(), 12,
                        () -> restoredIdentities.clientOrderId(12, client))).isEqualTo("client-102");
                assertThat(restoredIdentities.instrumentId(restoredIdentities.symbolId("1"))).isEqualTo("1");
                assertThat(restoredIdentities.asset(restoredIdentities.assetId(sellerAsset))).isEqualTo(sellerAsset);
                if (productLine != ProductLine.SPOT) {
                    for (long user : new long[]{11, 22}) {
                        Long position = originalIdentities.findPositionKey(user, "1");
                        assertThat(position).isNotNull();
                        assertThat(restoredIdentities.findPositionKey(user, "1")).isEqualTo(position);
                        assertThat(restoredIdentities.preparedPositionKey(user, "1")).isEqualTo(position);
                    }
                }

                assertThat(awaitMatchingHash(restored)).isEqualTo(awaitMatchingHash(state));
                // 第七条继续成交或主动平仓命令的终态回执。
                CoreResponse receipt7 = apply(restored, 7, 22, CoreMessageType.PLACE_ORDER,
                        place(202, CoreOrderSide.BUY, 100, 4, reservationKind, buyerAsset, 400));

                assertThat(orderIn(receipt7, 202).status()).isEqualTo("FILLED");
                assertThat(orderIn(receipt7, 202).executedQuantitySteps()).isEqualTo(4);
                assertThat(restored.terminalRetention().containsOrder(101, 11, "")).isTrue();
                assertThat(restored.tradingState().order(101)).isNull();
                assertThat(restored.tradingState().order(102).status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(restored.tradingState().order(102).remainingQuantitySteps()).isEqualTo(5);
            }
        }
    }

    /**
     * 改单补丁更换编号、价格和客户编号，要求旧单退休，新编号单保持 OPEN、价一百一十且客户编号已更新。
     */
    @Test
    void amendPatchReadsOriginalInsideCoreAndReturnsReplacement() {
        try (/* 本用例独立创建并在结束时关闭的产品线交易运行时。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            applyInstrument(state);
            apply(state, 1, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000)));
            apply(state, 2, 22, CoreMessageType.PLACE_ORDER,
                    place(202, CoreOrderSide.BUY, 100, 2, ReservationKind.SPOT_ASSET, "USDT", 200));

            // 只提供替换编号、客户号和部分字段的改单补丁命令。
            CoreMessage amend = message(state, 3, 22, CoreMessageType.AMEND_ORDER,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(202, 203, "amended",
                            110L, 2L, CoreTimeInForce.GTC, null)));
            // 当前准入、回调或最终命令响应。
            var response = drainMatching(state, submit(state, amend), amend);

            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingCommandCodec.decodeAmendOrder(amend.payload()).replacementOrderId()).isEqualTo(203);
            assertThat(state.tradingState().order(202)).isNull();
            assertThat(state.terminalRetention().containsOrder(202, 22, "")).isTrue();
            assertThat(state.tradingState().order(203).status()).isEqualTo(CoreOrderStatus.OPEN);
            assertThat(state.tradingState().order(203).priceTicks()).isEqualTo(110);
            assertThat(state.tradingState().order(203).clientOrderId()).isEqualTo("amended");
        }
    }

    /**
     * 编码币对一普通限价 GTC 非只减仓单；reservationKind/Asset 仅计算一个未使用局部值，reservedUnits 不读取，真实预留由核心推导。
     * @param orderId 普通订单业务编号
     * @param side 买入或卖出方向
     * @param priceTicks 实际写入请求的订单价格
     * @param quantitySteps 实际写入请求的订单数量
     * @param reservationKind 仅用于计算未使用 settleAsset 局部值的测试参照，未作为请求字段编码
     * @param reservationAsset 仅用于计算未使用 settleAsset 局部值的测试资产参照，未作为请求预留资产编码
     * @param reservedUnits 当前辅助方法完全不读取的预留参照数，真实冻结由核心计算
     */
    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits) {
        // 从参照预留参数计算出的资产名，当前方法后续没有读取或编码此局部值。
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));
    }

    /**
     * 编码指定订单类型、有效方式、postOnly 及客户号的币对一请求；matchingPriceTicks 和 reservedUnits 未编码，资产/预留用途只用于未使用局部值。
     * @param orderId 普通订单业务编号
     * @param side 买入或卖出方向
     * @param priceTicks 实际写入请求的订单价格
     * @param quantitySteps 实际写入请求的订单数量
     * @param reservationKind 仅用于计算未使用 settleAsset 局部值的测试参照，未作为请求字段编码
     * @param reservationAsset 仅用于计算未使用 settleAsset 局部值的测试资产参照，未作为请求预留资产编码
     * @param reservedUnits 当前辅助方法完全不读取的预留参照数，真实冻结由核心计算
     * @param orderType 实际编码的限价或市价类型
     * @param timeInForce 实际编码的 GTC/IOC/GTX 等有效方式
     * @param matchingPriceTicks 当前辅助器未读取和编码的保护价参照，不能用传入字面值作为输入覆盖证明
     * @param postOnly 实际编码的只挂单标记
     */
    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits,
            CoreOrderType orderType,
            CoreTimeInForce timeInForce,
            long matchingPriceTicks,
            boolean postOnly) {
        // 从参照预留参数计算出的资产名，当前方法后续没有读取或编码此局部值。
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, orderType, timeInForce, postOnly, "client-" + orderId));
    }

    /**
     * 编码指定订单类型、有效方式、reduceOnly、postOnly 和客户号的请求；传入费率、matchingPriceTicks 与 reservedUnits 均不读取，实际费率来自注册币对或原账户设置。
     * @param orderId 普通订单业务编号
     * @param side 买入或卖出方向
     * @param priceTicks 实际写入请求的订单价格
     * @param quantitySteps 实际写入请求的订单数量
     * @param reduceOnly 实际编码的只减仓标记
     * @param reservationKind 仅用于计算未使用 settleAsset 局部值的测试参照，未作为请求字段编码
     * @param reservationAsset 仅用于计算未使用 settleAsset 局部值的测试资产参照，未作为请求预留资产编码
     * @param reservedUnits 当前辅助方法完全不读取的预留参照数，真实冻结由核心计算
     * @param orderType 实际编码的限价或市价类型
     * @param timeInForce 实际编码的 GTC/IOC/GTX 等有效方式
     * @param matchingPriceTicks 当前辅助器未读取和编码的保护价参照，不能用传入字面值作为输入覆盖证明
     * @param postOnly 实际编码的只挂单标记
     * @param makerFeeRatePpm 此订单辅助器当前不读取的 Maker 费率参照，实际使用注册配置
     * @param takerFeeRatePpm 此订单辅助器当前不读取的 Taker 费率参照，实际使用注册配置
     */
    private static byte[] placeWithFees(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            boolean reduceOnly,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits,
            CoreOrderType orderType,
            CoreTimeInForce timeInForce,
            long matchingPriceTicks,
            boolean postOnly,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
        // 从参照预留参数计算出的资产名，当前方法后续没有读取或编码此局部值。
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps, reduceOnly, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, orderType, timeInForce, postOnly, "client-" + orderId));
    }

    /**
     * 编码允许指定 reduceOnly 的币对一限价 GTC 单；预留参数仍未编码，由核心自行派生。
     * @param orderId 普通订单业务编号
     * @param side 买入或卖出方向
     * @param priceTicks 实际写入请求的订单价格
     * @param quantitySteps 实际写入请求的订单数量
     * @param reduceOnly 实际编码的只减仓标记
     * @param reservationKind 仅用于计算未使用 settleAsset 局部值的测试参照，未作为请求字段编码
     * @param reservationAsset 仅用于计算未使用 settleAsset 局部值的测试资产参照，未作为请求预留资产编码
     * @param reservedUnits 当前辅助方法完全不读取的预留参照数，真实冻结由核心计算
     */
    private static byte[] place(
            long orderId,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            boolean reduceOnly,
            ReservationKind reservationKind,
            String reservationAsset,
            long reservedUnits) {
        // 从参照预留参数计算出的资产名，当前方法后续没有读取或编码此局部值。
        String settleAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN ? reservationAsset : "USDT";
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", side, priceTicks, quantitySteps, reduceOnly, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, ""));
    }

    /**
     * 以零 Maker/Taker 费率调用本测试币对注册辅助器。
     * @param state 当前被测产品线的交易运行时
     */
    private static void applyInstrument(TradingCoreRuntime state) {
        applyInstrument(state, 0, 0);
    }

    /**
     * 按当前产品注册币对一，提供相应到期/期权参数与传入费率，再提交固定参考价格；只要求这两条直接命令已应用，未在此预先开仓或增加用户资金。
     * @param state 当前被测产品线的交易运行时
     * @param makerFeeRatePpm 注册币对使用的 Maker 费率，百万分比
     * @param takerFeeRatePpm 注册币对使用的 Taker 费率，百万分比
     */
    private static void applyInstrument(TradingCoreRuntime state, long makerFeeRatePpm, long takerFeeRatePpm) {
        // 注册辅助器当前运行时所属的独立产品线。
        ProductLine productLine = state.productLine();
        // 对应产品线的合约类型，用于选择到期和期权参数。
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        // 交割和期权测试币对的未来到期时刻，永续及现货为零。
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        // 带本样例资产、风险和费率配置的注册币对命令。
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", settleAsset(productLine), 1, 1, type.isInverse() ? 1_000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                makerFeeRatePpm, takerFeeRatePpm, expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
        // 当前辅助器构造的完整协议命令帧。
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.randomUUID(), productLine, CommandSource.OPERATIONS, 88, 1, 1,
                1_000, 1), TradingCommandCodec.encodeRegisterInstrument(instrument, productLine));
        assertThat(submit(state, message).status()).isEqualTo(ResponseStatus.APPLIED);
        // 注册后提交的固定参考价命令，期权同时提供指数和远期参考价。
        CoreMessage mark = new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                UUID.randomUUID(), productLine, CommandSource.KAFKA_INPUT_BRIDGE, 89, 1, 1,
                1_000, 2), TradingCommandCodec.encodeApplyMarkPrice(
                        type.isOption()
                                ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, 1_000)
                                : new ApplyMarkPriceCommand("1", 100, 1, 1_000), productLine));
        assertThat(submit(state, mark).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 本测试币本位永续/交割使用 BTC，其余产品使用 USDT。
     * @param productLine 本用例当前独立产品线
     */
    private static String settleAsset(ProductLine productLine) {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    /**
     * 只从当前命令回执解码的订单列表查指定编号，缺失则断言失败，不回退查询已退休运行时订单。
     * @param receipt 需要读取订单结果的最终命令回执
     * @param orderId 普通订单业务编号
     */
    private static CoreOrderStateView orderIn(CoreResponse receipt, long orderId) {
        return CoreCommandResultCodec.decode(receipt.data()).orders().stream()
                .filter(/* 当前用于状态筛选、订单编号查找或结果断言的业务订单值。 */ order -> order.orderId() == orderId).findFirst()
                .orElseThrow(() -> new AssertionError("missing order " + orderId + " in command receipt"));
    }

    /**
     * 只等待命令准入和直接控制工作，在十秒截止点内检查健康并轮询；Matcher 终态仍由各用例显式完成，返回的响应必须非空。
     * @param state 当前被测产品线的交易运行时
     * @param command 要提交准入或控制工作的原命令帧
     */
    private static CoreResponse submit(TradingCoreRuntime state, CoreMessage command) {
        // 当前准入、回调或最终命令响应。
        CoreResponse response = state.apply(command);
        // 当前辅助操作的纳秒截止点，准入十秒、账户结算及哈希等待五秒。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (state.directCommand.active()) {
            response = state.directCommand.poll();
            state.assertHealthy();
            if (System.nanoTime() >= deadline)
                throw new AssertionError("command admission timed out: " + command.header().messageType());
            Thread.onSpinWait();
        }
        assertThat(response).isNotNull();
        return response;
    }

    /**
     * 构造一条命令，完成准入及必要撮合结算，再要求响应为 APPLIED 或 OK；拒绝场景使用更直接的辅助入口。
     * @param state 当前被测产品线的交易运行时
     * @param sequence 当前命令源序号，同时按本样例写入模拟位置
     * @param userId 该业务请求所属用户编号
     * @param messageType 本次请求的协议命令类型
     * @param payload 已经编码的请求载荷
     */
    private static CoreResponse apply(
            TradingCoreRuntime state,
            long sequence,
            long userId,
            CoreMessageType messageType,
            byte[] payload) {
        // 当前辅助器构造的完整协议命令帧。
        CoreMessage message = message(state, sequence, userId, messageType, payload);
        // 当前准入、回调或最终命令响应。
        CoreResponse response = drainMatching(state, submit(state, message), message);
        assertThat(response.status()).as("%s %s users=%s orders=%s", messageType, response.resultCode(),
                        state.tradingState().users().keySet(), state.tradingState().orders().keySet())
                .isIn(ResponseStatus.APPLIED, ResponseStatus.OK);
        return response;
    }

    /**
     * 非 MATCHING_PENDING 响应直接返回；否则找到在途撮合序号，用原完成辅助器收尾，要求得到 APPLIED 或 REJECTED 的非空终态。
     * @param state 当前被测产品线的交易运行时
     * @param response 准入或控制阶段当前响应，可能仍为 MATCHING_PENDING
     * @param message 需要继续等待撮合终态的原命令帧
     */
    private static CoreResponse drainMatching(TradingCoreRuntime state, CoreResponse response, CoreMessage message) {
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        // 由原命令 UUID 查询到的在途撮合序号。
        long sequence = state.matchingSequence(message.header().commandId());
        // 本命令最终完成响应，尚未完成时可为空。
        CoreResponse completed = CoreTestCompletion.completeMatchingSynchronously(state, sequence,
                message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        assertThat(completed).isNotNull();
        assertThat(completed.status()).isIn(ResponseStatus.APPLIED, ResponseStatus.REJECTED);
        return completed;
    }

    /**
     * 反复将同一原生撮合事实交回运行时，最多五秒等待账户结算最终响应；尚未完成时自旋，超时按测试失败处理。
     * @param state 当前被测产品线的交易运行时
     * @param sequence 当前已发布撮合事实对应的在途命令序号
     * @param result Matcher 已发布、仍需账户结算的同一撮合事实
     * @param clusterTimestamp 本样例交给结算的 Cluster 业务时间戳
     * @param clusterPosition 本样例交给结算的模拟日志位置，不是真实 Archive 字节偏移
     */
    private static CoreResponse completeUntilTerminalOrFailure(
            TradingCoreRuntime state, long sequence,
            com.surprising.aeron.service.matcher.MatchingResult result,
            long clusterTimestamp, long clusterPosition) {
        // 本命令最终完成响应，尚未完成时可为空。
        CoreResponse completed = null;
        // 当前辅助操作的纳秒截止点，准入十秒、账户结算及哈希等待五秒。
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(sequence, result, clusterTimestamp, clusterPosition);
            if (completed == null) Thread.onSpinWait();
        }
        if (completed == null) throw new AssertionError("account lane settlement did not complete");
        return completed;
    }

    /**
     * 请求异步 Matcher 状态哈希并最多自旋五秒，要求 future 已完成后读取其值，不把默认零当成未完成时的成功结果。
     * @param state 当前被测产品线的交易运行时
     */
    private static int awaitMatchingHash(TradingCoreRuntime state) {
        // 原 Matcher 状态哈希的异步查询结果。
        CompletableFuture<Integer> future = state.matchingFlow.matchingStateHashAsync();
        // 当前辅助操作的纳秒截止点，准入十秒、账户结算及哈希等待五秒。
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!future.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(future).isDone();
        return future.getNow(0);
    }

    /**
     * 构造当前产品的 Gateway 测试命令，UUID 随机，固定提交时间一千，源序号也作为本样例模拟位置。
     * @param state 当前被测产品线的交易运行时
     * @param sequence 当前命令源序号，同时按本样例写入模拟位置
     * @param userId 该业务请求所属用户编号
     * @param messageType 本次请求的协议命令类型
     * @param payload 已经编码的请求载荷
     */
    private static CoreMessage message(TradingCoreRuntime state, long sequence, long userId,
                                       CoreMessageType messageType, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, UUID.randomUUID(),
                state.productLine(), CommandSource.GATEWAY, 77, sequence, userId, 1_000, sequence), payload);
    }

    /**
     * 返回所有衍生品中剔除期权后的四条永续/交割产品线。
     */
    private static Stream<ProductLine> derivativeLines() {
        return Stream.of(ProductLine.values())
                .filter(ProductLine::isDerivative)
                .filter(/* 注册辅助器当前运行时所属的独立产品线。 */ productLine -> !productLine.isOptionProduct());
    }

    /**
     * 返回六条独立产品线供参数化用例各自建实例。
     */
    private static Stream<ProductLine> allProductLines() {
        return Stream.of(ProductLine.values());
    }

    /**
     * 按指定资产合计用户余额，加手续费、保险、强平费、资金费剩余、舍入剩余和清算盈亏，再减保险亏空。用户部分 stream 使用普通 long 求和，随后财库合并用精确加减；这是完成后当前测试状态口径，不是跨资产估值。
     * @param state 当前被测产品线的交易运行时
     * @param asset 本次测试合计的单一资产名称
     */
    private static long total(TradingCoreRuntime state, String asset) {
        // 按单一资产从所有用户累计的余额总量，stream 普通 long 求和不精确检查溢出。
        long users = state.tradingState().users().values().stream()
                .mapToLong(/* 当前初始化资产、核对资金或参与总额合计的用户编号/业务值。 */ user -> user.totalUnits(asset)).sum();
        // 完成后用于合计七类财库金额及亏空的业务状态视图。
        var treasury = state.tradingState().treasuryState();
        // 当前样例已合计的单资产资金量。
        long total = users;
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
    }
}
