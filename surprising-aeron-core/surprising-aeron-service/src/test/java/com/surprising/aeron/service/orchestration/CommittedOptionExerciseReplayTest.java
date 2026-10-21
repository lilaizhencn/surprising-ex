package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class CommittedOptionExerciseReplayTest {
    private static final long TIME=1_700_000_000_000L;
    private long sequence, now=TIME;
    private TradingCoreRuntime reference;

    @ParameterizedTest
    @CsvSource({"CALL,120,20", "CALL,100,0", "CALL,80,0", "PUT,80,20", "PUT,100,0", "PUT,120,0"})
    void exerciseCashIsSeparateFromPremiumAndMarginAndSurvivesSnapshot(OptionType type,long underlying,long cashPerContract) {
        try (var online=new TradingCoreRuntime(ProductLine.OPTION)) {
            reference=online;
            byte[] snapshot;
            try(var replay=new CommittedTradeReplay(ProductLine.OPTION,null)) {
                apply(replay,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand("1",ContractType.VANILLA_OPTION.ordinal(),"BTC","USDT","USDT",1,1,1,100_000,50_000,0,0,TIME+10_000,type.ordinal(),100)));
                apply(replay,CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1",10,100,105,1,TIME)));
                for(long user=1;user<=2;user++) {
                    apply(replay,CoreMessageType.ADJUST_BALANCE,user,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",100_000)));
                    apply(replay,CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"1",user==1?CoreOrderSide.SELL:CoreOrderSide.BUY,10,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"exercise-"+user)));
                    assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
                }
                now=TIME+20_000;
                apply(replay,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now,"1",underlying,cashPerContract,0,1,0,10)));
                if(cashPerContract==0) assertThat(replay.deliveryPage()).isNull();
                else assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(1,"USDT",-2*cashPerContract,100_020-2*cashPerContract));
                snapshot=replay.snapshot();
            }
            try(var replay=new CommittedTradeReplay(ProductLine.OPTION,snapshot)) {
                var command=apply(replay,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now,"1",underlying,cashPerContract,1,1,0,10)));
                try(var probe=TradingCoreRuntime.fromSnapshot(ProductLine.OPTION,replay.snapshot())) {
                    var result=probe.resultLedger.get(command.header().commandId());
                    assertThat(result.status()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(CoreSettlementProgressCodec.decode(result.responseData()).complete()).isTrue();
                }
                if(cashPerContract==0) assertThat(replay.deliveryPage()).isNull();
                else assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(2,"USDT",2*cashPerContract,99_980+2*cashPerContract));
                long hash=replay.businessHash();
                replay.apply(CoreMessageCodec.encode(command),now,(sequence+1)*4096);
                CoreTestCompletion.applyAsynchronously(reference,command,now,(sequence+1)*4096);
                assertThat(replay.deliveryPage()).isNull();
                assertThat(replay.businessHash()).isEqualTo(hash).isEqualTo(reference.snapshotBusinessStateHash());
            }
        }
    }
    private CoreMessage apply(CommittedTradeReplay replay,CoreMessageType type,long user,byte[] body) {
        long seq=++sequence;
        var command=new CoreMessage(CoreMessageHeader.command(type,new UUID(121,seq),ProductLine.OPTION,CommandSource.OPERATIONS,871,seq,user,now,seq),body);
        replay.apply(CoreMessageCodec.encode(command),now,seq*4096);
        CoreTestCompletion.applyAsynchronously(reference,command,now,seq*4096);
        assertThat(replay.businessHash()).as("read-side extraction preserves Core trading state for "+type).isEqualTo(reference.snapshotBusinessStateHash());
        return command;
    }
}
