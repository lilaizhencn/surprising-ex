package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class CoreLedgerQueryTest {
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    private long querySequence;

    @Test
    void treasuryQueryReadsOriginalLedgerWithoutAccountHandoffAndConsumesResultOnce() throws Exception {
        try (var core = new TradingCoreRuntime(LINE)) {
            core.activate();
            core.runtimeState.startSystemLedgerLane();
            var adjustment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_INSURANCE_FUND,
                    new UUID(936, 1), LINE, CommandSource.OPERATIONS, 936, 1, 0, 1, 1),
                    TradingCommandCodec.encodeAdjustInsuranceFund(new AdjustInsuranceFundCommand("USDT", 250)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, adjustment).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            int assetId = core.identities.assetId("USDT");
            assertThatThrownBy(() -> core.runtimeState.treasury().insurance(assetId)).isInstanceOf(IllegalStateException.class);
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var response = query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreStateQueryCodec.decodeTreasuryState(response.data())).singleElement()
                        .satisfies(asset -> {
                            assertThat(asset.asset()).isEqualTo("USDT");
                            assertThat(asset.insuranceBalanceUnits()).isEqualTo(250);
                        });
            }
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void insurancePageAllocatesWholeBudgetIncludingClaimsOutsidePageWithoutAccountReads() throws Exception {
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var first = new CoreLiquidationState(1, 1001, "1", 1, 1, 70, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        var second = new CoreLiquidationState(2, 1002, "1", 1, 1, 30, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        var state = new TradingCoreState(LINE, 0,
                Map.of(1001L, CoreUserState.empty(LINE, 1001), 1002L, CoreUserState.empty(LINE, 1002)),
                Map.of(), Map.of("1", instrument), new CoreRiskState(Map.of(), Map.of(), Map.of(1L, first, 2L, second), Map.of(), 3),
                CoreTreasuryState.empty().adjustInsurance("USDT", 25));
        try (var core = coreWithInitialState(state)) {
            core.activate(); core.runtimeState.startSystemLedgerLane();
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var pageOne = insuranceQuery(core, 0);
                assertThat(pageOne.complete()).isFalse();
                assertThat(pageOne.nextCursorLiquidationId()).isEqualTo(1);
                assertThat(pageOne.resolutions()).singleElement().satisfies(claim -> {
                    assertThat(claim.liquidationId()).isEqualTo(1);
                    assertThat(claim.recommendedCoveredUnits()).isEqualTo(18);
                });
                var pageTwo = insuranceQuery(core, 1);
                assertThat(pageTwo.complete()).isTrue();
                assertThat(pageTwo.resolutions()).singleElement().satisfies(claim -> {
                    assertThat(claim.liquidationId()).isEqualTo(2);
                    assertThat(claim.recommendedCoveredUnits()).isEqualTo(7);
                });
            }
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.runtimeState.treasuryForQuery(core.identities).insuranceBalances()).containsEntry("USDT", 25L);
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void oversizedTreasuryResponseRejectsOnlyQueryAndLedgerStillCompletesNextQuery() {
        var fees = new LinkedHashMap<String, Long>();
        for (int asset = 0; asset < 10_001; asset++) fees.put("ASSET" + asset, 1L);
        var state = new TradingCoreState(LINE, 0, Map.of(), Map.of(), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.ofSubledgers(fees, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        try (var core = coreWithInitialState(state)) {
            core.activate(); core.runtimeState.startSystemLedgerLane();
            var response = query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            assertThat(insuranceQuery(core, 0).resolutions()).isEmpty();
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void coldReadFailureDoesNotPoisonOriginalFinancialLedger() {
        try (var core = new TradingCoreRuntime(LINE)) {
            core.activate(); core.runtimeState.startSystemLedgerLane();
            assertThatThrownBy(() -> core.runtimeState.queryLedgerAtRest(ledger -> {
                throw new IllegalArgumentException("invalid cold query");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid cold query");
            assertThat(CoreStateQueryCodec.decodeTreasuryState(query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]).data()))
                    .isEmpty();
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void closeJoinsLedgerBeforeClearingUnconsumedQueryResults() {
        var core = new TradingCoreRuntime(LINE);
        try {
            core.activate(); core.runtimeState.startSystemLedgerLane();
            var message = message(CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
            assertThat(CoreTestCompletion.applyAsynchronously(core, message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(core.querySequence(message.header().commandId())).isNotZero();
        } finally { core.close(); }
        assertThat(core.runtimeState.systemLedgerStarted()).isFalse();
        assertThat(core.bookQueries.queryIds).isEmpty();
        assertThat(core.bookQueries.completedBookQueries).isEmpty();
    }

    private TradingCoreRuntime coreWithInitialState(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    private CoreLiquidationWorkView insuranceQuery(TradingCoreRuntime core, long cursor) {
        var payload = CoreLiquidationWorkCodec.encodeQuery(new CoreLiquidationWorkView.Query(
                LINE, CoreLiquidationWorkView.Purpose.INSURANCE, cursor, 1, 1_024));
        var response = query(core, CoreMessageType.LIQUIDATION_WORK_QUERY, payload);
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, byte[] payload) {
        var message = message(type, payload);
        assertThat(core.requiresOwnerLaneAccessForPreparation(message)).isFalse();
        var response = CoreTestCompletion.applyAsynchronously(core, message);
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long id = core.querySequence(message.header().commandId());
        assertThat(id).isNotZero();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((response = core.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.takeQueryResult(id)).isNull();
        assertThat(core.querySequence(message.header().commandId())).isZero();
        return response;
    }

    private CoreMessage message(CoreMessageType type, byte[] payload) {
        long seq = ++querySequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(937, seq), LINE, CommandSource.OPERATIONS,
                937, seq, 0, 1, seq), payload);
    }
}
