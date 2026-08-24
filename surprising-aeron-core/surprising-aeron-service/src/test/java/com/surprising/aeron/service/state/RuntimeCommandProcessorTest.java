package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeCommandProcessorTest {

    @Test
    void adjustsBalanceDirectlyInRuntimeAcrossEveryProductLine() {
        TradingCoreReducer reference = new TradingCoreReducer();
        for (ProductLine productLine : ProductLine.values()) {
            TradingCoreState before = TradingCoreState.empty(productLine);
            BalanceAdjustmentCommand command = new BalanceAdjustmentCommand("USDT", 1_000);
            TradingCoreState expected = reference.adjustBalance(before, 7, command);
            RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
            TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

            RuntimeCommandProcessor.adjustBalance(runtime, identities, 7, command);

            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .as(productLine.name())
                    .isEqualTo(expected);
        }
    }

    @Test
    void rejectedBalanceAdjustmentLeavesRuntimeUnchanged() {
        TradingCoreState before = new TradingCoreReducer().adjustBalance(
                TradingCoreState.empty(ProductLine.SPOT), 7, new BalanceAdjustmentCommand("USDT", 10));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimeCommandProcessor.adjustBalance(
                runtime, identities, 7, new BalanceAdjustmentCommand("USDT", -11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(before);
    }

    @Test
    void updatesRiskScanControlDirectlyInRuntime() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        UpdateRiskScanControlCommand command = new UpdateRiskScanControlCommand(
                before.riskState().scanControl().version(), "runtime-owner", true,
                25, 64, "qa", "runtime authority");
        TradingCoreState expected = new TradingCoreReducer().updateRiskScanControl(before, command, 123);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommandProcessor.updateRiskScanControl(runtime, command, 123);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    @Test
    void upsertsInstrumentDirectlyInRuntime() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.SPOT);
        UpsertInstrumentCommand command = new UpsertInstrumentCommand(
                "BTC-USDT", 1, ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
        TradingCoreState expected = new TradingCoreReducer().upsertInstrument(before, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommandProcessor.upsertInstrument(runtime, identities, command);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    @Test
    void updatesPositionModeDirectlyInRuntime() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        UpdatePositionModeCommand command = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        TradingCoreState expected = new TradingCoreReducer().updatePositionMode(before, 7, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThat(RuntimeCommandProcessor.updatePositionMode(runtime, 7, command)).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        assertThat(RuntimeCommandProcessor.updatePositionMode(runtime, 7, command)).isFalse();
    }

    @Test
    void updatesLeverageDirectlyInRuntime() {
        TradingCoreReducer reference = new TradingCoreReducer();
        TradingCoreState before = reference.upsertInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0));
        UpdateLeverageCommand command = new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 5_000_000);
        TradingCoreState expected = reference.updateLeverage(before, 7, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThat(RuntimeCommandProcessor.updateLeverage(runtime, identities, 7, command)).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }
}
