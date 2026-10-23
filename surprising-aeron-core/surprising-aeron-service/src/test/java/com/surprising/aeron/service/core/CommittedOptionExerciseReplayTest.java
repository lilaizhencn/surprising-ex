package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 期权到期行权现金的回放测试，覆盖 CALL/PUT 实值、平值和虚值，并把权利金、保证金返还与行权现金分开核对。在线对照接收完全相同原命令，回放提取现金页不得改变权威业务状态。
 */
class CommittedOptionExerciseReplayTest {
    /** 本期权场景固定毫秒时间基点。 */
    private static final long TIME=1_700_000_000_000L;
    /**
     * sequence：本用例原来源消息递增序号。
     * now：当前模拟业务时间，到期结算时推进至到期后。
     */
    private long sequence, now=TIME;
    /** 本用例资源作用域内在线运行时的引用，接收和回放相同消息。 */
    private TradingCoreRuntime reference;

    /**
     * 建立两份期权双向仓，先已付权利金二十；到期按每份行权现金结算卖方，页间恢复后结算买方并检查完成。平值/虚值无现金页，实值按两份数量收付；重复原结算不再出页且在线/回放哈希一致。
     * @param type 本场景 CALL 或 PUT 期权类型
     * @param underlying 用于到期行权的标的价格 tick 数
     * @param cashPerContract 本场景预期每份期权行权现金，实值二十，其余零
     */
    @ParameterizedTest
    @CsvSource({"CALL,120,20", "CALL,100,0", "CALL,80,0", "PUT,80,20", "PUT,100,0", "PUT,120,0"})
    void exerciseCashIsSeparateFromPremiumAndMarginAndSurvivesSnapshot(OptionType type,long underlying,long cashPerContract) {
        try (/* 同本次回放互相核对的期权权威运行时，退出自动关闭 */ var online=new TradingCoreRuntime(ProductLine.OPTION)) {
            reference=online;
            // 卖方首结算分页后的快照，供恢复后买方分页续接
            byte[] snapshot;
            try(/* 本轮新建或恢复的期权已提交日志回放资源 */ var replay=new CommittedTradeReplay(ProductLine.OPTION,null)) {
                apply(replay,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand("1","BTC","USDT","USDT",1,1,1,100_000,50_000,0,0,TIME+10_000,type.ordinal(),100)));
                apply(replay,CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1",10,100,105,1,TIME)));
                for(/* 当前初始化的卖方一或买方二 */ long user=1;user<=2;user++) {
                    apply(replay,CoreMessageType.ADJUST_BALANCE,user,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",100_000)));
                    apply(replay,CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"1",user==1?CoreOrderSide.SELL:CoreOrderSide.BUY,10,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"exercise-"+user)));
                    assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
                }
                now=TIME+20_000;
                apply(replay,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now,
                        "1", underlying, 0, 1, 0, 10)));
                if(cashPerContract==0) assertThat(replay.deliveryPage()).isNull();
                else assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(1,"USDT",-2*cashPerContract,100_020-2*cashPerContract));
                snapshot=replay.snapshot();
            }
            try(/* 本轮新建或恢复的期权已提交日志回放资源 */ var replay=new CommittedTradeReplay(ProductLine.OPTION,snapshot)) {
                // 本次买方结算原消息，或 apply 构造并同时交两边的原消息
                var command=apply(replay,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now,
                        "1", underlying, 1, 1, 0, 10)));
                try(/* 从买方结算后的回放快照恢复的结果核对运行时 */ var probe=TradingCoreRuntime.fromSnapshot(ProductLine.OPTION,replay.snapshot())) {
                    // 买方本次结算在幂等结果表中的已提交响应
                    var result=probe.resultLedger.get(command.header().commandId());
                    assertThat(result.status()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(CoreSettlementProgressCodec.decode(result.responseData()).complete()).isTrue();
                }
                if(cashPerContract==0) assertThat(replay.deliveryPage()).isNull();
                else assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(2,"USDT",2*cashPerContract,99_980+2*cashPerContract));
                // 重复提交买方结算前的回放业务哈希
                long hash=replay.businessHash();
                replay.apply(CoreMessageCodec.encode(command),now,(sequence+1)*4096);
                CoreTestCompletion.applyAsynchronously(reference,command,now,(sequence+1)*4096);
                assertThat(replay.deliveryPage()).isNull();
                assertThat(replay.businessHash()).isEqualTo(hash).isEqualTo(reference.snapshotBusinessStateHash());
            }
        }
    }
    /**
     * 以当前期权业务时间及递增来源构造消息，同时交回放和在线对照，核对业务哈希后返回同一原命令供后续幂等检查。
     * @param replay 当前新建或快照续接的期权回放实例
     * @param type 当前原协议消息类型
     * @param user 原命令所属用户，注册、价格及结算为零
     * @param body 已编码的原期权业务载荷
     */
    private CoreMessage apply(CommittedTradeReplay replay,CoreMessageType type,long user,byte[] body) {
        // 本测试新增原消息的来源序号，组成 UUID 与模拟日志位置
        long seq=++sequence;
        // 本次买方结算原消息，或 apply 构造并同时交两边的原消息
        var command=new CoreMessage(CoreMessageHeader.command(type,new UUID(121,seq),ProductLine.OPTION,CommandSource.OPERATIONS,871,seq,user,now,seq),body);
        replay.apply(CoreMessageCodec.encode(command),now,seq*4096);
        CoreTestCompletion.applyAsynchronously(reference,command,now,seq*4096);
        assertThat(replay.businessHash()).as("read-side extraction preserves Core trading state for "+type).isEqualTo(reference.snapshotBusinessStateHash());
        return command;
    }
}
