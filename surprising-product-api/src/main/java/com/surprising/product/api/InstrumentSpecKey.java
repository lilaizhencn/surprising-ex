package com.surprising.product.api;

import java.util.Locale;

/**
 * 合约规格的不可变内部定位键。
 *
 * <p>版本号是合约规格的唯一生命周期序号，产品线和标准化合约代码共同参与定位，
 * 避免不同产品线的同名合约互相覆盖。</p>
 */
public record InstrumentSpecKey(ProductLine productLine, String symbol, long version) {

    public InstrumentSpecKey {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
    }

    public String key() {
        return productLine.name() + ":" + symbol + ":" + version;
    }
}
