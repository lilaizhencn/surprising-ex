package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IndexPriceCalculator {

    private final IndexPriceProperties properties;

    public IndexPriceCalculator(IndexPriceProperties properties) {
        this.properties = properties;
    }

    public IndexPriceEvent calculate(String symbol, long sequence, int symbolMinValidSources,
                                     List<SourceQuote> quotes, Instant now) {
        List<SourceQuote> freshnessChecked = quotes.stream()
                .map(quote -> staleAware(quote, now))
                .toList();
        BigDecimal median = median(freshnessChecked.stream()
                .filter(SourceQuote::healthy)
                .map(SourceQuote::price)
                .sorted()
                .toList());

        List<IndexComponentSnapshot> components = new ArrayList<>();
        BigDecimal totalValidWeight = BigDecimal.ZERO;
        for (SourceQuote quote : freshnessChecked) {
            SourceQuote checked = outlierAware(quote, median);
            if (checked.healthy()) {
                totalValidWeight = totalValidWeight.add(checked.configuredWeight());
            }
            components.add(toComponent(checked, BigDecimal.ZERO));
        }

        int minValidSources = symbolMinValidSources > 0
                ? symbolMinValidSources
                : properties.getCalculation().getMinValidSources();
        int validCount = (int) components.stream().filter(component -> component.status() == SourceStatus.HEALTHY).count();
        if (validCount < minValidSources || totalValidWeight.signum() <= 0) {
            return new IndexPriceEvent(symbol, null, sequence, PriceStatus.INSUFFICIENT_SOURCES, components.size(),
                    validCount, configuredWeightTotal(quotes), now, components);
        }

        BigDecimal indexPrice = BigDecimal.ZERO;
        List<IndexComponentSnapshot> weightedComponents = new ArrayList<>(components.size());
        for (IndexComponentSnapshot component : components) {
            BigDecimal effectiveWeight = BigDecimal.ZERO;
            if (component.status() == SourceStatus.HEALTHY) {
                effectiveWeight = component.configuredWeight()
                        .divide(totalValidWeight, properties.getCalculation().getScale(), RoundingMode.HALF_UP);
                indexPrice = indexPrice.add(component.price().multiply(effectiveWeight));
            }
            weightedComponents.add(new IndexComponentSnapshot(component.source(), component.sourceSymbol(), component.price(),
                    component.bidPrice(), component.askPrice(), component.configuredWeight(), effectiveWeight,
                    component.status(), component.reason(), component.sourceTime(), component.receivedAt(), component.latencyMillis()));
        }

        PriceStatus status = validCount == components.size() ? PriceStatus.HEALTHY : PriceStatus.DEGRADED;
        return new IndexPriceEvent(symbol, indexPrice.setScale(properties.getCalculation().getScale(), RoundingMode.HALF_UP),
                sequence, status, weightedComponents.size(), validCount, configuredWeightTotal(quotes), now, weightedComponents);
    }

    private SourceQuote staleAware(SourceQuote quote, Instant now) {
        if (!quote.healthy() || quote.sourceTime() == null) {
            return quote;
        }
        if (Duration.between(quote.sourceTime(), now).compareTo(properties.getCalculation().getMaxSourceAge()) > 0) {
            return new SourceQuote(quote.source(), quote.sourceSymbol(), quote.price(), quote.bidPrice(), quote.askPrice(),
                    quote.configuredWeight(), SourceStatus.STALE, "source price is stale", quote.sourceTime(),
                    quote.receivedAt(), quote.latencyMillis());
        }
        return quote;
    }

    private SourceQuote outlierAware(SourceQuote quote, BigDecimal median) {
        if (!quote.healthy() || median == null || median.signum() <= 0) {
            return quote;
        }
        BigDecimal deviation = quote.price().subtract(median).abs()
                .divide(median, properties.getCalculation().getScale(), RoundingMode.HALF_UP);
        if (deviation.compareTo(properties.getCalculation().getOutlierThreshold()) > 0) {
            return new SourceQuote(quote.source(), quote.sourceSymbol(), quote.price(), quote.bidPrice(), quote.askPrice(),
                    quote.configuredWeight(), SourceStatus.OUTLIER,
                    "deviation " + deviation + " exceeds " + properties.getCalculation().getOutlierThreshold(),
                    quote.sourceTime(), quote.receivedAt(), quote.latencyMillis());
        }
        return quote;
    }

    private IndexComponentSnapshot toComponent(SourceQuote quote, BigDecimal effectiveWeight) {
        return new IndexComponentSnapshot(quote.source(), quote.sourceSymbol(), quote.price(), quote.bidPrice(),
                quote.askPrice(), quote.configuredWeight(), effectiveWeight, quote.status(), quote.reason(),
                quote.sourceTime(), quote.receivedAt(), quote.latencyMillis());
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), properties.getCalculation().getScale(), RoundingMode.HALF_UP);
    }

    private BigDecimal configuredWeightTotal(List<SourceQuote> quotes) {
        return quotes.stream()
                .map(SourceQuote::configuredWeight)
                .filter(weight -> weight != null && weight.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
