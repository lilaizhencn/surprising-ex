package com.surprising.price.index.repository;

/** 指数价格配置使用的合约版本键。 */
public record IndexInstrumentKey(String symbol, long version) {
}
