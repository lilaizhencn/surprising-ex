package com.surprising.candlestick.provider.aggregation;

public final class CandleStores {

    public static final String CANDLE_STORE = "candlestick-candles";
    public static final String DIRTY_STORE = "candlestick-dirty-candles";
    public static final String DEDUPE_STORE = "candlestick-trade-dedupe";
    public static final String SEQUENCE_STORE = "candlestick-symbol-sequences";

    private CandleStores() {
    }
}
