package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.math.BigDecimal;
import java.time.Instant;

public class CandleSnapshot {

    private String symbol;
    private String period;
    private Instant openTime;
    private Instant closeTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal baseVolume;
    private BigDecimal quoteVolume;
    private long tradeCount;
    private String firstTradeId;
    private String lastTradeId;
    private Long firstSequence;
    private Long lastSequence;
    private CandleStatus status;
    private Instant updatedAt;
    private Integer sourcePartition;
    private Long sourceOffset;

    public CandleSnapshot() {
    }

    public CandleSnapshot(String symbol, String period, Instant openTime, Instant closeTime,
                          BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
                          BigDecimal baseVolume, BigDecimal quoteVolume, long tradeCount,
                          String firstTradeId, String lastTradeId, Long firstSequence, Long lastSequence,
                          CandleStatus status, Instant updatedAt, Integer sourcePartition, Long sourceOffset) {
        this.symbol = symbol;
        this.period = period;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.baseVolume = baseVolume;
        this.quoteVolume = quoteVolume;
        this.tradeCount = tradeCount;
        this.firstTradeId = firstTradeId;
        this.lastTradeId = lastTradeId;
        this.firstSequence = firstSequence;
        this.lastSequence = lastSequence;
        this.status = status;
        this.updatedAt = updatedAt;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
    }

    public CandleUpdatedEvent toUpdatedEvent(Instant emittedAt) {
        return new CandleUpdatedEvent(symbol, period, openTime, closeTime, openPrice, highPrice, lowPrice, closePrice,
                baseVolume, quoteVolume, tradeCount, firstTradeId, lastTradeId, firstSequence, lastSequence,
                status, updatedAt, emittedAt, sourcePartition, sourceOffset);
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

    public CandleStatus getStatus() {
        return status;
    }

    public void setStatus(CandleStatus status) {
        this.status = status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getSourcePartition() {
        return sourcePartition;
    }

    public void setSourcePartition(Integer sourcePartition) {
        this.sourcePartition = sourcePartition;
    }

    public Long getSourceOffset() {
        return sourceOffset;
    }

    public void setSourceOffset(Long sourceOffset) {
        this.sourceOffset = sourceOffset;
    }
}
