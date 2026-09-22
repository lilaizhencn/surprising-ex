package com.surprising.candlestick.provider.aggregation;

import java.util.List;

public interface CandleSink {

    void upsertBatch(List<CandleSnapshot> candles);
}
