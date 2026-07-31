package com.surprising.instrument.provider.repository;

/** 标识一条不可变的合约配置版本。 */
public record InstrumentVersionKey(String symbol, long version) {
}
