package com.surprising.price.mark.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MarkPriceCalculator {

    private final MarkPriceProperties properties;

    public MarkPriceCalculator(MarkPriceProperties properties) {
        this.properties = properties;
    }

    public MarkPriceEvent calculate(String symbol,
                                    long sequence,
                                    IndexPriceEvent index,
                                    PerpBookTickerEvent book,
                                    PerpTradeEvent trade,
                                    PerpFundingRateEvent funding,
                                    BigDecimal basisAverage,
                                    Instant now) {
        int scale = properties.getCalculation().getScale();
        BigDecimal indexPrice = index.indexPrice();
        BigDecimal fundingRate = funding == null ? BigDecimal.ZERO : funding.fundingRate();
        Instant nextFundingTime = funding == null ? now.plus(Duration.ofHours(properties.getCalculation().getDefaultFundingIntervalHours()))
                : funding.nextFundingTime();
        int intervalHours = funding == null || funding.fundingIntervalHours() <= 0
                ? properties.getCalculation().getDefaultFundingIntervalHours()
                : funding.fundingIntervalHours();
        long timeUntilFundingSeconds = Math.max(0, Duration.between(now, nextFundingTime).toSeconds());
        BigDecimal fundingFraction = BigDecimal.valueOf(timeUntilFundingSeconds)
                .divide(BigDecimal.valueOf(intervalHours * 3600L), scale, RoundingMode.HALF_UP);

        BigDecimal price1 = indexPrice.multiply(BigDecimal.ONE.add(fundingRate.multiply(fundingFraction)))
                .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal price2 = indexPrice.add(basisAverage).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal lastTradePrice = trade.price();
        BigDecimal rawMark = median(List.of(price1, price2, lastTradePrice));

        BigDecimal clampLow = indexPrice.multiply(BigDecimal.ONE.subtract(properties.getCalculation().getClampRatio()))
                .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal clampHigh = indexPrice.multiply(BigDecimal.ONE.add(properties.getCalculation().getClampRatio()))
                .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal markPrice = rawMark.max(clampLow).min(clampHigh).setScale(scale, RoundingMode.HALF_UP);

        PriceStatus status = markPrice.compareTo(rawMark) == 0 ? PriceStatus.HEALTHY : PriceStatus.CLAMPED;
        if (funding == null && status == PriceStatus.HEALTHY) {
            status = PriceStatus.DEGRADED;
        }

        return new MarkPriceEvent(symbol, markPrice, indexPrice, price1, price2, lastTradePrice,
                book.bestBidPrice(), book.bestAskPrice(), fundingRate, nextFundingTime, timeUntilFundingSeconds,
                basisAverage, properties.getCalculation().getBasisWindow().toSeconds(), clampLow, clampHigh,
                sequence, status, now);
    }

    public BigDecimal basis(IndexPriceEvent index, PerpBookTickerEvent book) {
        BigDecimal mid = book.bestBidPrice().add(book.bestAskPrice())
                .divide(BigDecimal.valueOf(2), properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        return mid.subtract(index.indexPrice());
    }

    private BigDecimal median(List<BigDecimal> prices) {
        return prices.stream()
                .sorted()
                .skip(1)
                .findFirst()
                .orElseThrow();
    }
}
