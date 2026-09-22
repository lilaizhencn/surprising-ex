package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.math.BigDecimal;
import java.time.Instant;

public class CandleRollupAccumulator {
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
    private Instant firstMinute;
    private Instant lastMinute;
    private Instant updatedAt;
    private boolean complete;

    public static CandleRollupAccumulator create(String symbol, CandlePeriod period, Instant openTime) {
        CandleRollupAccumulator value = new CandleRollupAccumulator();
        value.symbol = symbol;
        value.period = period.code();
        value.openTime = openTime;
        value.closeTime = period.closeTime(openTime);
        return value;
    }

    public void add(CandleUpdatedEvent minute) {
        if (complete) {
            throw new IllegalStateException("closed candle rollup is immutable");
        }
        if (firstMinute == null || minute.openTime().isBefore(firstMinute)) {
            firstMinute = minute.openTime();
            openPrice = minute.openPrice();
            firstTradeId = minute.firstTradeId();
            firstSequence = minute.firstSequence();
        }
        if (lastMinute == null || minute.openTime().isAfter(lastMinute)) {
            lastMinute = minute.openTime();
            closePrice = minute.closePrice();
            lastTradeId = minute.lastTradeId();
            lastSequence = minute.lastSequence();
        }
        highPrice = highPrice == null ? minute.highPrice() : highPrice.max(minute.highPrice());
        lowPrice = lowPrice == null ? minute.lowPrice() : lowPrice.min(minute.lowPrice());
        baseVolume = baseVolume.add(minute.baseVolume());
        quoteVolume = quoteVolume.add(minute.quoteVolume());
        tradeCount += minute.tradeCount();
        if (updatedAt == null || minute.eventTime().isAfter(updatedAt)) {
            updatedAt = minute.eventTime();
        }
    }

    public void close() {
        complete = true;
    }

    public CandleUpdatedEvent event(Instant emittedAt) {
        return new CandleUpdatedEvent(symbol, period, openTime, closeTime, openPrice, highPrice, lowPrice, closePrice,
                baseVolume, quoteVolume, tradeCount, firstTradeId, lastTradeId, firstSequence, lastSequence,
                complete ? CandleStatus.CLOSED : CandleStatus.PARTIAL, updatedAt, emittedAt, null, null);
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Instant getOpenTime() { return openTime; }
    public void setOpenTime(Instant openTime) { this.openTime = openTime; }
    public Instant getCloseTime() { return closeTime; }
    public void setCloseTime(Instant closeTime) { this.closeTime = closeTime; }
    public BigDecimal getOpenPrice() { return openPrice; }
    public void setOpenPrice(BigDecimal openPrice) { this.openPrice = openPrice; }
    public BigDecimal getHighPrice() { return highPrice; }
    public void setHighPrice(BigDecimal highPrice) { this.highPrice = highPrice; }
    public BigDecimal getLowPrice() { return lowPrice; }
    public void setLowPrice(BigDecimal lowPrice) { this.lowPrice = lowPrice; }
    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }
    public BigDecimal getBaseVolume() { return baseVolume; }
    public void setBaseVolume(BigDecimal baseVolume) { this.baseVolume = baseVolume; }
    public BigDecimal getQuoteVolume() { return quoteVolume; }
    public void setQuoteVolume(BigDecimal quoteVolume) { this.quoteVolume = quoteVolume; }
    public long getTradeCount() { return tradeCount; }
    public void setTradeCount(long tradeCount) { this.tradeCount = tradeCount; }
    public String getFirstTradeId() { return firstTradeId; }
    public void setFirstTradeId(String firstTradeId) { this.firstTradeId = firstTradeId; }
    public String getLastTradeId() { return lastTradeId; }
    public void setLastTradeId(String lastTradeId) { this.lastTradeId = lastTradeId; }
    public Long getFirstSequence() { return firstSequence; }
    public void setFirstSequence(Long firstSequence) { this.firstSequence = firstSequence; }
    public Long getLastSequence() { return lastSequence; }
    public void setLastSequence(Long lastSequence) { this.lastSequence = lastSequence; }
    public Instant getFirstMinute() { return firstMinute; }
    public void setFirstMinute(Instant firstMinute) { this.firstMinute = firstMinute; }
    public Instant getLastMinute() { return lastMinute; }
    public void setLastMinute(Instant lastMinute) { this.lastMinute = lastMinute; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
}
