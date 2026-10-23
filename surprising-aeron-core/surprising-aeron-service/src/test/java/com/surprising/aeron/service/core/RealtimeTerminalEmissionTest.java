package com.surprising.aeron.service.core;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.config.TradingExecutionMode;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;

/**
 * 小规模实时输出功能轨迹，遍历六产品线与两执行模式，检查各命令的订单、成交和双方执行帧数及同实体上一载荷是否重复。不是时延、吞吐或负载测试；统计变量不作为性能结论。
 */
class RealtimeTerminalEmissionTest {
    /** 各轨迹调用共用的递增原命令序号，不是性能计数。 */
    private static long sequence;
    /** 本功能轨迹原业务时间基点。 */
    private static final long TIME=1_700_000_000_000L;
    /**
     * 暂设执行模式后，依次检查开仓、平仓、撤单及两用户各二十条批次的实时帧；首次挂单元数据和预留各一帧，最后要求无 outbox 丢批，finally 恢复原系统属性。
     * @param line 本轮独立运行时所属产品线
     * @param mode 本轮临时设置的 FUSED 或 PIPELINED 执行模式
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("productsAndModes")
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void committedOrdersEmitOncePerEntity(ProductLine line, TradingExecutionMode mode) {
        // 原执行模式系统属性，finally 原样恢复或清除
        String previous = System.setProperty("surprising.aeron.execution-mode", mode.name());
        try (/* 当前产品线和模式的原交易运行时，退出自动关闭 */ var state = new TradingCoreRuntime(line)) {
                // 当前产品线对应合约类型
                var type=ContractType.valueOf(line.contractTypeCode());
                // 按币本位或其他类型选择的结算资产
                String asset=type.isInverse()?"BTC":"USDT";
                send(state,line,CoreMessageType.REGISTER_INSTRUMENT,0,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                "1", "BTC", "USDT", asset, 1, 1, type.isInverse()?1000:1,
                                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                                0, 0, type.isDelivery()||type.isOption()?2_000_000_000_000L:0, type.isOption()?0:-1,
                                type.isOption()?100:0), line),null,null,"setup");
                for(long /* 初始化或批次下单的用户七、八 */ user:new long[]{7,8})for(String /* 本次初始化入账的 BTC 或 USDT 资产 */ a:List.of("BTC","USDT"))
                    send(state,line,CoreMessageType.ADJUST_BALANCE,user,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(a,1_000_000)),null,null,"setup");
                if(line!=ProductLine.SPOT)send(state,line,CoreMessageType.APPLY_MARK_PRICE,0,
                        TradingCommandCodec.encodeApplyMarkPrice(line==ProductLine.OPTION
                                ?new ApplyMarkPriceCommand("1",100,100,100,1,TIME)
                                :new ApplyMarkPriceCommand("1",100,1,TIME), line),null,null,"setup");
                // 只用于本功能轨迹的有界实时字节缓存
                var outbox=new RealtimeOutbox(1024,1_048_576);
                // 接入原运行时的实时捕获器
                var capture=state.attachRealtime(outbox);
                place(state,line,7,201,CoreOrderSide.SELL,capture,outbox,"resting-open");
                place(state,line,8,202,CoreOrderSide.BUY,capture,outbox,"fill-open");
                place(state,line,7,203,CoreOrderSide.BUY,capture,outbox,"resting-close");
                place(state,line,8,204,CoreOrderSide.SELL,capture,outbox,"fill-close");
                place(state,line,7,205,CoreOrderSide.SELL,capture,outbox,"resting-cancel");
                send(state,line,CoreMessageType.CANCEL_ORDER,7,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(205)),capture,outbox,"cancel");
                for(long /* 初始化或批次下单的用户七、八 */ user:new long[]{7,8}) {
                    // 当前用户二十个批次意图的短期清单
                    var orders=new ArrayList<PlaceOrderCommand>();
                    for(/* 当前用户二十个批次子单下标 */ int i=0;i<20;i++)orders.add(new PlaceOrderCommand(1_000+user*100+i,"1",
                            user==7?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"batch-"+user+"-"+i));
                    send(state,line,CoreMessageType.PLACE_ORDER_BATCH,user,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)),
                            capture,outbox,user==7?"batch-resting":"batch-fill");
                }
                if(outbox.droppedBatches()!=0)throw new IllegalStateException("dropped audit frames");
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
            else System.setProperty("surprising.aeron.execution-mode", previous);
        }
    }

    /**
     * 组合全部产品线和全部执行模式作为参数，不把不同产品线状态合在同一运行时。
     */
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> productsAndModes() {
        return Arrays.stream(ProductLine.values()).flatMap(/* 参数组合回调当前产品线 */ line -> Arrays.stream(TradingExecutionMode.values())
                .map(/* 参数组合回调当前执行模式 */ mode -> org.junit.jupiter.params.provider.Arguments.of(line, mode)));
    }

    /**
     * 发送标的一、数量二、价格一百的限价 GTC 单，把对应轨迹标签交帧数断言。
     * @param state 本轮原业务运行时
     * @param line 本轮独立运行时所属产品线
     * @param user 本条原命令所属用户
     * @param id 要构造的原订单号
     * @param side 原限价订单买卖方向
     * @param capture 可空捕获器；初始化阶段为空，不检查输出帧
     * @param outbox 捕获器对应的原输出缓存
     * @param label 当前业务轨迹标签，决定预期订单及成交数
     */
    private static void place(TradingCoreRuntime state,ProductLine line,long user,long id,CoreOrderSide side,
                              RealtimeStateCapture capture,RealtimeOutbox outbox,String label) {
        send(state,line,CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id,"1",side,100,2,false,CoreMarginMode.CROSS,
                        CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"audit-"+id)),capture,outbox,label);
    }
    /**
     * 以原日志位置和导出序号包围一次实际业务提交，再消费所有 outbox 帧；按种类计数，并把同实体本帧与其上一帧载荷比较，只检查这种重复及既定数量，不证明全部历史帧唯一。
     * @param state 本轮原业务运行时
     * @param line 本轮独立运行时所属产品线
     * @param type 本条原业务消息类型
     * @param user 本条原命令所属用户
     * @param payload 本条原协议载荷
     * @param capture 可空捕获器；初始化阶段为空，不检查输出帧
     * @param outbox 捕获器对应的原输出缓存
     * @param label 当前业务轨迹标签，决定预期订单及成交数
     */
    private static void send(TradingCoreRuntime state,ProductLine line,CoreMessageType type,long user,byte[] payload,
                             RealtimeStateCapture capture,RealtimeOutbox outbox,String label) {
        // 本条消息来源序号，同时作为模拟日志位置及 UUID 组成部分
        long seq=++sequence;
        // 当前原业务消息
        var message=new CoreMessage(CoreMessageHeader.command(type,new UUID(55,seq),line,
                CommandSource.OPERATIONS,991,seq,user,TIME+seq,seq),payload);
        if(capture!=null)capture.begin(seq,TIME+seq,0,state.realtimeExportSequence());
        // 等原业务实际结束后的响应，要求 commandStatus 为 APPLIED
        var response=CoreTestCompletion.applyAsynchronously(state, message, TIME+seq, seq);
        if(response.commandStatus()!=ResponseStatus.APPLIED)throw new IllegalStateException(label+" "+response.resultCode());
        if(capture==null)return;
        capture.commit(state.realtimeExportSequence());
        // 本条命令输出按帧种类统计的条数
        Map<RealtimeFrame.Kind,Integer> kinds=new EnumMap<>(RealtimeFrame.Kind.class);
        // 按种类、用户和实体 ID 保存的载荷清单，仅收集轨迹，不另作断言
        Map<String,List<byte[]>> perEntity=new TreeMap<>();
        // 每个实体最后一次载荷，用来和下一帧比较重复
        Map<String,byte[]> priorFrames=new HashMap<>();
        // 同实体本次与上一载荷相同的帧数，应为零
        // 同实体相邻相同载荷对应的字节累计，只收集不作断言
        int duplicateBytes=0,duplicateFrames=0;
        // 本轮消费的总帧数，只收集不作吞吐断言
        // 本轮消费的总编码字节数，只收集不作吞吐断言
        int bytes=0,frames=0;
        // 当前从 outbox 取走的完整编码帧字节
        byte[] encoded;
        while((encoded=outbox.poll())!=null) {
            frames++;bytes+=encoded.length;
            // 本轮解码出的实时帧
            var frame=RealtimeFrameCodec.decode(encoded);
            kinds.merge(frame.kind(),1,Integer::sum);
            perEntity.computeIfAbsent(frame.kind()+":"+frame.userId()+":"+frame.entityId(),/* 实体清单 map 的新键，只用于创建空载荷列表 */ k->new ArrayList<>()).add(frame.payload());
            // 种类、用户及实体 ID 组成的本次分组键
            String key=frame.kind()+":"+frame.userId()+":"+frame.entityId();
            // 该实体上一帧的原载荷字节，当前帧只与这一份比较重复
            byte[] previous=priorFrames.put(key,frame.payload());
            if(previous!=null && Arrays.equals(previous,frame.payload())) {
                duplicateBytes+=encoded.length;duplicateFrames++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(duplicateFrames).as(line + " " + label).isZero();
        if (label.equals("resting-open")) {
            // 已冻结的预留与用户修订在这次挂单轨迹中应各输出一次。
            org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.METADATA, 0))
                    .as(line + " resting-open metadata").isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.RESERVATION, 0))
                    .as(line + " resting-open reservation").isEqualTo(1);
        }
        // 按本业务轨迹确定的订单帧数，一、二、二十或四十
        int expectedOrders = switch(label) {
            case "fill-open", "fill-close" -> 2;
            case "batch-resting" -> 20;
            case "batch-fill" -> 40;
            default -> 1;
        };
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.ORDER, 0))
                .as(line + " " + label + " ORDER count").isEqualTo(expectedOrders);
        // 本轨迹预期成交数，批次二十、普通成交一、其余零
        int fills = label.equals("batch-fill") ? 20 : label.startsWith("fill-") ? 1 : 0;
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.TRADE, 0))
                .as(line + " " + label + " TRADE kinds=" + kinds + " captureFailures=" + capture.failures())
                .isEqualTo(fills);
        org.assertj.core.api.Assertions.assertThat(kinds.getOrDefault(RealtimeFrame.Kind.EXECUTION, 0)).isEqualTo(fills * 2);
    }
}
