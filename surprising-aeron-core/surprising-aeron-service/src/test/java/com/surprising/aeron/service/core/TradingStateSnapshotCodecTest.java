package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证交易状态内嵌快照编解码的业务字段、实体哈希与续扫游标，以及产品线、截断、版本和必需索引的拒绝边界。输入由测试状态辅助器或直接构造，未启动完整 Cluster 或实际撮合线程。
 */
class TradingStateSnapshotCodecTest {

    /**
     * 人工构造带成交金额高低位一和负一及提交元数据的订单，往返编解码后直接检查两半与业务哈希；提交时间/位置字段未在本方法逐项单独断言。
     */
    @Test
    void executionTotalSurvivesCheckpointAndCommitMetadata() {
        // 测试用同步状态转换器，建立输入而不是另写一套快照编解码。
        var reducer = new RuntimeTestStateTransitions();
        // 按场景创建的空状态或已注册品种并入账的基础状态。
        var empty = reducer.adjustBalance(reducer.registerInstrument(TradingCoreState.empty(ProductLine.SPOT),
                CoreStateTestFixtures.instrument(ProductLine.SPOT, "1", "BTC", "USDT", "USDT")),
                7, new BalanceAdjustmentCommand("USDT", 50_000));
        // 测试订单字段值，人工设置成交金额高低位并补提交元数据后作为构造输入。
        var mutableOrder = CoreStateTestFixtures.filledOrder(CoreStateTestFixtures.order(11, 7, 5, 10),
                3, 7, 4, com.surprising.aeron.model.CoreOrderStatus.OPEN, 2);
        mutableOrder.executionValue(1, -1);
        var runtimeOrder = mutableOrder.snapshot().withCommitMetadata(100, 200);
        // 人工构造的不可变订单，含指定成交金额高低位。
        var order = new com.surprising.aeron.model.CoreOrderState(11, ProductLine.SPOT, 7,
                "1", CoreOrderSide.BUY, 5, 10, 3, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new java.util.UUID(0, 11),
                0, 0, 4, runtimeOrder.executedValueHigh(), runtimeOrder.executedValueLow(),
                100, 100, 200, com.surprising.aeron.model.CoreOrderStatus.OPEN, 2);
        // 当前待编码或直接测试构造契约的交易状态。
        var state = new TradingCoreState(empty.productLine(), 2, empty.users(), Map.of(11L, order),
                empty.instruments(), empty.riskState(), empty.treasuryState(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        // 当前格式往返解码后的交易状态。
        var restored = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(state), ProductLine.SPOT);
        assertThat(restored.orders().get(11L).executedValueHigh()).isEqualTo(1);
        assertThat(restored.orders().get(11L).executedValueLow()).isEqualTo(-1);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
    }

    /**
     * 通过测试转换器建立期权品种、余额、标记价与 GTX 买单，往返后比较完整状态和业务哈希。
     */
    @Test
    void roundTripPreservesBusinessStateAndHash() {
        // 测试用同步状态转换器，建立输入而不是另写一套快照编解码。
        RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();
        // 当前待编码或直接测试构造契约的交易状态。
        TradingCoreState state = reducer.adjustBalance(
                reducer.registerInstrument(TradingCoreState.empty(ProductLine.OPTION),
                        CoreStateTestFixtures.instrument(ProductLine.OPTION,
                                "6", "BTC", "USDT", "USDT")), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("6", 500, 1_000, 1_000, 1, 1_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "6", CoreOrderSide.BUY, 500, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true, "option-client-71"));

        // 当前格式往返解码后的交易状态。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.OPTION);

        assertThat(restored).isEqualTo(state);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
    }

    /**
     * 直接填入风险续扫及追踪触发阶段的各个游标，往返后比较整个 RiskScan 值与业务哈希；不执行真实扫描或触发撮合。
     */
    @Test
    void roundTripPreservesRiskAndTriggerContinuationCursor() {
        // 按场景创建的空状态或已注册品种并入账的基础状态。
        TradingCoreState empty = TradingCoreState.empty(ProductLine.SPOT);
        // 人工填写的风险/触发续扫游标值，供往返比较。
        CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan(
                "1", 0, 7, 6, false, false, TriggerOrderIndex.PHASE_TRAILING_LESS_OR_EQUAL,
                400, 300, 500, 70_000, 1_234, 88, 77, 0,
                List.of(new RiskLaneProgress(0, false, 7, 1, "position-key", 9, 10, 11, 12, 13, 14, 15),
                        new RiskLaneProgress(20, false, 21, 2, "other-position", 22, 23, 24, 25, 26, 27, 28)));
        // 包含标记价与指定续扫任务的风险状态。
        CoreRiskState risk = new CoreRiskState(
                Map.of("1", new CoreMarkPriceState("1", 70_000, 7, 1_000)),
                Map.of(), Map.of(), Map.of("1", scan), 1, CoreRiskState.defaultScanControl(), 0);
        // 当前待编码或直接测试构造契约的交易状态。
        TradingCoreState state = new TradingCoreState(empty.productLine(), empty.revision(), empty.users(),
                empty.orders(), empty.instruments(), risk, empty.treasuryState(),
                empty.leverages(), empty.algoOrders(), empty.cancelAllAfterTimers(), empty.clientOrderIndex(),
                empty.triggerOrders());

        // 当前格式往返解码后的交易状态。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.SPOT);

        assertThat(restored.riskState().scans().get("1")).isEqualTo(scan);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());

        // 当前轮转点仍在 Lane 0；Lane 1 的进度和失效版本变化也必须改变状态哈希。
        var other = scan.laneProgress().get(1);
        var changed = List.of(scan.laneProgress().getFirst(),
                new RiskLaneProgress(other.lastUserId(), other.complete(), other.userId(), other.phase(),
                        other.positionCursor(), other.reservationCursor(), other.unrealizedPnlUnits(),
                        other.maintenanceMarginUnits(), other.isolatedMarginUnits(), other.isolatedReservationUnits(),
                        other.userRevision() + 1, other.marketRevision() + 1));
        for (var variant : List.of(
                new CoreRiskState.RiskScan("1", 0, 7, 6, false, false,
                        TriggerOrderIndex.PHASE_TRAILING_LESS_OR_EQUAL, 400, 300, 500, 70_000, 1_234, 88, 77, 0, changed),
                new CoreRiskState.RiskScan("1", 1, 7, 6, false, false,
                        TriggerOrderIndex.PHASE_TRAILING_LESS_OR_EQUAL, 400, 300, 500, 70_000, 1_234, 88, 77, 0,
                        scan.laneProgress()))) {
            var variantRisk = new CoreRiskState(risk.markPrices(), risk.snapshots(), risk.liquidations(),
                    Map.of("1", variant), risk.nextLiquidationId(), risk.scanControl(), risk.marketRevision());
            var variantState = new TradingCoreState(state.productLine(), state.revision(), state.users(),
                    state.orders(), state.instruments(), variantRisk, state.treasuryState(), state.leverages(),
                    state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(), state.triggerOrders());
            assertThat(variantState.businessStateHash()).isNotEqualTo(state.businessStateHash());
            assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(variantState), ProductLine.SPOT))
                    .isEqualTo(variantState);
        }
    }

    /**
     * 对现货空状态编码，要求按期权解码和截去最后一字节两种输入都被协议异常拒绝。
     */
    @Test
    void rejectsProductLineMismatchAndTruncation() {
        // 正常编码字节，按当前测试截断、换产品线或篡改版本。
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));

        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(encoded, ProductLine.OPTION))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class);
    }

    /**
     * 直接向完整交易状态构造器传空客户端订单索引，要求参数异常及明确消息；此项检查的是状态构造契约，不是字节解码过程。
     */
    @Test
    void authoritativeConstructorRejectsMissingClientOrderIndex() {
        // 当前待编码或直接测试构造契约的交易状态。
        TradingCoreState state = TradingCoreState.empty(ProductLine.SPOT);

        assertThatThrownBy(() -> new TradingCoreState(state.productLine(), state.revision(), state.users(),
                state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), null, state.triggerOrders()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client order index is required");
    }

    /**
     * 把内嵌交易状态格式的首四字节改成小端十五，要求解码以不支持版本拒绝，与外层 Core 快照 v27 独立。
     */
    @Test
    void rejectsUnsupportedSnapshotVersion() {
        // 正常编码字节，按当前测试截断、换产品线或篡改版本。
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));
        encoded[0] = 15;
        encoded[1] = 0;
        encoded[2] = 0;
        encoded[3] = 0;

        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(encoded, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported trading snapshot version");
    }
}
