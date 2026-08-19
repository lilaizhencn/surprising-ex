package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;

public record UserRuntime(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {

    public UserRuntime(long userId) {
        this(ProductLine.LINEAR_PERPETUAL, userId, 0, CorePositionMode.ONE_WAY);
    }

    public UserRuntime {
        if (productLine == null || userId <= 0 || revision < 0 || positionMode == null) {
            throw new IllegalArgumentException("invalid runtime user");
        }
    }
}
