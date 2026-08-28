package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
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
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DerivativeMixedWorkload {

    static final int DEFAULT_SYMBOLS = 4;
    static final int DEFAULT_HFT_BATCH_SIZE = 20;
    private static final int OPEN_ORDER_USER_CAP = 128;
    private static final int RISK_BATCH_SIZE = 64;
    private static final long ENTRY_PRICE = 100;
    private static final long SAFE_BALANCE = 10_000_000_000L;
    private static final long BASE_EPOCH_MILLIS = 1_700_000_000_000L;

    private DerivativeMixedWorkload() {
    }

    record Template(
            SnapshotTemplate snapshot,
            ProductLine productLine,
            int activeUsers,
            List<String> symbols,
            List<Long> makers,
            List<Long> takers,
            String settleAsset,
            long openingFunds) {

        Template {
            symbols = List.copyOf(symbols);
            makers = List.copyOf(makers);
            takers = List.copyOf(takers);
            if (!productLine.isDerivative() || snapshot.productLine() != productLine || activeUsers < 1
                    || symbols.size() < 2 || symbols.size() != makers.size()
                    || symbols.size() != takers.size() || settleAsset.isBlank() || openingFunds <= 0) {
                throw new IllegalArgumentException("invalid derivative workload template");
            }
        }
    }

    static Template template(ProductLine productLine, int accountLanes, int activeUsers, int symbolCount) {
        Profile profile = Profile.forProductLine(productLine);
        if (activeUsers < accountLanes || activeUsers > LinearPerpetualBenchmarkSupport.MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("activeUsers must cover every lane and be at most 10000");
        }
        if (symbolCount < 2 || symbolCount > 16 || activeUsers < symbolCount) {
            throw new IllegalArgumentException("symbolCount must be in [2,16] and not exceed activeUsers");
        }
        List<String> symbols = symbols(profile, symbolCount);
        Harness harness = Harness.create(accountLanes, productLine);
        try {
            for (int index = 0; index < symbolCount; index++) {
                String symbol = symbols.get(index);
                harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeUpsertInstrument(instrument(profile, symbol, index))));
                harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE,
                        CommandSource.KAFKA_INPUT_BRIDGE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand(symbol, 1, ENTRY_PRICE, 1, BASE_EPOCH_MILLIS))));
            }

            List<Long> users = LinearPerpetualBenchmarkSupport.usersAcrossLanes(
                    accountLanes, activeUsers + symbolCount * 3, profile.userIdBase());
            List<Long> retailUsers = users.subList(0, activeUsers);
            int cursor = activeUsers;
            List<Long> positionMakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> makers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> takers = List.copyOf(users.subList(cursor, cursor + symbolCount));

            long[] aggregateQuantity = new long[symbolCount];
            for (int index = 0; index < activeUsers; index++) {
                aggregateQuantity[index % symbolCount]++;
            }
            for (int index = 0; index < symbolCount; index++) {
                long positionMaker = positionMakers.get(index);
                harness.adjust(positionMaker, profile.settleAsset(), SAFE_BALANCE);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                        positionMaker, order(harness.nextOrderId(), symbols.get(index), CoreOrderSide.SELL,
                                ENTRY_PRICE, aggregateQuantity[index], CoreTimeInForce.GTC)));
                harness.adjust(makers.get(index), profile.settleAsset(), SAFE_BALANCE);
                harness.adjust(takers.get(index), profile.settleAsset(), SAFE_BALANCE);
            }
            for (int index = 0; index < activeUsers; index++) {
                long userId = retailUsers.get(index);
                String symbol = symbols.get(index % symbolCount);
                harness.adjust(userId, profile.settleAsset(), SAFE_BALANCE + index);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        order(harness.nextOrderId(), symbol, CoreOrderSide.BUY,
                                ENTRY_PRICE, 1, CoreTimeInForce.IOC)));
                int openOrders = index < OPEN_ORDER_USER_CAP ? index & 3 : 0;
                for (int open = 0; open < openOrders; open++) {
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                            order(harness.nextOrderId(), symbol, CoreOrderSide.BUY,
                                    90 - open, 1, CoreTimeInForce.GTC)));
                }
            }
            long openingFunds = totalFunds(harness.state().tradingState(), profile.settleAsset());
            return new Template(harness.snapshotTemplate(accountLanes), productLine, activeUsers,
                    symbols, makers, takers, profile.settleAsset(), openingFunds);
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
        Harness harness = Harness.restore(template.snapshot(), true);
        return new Scenario() {
            private long operations;
            private long acceptedOperations;
            private long terminalOperations;
            private long acceptedCoreMessages;
            private long terminalCoreMessages;
            private long maxBacklog;
            private long laneOperations;
            private final long[] laneOperationsByType = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
            private final long[] markSequences = new long[template.symbols().size()];
            private final long[] fundingIds = new long[template.symbols().size()];
            private final long[] fundingCursors = new long[template.symbols().size()];
            private Set<Long> latestLifecycleOrders = Set.of();

            {
                for (int index = 0; index < markSequences.length; index++) {
                    markSequences[index] = 1;
                    fundingIds[index] = 20_000L + index;
                }
            }

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
                    if (round < template.symbols().size()) executeHeavyWork(harness, template, round);
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

            private void executeHeavyWork(Harness target, Template source, int index) {
                String symbol = source.symbols().get(index);
                var scan = target.state().tradingState().riskState().scans().get(symbol);
                if (scan != null && !scan.complete()) {
                    target.execute(target.command(CoreMessageType.CONTINUE_RISK_SCAN,
                            CommandSource.OPERATIONS, 0,
                            TradingCommandCodec.encodeContinueRiskScan(
                                    new ContinueRiskScanCommand(RISK_BATCH_SIZE))));
                } else {
                    long sequence = ++markSequences[index];
                    target.execute(target.command(CoreMessageType.APPLY_MARK_PRICE,
                            CommandSource.KAFKA_INPUT_BRIDGE, 0,
                            TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                                    symbol, 1, 99, sequence, BASE_EPOCH_MILLIS + sequence))));
                }
                if (!source.productLine().isFundingProduct()) return;
                if (target.state().tradingState().treasuryState().fundingSettlement(symbol)
                        == fundingIds[index]) {
                    fundingIds[index] += source.symbols().size();
                    fundingCursors[index] = 0;
                }
                var response = target.execute(target.command(CoreMessageType.APPLY_FUNDING,
                        CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                                fundingIds[index], symbol, 1, (index & 1) == 0 ? 100_000 : -100_000,
                                fundingCursors[index], RISK_BATCH_SIZE))));
                var progress = CoreFundingProgressCodec.decode(response.data());
                fundingCursors[index] = progress.complete() ? 0 : progress.nextCursorUserId();
            }

            @Override public long operations() { return operations; }
            @Override public long acceptedOperations() { return acceptedOperations; }
            @Override public long terminalOperations() { return terminalOperations; }
            @Override public long acceptedCoreMessages() { return acceptedCoreMessages; }
            @Override public long terminalCoreMessages() { return terminalCoreMessages; }
            @Override public long maxBacklog() { return maxBacklog; }
            @Override public long laneOperations() { return laneOperations; }
            @Override public long laneOperations(int operationType) { return laneOperationsByType[operationType]; }

            @Override
            public void verify() {
                harness.verifyDeferredBatchResponse();
                TradingCoreState state = harness.state().tradingState();
                long closingFunds = totalFunds(state, template.settleAsset());
                if (operations <= 0 || acceptedOperations != operations || terminalOperations != operations
                        || acceptedCoreMessages != terminalCoreMessages
                        || closingFunds != template.openingFunds()) {
                    throw new IllegalStateException("derivative workload invariant failed line="
                            + template.productLine() + " operations=" + operations
                            + " accepted=" + acceptedOperations + " terminal=" + terminalOperations
                            + " opening=" + template.openingFunds() + " closing=" + closingFunds);
                }
                state.users().values().forEach(user -> user.balances().values().forEach(
                        DerivativeMixedWorkload::requireNonNegative));
                for (long orderId : latestLifecycleOrders) {
                    var order = state.orders().get(orderId);
                    var reservation = order == null ? null : state.user(order.userId()).reservations().get(orderId);
                    if (order != null && (order.status() == CoreOrderStatus.OPEN
                            || reservation == null || reservation.remainingUnits() != 0)) {
                        throw new IllegalStateException("derivative lifecycle order is not terminal: " + orderId);
                    }
                }
                SnapshotTemplate checkpoint = harness.snapshotTemplate(template.snapshot().accountLanes());
                try (Harness restored = Harness.restore(checkpoint)) {
                    TradingCoreState recovered = restored.state().tradingState();
                    if (recovered.businessStateHash() != state.businessStateHash()
                            || totalFunds(recovered, template.settleAsset()) != closingFunds) {
                        throw new IllegalStateException("derivative snapshot recovery changed business state");
                    }
                }
            }

            @Override public void close() { harness.close(); }
        };
    }

    private static void executeTwoSidedBurst(Harness harness, Template template, int batchSize,
                                              Set<Long> lifecycleOrders) {
        List<List<Long>> quotes = new ArrayList<>(template.symbols().size());
        for (int index = 0; index < template.symbols().size(); index++) {
            List<Long> ids = placeBatch(harness, template.makers().get(index), template.symbols().get(index),
                    CoreOrderSide.SELL, 102, 2, CoreTimeInForce.GTC, batchSize);
            quotes.add(ids);
            lifecycleOrders.addAll(ids);
        }
        harness.drainSubmitted();
        for (int index = 0; index < quotes.size(); index++) {
            cancelBatch(harness, template.makers().get(index), quotes.get(index));
        }
        harness.drainSubmitted();
        executePartialSide(harness, template, batchSize, CoreOrderSide.SELL, 101, lifecycleOrders);
        executePartialSide(harness, template, batchSize, CoreOrderSide.BUY, 99, lifecycleOrders);
    }

    private static void executePartialSide(Harness harness, Template template, int batchSize,
                                           CoreOrderSide makerSide, long price,
                                           Set<Long> lifecycleOrders) {
        long[] liquidity = new long[template.symbols().size()];
        for (int index = 0; index < template.symbols().size(); index++) {
            long orderId = harness.nextOrderId();
            liquidity[index] = orderId;
            lifecycleOrders.add(orderId);
            harness.submit(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                    template.makers().get(index), order(orderId, template.symbols().get(index), makerSide,
                            price, batchSize * 2L, CoreTimeInForce.GTC)));
        }
        harness.drainSubmitted();
        CoreOrderSide takerSide = makerSide == CoreOrderSide.SELL ? CoreOrderSide.BUY : CoreOrderSide.SELL;
        for (int index = 0; index < template.symbols().size(); index++) {
            lifecycleOrders.addAll(placeBatch(harness, template.takers().get(index),
                    template.symbols().get(index), takerSide, price, 1, CoreTimeInForce.IOC, batchSize));
        }
        harness.drainSubmitted();
        for (int index = 0; index < template.symbols().size(); index++) {
            harness.submit(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY,
                    template.makers().get(index), TradingCommandCodec.encodeCancelOrder(
                            new CancelOrderCommand(liquidity[index]))));
        }
        harness.drainSubmitted();
    }

    private static List<Long> placeBatch(Harness harness, long userId, String symbol, CoreOrderSide side,
                                         long price, long quantity, CoreTimeInForce tif, int size) {
        List<Long> ids = new ArrayList<>(size);
        List<PlaceOrderCommand> orders = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            long orderId = harness.nextOrderId();
            ids.add(orderId);
            orders.add(orderCommand(orderId, symbol, side, price, quantity, tif));
        }
        harness.submit(harness.command(CoreMessageType.PLACE_ORDER_BATCH, CommandSource.GATEWAY, userId,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
        return List.copyOf(ids);
    }

    private static void cancelBatch(Harness harness, long userId, List<Long> orderIds) {
        harness.submit(harness.command(CoreMessageType.CANCEL_ORDER_BATCH, CommandSource.GATEWAY, userId,
                TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(
                        orderIds.stream().map(CancelOrderCommand::new).toList()))));
    }

    private static long[] completedLaneOperations(CoreProbeState state) {
        long[] total = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
        long[] completed = state.laneMetrics().accountLaneCompletedOperations();
        for (int index = 0; index < completed.length; index++) {
            total[index % CoreLaneMetrics.OPERATION_TYPE_COUNT] += completed[index];
        }
        return total;
    }

    private static long totalFunds(TradingCoreState state, String asset) {
        long total = 0;
        for (var user : state.users().values()) total = Math.addExact(total, user.totalUnits(asset));
        CoreTreasuryState treasury = state.treasuryState();
        for (Map<String, Long> ledger : List.of(treasury.feeBalances(), treasury.insuranceBalances(),
                treasury.liquidationFeeBalances(), treasury.fundingResidualBalances(),
                treasury.roundingResidualBalances(), treasury.clearingPnlBalances())) {
            total = Math.addExact(total, ledger.getOrDefault(asset, 0L));
        }
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
    }

    private static void requireNonNegative(AssetBalance balance) {
        if (balance.availableUnits() < 0 || balance.lockedUnits() < 0) {
            throw new IllegalStateException("derivative workload produced a negative balance: " + balance);
        }
    }

    private static List<String> symbols(Profile profile, int count) {
        List<String> symbols = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            symbols.add("JMH-" + profile.productLine().topicSegment().toUpperCase() + "-" + index);
        }
        return List.copyOf(symbols);
    }

    private static byte[] order(long id, String symbol, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce tif) {
        return TradingCommandCodec.encodePlaceOrder(orderCommand(id, symbol, side, price, quantity, tif));
    }

    private static PlaceOrderCommand orderCommand(long id, String symbol, CoreOrderSide side, long price,
                                                   long quantity, CoreTimeInForce tif) {
        return new PlaceOrderCommand(id, symbol, 1, side, price, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, tif, false,
                "derivative-mixed-" + id);
    }

    private static UpsertInstrumentCommand instrument(Profile profile, String symbol, int index) {
        return new UpsertInstrumentCommand(symbol, 1, profile.contractType().ordinal(),
                "D" + index, profile.quoteAsset(), profile.settleAsset(),
                profile.notionalMultiplier(), 1, profile.settleScale(), 100_000, 50_000,
                0, 0, profile.expiryEpochMillis(), profile.optionTypeCode(), profile.strikePrice());
    }

    private record Profile(ProductLine productLine, ContractType contractType, String quoteAsset,
                           String settleAsset, long notionalMultiplier, long settleScale,
                           long expiryEpochMillis, int optionTypeCode, long strikePrice,
                           long userIdBase) {
        static Profile forProductLine(ProductLine productLine) {
            return switch (productLine) {
                case LINEAR_PERPETUAL -> new Profile(productLine, ContractType.LINEAR_PERPETUAL,
                        "USDT", "USDT", 1, 1, 0, -1, 0, 400_000);
                case INVERSE_PERPETUAL -> new Profile(productLine, ContractType.INVERSE_PERPETUAL,
                        "USD", "BTC", 100, 100, 0, -1, 0, 500_000);
                case LINEAR_DELIVERY -> new Profile(productLine, ContractType.LINEAR_DELIVERY,
                        "USDT", "USDT", 1, 1, 2_000_000_000_000L, -1, 0, 600_000);
                case INVERSE_DELIVERY -> new Profile(productLine, ContractType.INVERSE_DELIVERY,
                        "USD", "BTC", 100, 100, 2_000_000_000_000L, -1, 0, 700_000);
                case OPTION -> new Profile(productLine, ContractType.VANILLA_OPTION,
                        "USDT", "USDT", 1, 1, 2_000_000_000_000L,
                        OptionType.CALL.ordinal(), ENTRY_PRICE, 800_000);
                case SPOT -> throw new IllegalArgumentException("spot is not a derivative workload");
            };
        }
    }
}
