package com.surprising.product.api;

import java.util.Locale;

/**
 * 不可变合约规格的内部身份。
 *
 * <p>symbol 是逻辑名称，specId 是同一逻辑名称下的规格生命周期序号；产品线必须参与键，
 * 防止现货、永续、交割和期权的同名合约互相污染。该类型不属于公共下单参数。</p>
 */
public record InstrumentSpecId(ProductLine productLine, String symbol, long specId) {

    public InstrumentSpecId {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (specId <= 0L) {
            throw new IllegalArgumentException("specId must be positive");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
    }

    public String key() {
        return productLine.name() + ":" + symbol + ":" + specId;
    }
}
