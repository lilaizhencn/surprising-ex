package com.surprising.candlestick.provider.aggregation;

public final class CandleStores {

    public static final String CANDLE_STORE = "candlestick-candles";
    public static final String DIRTY_STORE = "candlestick-dirty-candles";
    public static final String CLOSED_M1_WATERMARK_STORE = "candlestick-closed-one-minute-watermarks";
    public static final String DEDUPE_STORE = "candlestick-trade-dedupe";
    public static final String SEQUENCE_STORE = "candlestick-symbol-sequences";
    public static final String ROLLUP_STORE = "candlestick-rollups";
    public static final String ROLLUP_SEEN_STORE = "candlestick-rollup-seen-minutes";
    public static final String ROLLUP_WATERMARK_STORE = "candlestick-rollup-watermarks";

    private CandleStores() {
    }
}
