package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeStateTransitionsTest {

    @Test
    void pendingQueriesAdvancePastPermanentlyPendingPrefixAndWrap() {
        var before = TradingCoreState.empty(ProductLine.SPOT);
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(before, identities)) {
            var pending = new java.util.TreeMap<Long, com.surprising.aeron.service.state.account.TransferRuntime>();
            for (long id = 1; id <= 5; id++) pending.put(id,
                    new com.surprising.aeron.service.state.account.TransferRuntime(7,
                            new TransferFundsCommand(id, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                                    "SPOT", "USDT_PERPETUAL", "USDT", 1, "r" + id, "", 7L, 7L)));
            runtime.restorePendingTransfers(pending);
            assertThat(runtime.pendingTransfers(2)).extracting(t -> t.transferId()).containsExactly(1L, 2L);
            assertThat(runtime.pendingTransfers(2)).extracting(t -> t.transferId()).containsExactly(3L, 4L);
            assertThat(runtime.pendingTransfers(2)).extracting(t -> t.transferId()).containsExactly(5L);
            assertThat(runtime.pendingTransfers(2)).extracting(t -> t.transferId()).containsExactly(1L, 2L);
            assertThat(runtime.pendingTransfersSnapshot()).isEqualTo(pending);
        }
    }

    @Test
    void productTransferUsesOnlyBoundedRuntimeState() {
        TradingCoreState before = new RuntimeTestStateTransitions().adjustBalance(
                TradingCoreState.empty(ProductLine.SPOT), 7,
                new BalanceAdjustmentCommand("USDT", 1_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        TransferFundsCommand transfer = new TransferFundsCommand(91L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 250L,
                "transfer-91", "product allocation", 7L, 7L);

        assertThat(RuntimeAccountStateTransitions.transferOut(runtime, identities, 7L, transfer)).isTrue();
        assertThat(runtime.pendingTransfer(91L)).isNotNull();
        assertThat(runtime.balance(7L, identities.assetId("USDT")).availableUnits()).isEqualTo(750L);
        assertThat(RuntimeAccountStateTransitions.transferOut(runtime, identities, 7L, transfer)).isFalse();

        assertThat(RuntimeAccountStateTransitions.completeTransfer(runtime, 7L, 91L)).isTrue();
        assertThat(runtime.pendingTransfer(91L)).isNull();
        assertThat(RuntimeAccountStateTransitions.completeTransfer(runtime, 7L, 91L)).isFalse();
    }

    @Test
    void transferInCreditsTargetRuntime() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities);
        TransferFundsCommand transfer = new TransferFundsCommand(91L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 250L,
                "transfer-91", "product allocation", 7L, 7L);

        RuntimeAccountStateTransitions.transferIn(runtime, identities, 7L, transfer);

        assertThat(runtime.balance(7L, identities.assetId("USDT")).availableUnits()).isEqualTo(250L);
    }

    @Test
    void adjustsBalanceDirectlyInRuntimeAcrossEveryProductLine() {
        RuntimeTestStateTransitions reference = new RuntimeTestStateTransitions();
        for (ProductLine productLine : ProductLine.values()) {
            TradingCoreState before = TradingCoreState.empty(productLine);
            BalanceAdjustmentCommand command = new BalanceAdjustmentCommand("USDT", 1_000);
            TradingCoreState expected = reference.adjustBalance(before, 7, command);
            RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
            TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

            RuntimeAccountStateTransitions.adjustBalance(runtime, identities, 7, command);

            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .as(productLine.name())
                    .isEqualTo(expected);
        }
    }

    @Test
    void rejectedBalanceAdjustmentLeavesRuntimeUnchanged() {
        TradingCoreState before = new RuntimeTestStateTransitions().adjustBalance(
                TradingCoreState.empty(ProductLine.SPOT), 7, new BalanceAdjustmentCommand("USDT", 10));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimeAccountStateTransitions.adjustBalance(
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
        TradingCoreState expected = new RuntimeTestStateTransitions().updateRiskScanControl(before, command, 123);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeRiskStateTransitions.updateScanControl(runtime, command, 123);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    @Test
    void upsertsInstrumentDirectlyInRuntime() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.SPOT);
        RegisterInstrumentCommand command = new RegisterInstrumentCommand(
                "1", ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
        TradingCoreState expected = new RuntimeTestStateTransitions().registerInstrument(before, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeInstrumentStateTransitions.applyConfiguration(runtime, identities, command);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    @Test
    void updatesPositionModeDirectlyInRuntime() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        UpdatePositionModeCommand command = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        TradingCoreState expected = new RuntimeTestStateTransitions().updatePositionMode(before, 7, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThat(DerivativeAccountCommandProcessor.updatePositionMode(runtime, 7, command)).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        assertThat(DerivativeAccountCommandProcessor.updatePositionMode(runtime, 7, command)).isFalse();
    }

    @Test
    void updatesLeverageDirectlyInRuntime() {
        RuntimeTestStateTransitions reference = new RuntimeTestStateTransitions();
        TradingCoreState before = reference.registerInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0));
        UpdateLeverageCommand command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000);
        TradingCoreState expected = reference.updateLeverage(before, 7, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThat(DerivativeAccountCommandProcessor.updateLeverage(runtime, identities, 7, command)).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }
}
