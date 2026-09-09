package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Actual paused-instrument admission after snapshot recovery; no database or scheduler in the measured path. */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3) @Measurement(iterations=5) @Fork(1) @Threads(1)
public class InstrumentPauseAdmissionBenchmark {
    @State(Scope.Thread)
    public static class Fixture {
        @Param({"SPOT","LINEAR_PERPETUAL","INVERSE_PERPETUAL","LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
        public ProductLine productLine;
        private byte[] snapshot;
        private TradingCoreRuntime core;
        private CoreMessage order;
        private long funds;
        @Setup(Level.Trial) public void initialize() {
            var type=ContractType.valueOf(productLine.contractTypeCode());
            try(var state=new TradingCoreRuntime(productLine)) {
                var config=new UpsertInstrumentCommand("BTC-USDT",1,type.ordinal(),"BTC","USDT",type.isInverse()?"BTC":"USDT",
                        1,1,type.isInverse()?1000:1,100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                        type.isOption()?0:-1,type.isOption()?100:0);
                var paused=new UpsertInstrumentCommand(config.symbol(),config.instrumentChangeId(),config.contractTypeCode(),config.baseAsset(),
                        config.quoteAsset(),config.settleAsset(),config.notionalMultiplierUnits(),config.priceTickUnits(),config.settleScaleUnits(),
                        config.initialMarginRatePpm(),config.maintenanceMarginRatePpm(),config.makerFeeRatePpm(),config.takerFeeRatePpm(),
                        config.expiryEpochMillis(),config.optionTypeCode(),config.strikePriceTicks(),config.maxLeveragePpm(),config.maxPositionNotionalUnits(),
                        config.userOpenInterestLimitRatePpm(),config.userOpenInterestLimitFloorUnits(),config.riskLimitBrackets(),2,2);
                requireApplied(state.apply(message(productLine,1,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(paused))));
                requireApplied(state.apply(message(productLine,2,CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(type.isInverse()?"BTC":"USDT",20_000)))));
                snapshot=state.snapshot(777);
            }
            order=message(productLine,3,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(100,"BTC-USDT",1,CoreOrderSide.BUY,100,4,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"pause-benchmark")));
        }
        @Setup(Level.Invocation) public void restore() {
            core=TradingCoreRuntime.fromSnapshot(productLine,snapshot);
            if(core.tradingState().instruments().get("BTC-USDT").status()!=com.surprising.instrument.api.model.InstrumentStatus.HALT)
                throw new IllegalStateException("pause lost on recovery");
            funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(core.tradingState());
        }
        @TearDown(Level.Invocation) public void verify() {
            try {
                if(funds!=com.surprising.aeron.service.state.RollingFundsStateHash.compute(core.tradingState()) || !core.tradingState().orders().isEmpty())
                    throw new IllegalStateException("paused admission changed funds or created an order");
            } finally { core.close(); }
        }
    }
    @Benchmark public CoreResponse pausedOrder(Fixture fixture) {
        var response=fixture.core.apply(fixture.order);
        if(response.commandStatus()!=ResponseStatus.REJECTED || response.resultCode()!=CoreResultCode.INSTRUMENT_NOT_TRADING)
            throw new IllegalStateException("pause did not reject admission: "+response.resultCode());
        return response;
    }
    private static void requireApplied(CoreResponse response) { if(response.commandStatus()!=ResponseStatus.APPLIED) throw new IllegalStateException("fixture rejected"); }
    private static CoreMessage message(ProductLine line,long sequence,CoreMessageType type,byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type,UUID.randomUUID(),line,CommandSource.OPERATIONS,994,sequence,22,
                1_700_000_000_000L+sequence,sequence),payload);
    }
}
