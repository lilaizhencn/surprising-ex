package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** HTTP boundary for a terminal business rejection; never represents an unknown outcome. */
public final class OrderCommandRejectedException extends ResponseStatusException {
    public OrderCommandRejectedException(CoreResultCode code) {
        super(HttpStatus.CONFLICT, message(code));
        getBody().setProperty("code", code.name());
    }

    private static String message(CoreResultCode code) {
        return switch (code) {
            case LEVERAGE_UPDATE_BLOCKED -> "Close positions and cancel open orders before changing leverage.";
            case LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT, LEVERAGE_EXCEEDS_RISK_BRACKET ->
                    "The selected leverage exceeds the contract or position limit.";
            case LEVERAGE_REPRICE_REQUIRES_CROSS -> "Margin repricing requires cross margin.";
            case LEVERAGE_REPRICE_INCREASE_BLOCKED -> "Only lowering leverage is supported while repricing margin.";
            case OPTION_LEVERAGE_UNSUPPORTED -> "Leverage adjustment is unavailable for this option contract.";
            case INSUFFICIENT_BALANCE, INSUFFICIENT_AVAILABLE_BALANCE -> "Insufficient available balance.";
            case POSITION_MODE_SWITCH_BLOCKED -> "Close positions and cancel open orders before changing position mode.";
            case POSITION_NOT_FOUND -> "The position is no longer open. Refresh and try again.";
            case ORDER_NOT_FOUND, ENTITY_NOT_FOUND -> "The requested order or record is no longer available.";
            case MARK_PRICE_NOT_FOUND, MARK_PRICE_MISSING, MARK_PRICE_UNAVAILABLE, STALE_MARK_PRICE ->
                    "A current reference price is unavailable. Please try again shortly.";
            case LIFECYCLE_IN_PROGRESS -> "Settlement is in progress. Please try again shortly.";
            default -> "The request could not be completed. Refresh your account and check the requested settings.";
        };
    }
}
