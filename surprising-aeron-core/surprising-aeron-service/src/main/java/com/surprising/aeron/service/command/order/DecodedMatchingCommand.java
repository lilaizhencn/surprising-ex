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
    private CancelOrderCommand cancelSlot;
    private CancelOrderBatchCommand cancelBatchSlot;
    private AmendOrderCommand amendSlot;
    private AmendOrderBatchCommand amendBatchSlot;
    private ReplaceOrderCommand replaceSlot;
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
        bindValue(value);
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

    public boolean isDecoded() { return value != null; }

    private void clearValues() { value = null; }

    private void bindValue(Object value) { this.value = value; }

    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalStateException("decoded matching command type mismatch");
        return type.cast(value);
    }

    public static DecodedMatchingCommand decode(CoreMessage message) {
        return new DecodedMatchingCommand(decodeRawPayload(message));
    }

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
}
