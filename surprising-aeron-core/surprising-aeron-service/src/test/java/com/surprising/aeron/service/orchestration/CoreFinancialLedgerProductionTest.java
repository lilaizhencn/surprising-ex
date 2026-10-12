package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;
import java.util.List;
import java.util.function.BiConsumer;
import static org.assertj.core.api.Assertions.*;

/** Real command ingress with automatic Ledger activation; no snapshot/recovery or load generation. */
class CoreFinancialLedgerProductionTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test
    void liquidationInsuranceAndAdlCommitOriginalLedgerInProductionOrder() throws Exception {
        var line = ProductLine.LINEAR_PERPETUAL;
        try (var core = new TradingCoreRuntime(line)) {
            register(core, line);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)), TIME);
            balance(core, 1, "USDT", 110);
            balance(core, 2, "USDT", 10_000);
            trade(core, line, 10, 100);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 1, 2, TIME)), TIME);
            for (int polls = 0; core.runtimeState.firstRiskIncompleteScan() != null; polls++) {
                assertThat(polls).isLessThan(20);
                applied(core, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)), TIME);
            }
            var planned = core.tradingState().riskState().liquidations().values().stream()
                    .filter(value -> value.userId() == 1).findFirst().orElseThrow();
            var execute = new ExecuteLiquidationCommand(planned.liquidationId(), 2, 1, 0);
            var expected = expected(core.tradingState(), (runtime, identities) ->
                    RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(execute, List.of(), runtime, identities));
            long handoff = core.runtimeState.laneHandoffEpoch();
            applied(core, CoreMessageType.EXECUTE_LIQUIDATION, 0, TradingCommandCodec.encodeExecuteLiquidation(execute), TIME);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(expected);

            var topUp = new AdjustInsuranceFundCommand("USDT", 25 - expected.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
            expected = expected(expected, (runtime, identities) -> RuntimeInsuranceFundStateTransitions.adjust(runtime, identities, topUp));
            appliedWithGuard(core, CoreMessageType.ADJUST_INSURANCE_FUND, 0, TradingCommandCodec.encodeAdjustInsuranceFund(topUp), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            var insurance = new ResolveLiquidationCommand(planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, 25);
            expected = expected(expected, (runtime, identities) ->
                    RuntimeLiquidationResolution.applyRuntime(insurance, runtime, identities, List.of(planned.liquidationId())));
            appliedWithGuard(core, CoreMessageType.RESOLVE_LIQUIDATION, 0, TradingCommandCodec.encodeResolveLiquidation(insurance), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            var residual = expected.riskState().liquidations().get(planned.liquidationId());
            assertThat(residual.status()).isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
            var adl = new ExecuteAdlCommand(planned.liquidationId(), 2, "1", CoreMarginMode.CROSS,
                    CorePositionSide.NET, -10, 100, 2, 10, residual.deficitUnits());
            expected = expected(expected, (runtime, identities) -> RuntimeAdlExecution.applyRuntime(adl, runtime, identities));
            appliedWithGuard(core, CoreMessageType.EXECUTE_ADL, 0, TradingCommandCodec.encodeExecuteAdl(adl), TIME);
            assertThat(core.tradingState()).isEqualTo(expected);
            assertThat(core.tradingState().users().values()).allSatisfy(user -> assertThat(user.positions().values()).allSatisfy(position -> assertThat(position.signedQuantitySteps()).isZero()));
            assertThat(core.runtimeState.systemLedgerStarted()).isTrue();
            core.assertClusterCallbackComplete();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void expiryCommandUsesAutomaticallyStartedLedgerAndMatchesColdStateTransitions(ProductLine line) throws Exception {
        try (var core = new TradingCoreRuntime(line)) {
            register(core, line);
            String asset = line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
            for (long user : new long[]{1, 2}) balance(core, user, asset, 10_000);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct() ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME)), TIME);
            trade(core, line, 2, line == ProductLine.OPTION ? 10 : 100);
            var settlement = new SettleInstrumentCommand(901, "1", 120, 0);
            var opening = core.tradingState();
            var expected = expected(opening, (runtime, identities) ->
                    RuntimeLifecycleSettlement.apply(opening, settlement, null, null, null, runtime, identities));
            long handoff = core.runtimeState.laneHandoffEpoch();
            appliedWithGuard(core, CoreMessageType.SETTLE_INSTRUMENT, 0, TradingCommandCodec.encodeSettleInstrument(settlement), TIME + 2_000);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(expected);
            assertThat(core.tradingState().users().values()).allSatisfy(user -> assertThat(user.positions().values()).allSatisfy(position -> assertThat(position.signedQuantitySteps()).isZero()));
            assertThat(core.runtimeState.systemLedgerStarted()).isTrue();
            core.assertClusterCallbackComplete();
        }
    }

    private TradingCoreState expected(TradingCoreState before, BiConsumer<TradingRuntimeState, RuntimeIdentityRegistry> mutation) {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(before, identities)) {
            mutation.accept(runtime, identities);
            return RuntimeStateMaterializer.materialize(runtime, identities);
        }
    }

    private void register(TradingCoreRuntime core, ProductLine line) {
        var type = ContractType.valueOf(line.contractTypeCode());
        boolean inverse = type.isInverse();
        applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                        "BTC", "USDT", inverse ? "BTC" : "USDT", 1, 1, inverse ? 1_000 : 1,
                        100_000, 50_000, 0, 0, line.isDeliveryProduct() ? TIME + 1_000 : 0,
                        line.isOptionProduct() ? OptionType.CALL.ordinal() : -1, line.isOptionProduct() ? 100 : 0)), TIME);
    }

    private void balance(TradingCoreRuntime core, long user, String asset, long amount) {
        applied(core, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, amount)), TIME);
    }

    private void trade(TradingCoreRuntime core, ProductLine line, long quantity, long price) {
        for (long user : new long[]{2, 1}) applied(core, CoreMessageType.PLACE_ORDER, user,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                        user == 2 ? CoreOrderSide.SELL : CoreOrderSide.BUY, price, quantity, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        false, "financial-" + user)), TIME);
    }

    private CoreResponse appliedWithGuard(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload, long timestamp) throws Exception {
        try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            return applied(core, type, user, payload, timestamp);
        }
    }

    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload, long timestamp) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(948, seq), core.productLine,
                CommandSource.OPERATIONS, 948, seq, user, timestamp, seq), payload);
        var response = CoreTestCompletion.applyAsynchronously(core, message, timestamp, seq);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
