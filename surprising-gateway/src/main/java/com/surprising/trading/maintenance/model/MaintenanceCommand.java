package com.surprising.trading.maintenance.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** 已绑定的维护意图；价格与范围组合属于业务规则，Service在持久化前校验。 */
public record MaintenanceCommand(UUID requestId, String instrumentId,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) long userId, Mode mode,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) long priceTicks, String reason) {
    public enum Mode { CANCEL, MARKET, LIMIT, SETTLEMENT }

    public void validate(ProductLine line) {
        if (line == ProductLine.SPOT && mode != Mode.CANCEL) {
            throw new IllegalArgumentException("spot supports cancellation only; balances are not positions");
        }
        if (userId < 0 || priceTicks < 0 || ((mode == Mode.LIMIT || mode == Mode.SETTLEMENT) != (priceTicks > 0))) {
            throw new IllegalArgumentException("LIMIT and SETTLEMENT require a positive priceTicks; other modes require zero");
        }
        if (mode == Mode.SETTLEMENT && userId != 0) {
            throw new IllegalArgumentException("fixed-price settlement must clear the entire instrumentId");
        }
    }
}
