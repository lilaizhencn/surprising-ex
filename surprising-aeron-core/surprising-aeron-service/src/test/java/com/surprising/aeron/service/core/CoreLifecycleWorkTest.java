package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreRoute;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证内存快照恢复后的资金费与到期结算游标查询，以及强平执行、保险、ADL 待办的有界分页。这里只查询预先构造的工作状态，不执行对应资金费、交割、保险或减仓结算。
 */
class CoreLifecycleWorkTest {

    /**
     * 从含有各类待办的运行时保存快照并恢复，核对资金费用户游标四十一和结算订单游标九十一，再按三种强平用途检查单页大小、字节上限与续页无重复。
     */
    @Test
    void resumesAllWorkKindsFromSnapshotWithinBounds() {
        // 先构造含待办的运行时，再赋为快照恢复后的查询实例。
        TradingCoreRuntime restored = stateWithLifecycleWork();
        restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, restored.snapshot());

        // 恢复后资金费进度查询响应。
        var funding = restored.apply(query(CoreMessageType.FUNDING_PROGRESS_QUERY,
                CoreStateQueryCodec.encodeFundingProgressQuery("1")));
        // 恢复后到期结算进度查询响应。
        var settlement = restored.apply(query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY,
                CoreStateQueryCodec.encodeSettlementProgressQuery("1")));
        assertThat(CoreFundingProgressCodec.decode(funding.data()).nextCursorUserId()).isEqualTo(41);
        assertThat(CoreSettlementProgressCodec.decode(settlement.data()).nextCursorOrderId()).isEqualTo(91);

        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.EXECUTION, 1);
        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.INSURANCE, 2);
        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.ADL, 3);
    }

    /**
     * 向 U 本位实例提交币本位用途查询，要求返回产品线不匹配拒绝码；此用例没有观测索引是否被遍历。
     */
    @Test
    void rejectsCrossLineWorkQueryBeforeSelection() {
        // 预先装入 U 本位待办的被查询运行时。
        TradingCoreRuntime state = stateWithLifecycleWork();
        // 明确声明币本位永续的跨线查询参数。
        byte[] payload = CoreLiquidationWorkCodec.encodeQuery(ProductLine.INVERSE_PERPETUAL,
                CoreLiquidationWorkView.Purpose.INSURANCE, 0, 1, 1_024);

        // 跨产品线查询的实际拒绝响应。
        var response = CoreTestCompletion.querySynchronously(state, query(CoreMessageType.LIQUIDATION_WORK_QUERY, payload));

        assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_MISMATCH);
    }

    /**
     * 在三条固定强平记录中按用途取一条，检查响应不超过一千零二十四字节及编号，随后用返回游标续查必须为空且完成。这里验证查询分页，不代表业务执行只能发生一次。
     * @param state 包含三种强平待办的交易运行时
     * @param purpose 本次查询的执行、保险或 ADL 用途
     * @param expectedLiquidationId 该用途首条返回记录应有的强平编号
     */
    private static void assertBoundedExactlyOnce(TradingCoreRuntime state,
                                                  CoreLiquidationWorkView.Purpose purpose,
                                                  long expectedLiquidationId) {
        // 当前用途最多一条待办的首个查询响应。
        var firstResponse = CoreTestCompletion.querySynchronously(state, query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL, purpose, 0, 1, 1_024)));
        // 首个响应解码后的待办页，包含实际记录和下一游标。
        CoreLiquidationWorkView first = CoreLiquidationWorkCodec.decodeWork(firstResponse.data());
        assertThat(firstResponse.data().length).isLessThanOrEqualTo(1_024);
        assertThat(first.productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(first.nextCursorLiquidationId()).isEqualTo(3);
        assertThat(first.actions().size() + first.resolutions().size()).isEqualTo(1);
        if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
            assertThat(first.actions().getFirst().liquidationId()).isEqualTo(expectedLiquidationId);
        } else {
            assertThat(first.resolutions().getFirst().liquidationId()).isEqualTo(expectedLiquidationId);
        }

        // 使用首个页游标继续查询的响应。
        var resumedResponse = CoreTestCompletion.querySynchronously(state, query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL, purpose,
                        first.nextCursorLiquidationId(), 1, 1_024)));
        // 预期为空且完成的续页内容。
        CoreLiquidationWorkView resumed = CoreLiquidationWorkCodec.decodeWork(resumedResponse.data());
        assertThat(resumed.actions()).isEmpty();
        assertThat(resumed.resolutions()).isEmpty();
        assertThat(resumed.complete()).isTrue();
    }

    /**
     * 构造 U 本位币对、标记价、三种强平待办以及未完成资金费和结算游标，再通过原测试恢复入口组装运行时。
     */
    private static TradingCoreRuntime stateWithLifecycleWork() {
        // 供生命周期进度和强平记录引用的 U 本位永续币对配置。
        CoreInstrument instrument = new CoreInstrument("1",
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, null, 0, 10_000_000, 1_000_000,
                0, 1, List.of(new CoreRiskLimitBracket(1, 0, 1_000_000,
                10_000_000, 100_000, 50_000)));
        // 待执行强平记录，编号一。
        var planned = liquidation(1, 1001, 0, CoreLiquidationState.Status.PLANNED);
        // 等待保险处理的记录，编号二。
        var insurance = liquidation(2, 1002, 100, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 等待 ADL 的记录，编号三。
        var adl = liquidation(3, 1003, 50, CoreLiquidationState.Status.ADL_REQUIRED);
        // 包含固定标记价及三条强平待办的风险状态。
        CoreRiskState risk = new CoreRiskState(
                Map.of("1", new CoreMarkPriceState("1", 60_000, 9, 1_000)),
                Map.of(), Map.of(1L, planned, 2L, insurance, 3L, adl), Map.of(), 4);
        // 保存预设资金费用户游标和到期撤单游标的财库状态。
        CoreTreasuryState treasury = new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("1", new CoreTreasuryState.FundingProgress(11, 100, 0, 41,
                        UUID.fromString("00000000-0000-0000-0000-000000000011"), 60_000, 9)),
                Map.of("1", new CoreTreasuryState.LifecycleProgress(12, 60_000,
                        0, false, 91, 0,
                        UUID.fromString("00000000-0000-0000-0000-000000000012"))));
        // 用上述币对、风险及财库组装的旧交易状态，供恢复入口投影。
        TradingCoreState trading = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(), Map.of(), Map.of("1", instrument), risk, treasury);
        return TradingCoreRuntimeRestoreTestSupport.restore(ProductLine.LINEAR_PERPETUAL, 0,
                Map.of(), Map.of(), trading);
    }

    /**
     * 构造指定编号、用户、亏空和状态的强平测试记录；计划态保留零执行价和零执行保证金，其他状态填入固定执行数据。
     * @param id 构造记录使用的强平编号
     * @param userId 记录所属用户
     * @param deficit 记录保存的亏空金额
     * @param status 预先构造的强平业务状态
     */
    private static CoreLiquidationState liquidation(long id, long userId, long deficit,
                                                     CoreLiquidationState.Status status) {
        return new CoreLiquidationState(id, userId, "1", CoreMarginMode.CROSS,
                CorePositionSide.NET, 9, 10, 10, deficit, status == CoreLiquidationState.Status.PLANNED ? 0 : 60_000,
                status == CoreLiquidationState.Status.PLANNED ? 0 : 3_000, 0, status);
    }

    /**
     * 构造 U 本位永续的查询帧，使用随机 UUID 并携带指定查询载荷。
     * @param type 查询协议类型
     * @param payload 已编码查询参数
     */
    private static CoreMessage query(CoreMessageType type, byte[] payload) {
        return new CoreMessage(new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION,
                com.surprising.aeron.protocol.WireMessageKind.QUERY, type, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CoreRoute.DEFAULT, CommandSource.GATEWAY, 1, 0, 0, 1, 1), payload);
    }
}
