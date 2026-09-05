package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Harness;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Scenario;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.SnapshotTemplate;
import com.surprising.aeron.service.state.AssetBalance;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SpotMixedWorkload {

    static final int DEFAULT_SYMBOLS = 4;
    static final int DEFAULT_HFT_BATCH_SIZE = 20;
    private static final String QUOTE_ASSET = "USDT";
    private static final long RETAIL_QUOTE_UNITS = 1_000_000L;
    private static final long RETAIL_BASE_UNITS = 10_000L;
    private static final long HFT_QUOTE_UNITS = 10_000_000_000L;
    private static final long HFT_BASE_UNITS = 100_000_000L;

    private SpotMixedWorkload() {
    }

    record Template(
            SnapshotTemplate snapshot,
            int activeUsers,
            List<String> symbols,
            List<String> baseAssets,
            List<Long> makers,
            List<Long> takers,
            Map<String, Long> openingTotals) {

        Template {
            symbols = List.copyOf(symbols);
            baseAssets = List.copyOf(baseAssets);
            makers = List.copyOf(makers);
            takers = List.copyOf(takers);
            openingTotals = Map.copyOf(openingTotals);
            if (snapshot.productLine() != ProductLine.SPOT || activeUsers < 1 || symbols.size() < 2
                    || symbols.size() != baseAssets.size() || symbols.size() != makers.size()
                    || symbols.size() != takers.size() || openingTotals.size() != symbols.size() + 1) {
                throw new IllegalArgumentException("invalid spot workload template");
            }
        }
    }

    static Template template(int accountLanes, int activeUsers, int symbolCount) {
        if (activeUsers < accountLanes || activeUsers > LinearPerpetualBenchmarkSupport.MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("activeUsers must cover every lane and be at most 10000");
        }
        if (symbolCount < 2 || symbolCount > 256 || activeUsers < symbolCount) {
            throw new IllegalArgumentException("symbolCount must be in [2,256] and not exceed activeUsers");
        }
        List<String> symbols = symbols(symbolCount);
        List<String> baseAssets = baseAssets(symbolCount);
        Harness harness = Harness.create(accountLanes, ProductLine.SPOT);
        try {
            for (int index = 0; index < symbolCount; index++) {
                harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeUpsertInstrument(
                                instrument(symbols.get(index), baseAssets.get(index)))));
            }

            List<Long> users = LinearPerpetualBenchmarkSupport.usersAcrossLanes(
                    accountLanes, activeUsers + symbolCount * 2, 300_000);
            List<Long> retailUsers = users.subList(0, activeUsers);
            List<Long> makers = List.copyOf(users.subList(activeUsers, activeUsers + symbolCount));
            List<Long> takerUsers = users.subList(activeUsers + symbolCount, users.size());
            List<Long> takers = new ArrayList<>(symbolCount);
            for (int index = 0; index < symbolCount; index++) {
                takers.add(takerUsers.get((index + 1) % symbolCount));
            }
            takers = List.copyOf(takers);
            for (int index = 0; index < activeUsers; index++) {
                long userId = retailUsers.get(index);
                String symbol = symbols.get(index % symbolCount);
                String baseAsset = baseAssets.get(index % symbolCount);
                harness.adjust(userId, QUOTE_ASSET, RETAIL_QUOTE_UNITS + index);
                harness.adjust(userId, baseAsset, RETAIL_BASE_UNITS + (index & 7));
                int openOrders = index & 3;
                for (int order = 0; order < openOrders; order++) {
                    CoreOrderSide side = (order & 1) == 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL;
                    long price = side == CoreOrderSide.BUY ? 90 - order : 110 + order;
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                            order(harness.nextOrderId(), symbol, side, price, 1, CoreTimeInForce.GTC)));
                }
            }
            for (int index = 0; index < symbolCount; index++) {
                fundHftUser(harness, makers.get(index), baseAssets.get(index));
                fundHftUser(harness, takers.get(index), baseAssets.get(index));
            }
            List<String> assets = new ArrayList<>(baseAssets);
            assets.add(QUOTE_ASSET);
            Map<String, Long> openingTotals = totalAssets(harness.state().tradingState(), assets);
            return new Template(harness.snapshotTemplate(accountLanes), activeUsers, symbols, baseAssets,
                    makers, takers, openingTotals);
        } finally {
            harness.close();
        }
    }

    static Scenario scenario(Template template, int hftRounds, int hftBatchSize) {
        if (hftRounds < 1 || hftRounds > 10_000) {
            throw new IllegalArgumentException("hftRounds must be in [1,10000]");
        }
        if (hftBatchSize < 1 || hftBatchSize > PlaceOrderBatchCommand.MAX_ORDERS) {
            throw new IllegalArgumentException("hftBatchSize exceeds the order batch protocol limit");
        }
        Harness harness = Harness.restore(template.snapshot());
        return new Scenario() {
            private long operations;
            private long acceptedOperations;
            private long terminalOperations;
            private long acceptedCoreMessages;
            private long terminalCoreMessages;
            private long maxBacklog;
            private long laneOperations;
            private final long[] laneOperationsByType = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
            private Set<Long> latestLifecycleOrders = Set.of();

            @Override
            public long run() {
                long operationsBefore = harness.executedMessages();
                long acceptedBefore = harness.acceptedMessages();
                long terminalBefore = harness.terminalMessages();
                long acceptedCoreBefore = harness.acceptedCoreMessages();
                long terminalCoreBefore = harness.terminalCoreMessages();
                long[] laneBefore = completedLaneOperations(harness.state());
                Set<Long> lifecycleOrders = new LinkedHashSet<>();
                for (int round = 0; round < hftRounds; round++) {
                    executeTwoSidedBurst(harness, template, hftBatchSize, lifecycleOrders);
                }
                latestLifecycleOrders = Set.copyOf(lifecycleOrders);
                operations = harness.executedMessages() - operationsBefore;
                acceptedOperations = harness.acceptedMessages() - acceptedBefore;
                terminalOperations = harness.terminalMessages() - terminalBefore;
                acceptedCoreMessages = harness.acceptedCoreMessages() - acceptedCoreBefore;
                terminalCoreMessages = harness.terminalCoreMessages() - terminalCoreBefore;
                maxBacklog = harness.maxMatchingBacklog();
                long[] laneAfter = completedLaneOperations(harness.state());
                laneOperations = 0;
                for (int type = 0; type < laneOperationsByType.length; type++) {
                    laneOperationsByType[type] = laneAfter[type] - laneBefore[type];
                    laneOperations += laneOperationsByType[type];
                }
                return harness.state().stateHash();
            }

            @Override
            public long operations() {
                return operations;
            }

            @Override
            public long acceptedOperations() {
                return acceptedOperations;
            }

            @Override
            public long terminalOperations() {
                return terminalOperations;
            }

            @Override
            public long acceptedCoreMessages() {
                return acceptedCoreMessages;
            }

            @Override
            public long terminalCoreMessages() {
                return terminalCoreMessages;
            }

            @Override
            public long maxBacklog() {
                return maxBacklog;
            }

            @Override
            public long laneOperations() {
                return laneOperations;
            }

            @Override
            public long laneOperations(int operationType) {
                return laneOperationsByType[operationType];
            }

            @Override
            public void verify() {
                TradingCoreState state = harness.state().tradingState();
                List<String> assets = new ArrayList<>(template.baseAssets());
                assets.add(QUOTE_ASSET);
                Map<String, Long> closingTotals = totalAssets(state, assets);
                if (operations <= 0 || acceptedOperations != operations || terminalOperations != operations
                        || acceptedCoreMessages != terminalCoreMessages
                        || !closingTotals.equals(template.openingTotals())) {
                    throw new IllegalStateException("spot workload violated command or funds invariant: operations="
                            + operations + ", accepted=" + acceptedOperations + ", terminal=" + terminalOperations
                            + ", opening=" + template.openingTotals() + ", closing=" + closingTotals);
                }
                state.users().values().forEach(user -> {
                    if (!user.positions().isEmpty()) {
                        throw new IllegalStateException("spot workload created a derivative position");
                    }
                    user.balances().values().forEach(SpotMixedWorkload::requireNonNegative);
                });
                for (long orderId : latestLifecycleOrders) {
                    var order = state.orders().get(orderId);
                    var reservation = order == null ? null : state.user(order.userId()).reservations().get(orderId);
                    if (order != null && (order.status() == CoreOrderStatus.OPEN
                            || reservation == null || reservation.remainingUnits() != 0)) {
                        throw new IllegalStateException("spot lifecycle order is not terminal: orderId=" + orderId
                                + ", order=" + order + ", reservation=" + reservation);
                    }
                }
                SnapshotTemplate checkpoint = harness.snapshotTemplate(template.snapshot().accountLanes());
                try (Harness restored = Harness.restore(checkpoint)) {
                    TradingCoreState recovered = restored.state().tradingState();
                    if (recovered.businessStateHash() != state.businessStateHash()
                            || !totalAssets(recovered, assets).equals(closingTotals)) {
                        throw new IllegalStateException("spot snapshot recovery changed business state");
                    }
                }
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    private static void executeTwoSidedBurst(Harness harness, Template template, int batchSize,
                                              Set<Long> lifecycleOrders) {
        List<List<Long>> quotedOrderIds = new ArrayList<>(template.symbols().size());
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            String symbol = template.symbols().get(symbolIndex);
            long maker = template.makers().get(symbolIndex);
            List<Long> quotedIds = placeBatch(harness, maker, symbol, CoreOrderSide.SELL,
                    102, 2, CoreTimeInForce.GTC, batchSize);
            quotedOrderIds.add(quotedIds);
            lifecycleOrders.addAll(quotedIds);
        }
        harness.drainSubmitted();
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            cancelBatch(harness, template.makers().get(symbolIndex), quotedOrderIds.get(symbolIndex));
        }
        harness.drainSubmitted();

        long[] partialSellIds = new long[template.symbols().size()];
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            String symbol = template.symbols().get(symbolIndex);
            long maker = template.makers().get(symbolIndex);
            long partialSellId = harness.nextOrderId();
            partialSellIds[symbolIndex] = partialSellId;
            lifecycleOrders.add(partialSellId);
            harness.submit(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                    order(partialSellId, symbol, CoreOrderSide.SELL, 101, batchSize * 2L, CoreTimeInForce.GTC)));
        }
        harness.drainSubmitted();
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            lifecycleOrders.addAll(placeBatch(harness, template.takers().get(symbolIndex),
                    template.symbols().get(symbolIndex), CoreOrderSide.BUY,
                    101, 1, CoreTimeInForce.IOC, batchSize));
        }
        harness.drainSubmitted();
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            cancel(harness, template.makers().get(symbolIndex), partialSellIds[symbolIndex]);
        }
        harness.drainSubmitted();

        long[] partialBuyIds = new long[template.symbols().size()];
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            String symbol = template.symbols().get(symbolIndex);
            long maker = template.makers().get(symbolIndex);
            long partialBuyId = harness.nextOrderId();
            partialBuyIds[symbolIndex] = partialBuyId;
            lifecycleOrders.add(partialBuyId);
            harness.submit(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                    order(partialBuyId, symbol, CoreOrderSide.BUY, 99, batchSize * 2L, CoreTimeInForce.GTC)));
        }
        harness.drainSubmitted();
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            lifecycleOrders.addAll(placeBatch(harness, template.takers().get(symbolIndex),
                    template.symbols().get(symbolIndex), CoreOrderSide.SELL,
                    99, 1, CoreTimeInForce.IOC, batchSize));
        }
        harness.drainSubmitted();
        for (int symbolIndex = 0; symbolIndex < template.symbols().size(); symbolIndex++) {
            cancel(harness, template.makers().get(symbolIndex), partialBuyIds[symbolIndex]);
        }
        harness.drainSubmitted();
    }

    private static List<Long> placeBatch(Harness harness, long userId, String symbol, CoreOrderSide side,
                                         long price, long quantity, CoreTimeInForce timeInForce, int size) {
        List<Long> orderIds = new ArrayList<>(size);
        List<PlaceOrderCommand> orders = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            long orderId = harness.nextOrderId();
            orderIds.add(orderId);
            orders.add(orderCommand(orderId, symbol, side, price, quantity, timeInForce));
        }
        harness.submit(harness.batchCommand(CoreMessageType.PLACE_ORDER_BATCH, CommandSource.GATEWAY, userId,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)), orders.size()));
        return List.copyOf(orderIds);
    }

    private static void cancelBatch(Harness harness, long userId, List<Long> orderIds) {
        List<CancelOrderCommand> cancels = orderIds.stream().map(CancelOrderCommand::new).toList();
        harness.submit(harness.batchCommand(CoreMessageType.CANCEL_ORDER_BATCH, CommandSource.GATEWAY, userId,
                TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(cancels)), cancels.size()));
    }

    private static void cancel(Harness harness, long userId, long orderId) {
        harness.submit(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId))));
    }

    private static long[] completedLaneOperations(CoreProbeState state) {
        long[] total = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
        long[] completed = state.laneMetrics().accountLaneCompletedOperations();
        for (int index = 0; index < completed.length; index++) {
            total[index % CoreLaneMetrics.OPERATION_TYPE_COUNT] += completed[index];
        }
        return total;
    }

    private static Map<String, Long> totalAssets(TradingCoreState state, List<String> assets) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String asset : assets) {
            long total = 0;
            for (var user : state.users().values()) total = Math.addExact(total, user.totalUnits(asset));
            CoreTreasuryState treasury = state.treasuryState();
            total = addLedger(total, treasury.feeBalances(), asset);
            total = addLedger(total, treasury.insuranceBalances(), asset);
            total = addLedger(total, treasury.liquidationFeeBalances(), asset);
            total = addLedger(total, treasury.fundingResidualBalances(), asset);
            total = addLedger(total, treasury.roundingResidualBalances(), asset);
            total = addLedger(total, treasury.clearingPnlBalances(), asset);
            total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
            totals.put(asset, total);
        }
        return Map.copyOf(totals);
    }

    private static long addLedger(long total, Map<String, Long> ledger, String asset) {
        return Math.addExact(total, ledger.getOrDefault(asset, 0L));
    }

    private static void requireNonNegative(AssetBalance balance) {
        if (balance.availableUnits() < 0 || balance.lockedUnits() < 0) {
            throw new IllegalStateException("spot workload produced a negative balance: " + balance);
        }
    }

    private static void fundHftUser(Harness harness, long userId, String baseAsset) {
        harness.adjust(userId, QUOTE_ASSET, HFT_QUOTE_UNITS);
        harness.adjust(userId, baseAsset, HFT_BASE_UNITS);
    }

    private static List<String> symbols(int count) {
        List<String> symbols = new ArrayList<>(count);
        for (int index = 0; index < count; index++) symbols.add("JMH-SPOT-" + index + "-USDT");
        return List.copyOf(symbols);
    }

    private static List<String> baseAssets(int count) {
        List<String> assets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) assets.add("SPOT" + index);
        return List.copyOf(assets);
    }

    private static byte[] order(long orderId, String symbol, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce timeInForce) {
        return TradingCommandCodec.encodePlaceOrder(
                orderCommand(orderId, symbol, side, price, quantity, timeInForce));
    }

    private static PlaceOrderCommand orderCommand(long orderId, String symbol, CoreOrderSide side, long price,
                                                   long quantity, CoreTimeInForce timeInForce) {
        return new PlaceOrderCommand(orderId, symbol, 1, side, price, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, timeInForce, false,
                "spot-mixed-" + orderId);
    }

    private static UpsertInstrumentCommand instrument(String symbol, String baseAsset) {
        return new UpsertInstrumentCommand(symbol, 1, ContractType.SPOT.ordinal(), baseAsset, QUOTE_ASSET,
                QUOTE_ASSET, 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }
}
