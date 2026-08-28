package com.surprising.aeron.service;

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

final class DecodedMatchingCommand {

    private final Object value;

    private DecodedMatchingCommand(Object value) {
        this.value = value;
    }

    static DecodedMatchingCommand decode(CoreMessage message) {
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

    PlaceOrderCommand placeOrder() { return required(PlaceOrderCommand.class); }
    PlaceOrderBatchCommand placeOrderBatch() { return required(PlaceOrderBatchCommand.class); }
    CancelOrderCommand cancelOrder() { return required(CancelOrderCommand.class); }
    CancelOrderBatchCommand cancelOrderBatch() { return required(CancelOrderBatchCommand.class); }
    ReplaceOrderCommand replaceOrder() { return required(ReplaceOrderCommand.class); }
    AmendOrderCommand amendOrder() { return required(AmendOrderCommand.class); }
    AmendOrderBatchCommand amendOrderBatch() { return required(AmendOrderBatchCommand.class); }
    ExecuteLiquidationCommand liquidation() { return required(ExecuteLiquidationCommand.class); }
    ExecuteLiquidationBatchCommand liquidationBatch() { return required(ExecuteLiquidationBatchCommand.class); }
    SettleInstrumentCommand settlement() { return required(SettleInstrumentCommand.class); }
    long[] trigger() { return required(long[].class); }

    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalStateException("decoded matching command type mismatch");
        }
        return type.cast(value);
    }
}
