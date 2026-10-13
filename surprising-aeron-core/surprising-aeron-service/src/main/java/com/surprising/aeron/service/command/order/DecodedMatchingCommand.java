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

public final class DecodedMatchingCommand {

    private Object value;
    private PlaceOrderCommand placeSlot;
    private PlaceOrderBatchCommand placeBatchSlot;
    private boolean commandSlot;
    /** 命令作用域内最后一个币对的稳定路由，避免同币对批量逐阶段查询并发字典。 */
    private String routedSymbol;
    private int routedShard;

    public int matcherShard(com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter adapter, String instrumentId) {
        if (!instrumentId.equals(routedSymbol)) {
            routedShard = adapter.matcherShardId(instrumentId);
            routedSymbol = instrumentId;
        }
        return routedShard;
    }


    private DecodedMatchingCommand(Object value) {
        this.value = value;
    }

    public static DecodedMatchingCommand commandSlot() {
        var result = new DecodedMatchingCommand(null);
        result.commandSlot = true;
        return result;
    }

    public void decodeInto(CoreMessage message) {
        if (!commandSlot) throw new IllegalStateException("decoded command is not a window slot");
        clearCommandSlot();
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER) {
            if (placeSlot == null) placeSlot = PlaceOrderCommand.commandSlot();
            value = TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), placeSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER_BATCH) {
            if (placeBatchSlot == null) placeBatchSlot = PlaceOrderBatchCommand.commandSlot();
            value = TradingOrderBatchCodec.decodePlaceOrderBatchInto(message.payloadUnsafe(), placeBatchSlot);
        } else value = decode(message).value;
    }

    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable decoded command");
        value = null; routedSymbol = null; routedShard = 0;
        if (placeSlot != null) placeSlot.clearCommandSlot();
        if (placeBatchSlot != null) placeBatchSlot.clearCommandSlot();
    }

    public static DecodedMatchingCommand decode(CoreMessage message) {
        Object decoded = switch (message.header().messageType()) {
            case PLACE_ORDER -> TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
            case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe());
            case CANCEL_ORDER -> TradingCommandCodec.decodeCancelOrder(message.payloadUnsafe());
            case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe());
            case REPLACE_ORDER -> TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe());
            case AMEND_ORDER -> TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe());
            case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe());
            case EXECUTE_TRIGGER_ORDER ->
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION -> TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION_BATCH ->
                    TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
            case SETTLE_INSTRUMENT -> TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
            default -> message;
        };
        return new DecodedMatchingCommand(decoded);
    }

    public PlaceOrderCommand placeOrder() { return required(PlaceOrderCommand.class); }
    public PlaceOrderBatchCommand placeOrderBatch() { return required(PlaceOrderBatchCommand.class); }
    public CancelOrderCommand cancelOrder() { return required(CancelOrderCommand.class); }
    public CancelOrderBatchCommand cancelOrderBatch() { return required(CancelOrderBatchCommand.class); }
    public ReplaceOrderCommand replaceOrder() { return required(ReplaceOrderCommand.class); }
    public AmendOrderCommand amendOrder() { return required(AmendOrderCommand.class); }
    public AmendOrderBatchCommand amendOrderBatch() { return required(AmendOrderBatchCommand.class); }
    public ExecuteLiquidationCommand liquidation() { return required(ExecuteLiquidationCommand.class); }
    public ExecuteLiquidationBatchCommand liquidationBatch() { return required(ExecuteLiquidationBatchCommand.class); }
    public SettleInstrumentCommand settlement() { return required(SettleInstrumentCommand.class); }
    public long[] trigger() { return required(long[].class); }

    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalStateException("decoded matching command type mismatch");
        }
        return type.cast(value);
    }
}
