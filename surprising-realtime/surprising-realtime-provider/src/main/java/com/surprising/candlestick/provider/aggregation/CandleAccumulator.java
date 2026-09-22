package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mutable hot-state object stored in Kafka Streams RocksDB.
 *
 * <p>It keeps the full OHLCV snapshot for one {@code symbol + period + openTime}. The processor
 * updates this object per accepted trade and later converts it to {@link CandleSnapshot} for
 * PostgreSQL upsert and realtime Kafka emission.</p>
 */
public class CandleAccumulator {

    private String symbol;
    private String period;
    private Instant openTime;
    private Instant closeTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal baseVolume = BigDecimal.ZERO;
    private BigDecimal quoteVolume = BigDecimal.ZERO;
    private long tradeCount;
    private String firstTradeId;
    private String lastTradeId;
    private Long firstSequence;
    private Long lastSequence;
    private Instant firstTradeTime;
    private Instant lastTradeTime;
    private Instant updatedAt;

    /**
     * Creates an empty candle bucket. Prices are set only when the first trade arrives.
     */
    public static CandleAccumulator create(String symbol, CandlePeriod period, Instant openTime) {
        CandleAccumulator accumulator = new CandleAccumulator();
        accumulator.symbol = symbol;
        accumulator.period = period.code();
        accumulator.openTime = openTime;
        accumulator.closeTime = period.closeTime(openTime);
        accumulator.baseVolume = BigDecimal.ZERO;
        accumulator.quoteVolume = BigDecimal.ZERO;
        accumulator.tradeCount = 0;
        return accumulator;
    }

    /**
     * Builds an immutable-ish snapshot for downstream persistence and push.
     */
    public CandleSnapshot snapshot(Instant now, Integer sourcePartition, Long sourceOffset) {
        CandleStatus status = closeTime != null && !closeTime.isAfter(now) ? CandleStatus.CLOSED : CandleStatus.PARTIAL;
        return new CandleSnapshot(symbol, period, openTime, closeTime, openPrice, highPrice, lowPrice, closePrice,
                baseVolume, quoteVolume, tradeCount, firstTradeId, lastTradeId, firstSequence, lastSequence,
                status, updatedAt, sourcePartition, sourceOffset);
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Instant getOpenTime() {
        return openTime;
    }

    public void setOpenTime(Instant openTime) {
        this.openTime = openTime;
    }

    public Instant getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(Instant closeTime) {
        this.closeTime = closeTime;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(BigDecimal highPrice) {
        this.highPrice = highPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(BigDecimal lowPrice) {
        this.lowPrice = lowPrice;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getBaseVolume() {
        return baseVolume;
    }

    public void setBaseVolume(BigDecimal baseVolume) {
        this.baseVolume = baseVolume;
    }

    public BigDecimal getQuoteVolume() {
        return quoteVolume;
    }

    public void setQuoteVolume(BigDecimal quoteVolume) {
        this.quoteVolume = quoteVolume;
    }

    public long getTradeCount() {
        return tradeCount;
    }

    public void setTradeCount(long tradeCount) {
        this.tradeCount = tradeCount;
    }

    public String getFirstTradeId() {
        return firstTradeId;
    }

    public void setFirstTradeId(String firstTradeId) {
        this.firstTradeId = firstTradeId;
    }

    public String getLastTradeId() {
        return lastTradeId;
    }

    public void setLastTradeId(String lastTradeId) {
        this.lastTradeId = lastTradeId;
    }

    public Long getFirstSequence() {
        return firstSequence;
    }

    public void setFirstSequence(Long firstSequence) {
        this.firstSequence = firstSequence;
    }

    public Long getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(Long lastSequence) {
        this.lastSequence = lastSequence;
    }

    public Instant getFirstTradeTime() {
        return firstTradeTime;
    }

    public void setFirstTradeTime(Instant firstTradeTime) {
        this.firstTradeTime = firstTradeTime;
    }

    public Instant getLastTradeTime() {
        return lastTradeTime;
    }

    public void setLastTradeTime(Instant lastTradeTime) {
        this.lastTradeTime = lastTradeTime;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
