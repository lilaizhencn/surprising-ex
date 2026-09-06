package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreMaintenanceTest {
    private long sequence;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void statusOnlyChangesPreserveOpenStateMathAndRecoverWithoutReleasingMaintenance(ProductLine line) {
        try (var state=fixture(line)) {
            applied(state,command(line,11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(301,false,CoreOrderSide.SELL))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(302,false,CoreOrderSide.BUY))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(303,false,CoreOrderSide.BUY))));
            long funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            var instrument=state.tradingState().instruments().get("BTC-USDT");
            var halt=config(instrument,1,2,2);
            applied(state,command(line,0,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(halt)));
            assertThat(state.tradingState().instruments().get("BTC-USDT").changeId()).isEqualTo(1);
            assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            var denied=apply(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(304,false,CoreOrderSide.BUY))));
            assertThat(denied.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            // A new calculation reference remains forbidden while an order or position uses the instrument.
            var edit=apply(state,command(line,0,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(config(instrument,3,3,2))));
            assertThat(edit.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            try (var restored=CoreProbeState.fromSnapshot(line,state.snapshot(501))) {
                var recovered=restored.tradingState().instruments().get("BTC-USDT");
                assertThat(recovered.status()).isEqualTo(com.surprising.instrument.api.model.InstrumentStatus.HALT);
                assertThat(recovered.lastChangeId()).isEqualTo(2);
                assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(restored.tradingState())).isEqualTo(funds);
                applied(restored,gate(line,0,new CoreInstrumentMaintenance(999,CoreInstrumentMaintenance.Mode.HALTED,0)));
                applied(restored,command(line,0,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(config(instrument,1,4,1))));
                assertThat(restored.tradingState().instruments().get("BTC-USDT").maintenance().taskId()).isEqualTo(999);
                assertThat(apply(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(305,false,CoreOrderSide.BUY)))).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                applied(restored,gate(line,999,CoreInstrumentMaintenance.TRADING));
                applied(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(306,false,CoreOrderSide.BUY))));
                // A delayed pause cannot override the newer resume.
                assertThat(apply(restored,command(line,0,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(halt))).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            }
        }
    }

    private static UpsertInstrumentCommand config(com.surprising.aeron.service.state.CoreInstrumentState v,long calculationId,long auditId,int status) {
        return new UpsertInstrumentCommand(v.symbol(),calculationId,v.contractType().ordinal(),v.baseAsset(),v.quoteAsset(),v.settleAsset(),
                v.notionalMultiplierUnits(),v.priceTickUnits(),v.settleScaleUnits(),v.initialMarginRatePpm(),v.maintenanceMarginRatePpm(),
                v.makerFeeRatePpm(),v.takerFeeRatePpm(),v.expiryEpochMillis(),v.optionType()==null?-1:v.optionType().ordinal(),v.strikePriceTicks(),
                v.maxLeveragePpm(),v.maxPositionNotionalUnits(),v.userOpenInterestLimitRatePpm(),v.userOpenInterestLimitFloorUnits(),v.riskLimitBrackets(),status,auditId);
    }


    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void gateSurvivesSnapshotRejectsOpeningAndRequiresTaskOwnership(ProductLine line) {
        try (var state = fixture(line)) {
            var mode = line == ProductLine.SPOT ? CoreInstrumentMaintenance.Mode.HALTED : CoreInstrumentMaintenance.Mode.REDUCE_ONLY;
            applied(state,gate(line,0,new CoreInstrumentMaintenance(701,mode,0)));
            long funds = com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            var denied = apply(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(200,false,CoreOrderSide.BUY))));
            assertThat(denied.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            try (var restored = CoreProbeState.fromSnapshot(line,state.snapshot(500))) {
                assertThat(restored.tradingState().instruments().get("BTC-USDT").maintenance()).isEqualTo(new CoreInstrumentMaintenance(701,mode,0));
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                var wrongTask = apply(restored,gate(line,702,CoreInstrumentMaintenance.TRADING));
                assertThat(wrongTask.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                applied(restored,gate(line,701,CoreInstrumentMaintenance.TRADING));
                applied(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(201,false,CoreOrderSide.BUY))));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value=ProductLine.class,names={"SPOT"},mode=EnumSource.Mode.EXCLUDE)
    void fixedPriceClearanceIsFundedIdempotentAndRecoversBetweenChunks(ProductLine line) {
        try (var state = fixture(line)) {
            applied(state,command(line,11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(101,false,CoreOrderSide.SELL))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(102,false,CoreOrderSide.BUY))));
            long initialTotal = total(state,line);
            applied(state,gate(line,0,new CoreInstrumentMaintenance(801,CoreInstrumentMaintenance.Mode.SETTLEMENT,120)));
            var type=ContractType.valueOf(line.contractTypeCode());
            applied(state,command(line,0,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                    ? new ApplyMarkPriceCommand("BTC-USDT",1,10_000,10_000,10_000,2,1_700_000_000_000L+sequence+1)
                    : new ApplyMarkPriceCommand("BTC-USDT",1,10_000,2,1_700_000_000_000L+sequence+1))));
            applied(state,command(line,0,CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(16))));
            assertThat(state.tradingState().riskState().liquidations()).isEmpty();
            if (type.isPerpetual()) {
                assertThat(apply(state,command(line,0,CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(899,"BTC-USDT",1,10_000)))).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            }
            // A different price and premature ordinary expiry settlement must never clear positions.
            var repriced = apply(state,command(line,0,CoreMessageType.SETTLE_INSTRUMENT,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(801,"BTC-USDT",1,121,0))));
            assertThat(repriced.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            var first = applied(state,command(line,0,CoreMessageType.SETTLE_INSTRUMENT,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(801,"BTC-USDT",1,120,0,0,1,0,1))));
            var progress = CoreSettlementProgressCodec.decode(first.data());
            try (var restored = CoreProbeState.fromSnapshot(line,state.snapshot(700))) {
                int steps = 0;
                while (!progress.complete()) {
                    assertThat(++steps).isLessThan(20);
                    var message = command(line,0,CoreMessageType.SETTLE_INSTRUMENT,TradingCommandCodec.encodeSettleInstrument(
                            new SettleInstrumentCommand(801,"BTC-USDT",1,120,0,progress.nextCursorUserId(),1,progress.nextCursorOrderId(),1)));
                    var response = applied(restored,message);
                    progress = CoreSettlementProgressCodec.decode(response.data());
                    assertThat(apply(restored,message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                }
                assertThat(restored.tradingState().users().values()).allSatisfy(user -> {
                    assertThat(user.positions().values()).allSatisfy(p -> assertThat(p.signedQuantitySteps()).isZero());
                    assertThat(user.balances().values()).allSatisfy(balance -> assertThat(balance.lockedUnits()).isZero());
                });
                assertThat(total(restored,line)).isEqualTo(initialTotal);
                applied(restored,gate(line,801,new CoreInstrumentMaintenance(801,CoreInstrumentMaintenance.Mode.CLOSED,120)));
                assertThat(apply(restored,gate(line,801,CoreInstrumentMaintenance.TRADING)).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            }
        }
    }

    private CoreProbeState fixture(ProductLine line) {
        var state = new CoreProbeState(line);
        var type = ContractType.valueOf(line.contractTypeCode());
        String asset = type.isInverse() ? "BTC" : "USDT";
        applied(state,command(line,1,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(
                new UpsertInstrumentCommand("BTC-USDT",1,type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1_000:1,
                        100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,type.isOption()?0:-1,type.isOption()?100:0))));
        applied(state,command(line,1,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                ? new ApplyMarkPriceCommand("BTC-USDT",1,100,100,100,1,1_700_000_000_000L)
                : new ApplyMarkPriceCommand("BTC-USDT",1,100,1,1_700_000_000_000L))));
        applied(state,command(line,11,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(line==ProductLine.SPOT?"BTC":asset,20_000))));
        applied(state,command(line,22,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,20_000))));
        return state;
    }
    private static long total(CoreProbeState state, ProductLine line) {
        String asset = ContractType.valueOf(line.contractTypeCode()).isInverse()?"BTC":"USDT";
        var t = state.tradingState().treasuryState();
        return state.tradingState().users().values().stream().mapToLong(u -> u.totalUnits(asset)).sum()
                + t.feeBalances().getOrDefault(asset,0L)+t.insuranceBalances().getOrDefault(asset,0L)
                + t.clearingPnlBalances().getOrDefault(asset,0L)+t.roundingResidualBalances().getOrDefault(asset,0L);
    }
    private CoreMessage gate(ProductLine line,long expected,CoreInstrumentMaintenance value) {
        return command(line,0,CoreMessageType.UPDATE_INSTRUMENT_MAINTENANCE,
                CoreMaintenanceCodec.encodeCommand(new CoreMaintenanceCodec.Command("BTC-USDT",expected,value)));
    }
    private CoreMessage command(ProductLine line,long user,CoreMessageType type,byte[] payload) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type,UUID.randomUUID(),line,CommandSource.OPERATIONS,981,seq,user,1_700_000_000_000L+seq,seq),payload);
    }
    private static PlaceOrderCommand order(long id,boolean reduce,CoreOrderSide side) {
        return new PlaceOrderCommand(id,"BTC-USDT",1,side,100,4,reduce,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"maint-test-"+id);
    }
    private static CoreResponse applied(CoreProbeState state,CoreMessage message) {
        var response = apply(state,message);
        assertThat(response.commandStatus()).as("%s: %s",message.header().messageType(),response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
    private static CoreResponse apply(CoreProbeState state,CoreMessage message) {
        var response = state.apply(message);
        if (response.resultCode()==CoreResultCode.MATCHING_PENDING) response = state.completeMatchingSynchronously(state.matchingSequence(message.header().commandId()),message.header().submittedAtEpochMillis(),message.header().sourceSequence());
        while(state.firstPendingMatchingSequence()!=0) state.completeMatchingSynchronously(state.firstPendingMatchingSequence(),message.header().submittedAtEpochMillis(),message.header().sourceSequence());
        return response;
    }
}
