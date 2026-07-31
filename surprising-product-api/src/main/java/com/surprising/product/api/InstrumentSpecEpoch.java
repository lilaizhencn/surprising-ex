package com.surprising.product.api;

import java.util.Locale;

/**
 * 合约规格的内部生命周期代际。
 *
 * <p>epoch 是规格快照的内部兼容名称，迁移期间与旧事件中的 instrumentVersion 数值一一对应。
 * 它不作为下单参数或公共 WebSocket 字段暴露；旧数据库列和历史事件仍由适配层读取。</p>
 */
public record InstrumentSpecEpoch(ProductLine productLine, String symbol, long epoch) {

    public InstrumentSpecEpoch {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (epoch <= 0L) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
    }

    public InstrumentSpecId asSpecId() {
        return new InstrumentSpecId(productLine, symbol, epoch);
    }

    public String key() {
        return productLine.name() + ":" + symbol + ":" + epoch;
    }
}
