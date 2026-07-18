package com.surprising.account.api.model;

import com.surprising.trading.api.model.MatchTradeEvent;

public record TradeSideSettlementCommand(
        MatchTradeEvent trade,
        TradeParticipantRole participantRole,
        long orderQuantitySteps,
        boolean reduceOnly) {

    public TradeSideSettlementCommand {
        if (trade == null || participantRole == null || orderQuantitySteps <= 0
                || orderQuantitySteps < trade.quantitySteps()) {
            throw new IllegalArgumentException("valid trade, participantRole and order quantity are required");
        }
    }

    public long userId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
    }

    public long orderId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerOrderId() : trade.makerOrderId();
    }
}
