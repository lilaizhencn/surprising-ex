package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import java.lang.invoke.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Actual private reconciliation/cache paths; access is resolved once, outside measurement. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
public class MakerReconciliationAllocationBenchmark {
    @Param({"20", "120"}) public int levels;
    MarketMakerService service;
    MarketMakerProperties.Strategy strategy;
    MarketMakerProperties.Quoting quoting;
    QuotePlan plan;
    List<OrderResponse> orders;
    List<OrderResponse> replacements;
    List<Long> removedIds;
    MethodHandle replace, update, refill;
    Map<String, Object> cache;
    final String prefix = "mm-benchmark-7-";
    final long bid = Long.MAX_VALUE / 100, ask = bid + 1;

    @Setup @SuppressWarnings("unchecked") public void setup() throws Throwable {
        var properties = new MarketMakerProperties();
        quoting = properties.getQuoting();
        quoting.setLinearLiquidityTargetNotionalUnits(1);
        quoting.setMaxOpenOrdersPerAccountSymbol(512);
        quoting.setLiquiditySlippagePpm(100);
        var constructor = MarketMakerService.class.getDeclaredConstructors()[0];
        Object[] dependencies = new Object[constructor.getParameterCount()];
        dependencies[0] = properties;
        service = (MarketMakerService) constructor.newInstance(dependencies);
        strategy = new MarketMakerProperties.Strategy();
        strategy.setProductLine(ProductLine.LINEAR_PERPETUAL);
        var quotes = new ArrayList<DesiredQuote>();
        orders = new ArrayList<>();
        for (int side = 0; side < 2; side++) for (int level = 0; level < levels; level++) {
            var direction = side == 0 ? OrderSide.BUY : OrderSide.SELL;
            long price = side == 0 ? bid - level : ask + level;
            quotes.add(new DesiredQuote(direction, level, price, 10));
            orders.add(new OrderResponse(side * levels + level + 1, 7,
                    prefix + (side == 0 ? "b" : "s") + level + "-1", "604", direction,
                    OrderType.LIMIT, TimeInForce.GTX, price - 1, 10, 0, 10,
                    false, true, OrderStatus.ACCEPTED, null, Instant.EPOCH, Instant.EPOCH));
        }
        plan = new QuotePlan(bid, 0, quotes, 0);
        replacements = List.copyOf(orders.subList(0, 20));
        removedIds = replacements.stream().map(OrderResponse::orderId).toList();
        var lookup = MethodHandles.privateLookupIn(MarketMakerService.class, MethodHandles.lookup());
        replace = lookup.findVirtual(MarketMakerService.class, "liquidityQuoteReplacement",
                MethodType.methodType(DesiredQuote.class, MarketMakerProperties.Strategy.class, QuotePlan.class,
                        OrderResponse.class, String.class, long.class, long.class, MarketMakerProperties.Quoting.class)).bindTo(service);
        update = lookup.findVirtual(MarketMakerService.class, "updateCachedOrders",
                MethodType.methodType(void.class, ProductLine.class, long.class, String.class, List.class, List.class)).bindTo(service);
        Class<?> reconcile = Class.forName(MarketMakerService.class.getName() + "$ReconcileResult");
        refill = lookup.findVirtual(MarketMakerService.class, "placeMissingQuotes",
                MethodType.methodType(reconcile, MarketMakerProperties.Strategy.class, long.class, String.class,
                        QuotePlan.class, List.class, String.class, long.class)).bindTo(service)
                .asType(MethodType.methodType(Object.class, MarketMakerProperties.Strategy.class, long.class,
                        String.class, QuotePlan.class, List.class, String.class, long.class));
        var field = MarketMakerService.class.getDeclaredField("openOrderSnapshots"); field.setAccessible(true);
        cache = (Map<String, Object>) field.get(service);
        Class<?> snapshot = Class.forName(MarketMakerService.class.getName() + "$CachedOpenOrders");
        var cached = snapshot.getDeclaredConstructor(List.class, Instant.class); cached.setAccessible(true);
        cache.put("LINEAR_PERPETUAL:7:604", cached.newInstance(List.copyOf(orders), Instant.EPOCH));
    }

    @Benchmark public int liquidityReplacements() throws Throwable {
        int matched = 0;
        for (var order : orders) {
            DesiredQuote quote = (DesiredQuote) replace.invokeExact(strategy, plan, order, prefix, bid, ask, quoting);
            if (quote != null) matched++;
        }
        if (matched != levels * 2) throw new IllegalStateException("liquidity slot matching incorrect");
        return matched;
    }

    /** A populated ladder must only be checked; no HTTP or replacement order is needed. */
    @Benchmark public Object quoteRefillScan() throws Throwable {
        return (Object) refill.invokeExact(strategy, 7L, "604", plan, orders, prefix, 1L);
    }

    @TearDown public void verifyPopulatedLadder() {
        if (orders.size() != levels * 2 || orders.contains(null))
            throw new IllegalStateException("refill changed a populated ladder");
    }

    @Benchmark public Object confirmedBatchSnapshot() throws Throwable {
        update.invokeExact(ProductLine.LINEAR_PERPETUAL, 7L, "604", removedIds, replacements);
        return cache.get("LINEAR_PERPETUAL:7:604");
    }
}
