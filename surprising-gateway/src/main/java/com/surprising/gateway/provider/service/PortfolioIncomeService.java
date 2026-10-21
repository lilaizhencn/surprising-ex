package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.repository.PortfolioIncomeRepository;
import com.surprising.gateway.provider.repository.PortfolioIncomeRepository.Cut;
import com.surprising.gateway.provider.repository.PortfolioIncomeRepository.Flow;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.PortfolioIncomePeriod;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Marked portfolio performance; histories and prices share one repeatable-read database snapshot. */
@Service
public class PortfolioIncomeService {
    private final PortfolioIncomeRepository repository;
    private final AssetRepository assets;
    private final Clock clock;

    @Autowired
    public PortfolioIncomeService(PortfolioIncomeRepository repository, AssetRepository assets) {
        this(repository, assets, Clock.systemUTC());
    }

    PortfolioIncomeService(PortfolioIncomeRepository repository, AssetRepository assets, Clock clock) {
        this.repository = repository;
        this.assets = assets;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary income(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("portfolio owner is required");
        long now = clock.millis(), opening = PortfolioIncomePeriod.dayStart(now);
        var cuts = new EnumMap<ProductLine, NavigableMap<Long, Cut>>(ProductLine.class);
        for (var cut : repository.cuts(userId, opening, Math.max(opening, now - 120_000), PortfolioIncomePeriod.minuteStart(now)))
            cuts.computeIfAbsent(cut.product(), ignored -> new TreeMap<>()).put(cut.time(), cut);
        var data = new Data(userId, opening, now, cuts, repository.flows(userId, opening), assets.findAll(), new HashMap<>());
        var groups = new LinkedHashMap<String, Income>();
        groups.put("overview", group(EnumSet.allOf(ProductLine.class), data));
        groups.put("funding", group(EnumSet.of(ProductLine.SPOT), data));
        var trading = EnumSet.allOf(ProductLine.class); trading.remove(ProductLine.SPOT);
        groups.put("trading", group(trading, data));
        for (var product : ProductLine.values())
            groups.put(accountType(product), product == ProductLine.SPOT ? groups.get("funding") : group(EnumSet.of(product), data));
        return new Summary("USDT", PortfolioIncomePeriod.ZONE.getId(), Instant.ofEpochMilli(opening),
                Instant.ofEpochMilli(now), Map.copyOf(groups));
    }

    private Income group(Set<ProductLine> scope, Data data) {
        var candidates = new TreeSet<Long>(Comparator.reverseOrder());
        var first = data.cuts().get(scope.iterator().next());
        if (first == null) return unavailable("SNAPSHOT_UNAVAILABLE", null, null);
        candidates.addAll(first.keySet());
        long selected = -1;
        BigDecimal current = null;
        for (long candidate : candidates) {
            if (candidate < data.opening() || data.now() - candidate > 120_000
                    || scope.stream().anyMatch(product -> cut(data, product, candidate) == null)) continue;
            selected = candidate;
            break;
        }
        if (selected < 0) return unavailable("SNAPSHOT_UNAVAILABLE", null, null);
        // A newer common cut with incomplete funds/prices must stay unavailable;
        // selecting an older zero balance could hide a recent deposit.
        if (!reconciled(scope, selected, data)) return unavailable("TRANSFER_PENDING", null, selected);
        current = equity(scope, selected, data);
        if (current == null) return unavailable("PRICE_UNAVAILABLE", null, selected);
        if (selected == data.opening()) return unavailable("PERIOD_START", current, selected);
        for (var product : scope) {
            var base = cut(data, product, data.opening());
            if (base == null || !base.opening() || base.coveredFrom() > data.opening()
                    || cut(data, product, selected).coveredFrom() > data.opening())
                return unavailable("HISTORY_UNAVAILABLE", current, selected);
        }
        if (!reconciled(scope, data.opening(), data)) return unavailable("HISTORY_UNAVAILABLE", current, selected);
        BigDecimal initial = equity(scope, data.opening(), data);
        if (initial == null) return unavailable("HISTORY_UNAVAILABLE", current, selected);
        var external = new ArrayList<DailyIncomeCalculator.ExternalFlow>();
        for (var flow : data.flows()) {
            if (!scope.contains(flow.product()) || internal(flow, scope, data.user())) continue;
            var before = cut(data, flow.product(), data.opening());
            var after = cut(data, flow.product(), selected);
            if (flow.position() <= before.position() || flow.position() > after.position()) continue;
            if (flow.time() < data.opening() || flow.time() >= selected)
                return unavailable("HISTORY_UNAVAILABLE", current, selected);
            BigDecimal value = value(flow.asset(), flow.units(), flow.time(), selected, data);
            if (value == null) return unavailable("PRICE_UNAVAILABLE", current, selected);
            external.add(new DailyIncomeCalculator.ExternalFlow(flow.product() + ":" + flow.commandId(), Instant.ofEpochMilli(flow.time()), value));
        }
        var income = DailyIncomeCalculator.calculate(Instant.ofEpochMilli(data.opening()), Instant.ofEpochMilli(selected), initial, current, external);
        return new Income(true, "AVAILABLE", decimal(income.amount()), decimal(income.percent()), decimal(current),
                decimal(initial), Instant.ofEpochMilli(selected));
    }

    private boolean reconciled(Set<ProductLine> scope, long time, Data data) {
        var net = new HashMap<String, BigInteger>();
        for (var flow : data.flows()) {
            if (!scope.contains(flow.product()) || !internal(flow, scope, data.user())) continue;
            var point = cut(data, flow.product(), time);
            if (point == null) return false;
            if (flow.position() <= point.position()) net.merge(flow.asset(), flow.units(), BigInteger::add);
        }
        return net.values().stream().allMatch(amount -> amount.signum() == 0);
    }

    private static boolean internal(Flow flow, Set<ProductLine> scope, long user) {
        return flow.transferId() != null && flow.sourceUser() == user && flow.targetUser() == user
                && scope.contains(flow.sourceProduct()) && scope.contains(flow.targetProduct());
    }

    private BigDecimal equity(Set<ProductLine> scope, long time, Data data) {
        BigDecimal total = BigDecimal.ZERO;
        for (var product : scope) {
            var point = cut(data, product, time);
            if (point == null || !point.complete()) return null;
            for (var asset : point.units().entrySet()) {
                BigDecimal value = value(asset.getKey(), asset.getValue(), time - 1, time, data);
                if (value == null) return null;
                total = total.add(value);
            }
        }
        return total;
    }

    private BigDecimal value(String asset, BigInteger units, long priceTime, long cutoff, Data data) {
        if (units.signum() == 0) return BigDecimal.ZERO;
        Long scale = data.scales().get(asset);
        if (!validScale(scale)) return null;
        BigDecimal rate;
        if (asset.equals("USDT")) rate = BigDecimal.ONE;
        else {
            var spot = cut(data, ProductLine.SPOT, cutoff);
            var key = new RateKey(asset, priceTime, spot == null ? -1 : spot.position());
            rate = data.rates().computeIfAbsent(key, ignored -> repository.spotPrice(asset, priceTime, spot).flatMap(price -> {
                if (!validScale(price.quoteScaleUnits())) return Optional.empty();
                return Optional.of(BigDecimal.valueOf(price.ticks()).multiply(BigDecimal.valueOf(price.tickUnits()))
                        .divide(BigDecimal.valueOf(price.quoteScaleUnits())));
            })).orElse(null);
        }
        return rate == null ? null : new BigDecimal(units).divide(BigDecimal.valueOf(scale)).multiply(rate);
    }

    private static boolean validScale(Long scale) {
        if (scale == null || scale <= 0) return false;
        long value = scale;
        while (value > 1 && value % 10 == 0) value /= 10;
        return value == 1;
    }
    private static Cut cut(Data data, ProductLine product, long time) {
        var values = data.cuts().get(product);
        return values == null ? null : values.get(time);
    }
    private static Income unavailable(String reason, BigDecimal current, Long time) {
        return new Income(false, reason, null, null, decimal(current), null, time == null ? null : Instant.ofEpochMilli(time));
    }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static String accountType(ProductLine product) {
        return switch (product) {
            case SPOT -> "SPOT";
            case LINEAR_PERPETUAL -> "USDT_PERPETUAL";
            case INVERSE_PERPETUAL -> "COIN_PERPETUAL";
            case LINEAR_DELIVERY -> "USDT_DELIVERY";
            case INVERSE_DELIVERY -> "COIN_DELIVERY";
            case OPTION -> "OPTION";
        };
    }
    private record RateKey(String asset, long time, long position) {}
    private record Data(long user, long opening, long now, Map<ProductLine, NavigableMap<Long, Cut>> cuts,
                        List<Flow> flows, Map<String, Long> scales, Map<RateKey, Optional<BigDecimal>> rates) {}
    public record Income(boolean available, String status, String amountUsdt, String percent,
                         String currentEquityUsdt, String openingEquityUsdt, Instant asOf) {}
    public record Summary(String currency, String timeZone, Instant periodStart, Instant generatedAt, Map<String, Income> groups) {}
}
