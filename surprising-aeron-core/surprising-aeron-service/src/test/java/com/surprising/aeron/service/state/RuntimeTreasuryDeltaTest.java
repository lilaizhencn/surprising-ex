package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.settlement.FundsPosting;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RuntimeTreasuryDeltaTest {

    @Test
    void recordsFirstTreasuryValuesAndIncludesAllSevenSubledgersInFundsAndRollback() {
        TreasuryRuntime treasury = new TreasuryRuntime();
        treasury.setFee(7, 90);
        treasury.setInsurance(7, 500, 20);
        treasury.setLiquidationFee(7, 1);
        treasury.setFundingResidual(7, 4);
        treasury.setRoundingResidual(7, 5);
        treasury.setClearingPnl(7, -100);
        treasury.clearChangedKeys();

        treasury.setFee(7, 95);
        treasury.setFee(7, 100);
        treasury.setInsurance(7, 450, 25);
        treasury.setLiquidationFee(7, 3);
        treasury.setFundingResidual(7, 0);
        treasury.setRoundingResidual(7, 6);
        treasury.setClearingPnl(7, -54);

        assertThat(treasury.patchAssetBefore(7)).isEqualTo(
                new TreasuryRuntime.AssetState(90, 500, 20, 1, 4, 5, -100));
        RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        treasury.appendFundsDelta(funds);
        funds.requireConserved(false);
        assertThat(funds.toDelta().postings()).containsExactly(
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FEE, 10),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.INSURANCE, -50),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.DEFICIT, -5),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.LIQUIDATION_FEE, 2),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FUNDING_RESIDUAL, -4),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.ROUNDING_RESIDUAL, 1),
                new RuntimeFundsDelta.Posting(7, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.CLEARING_PNL, 46));

        treasury.rollbackChangedValues();

        assertThat(treasury.fee(7)).isEqualTo(90);
        assertThat(treasury.insurance(7)).isEqualTo(500);
        assertThat(treasury.insuranceDeficit(7)).isEqualTo(20);
        assertThat(treasury.liquidationFee(7)).isEqualTo(1);
        assertThat(treasury.fundingResidual(7)).isEqualTo(4);
        assertThat(treasury.roundingResidual(7)).isEqualTo(5);
        assertThat(treasury.clearingPnl(7)).isEqualTo(-100);
        assertThat(treasury.hasChangedValues()).isFalse();
        assertThat(treasury.patchAssetBefore(7)).isNull();
    }

    @Test
    void keepsSeparateAssetBeforeValuesAcrossRepeatedTransactions() {
        TreasuryRuntime treasury = new TreasuryRuntime();
        for (int asset = 0; asset < 25; asset++) treasury.setFee(asset, asset);
        treasury.clearChangedKeys();
        for (int transaction = 0; transaction < 3; transaction++) {
            for (int asset = 24; asset >= 0; asset--) {
                treasury.setFee(asset, 100 + asset);
                treasury.setFee(asset, 200 + asset);
                treasury.setInsurance(asset, 300 + asset, asset);
            }
            RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator(75);
            treasury.appendFundsDelta(funds);
            var postings = funds.toDelta().postings();
            for (int asset = 0; asset < 25; asset++) {
                assertThat(postings).contains(new RuntimeFundsDelta.Posting(asset,
                        FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FEE, 200));
                assertThat(postings).contains(new RuntimeFundsDelta.Posting(asset,
                        FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.INSURANCE, 300 + asset));
            }
            treasury.rollbackChangedValues();
            for (int asset = 0; asset < 25; asset++) {
                assertThat(treasury.fee(asset)).isEqualTo(asset);
                assertThat(treasury.insurance(asset)).isZero();
                assertThat(treasury.insuranceDeficit(asset)).isZero();
            }
            assertThat(treasury.hasChangedValues()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = FundsPosting.Subledger.class, names = {
            "FEE", "INSURANCE", "DEFICIT", "FUNDING_RESIDUAL", "ROUNDING_RESIDUAL", "CLEARING_PNL"})
    void rejectsOverflowInSingleOrLaterAssetBeforeWritingAnyFunds(FundsPosting.Subledger subledger) {
        for (boolean anotherAsset : new boolean[] { false, true }) {
            TreasuryRuntime treasury = new TreasuryRuntime();
            treasury.setFee(1, 4);
            RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
            if (anotherAsset) delta.addFee(1, 3);
            switch (subledger) {
                case FEE -> { treasury.setFee(2, Long.MAX_VALUE); delta.addFee(2, 1); }
                case INSURANCE -> { treasury.setInsurance(2, Long.MAX_VALUE, 0); delta.addInsurance(2, 1); }
                case DEFICIT -> { treasury.setDeficit(2, Long.MAX_VALUE); delta.addDeficit(2, 1); }
                case FUNDING_RESIDUAL -> { treasury.setFundingResidual(2, Long.MAX_VALUE); delta.addFundingResidual(2, 1); }
                case ROUNDING_RESIDUAL -> { treasury.setRoundingResidual(2, Long.MIN_VALUE); delta.addRoundingResidual(2, -1); }
                case CLEARING_PNL -> { treasury.setClearingPnl(2, Long.MIN_VALUE); delta.addClearing(2, -1); }
                default -> throw new AssertionError("unexpected Treasury subledger");
            }
            treasury.clearChangedKeys();

            assertThatThrownBy(() -> delta.apply(treasury)).isInstanceOf(ArithmeticException.class);

            assertThat(treasury.fee(1)).isEqualTo(4);
            assertThat(treasury.hasChangedValues()).isFalse();
            long unchanged = switch (subledger) {
                case FEE -> treasury.fee(2);
                case INSURANCE -> treasury.insurance(2);
                case DEFICIT -> treasury.insuranceDeficit(2);
                case FUNDING_RESIDUAL -> treasury.fundingResidual(2);
                case ROUNDING_RESIDUAL -> treasury.roundingResidual(2);
                case CLEARING_PNL -> treasury.clearingPnl(2);
                default -> throw new AssertionError("unexpected Treasury subledger");
            };
            assertThat(unchanged).isEqualTo(subledger == FundsPosting.Subledger.ROUNDING_RESIDUAL
                    || subledger == FundsPosting.Subledger.CLEARING_PNL ? Long.MIN_VALUE : Long.MAX_VALUE);
        }
    }

    @Test
    void clearingTwicePreservesFundsAndCapturesTheNextBeforeValue() {
        var treasury = new TreasuryRuntime();
        treasury.setFee(7, 100);
        treasury.setFundingSettlement(8, 11);
        treasury.setLifecycleSettlement(9, 12);
        treasury.clearChangedKeys(); treasury.clearChangedKeys();
        assertThat(treasury.fee(7)).isEqualTo(100);
        assertThat(treasury.changedAssets().isEmpty()).isTrue();
        assertThat(treasury.changedFundingSymbols().isEmpty()).isTrue();
        assertThat(treasury.changedLifecycleSymbols().isEmpty()).isTrue();
        assertThat(treasury.patchAssetBefore(7)).isNull();
        treasury.setFee(7, 200);
        assertThat(treasury.patchAssetBefore(7)).isNotNull();
        assertThat(treasury.changedAssets().contains(7)).isTrue();
    }

    @Test
    void everyTreasurySubledgerContributesToTheBusinessStateHash() {
        CoreTreasuryState empty = CoreTreasuryState.empty();
        Set<Long> hashes = Set.of(
                state(empty, 1).businessStateHash(),
                state(empty.adjustFee("USDT", 1), 1).businessStateHash(),
                state(empty.adjustInsurance("USDT", 1), 1).businessStateHash(),
                state(empty.adjustDeficit("USDT", 1), 1).businessStateHash(),
                state(empty.adjustLiquidationFee("USDT", 1), 1).businessStateHash(),
                state(empty.adjustFundingResidual("USDT", 1), 1).businessStateHash(),
                state(empty.adjustRoundingResidual("USDT", 1), 1).businessStateHash(),
                state(empty.adjustClearingPnl("USDT", 1), 1).businessStateHash());

        assertThat(hashes).hasSize(8);
    }

    @Test
    void projectsEveryTreasurySubledgerWithoutDroppingFunds() {
        CoreTreasuryState treasury = CoreTreasuryState.ofSubledgers(
                Map.of("USDT", 1L), Map.of("USDT", 2L), Map.of("USDT", 3L),
                Map.of("USDT", -4L), Map.of("USDT", 5L), Map.of("USDT", -6L),
                Map.of("USDT", 7L));
        TradingCoreState expected = state(treasury, 1);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        TradingRuntimeState runtime = RuntimeStateProjector.project(expected, identities);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        assertThat(expected.businessStateHash()).isEqualTo(expected.fullBusinessStateHash());
    }

    @Test
    void appliesOnlyChangedTreasuryEntriesAndPreservesRuntimeParity() {
        UUID fundingCommandId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID lifecycleCommandId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        CoreTreasuryState beforeTreasury = new CoreTreasuryState(
                Map.of("USDT", 100L, "BTC", 200L),
                Map.of("USDT", 50L),
                Map.of(),
                Map.of("1", 7L),
                Map.of("2", 3L),
                Map.of("1", new CoreTreasuryState.FundingProgress(7, 10_000, 0, 11, fundingCommandId, 60_000, 1)),
                Map.of("2", new CoreTreasuryState.LifecycleProgress(3, 60_000,
                        0, true, 0, 12, lifecycleCommandId)));
        TradingCoreState before = state(beforeTreasury, 1);

        CoreTreasuryState afterTreasury = beforeTreasury
                .adjustFee("USDT", -100)
                .adjustInsurance("USDT", -100)
                .recordFunding("1", 8)
                .recordLifecycle("2", 4);
        TradingCoreState after = state(afterTreasury, 2);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        TreasuryRuntime treasury = runtime.treasury();
        int usdt = identities.assetId("USDT");
        treasury.setFee(usdt, 0);
        treasury.setInsurance(usdt, -50, 0);
        treasury.setFundingSettlement(identities.symbolId("1"), 8);
        treasury.setLifecycleSettlement(identities.symbolId("2"), 4);
        runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 2);

        assertThat(treasury.fee(identities.assetId("BTC"))).isEqualTo(200L);
        assertThat(treasury.fee(identities.assetId("USDT"))).isZero();
        assertThat(treasury.insurance(identities.assetId("USDT"))).isEqualTo(-50L);
        assertThat(treasury.insuranceDeficit(identities.assetId("USDT"))).isZero();
        assertThat(treasury.fundingSettlement(identities.symbolId("1"))).isEqualTo(8L);
        assertThat(treasury.fundingProgress(identities.symbolId("1"))).isNull();
        assertThat(treasury.lifecycleSettlement(identities.symbolId("2"))).isEqualTo(4L);
        assertThat(treasury.lifecycleProgress(identities.symbolId("2"))).isNull();
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(after);
    }

    @Test
    void aggregatesContributionsWithoutNettingDifferentAssets() {
        RuntimeTreasuryDelta first = new RuntimeTreasuryDelta();
        first.addFee(1, 7);
        first.addClearing(2, -11);
        RuntimeTreasuryDelta second = new RuntimeTreasuryDelta();
        second.addFee(2, 13);
        second.addClearing(1, -17);

        first.merge(second);

        assertThat(first.size()).isEqualTo(2);
        assertThat(first.assetId(0)).isEqualTo(1);
        assertThat(first.feeUnits(0)).isEqualTo(7);
        assertThat(first.clearingUnits(0)).isEqualTo(-17);
        assertThat(first.assetId(1)).isEqualTo(2);
        assertThat(first.feeUnits(1)).isEqualTo(13);
        assertThat(first.clearingUnits(1)).isEqualTo(-11);
    }

    @Test
    void derivesLaneTreasuryAcknowledgementFromPrimitiveFundsPostings() {
        RuntimeFundsDelta funds = new RuntimeFundsDelta(List.of(
                new RuntimeFundsDelta.Posting(1, FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.FEE, 7),
                new RuntimeFundsDelta.Posting(1, FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.LIQUIDATION_FEE, 11),
                new RuntimeFundsDelta.Posting(1, FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.DEFICIT, -13),
                new RuntimeFundsDelta.Posting(1, FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.FUNDING_RESIDUAL, -17),
                new RuntimeFundsDelta.Posting(2, FundsPosting.OwnerKind.USER, 9,
                        FundsPosting.Subledger.AVAILABLE, -19)));

        RuntimeTreasuryDelta treasury = funds.treasuryDelta();

        assertThat(treasury.size()).isOne();
        assertThat(treasury.assetId(0)).isEqualTo(1);
        assertThat(treasury.feeUnits(0)).isEqualTo(7);
        assertThat(treasury.insuranceUnits(0)).isEqualTo(11);
        assertThat(treasury.deficitUnits(0)).isEqualTo(13);
        assertThat(treasury.fundingResidualUnits(0)).isEqualTo(-17);
    }

    @Test
    void rejectsUserOnlySubledgersForTreasuryOwner() {
        for (FundsPosting.Subledger subledger : List.of(
                FundsPosting.Subledger.RESERVATION, FundsPosting.Subledger.POSITION_MARGIN)) {
            RuntimeFundsDelta funds = new RuntimeFundsDelta(List.of(
                    new RuntimeFundsDelta.Posting(1, FundsPosting.OwnerKind.TREASURY, 0, subledger, 1)));

            assertThatThrownBy(funds::treasuryDelta)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("invalid Treasury funds subledger: " + subledger);
        }
    }

    private static TradingCoreState state(CoreTreasuryState treasury, long revision) {
        CoreInstrument btc = instrument("1", "BTC");
        CoreInstrument eth = instrument("2", "ETH");
        return new TradingCoreState(ProductLine.LINEAR_PERPETUAL, revision,
                Map.of(), Map.of(), Map.of(btc.instrumentId(), btc, eth.instrumentId(), eth),
                CoreRiskState.empty(), treasury);
    }

    private static CoreInstrument instrument(String instrumentId, String baseAsset) {
        return CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(instrumentId, ContractType.LINEAR_PERPETUAL.ordinal(),
                        baseAsset, "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0));
    }
}
