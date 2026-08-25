package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreMarkPriceState;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreLifecycleWorkTest {

    @Test
    void resumesAllWorkKindsFromSnapshotWithinBounds() {
        CoreProbeState restored = stateWithLifecycleWork();
        restored = CoreProbeState.fromSnapshot(ProductLine.LINEAR_PERPETUAL, restored.snapshot());

        var funding = restored.apply(query(CoreMessageType.FUNDING_PROGRESS_QUERY,
                CoreStateQueryCodec.encodeFundingProgressQuery("BTC-USDT")));
        var settlement = restored.apply(query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY,
                CoreStateQueryCodec.encodeSettlementProgressQuery("BTC-USDT")));
        assertThat(CoreFundingProgressCodec.decode(funding.data()).nextCursorUserId()).isEqualTo(41);
        assertThat(CoreSettlementProgressCodec.decode(settlement.data()).nextCursorOrderId()).isEqualTo(91);

        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.EXECUTION, 1);
        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.INSURANCE, 2);
        assertBoundedExactlyOnce(restored, CoreLiquidationWorkView.Purpose.ADL, 3);
    }

    @Test
    void rejectsCrossLineWorkQueryBeforeSelection() {
        CoreProbeState state = stateWithLifecycleWork();
        byte[] payload = CoreLiquidationWorkCodec.encodeQuery(ProductLine.INVERSE_PERPETUAL,
                CoreLiquidationWorkView.Purpose.INSURANCE, 0, 1, 1_024);

        var response = state.apply(query(CoreMessageType.LIQUIDATION_WORK_QUERY, payload));

        assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_MISMATCH);
    }

    private static void assertBoundedExactlyOnce(CoreProbeState state,
                                                  CoreLiquidationWorkView.Purpose purpose,
                                                  long expectedLiquidationId) {
        var firstResponse = state.apply(query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL, purpose, 0, 1, 1_024)));
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

        var resumedResponse = state.apply(query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL, purpose,
                        first.nextCursorLiquidationId(), 1, 1_024)));
        CoreLiquidationWorkView resumed = CoreLiquidationWorkCodec.decodeWork(resumedResponse.data());
        assertThat(resumed.actions()).isEmpty();
        assertThat(resumed.resolutions()).isEmpty();
        assertThat(resumed.complete()).isTrue();
    }

    private static CoreProbeState stateWithLifecycleWork() {
        CoreInstrumentState instrument = new CoreInstrumentState("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, null, 0, 10_000_000, 1_000_000,
                0, 1, List.of(new CoreRiskLimitBracket(1, 0, 1_000_000,
                10_000_000, 100_000, 50_000)));
        var planned = liquidation(1, 1001, 0, CoreLiquidationState.Status.PLANNED);
        var insurance = liquidation(2, 1002, 100, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        var adl = liquidation(3, 1003, 50, CoreLiquidationState.Status.ADL_REQUIRED);
        CoreRiskState risk = new CoreRiskState(
                Map.of("BTC-USDT", new CoreMarkPriceState("BTC-USDT", 1, 60_000, 9, 1_000)),
                Map.of(), Map.of(1L, planned, 2L, insurance, 3L, adl), Map.of(), 4);
        CoreTreasuryState treasury = new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("BTC-USDT", new CoreTreasuryState.FundingProgress(11, 1, 100, 41,
                        UUID.fromString("00000000-0000-0000-0000-000000000011"))),
                Map.of("BTC-USDT", new CoreTreasuryState.LifecycleProgress(12, 1, 60_000,
                        0, false, 91, 0,
                        UUID.fromString("00000000-0000-0000-0000-000000000012"))));
        TradingCoreState trading = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(), Map.of(), Map.of("BTC-USDT", instrument), risk, treasury);
        return CoreProbeState.restore(ProductLine.LINEAR_PERPETUAL, 0, 0,
                Map.of(), Map.of(), trading, new CoreExportState());
    }

    private static CoreLiquidationState liquidation(long id, long userId, long deficit,
                                                     CoreLiquidationState.Status status) {
        return new CoreLiquidationState(id, userId, "BTC-USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 9, 10, 10, deficit, status == CoreLiquidationState.Status.PLANNED ? 0 : 60_000,
                status == CoreLiquidationState.Status.PLANNED ? 0 : 3_000, 0, status);
    }

    private static CoreMessage query(CoreMessageType type, byte[] payload) {
        return new CoreMessage(new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION,
                com.surprising.aeron.protocol.WireMessageKind.QUERY, type, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CoreRoute.DEFAULT, CommandSource.GATEWAY, 1, 0, 0, 1, 1), payload);
    }
}
