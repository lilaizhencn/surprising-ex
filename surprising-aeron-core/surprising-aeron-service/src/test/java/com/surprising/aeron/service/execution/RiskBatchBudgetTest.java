package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class RiskBatchBudgetTest {
    private long sequence;
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void heavySymbolCannotConsumeEverySliceOfSharedBudget(ProductLine line) {
        try (var state = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            String asset=type.isInverse()?"BTC":"USDT";
            for (int i=0;i<2;i++) {
                String symbol="SYM"+i+"-USDT";
                applied(state,command(line,CoreMessageType.UPSERT_INSTRUMENT,
                        TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol,1,
                                type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1000:1,
                                100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
                applied(state,mark(line,symbol,1));
            }
            for(long user=1;user<=50;user++) {
                applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,1_000_000))));
                applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,
                                user<=48?"SYM0-USDT":"SYM1-USDT",1,
                                user%2==1?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                                CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            }
            long funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            applied(state,batch(line,work(state,line),16));
            assertThat(state.tradingState().riskState().scans().get("SYM0-USDT").riskComplete()).isFalse();
            assertThat(state.tradingState().riskState().scans().get("SYM1-USDT").lastScheduledRevision()).isPositive();
            try(var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                for(int i=0;i<30 && work(state,line).riskScanPending();i++) {
                    var next=batch(line,work(state,line),64);
                    applied(state,next);applied(restored,next);
                }
                assertThat(work(state,line).riskScanPending()).isFalse();
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void budgetSpansSymbolsIsBoundedAndRejectsStaleTokensAcrossRecovery(ProductLine line) {
        try (var state = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            for (int i=0;i<5;i++) {
                String symbol="SYM"+i+"-USDT";
                applied(state, command(line, CoreMessageType.UPSERT_INSTRUMENT,
                        TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol,1,
                                type.ordinal(),"BTC","USDT",type.isInverse()?"BTC":"USDT",1,1,
                                type.isInverse()?1000:1,100_000,50_000,0,0,
                                type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
                applied(state, mark(line,symbol,1));
            }
            for(long user:new long[]{7,8}) applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(type.isInverse()?"BTC":"USDT",1_000_000))));
            for(long user:new long[]{7,8}) applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"SYM4-USDT",1,
                            user==7?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            long funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            var first=work(state,line);
            applied(state, batch(line,first,2));
            assertThat(state.tradingState().riskState().scans().values().stream()
                    .filter(s->s.riskComplete()).count()).isEqualTo(2);
            long hash=state.tradingState().businessStateHash();
            assertThat(apply(state,batch(line,first,2)).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(hash);
            applied(state,mark(line,first.riskScanContinuation().symbol(),2));
            var next=work(state,line);
            assertThat(next.riskScanContinuation().symbol()).isNotEqualTo(first.riskScanContinuation().symbol());
            try(var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                assertThat(work(restored,line).riskScanContinuation()).isEqualTo(next.riskScanContinuation());
                var finish=batch(line,next,64);
                applied(state,finish);applied(restored,finish);
                assertThat(state.tradingState().riskState().scans().values()).allMatch(s->s.riskComplete());
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    private CoreLiquidationWorkView work(TradingCoreRuntime state,ProductLine line) {
        var response=apply(state,command(line,CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(line,CoreLiquidationWorkView.Purpose.EXECUTION,0,1000,1_048_576)));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }
    private CoreMessage batch(ProductLine line,CoreLiquidationWorkView work,int budget) {
        return command(line,CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work,0,budget)));
    }
    private CoreMessage mark(ProductLine line,String symbol,long priceSequence) {
        return command(line,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(
                line==ProductLine.OPTION ? new ApplyMarkPriceCommand(symbol,1,100,100,100,priceSequence,TIME)
                        :new ApplyMarkPriceCommand(symbol,1,100,priceSequence,TIME)));
    }
    private CoreMessage command(ProductLine line,CoreMessageType type,byte[] payload) {
        return command(line,type,0,payload);
    }
    private CoreMessage command(ProductLine line,CoreMessageType type,long user,byte[] payload) {
        long seq=++sequence;
        var id=UUID.randomUUID();
        return new CoreMessage(type==CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq)
                : CoreMessageHeader.command(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq),payload);
    }
    private static CoreResponse apply(TradingCoreRuntime state,CoreMessage message) {
        var response=state.apply(message);
        if(response.resultCode()==CoreResultCode.MATCHING_PENDING)
            response=state.commits.completeMatchingSynchronously(state.matchingSequence(message.header().commandId()),
                    message.header().submittedAtEpochMillis(),message.header().sourceSequence());
        return response;
    }
    private static void applied(TradingCoreRuntime state,CoreMessage message) {
        var response=apply(state,message);
        assertThat(response.commandStatus()).as("%s %s",message.header().messageType(),response.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
    }
}
