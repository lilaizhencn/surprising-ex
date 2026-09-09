package com.surprising.aeron.service.execution;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;

/** Small functional trace only: no timing, throughput, load loop or production instrumentation. */
class RealtimeTerminalEmissionTest {
    private static long sequence;
    private static final long TIME=1_700_000_000_000L;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void committedOrdersEmitOncePerEntity(ProductLine line) {
            try(var state=new CoreProbeState(line)) {
                var type=ContractType.valueOf(line.contractTypeCode());
                String asset=type.isInverse()?"BTC":"USDT";
                send(state,line,CoreMessageType.UPSERT_INSTRUMENT,0,
                        TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("BTC-USDT",1,
                                type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1000:1,
                                100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0)),null,null,"setup");
                for(long user:new long[]{7,8})for(String a:List.of("BTC","USDT"))
                    send(state,line,CoreMessageType.ADJUST_BALANCE,user,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(a,1_000_000)),null,null,"setup");
                if(line!=ProductLine.SPOT)send(state,line,CoreMessageType.APPLY_MARK_PRICE,0,
                        TradingCommandCodec.encodeApplyMarkPrice(line==ProductLine.OPTION
                                ?new ApplyMarkPriceCommand("BTC-USDT",1,100,100,100,1,TIME)
                                :new ApplyMarkPriceCommand("BTC-USDT",1,100,1,TIME)),null,null,"setup");
                var outbox=new RealtimeOutbox(1024,1_048_576);
                var capture=state.attachRealtime(outbox);
                place(state,line,7,201,CoreOrderSide.SELL,capture,outbox,"resting-open");
                place(state,line,8,202,CoreOrderSide.BUY,capture,outbox,"fill-open");
                place(state,line,7,203,CoreOrderSide.BUY,capture,outbox,"resting-close");
                place(state,line,8,204,CoreOrderSide.SELL,capture,outbox,"fill-close");
                place(state,line,7,205,CoreOrderSide.SELL,capture,outbox,"resting-cancel");
                send(state,line,CoreMessageType.CANCEL_ORDER,7,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(205)),capture,outbox,"cancel");
                for(long user:new long[]{7,8}) {
                    var orders=new ArrayList<PlaceOrderCommand>();
                    for(int i=0;i<20;i++)orders.add(new PlaceOrderCommand(1_000+user*100+i,"BTC-USDT",1,
                            user==7?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"batch-"+user+"-"+i));
                    send(state,line,CoreMessageType.PLACE_ORDER_BATCH,user,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)),
                            capture,outbox,user==7?"batch-resting":"batch-fill");
                }
                if(outbox.droppedBatches()!=0)throw new IllegalStateException("dropped audit frames");
            }
    }
    private static void place(CoreProbeState state,ProductLine line,long user,long id,CoreOrderSide side,
                              RealtimeStateCapture capture,RealtimeOutbox outbox,String label) {
        send(state,line,CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id,"BTC-USDT",1,side,100,2,false,CoreMarginMode.CROSS,
                        CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"audit-"+id)),capture,outbox,label);
    }
    private static void send(CoreProbeState state,ProductLine line,CoreMessageType type,long user,byte[] payload,
                             RealtimeStateCapture capture,RealtimeOutbox outbox,String label) {
        long seq=++sequence;
        var message=new CoreMessage(CoreMessageHeader.command(type,new UUID(55,seq),line,
                CommandSource.OPERATIONS,991,seq,user,TIME+seq,seq),payload);
        if(capture!=null)capture.begin(seq,TIME+seq,0,state.realtimeExportSequence());
        var response=state.apply(message);
        if(response.resultCode()==CoreResultCode.MATCHING_PENDING)
            response=state.completeMatchingSynchronously(state.matchingSequence(message.header().commandId()),TIME+seq,seq);
        if(response.commandStatus()!=ResponseStatus.APPLIED)throw new IllegalStateException(label+" "+response.resultCode());
        if(capture==null)return;
        capture.commit(state.realtimeExportSequence());
        Map<RealtimeFrame.Kind,Integer> kinds=new EnumMap<>(RealtimeFrame.Kind.class);
        Map<String,List<byte[]>> perEntity=new TreeMap<>();
        Map<String,byte[]> priorFrames=new HashMap<>();
        int duplicateBytes=0,duplicateFrames=0;
        int bytes=0,frames=0;
        byte[] encoded;
        while((encoded=outbox.poll())!=null) {
            frames++;bytes+=encoded.length;
            var frame=RealtimeFrameCodec.decode(encoded);
            kinds.merge(frame.kind(),1,Integer::sum);
            perEntity.computeIfAbsent(frame.kind()+":"+frame.userId()+":"+frame.entityId(),k->new ArrayList<>()).add(frame.payload());
            String key=frame.kind()+":"+frame.userId()+":"+frame.entityId();
            byte[] previous=priorFrames.put(key,frame.payload());
            if(previous!=null && Arrays.equals(previous,frame.payload())) {
                duplicateBytes+=encoded.length;duplicateFrames++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(duplicateFrames).as(line + " " + label).isZero();
        int expectedOrders = switch(label) {
            case "fill-open", "fill-close" -> 2;
            case "batch-resting" -> 20;
            case "batch-fill" -> 40;
            default -> 1;
        };
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.ORDER, 0))
                .as(line + " " + label + " ORDER count").isEqualTo(expectedOrders);
        int fills = label.equals("batch-fill") ? 20 : label.startsWith("fill-") ? 1 : 0;
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.TRADE, 0)).isEqualTo(fills);
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.EXECUTION, 0)).isEqualTo(fills * 2);
    }
}
