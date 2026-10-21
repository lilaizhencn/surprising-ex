package com.surprising.aeron.service.command.order;

import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;

/**
 * 撮合相关命令的解码持有者，既可单次解码，也可作为命令窗口复用槽。复用槽保存各协议类型自己的可清理载体，当前 value 只指向本次命令；使用者归还前不能覆盖或清空。这里只转换协议及缓存单个标的路由，不执行账户准入。
 */
public final class DecodedMatchingCommand {

    /** 当前命令实际解码值或通用分支保留的原消息，清理后为空。 */
    private Object value;
    /** 本窗口槽延迟创建、反复使用的普通下单载体。 */
    private PlaceOrderCommand placeSlot;
    /** 本窗口槽延迟创建、反复使用的批量下单载体。 */
    private PlaceOrderBatchCommand placeBatchSlot;
    /** 本窗口槽延迟创建、反复使用的普通撤单载体。 */
    private CancelOrderCommand cancelSlot;
    /** 本窗口槽延迟创建、反复使用的批量撤单载体。 */
    private CancelOrderBatchCommand cancelBatchSlot;
    /** 本窗口槽延迟创建、反复使用的普通改单载体。 */
    private AmendOrderCommand amendSlot;
    /** 本窗口槽延迟创建、反复使用的批量改单载体。 */
    private AmendOrderBatchCommand amendBatchSlot;
    /** 本窗口槽延迟创建、反复使用的撤改重下载体。 */
    private ReplaceOrderCommand replaceSlot;
    /** 是否允许通过窗口槽接口重解码和清理，普通单次解码为假。 */
    private boolean commandSlot;
    /** 本命令最近一次已确定分片的永久数字标的文本，清理时释放。 */
    private String routedSymbol;
    /** 与最近标的配套的 Matcher 分片编号，标的未缓存时零不代表已经路由。 */
    private int routedShard;

    /**
     * 同标的复用本命令作用域缓存的 Matcher 分片，换标的才查适配器；调用方提供非空规范标的和匹配的拓扑，缓存不跨线程同步。
     * @param adapter 同一产品拓扑的 Matcher 适配器，提供标的分片路由
     * @param instrumentId 非空的永久数字标的文本
     */
    public int matcherShard(com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter adapter, String instrumentId) {
        if (!instrumentId.equals(routedSymbol)) {
            routedShard = adapter.matcherShardId(instrumentId);
            routedSymbol = instrumentId;
        }
        return routedShard;
    }


    /**
     * 保存本次已解码值；普通构造出来的实例不能按窗口槽接口清理或重解码。
     * @param value 本次要持有的解码载体或原消息引用，可空表示未绑定
     */
    private DecodedMatchingCommand(Object value) {
        bindValue(value);
    }

    /**
     * 建立可反复解码和清理的空窗口槽，具体协议载体在首次需要时延迟创建。
     */
    public static DecodedMatchingCommand commandSlot() {
        // 新创建且已标记可复用的空解码窗口槽
        var result = new DecodedMatchingCommand(null);
        result.commandSlot = true;
        return result;
    }

    /**
     * 只允许复用槽操作，先清旧值、路由及各载体，再按消息类型原位解码常用订单类型；其他类型交通用解码。失败直接传播，当前 value 不发布未完成的解码。
     * @param message 头部及有效载荷仍可借用的当前原消息
     */
    public void decodeInto(CoreMessage message) {
        if (!commandSlot) throw new IllegalStateException("decoded command is not a window slot");
        clearCommandSlot();
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER) {
            if (placeSlot == null) placeSlot = PlaceOrderCommand.commandSlot();
            TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), placeSlot);
            bindValue(placeSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER_BATCH) {
            if (placeBatchSlot == null) placeBatchSlot = PlaceOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodePlaceOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), placeBatchSlot);
            bindValue(placeBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER) {
            if (cancelSlot == null) cancelSlot = CancelOrderCommand.commandSlot();
            TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), cancelSlot);
            bindValue(cancelSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER_BATCH) {
            if (cancelBatchSlot == null) cancelBatchSlot = CancelOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeCancelOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), cancelBatchSlot);
            bindValue(cancelBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER) {
            if (amendSlot == null) amendSlot = AmendOrderCommand.commandSlot();
            TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), amendSlot);
            bindValue(amendSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER_BATCH) {
            if (amendBatchSlot == null) amendBatchSlot = AmendOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeAmendOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), amendBatchSlot);
            bindValue(amendBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.REPLACE_ORDER) {
            if (replaceSlot == null) replaceSlot = ReplaceOrderCommand.commandSlot();
            TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), replaceSlot);
            bindValue(replaceSlot);
        } else {
            bindValue(decodeRawPayload(message));
        }
    }

    /**
     * 只允许复用槽清理：解除当前值和路由缓存，清空已创建的各协议载体，但保留载体对象供后续复用；调用方须先结束所有借用。
     */
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable decoded command");
        clearValues();
        routedSymbol = null;
        routedShard = 0;
        if (placeSlot != null) placeSlot.clearCommandSlot();
        if (placeBatchSlot != null) placeBatchSlot.clearCommandSlot();
        if (cancelSlot != null) cancelSlot.clearCommandSlot();
        if (cancelBatchSlot != null) cancelBatchSlot.clearCommandSlot();
        if (amendSlot != null) amendSlot.clearCommandSlot();
        if (amendBatchSlot != null) amendBatchSlot.clearCommandSlot();
        if (replaceSlot != null) replaceSlot.clearCommandSlot();
    }

    /**
     * 判断当前 value 是否非空；这只说明已绑定一个值，不代表业务校验或执行成功。
     */
    public boolean isDecoded() { return value != null; }

    /**
     * 仅解除当前命令值引用，协议复用载体由外层清理方法另外处理。
     */
    private void clearValues() { value = null; }

    /**
     * 把当前值指向已解码载体或原消息，不进行复制或类型校验。
     * @param value 本次要持有的解码载体或原消息引用，可空表示未绑定
     */
    private void bindValue(Object value) { this.value = value; }

    /**
     * 核对当前值确实属于所需协议类型后返回原引用；未解码或类型不符立即报错。
     * @param <T> 调用方需要的协议载体类型
     * @param type 本调用期望的协议载体类型
     */
    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalStateException("decoded matching command type mismatch");
        return type.cast(value);
    }

    /**
     * 创建单次解码持有者，不借用本类的窗口复用槽；通用解码未识别的消息类型仍保存原消息引用。
     * @param message 头部及有效载荷仍可借用的当前原消息
     */
    public static DecodedMatchingCommand decode(CoreMessage message) {
        return new DecodedMatchingCommand(decodeRawPayload(message));
    }

    /**
     * 按头部消息类型调用现有订单、触发执行、强平或到期结算解码器；其他类型原样返回消息，后续取类型时再检查，不在此拒绝所有未知分支。
     * @param message 头部及有效载荷仍可借用的当前原消息
     */
    private static Object decodeRawPayload(CoreMessage message) {
        return switch (message.header().messageType()) {
            case PLACE_ORDER -> TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case CANCEL_ORDER -> TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case REPLACE_ORDER -> TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER -> TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case EXECUTE_TRIGGER_ORDER ->
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION -> TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION_BATCH ->
                    TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
            case SETTLE_INSTRUMENT -> TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
            default -> message;
        };
    }

    /**
     * 核对并借出当前普通下单载体。
     */
    public PlaceOrderCommand placeOrder() { return required(PlaceOrderCommand.class); }
    /**
     * 核对并借出当前批量下单载体。
     */
    public PlaceOrderBatchCommand placeOrderBatch() { return required(PlaceOrderBatchCommand.class); }
    /**
     * 核对并借出当前普通撤单载体。
     */
    public CancelOrderCommand cancelOrder() { return required(CancelOrderCommand.class); }
    /**
     * 核对并借出当前批量撤单载体。
     */
    public CancelOrderBatchCommand cancelOrderBatch() { return required(CancelOrderBatchCommand.class); }
    /**
     * 核对并借出当前撤改重下命令载体。
     */
    public ReplaceOrderCommand replaceOrder() { return required(ReplaceOrderCommand.class); }
    /**
     * 核对并借出当前普通改单载体。
     */
    public AmendOrderCommand amendOrder() { return required(AmendOrderCommand.class); }
    /**
     * 核对并借出当前批量改单载体。
     */
    public AmendOrderBatchCommand amendOrderBatch() { return required(AmendOrderBatchCommand.class); }
    /**
     * 核对并借出当前单项强平执行参数。
     */
    public ExecuteLiquidationCommand liquidation() { return required(ExecuteLiquidationCommand.class); }
    /**
     * 核对并借出当前批量强平执行参数。
     */
    public ExecuteLiquidationBatchCommand liquidationBatch() { return required(ExecuteLiquidationBatchCommand.class); }
    /**
     * 核对并借出当前合约到期结算参数。
     */
    public SettleInstrumentCommand settlement() { return required(SettleInstrumentCommand.class); }
    /**
     * 核对并借出触发执行解码器返回的原 long 参数数组；字段顺序沿用现有协议，调用方不得跨槽清理保留引用。
     */
    public long[] trigger() { return required(long[].class); }
}
