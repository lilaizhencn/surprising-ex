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
    private PlaceOrderCommand placeOrder;
    private PlaceOrderBatchCommand placeOrderBatch;
    private CancelOrderCommand cancelOrder;
    private CancelOrderBatchCommand cancelOrderBatch;
    private ReplaceOrderCommand replaceOrder;
    private AmendOrderCommand amendOrder;
    private AmendOrderBatchCommand amendOrderBatch;
    private ExecuteLiquidationCommand liquidation;
    private ExecuteLiquidationBatchCommand liquidationBatch;
    private SettleInstrumentCommand settlement;
    private long[] trigger;

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
            TradingOrderBatchCodec.decodePlaceOrderBatchInto(message.payloadUnsafe(), placeBatchSlot);
            bindValue(placeBatchSlot);
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
    }

    private void clearValues() {
        value = null;
        placeOrder = null;
        placeOrderBatch = null;
        cancelOrder = null;
        cancelOrderBatch = null;
        replaceOrder = null;
        amendOrder = null;
        amendOrderBatch = null;
        liquidation = null;
        liquidationBatch = null;
        settlement = null;
        trigger = null;
    }

    private void bindValue(Object val) {
        this.value = val;
        if (val instanceof PlaceOrderCommand cmd) this.placeOrder = cmd;
        else if (val instanceof PlaceOrderBatchCommand cmd) this.placeOrderBatch = cmd;
        else if (val instanceof CancelOrderCommand cmd) this.cancelOrder = cmd;
        else if (val instanceof CancelOrderBatchCommand cmd) this.cancelOrderBatch = cmd;
        else if (val instanceof ReplaceOrderCommand cmd) this.replaceOrder = cmd;
        else if (val instanceof AmendOrderCommand cmd) this.amendOrder = cmd;
        else if (val instanceof AmendOrderBatchCommand cmd) this.amendOrderBatch = cmd;
        else if (val instanceof ExecuteLiquidationCommand cmd) this.liquidation = cmd;
        else if (val instanceof ExecuteLiquidationBatchCommand cmd) this.liquidationBatch = cmd;
        else if (val instanceof SettleInstrumentCommand cmd) this.settlement = cmd;
        else if (val instanceof long[] cmd) this.trigger = cmd;
    }

    public static DecodedMatchingCommand decode(CoreMessage message) {
        return new DecodedMatchingCommand(decodeRawPayload(message));
    }

    private static Object decodeRawPayload(CoreMessage message) {
        return switch (message.header().messageType()) {
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
    }

    public PlaceOrderCommand placeOrder() {
        if (placeOrder == null) throw new IllegalStateException("decoded matching command type mismatch");
        return placeOrder;
    }
    public PlaceOrderBatchCommand placeOrderBatch() {
        if (placeOrderBatch == null) throw new IllegalStateException("decoded matching command type mismatch");
        return placeOrderBatch;
    }
    public CancelOrderCommand cancelOrder() {
        if (cancelOrder == null) throw new IllegalStateException("decoded matching command type mismatch");
        return cancelOrder;
    }
    public CancelOrderBatchCommand cancelOrderBatch() {
        if (cancelOrderBatch == null) throw new IllegalStateException("decoded matching command type mismatch");
        return cancelOrderBatch;
    }
    public ReplaceOrderCommand replaceOrder() {
        if (replaceOrder == null) throw new IllegalStateException("decoded matching command type mismatch");
        return replaceOrder;
    }
    public AmendOrderCommand amendOrder() {
        if (amendOrder == null) throw new IllegalStateException("decoded matching command type mismatch");
        return amendOrder;
    }
    public AmendOrderBatchCommand amendOrderBatch() {
        if (amendOrderBatch == null) throw new IllegalStateException("decoded matching command type mismatch");
        return amendOrderBatch;
    }
    public ExecuteLiquidationCommand liquidation() {
        if (liquidation == null) throw new IllegalStateException("decoded matching command type mismatch");
        return liquidation;
    }
    public ExecuteLiquidationBatchCommand liquidationBatch() {
        if (liquidationBatch == null) throw new IllegalStateException("decoded matching command type mismatch");
        return liquidationBatch;
    }
    public SettleInstrumentCommand settlement() {
        if (settlement == null) throw new IllegalStateException("decoded matching command type mismatch");
        return settlement;
    }
    public long[] trigger() {
        if (trigger == null) throw new IllegalStateException("decoded matching command type mismatch");
        return trigger;
    }
}
