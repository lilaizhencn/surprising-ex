package com.surprising.trading.maintenance;

import com.surprising.product.api.ProductLine;
import java.util.UUID;

public record MaintenanceRequest(UUID requestId, String symbol, String userId, Mode mode,
                                 String priceTicks, String reason) {
    public enum Mode { CANCEL, MARKET, LIMIT, SETTLEMENT }
    public MaintenanceRequest {
        if (requestId == null || symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")
                || mode == null || reason == null || reason.isBlank() || reason.length() > 1024) {
            throw new IllegalArgumentException("requestId, explicit symbol, mode and maintenance reason are required");
        }
        userId = userId == null || userId.isBlank() ? "0" : userId;
        priceTicks = priceTicks == null || priceTicks.isBlank() ? "0" : priceTicks;
        if (!userId.matches("0|[1-9][0-9]*") || !priceTicks.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("identifiers and prices must be decimal integer strings");
        }
        long user = Long.parseLong(userId), price = Long.parseLong(priceTicks);
        if (user < 0 || price < 0 || ((mode == Mode.LIMIT || mode == Mode.SETTLEMENT) != (price > 0))) {
            throw new IllegalArgumentException("LIMIT and SETTLEMENT require a positive priceTicks; other modes require zero");
        }
        if (mode == Mode.SETTLEMENT && user != 0) throw new IllegalArgumentException("fixed-price settlement must clear the entire symbol");
        reason = reason.trim();
    }
    public void validate(ProductLine line) {
        if (line == ProductLine.SPOT && mode != Mode.CANCEL) throw new IllegalArgumentException("spot supports cancellation only; balances are not positions");
    }
}
