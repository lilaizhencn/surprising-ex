package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.util.Optional;

public enum AccountType {
    FUNDING(null),
    SPOT(ProductLine.SPOT),
    USDT_PERPETUAL(ProductLine.LINEAR_PERPETUAL),
    COIN_PERPETUAL(ProductLine.INVERSE_PERPETUAL),
    USDT_DELIVERY(ProductLine.LINEAR_DELIVERY),
    COIN_DELIVERY(ProductLine.INVERSE_DELIVERY),
    OPTION(ProductLine.OPTION);

    private final ProductLine productLine;

    AccountType(ProductLine productLine) {
        this.productLine = productLine;
    }

    public Optional<ProductLine> productLine() {
        return Optional.ofNullable(productLine);
    }
}
