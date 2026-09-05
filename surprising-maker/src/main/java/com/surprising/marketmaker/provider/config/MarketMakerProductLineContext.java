package com.surprising.marketmaker.provider.config;

import com.surprising.product.api.ProductLine;

public final class MarketMakerProductLineContext {

    private static final ThreadLocal<ProductLine> CURRENT = new ThreadLocal<>();

    private MarketMakerProductLineContext() {
    }

    public static ProductLine current() {
        return CURRENT.get();
    }

    public static void set(ProductLine productLine) {
        if (productLine == null) {
            clear();
        } else {
            CURRENT.set(productLine);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
