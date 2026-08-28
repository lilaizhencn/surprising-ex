package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderCodec;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Harness;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Scenario;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.SnapshotTemplate;
import com.surprising.aeron.service.state.AssetBalance;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.CorePositionState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LinearPerpetualMixedWorkload {

    static final int DEFAULT_SYMBOLS = 4;
    static final int DEFAULT_HFT_BATCH_SIZE = 20;
    static final int HEAVY_WORK_BATCH_SIZE = 64;
    static final int OPEN_ORDER_USER_CAP = 128;
    private static final String SETTLE_ASSET = "USDT";
    private static final long ENTRY_PRICE = 100;
    private static final long SAFE_MARK = 99;
    private static final long LIQUIDATION_MARK = 1;
    private static final long SAFE_BALANCE = 1_000_000_000L;
    private static final long LIQUIDATION_BALANCE = 100;
    private static final long INSURANCE_SEED = 25;
    private LinearPerpetualMixedWorkload() {
    }

    interface StatefulScenario extends Scenario {
        SnapshotTemplate captureSnapshot();
    }

    record Template(
            SnapshotTemplate snapshot,
            int activeUsers,
            List<String> listedSymbols,
            List<String> symbols,
            List<Long> positionMakers,
            List<Long> hftMakers,
            List<Long> hftTakers,
            long liquidationUser,
            int liquidationSymbolIndex,
            LinearPerpetualScaleConfig scaleConfig,
            long openingFunds) {

        Template {
            listedSymbols = List.copyOf(listedSymbols);
            symbols = List.copyOf(symbols);
            positionMakers = List.copyOf(positionMakers);
            hftMakers = List.copyOf(hftMakers);
            hftTakers = List.copyOf(hftTakers);
            if (activeUsers < 1 || listedSymbols.size() < symbols.size() || symbols.isEmpty()
                    || symbols.size() != positionMakers.size()
                    || symbols.size() != hftMakers.size() || symbols.size() != hftTakers.size()
                    || liquidationUser <= 0 || liquidationSymbolIndex < 0
                    || liquidationSymbolIndex >= symbols.size() || scaleConfig == null || openingFunds <= 0) {
                throw new IllegalArgumentException("invalid mixed workload template");
            }
        }
    }

    static Template template(int accountLanes, int activeUsers, int symbolCount) {
        return template(accountLanes, activeUsers, LinearPerpetualScaleConfig.production(symbolCount));
    }

    static Template template(int accountLanes, int activeUsers, LinearPerpetualScaleConfig scaleConfig) {
        if (activeUsers < accountLanes || activeUsers > LinearPerpetualBenchmarkSupport.MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("activeUsers must cover every lane and be at most 10000");
        }
        if (activeUsers < scaleConfig.activeSymbols()) {
            throw new IllegalArgumentException("activeUsers must cover every active symbol");
        }
        List<String> listedSymbols = symbols(scaleConfig.listedSymbols());
        List<String> symbols = List.copyOf(listedSymbols.subList(0, scaleConfig.activeSymbols()));
        int symbolCount = symbols.size();
        int liquidationSymbolIndex = scaleConfig.trafficProfile() == LinearPerpetualTrafficProfile.SINGLE_HOT
                ? 0 : symbolCount - 1;
        Harness harness = Harness.create(accountLanes);
        try {
            for (String symbol : listedSymbols) {
                harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeUpsertInstrument(instrument(symbol))));
                harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE,
                        CommandSource.KAFKA_INPUT_BRIDGE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand(symbol, 1, ENTRY_PRICE, 1,
                                        harness.nextCommandTimestamp()))));
            }

            int infrastructureUsers = Math.addExact(Math.multiplyExact(symbolCount, 3), 1);
            List<Long> users = LinearPerpetualBenchmarkSupport.usersAcrossLanes(
                    accountLanes, Math.addExact(activeUsers, infrastructureUsers), 100_000);
            long liquidationUser = users.getFirst();
            List<Long> retailUsers = users.subList(1, activeUsers + 1);
            int cursor = activeUsers + 1;
            List<Long> positionMakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> hftMakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> hftTakers = List.copyOf(users.subList(cursor, cursor += symbolCount));

            long[] aggregateQuantities = new long[symbolCount];
            for (int index = 0; index < activeUsers; index++) {
                int positions = positionCount(index, scaleConfig);
                for (int position = 0; position < positions; position++) {
                    int symbolIndex = positionSymbolIndex(index, position, scaleConfig);
                    aggregateQuantities[symbolIndex] = Math.addExact(
                            aggregateQuantities[symbolIndex], positionQuantity(index + position));
                }
            }
            aggregateQuantities[liquidationSymbolIndex] = Math.addExact(
                    aggregateQuantities[liquidationSymbolIndex], 10);

            for (int index = 0; index < symbolCount; index++) {
                long maker = positionMakers.get(index);
                harness.adjust(maker, SAFE_BALANCE);
                if (aggregateQuantities[index] > 0) {
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                            order(harness.nextOrderId(), symbols.get(index), CoreOrderSide.SELL, ENTRY_PRICE,
                                    aggregateQuantities[index], CoreTimeInForce.GTC)));
                }
                harness.adjust(hftMakers.get(index), SAFE_BALANCE);
                harness.adjust(hftTakers.get(index), SAFE_BALANCE);
            }

            for (int index = 0; index < activeUsers; index++) {
                long userId = retailUsers.get(index);
                harness.adjust(userId, SAFE_BALANCE);
                int positions = positionCount(index, scaleConfig);
                for (int position = 0; position < positions; position++) {
                    String symbol = symbols.get(positionSymbolIndex(index, position, scaleConfig));
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                            order(harness.nextOrderId(), symbol, CoreOrderSide.BUY, ENTRY_PRICE,
                                    positionQuantity(index + position), CoreTimeInForce.IOC)));
                }
            }
            harness.adjust(liquidationUser, LIQUIDATION_BALANCE);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, liquidationUser,
                    order(harness.nextOrderId(), symbols.get(liquidationSymbolIndex), CoreOrderSide.BUY, ENTRY_PRICE,
                            10, CoreTimeInForce.IOC)));

            int openOrderUsers = scaleConfig.boundedSymbolWork()
                    ? activeUsers : Math.min(activeUsers, OPEN_ORDER_USER_CAP);
            for (int index = 0; index < openOrderUsers; index++) {
                long userId = retailUsers.get(index);
                int openOrders = openOrderCount(index, scaleConfig);
                int positions = positionCount(index, scaleConfig);
                for (int open = 0; open < openOrders; open++) {
                    String symbol = symbols.get(positionSymbolIndex(index, open % positions, scaleConfig));
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                            order(harness.nextOrderId(), symbol, CoreOrderSide.BUY, 90 - open % 10,
                                    1, CoreTimeInForce.GTC)));
                }
            }
            harness.execute(harness.command(CoreMessageType.ADJUST_INSURANCE_FUND, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new AdjustInsuranceFundCommand(SETTLE_ASSET, INSURANCE_SEED))));
            harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE,
                    CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                            symbols.get(liquidationSymbolIndex), 1, LIQUIDATION_MARK, 2,
                            harness.nextCommandTimestamp()))));
            while (!harness.state().tradingState().riskState().scan().complete()) {
                harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeContinueRiskScan(
                                new ContinueRiskScanCommand(HEAVY_WORK_BATCH_SIZE))));
            }
            verifyPopulation(harness.state().tradingState(), retailUsers, scaleConfig);
            long openingFunds = totalFunds(harness.state().tradingState());
            return new Template(harness.snapshotTemplate(accountLanes), activeUsers, listedSymbols, symbols,
                    positionMakers, hftMakers, hftTakers, liquidationUser, liquidationSymbolIndex,
                    scaleConfig, openingFunds);
        } finally {
            harness.close();
        }
    }

    static Scenario scenario(Template template) {
        return scenario(template, 1, DEFAULT_HFT_BATCH_SIZE);
    }

    static Scenario scenario(Template template, int hftRounds) {
        return scenario(template, hftRounds, DEFAULT_HFT_BATCH_SIZE);
    }

    static Scenario scenario(Template template, int hftRounds, int hftBatchSize) {
        return scenario(template, hftRounds, hftBatchSize, true);
    }

    static Scenario productionScenario(Template template, int hftRounds, int hftBatchSize) {
        return scenario(template, hftRounds, hftBatchSize, false);
    }

    static StatefulScenario scaleScenario(Template template, int hftRounds, int hftBatchSize) {
        return scenario(template, hftRounds, hftBatchSize, false);
    }

    private static StatefulScenario scenario(Template template, int hftRounds, int hftBatchSize,
                                             boolean completeHeavyCycles) {
        if (hftRounds < 1 || hftRounds > 10_000) {
            throw new IllegalArgumentException("hftRounds must be in [1,10000]");
        }
        if (hftBatchSize < 1 || hftBatchSize > PlaceOrderBatchCommand.MAX_ORDERS) {
            throw new IllegalArgumentException("hftBatchSize exceeds the order batch protocol limit");
        }
        Harness harness = Harness.restore(template.snapshot(), !completeHeavyCycles);
        return new StatefulScenario() {
            private long operations;
            private long acceptedOperations;
            private long terminalOperations;
            private long acceptedCoreMessages;
            private long terminalCoreMessages;
            private long maxBacklog;
            private long laneOperations;
            private long[] laneOperationsByType = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
            private long liquidationId;
            private boolean lossLifecycleExecuted;
            private long runSequence;
            private long triggerExecutions;
            private int lifecycleCursor;
            private final long[] fundingSettlementIds = initialFundingSettlementIds(template.symbols().size());
            private final long[] fundingCursors = new long[template.symbols().size()];
            private final long[] markPriceSequences = initialMarkPriceSequences(template);
            private final boolean[] fundingTouched = new boolean[template.symbols().size()];

            @Override
            public long run() {
                runSequence = Math.incrementExact(runSequence);
                long batchBefore = harness.executedMessages();
                long acceptedBefore = harness.acceptedMessages();
                long terminalBefore = harness.terminalMessages();
                long acceptedCoreBefore = harness.acceptedCoreMessages();
                long terminalCoreBefore = harness.terminalCoreMessages();
                long[] laneOperationsBefore = completedLaneOperations(harness.state());
                for (int round = 0; round < hftRounds; round++) {
                    int[] tradingSymbols = tradingSymbolIndices(template.scaleConfig(), round);
                    executeHftBurstsPipelined(harness, template, hftBatchSize, tradingSymbols);
                    int[] lifecycleSymbols = template.scaleConfig().trafficProfile()
                            == LinearPerpetualTrafficProfile.MARK_PRICE_STORM
                            ? allIndices(template.symbols().size()) : tradingSymbols;
                    int[] scheduledLifecycleSymbols = lifecycleSymbols;
                    if (!completeHeavyCycles && template.scaleConfig().boundedSymbolWork()) {
                        scheduledLifecycleSymbols = scheduledLifecycleSymbols(lifecycleSymbols,
                                template.scaleConfig().lifecycleSymbolsPerRun(), lifecycleCursor);
                        lifecycleCursor = lifecycleSymbols.length == 0 ? 0
                                : (lifecycleCursor + scheduledLifecycleSymbols.length) % lifecycleSymbols.length;
                    }
                    if (!completeHeavyCycles && round == 0) {
                        int[] triggerSymbols = template.scaleConfig().trafficProfile()
                                == LinearPerpetualTrafficProfile.MARK_PRICE_STORM
                                ? tradingSymbols : scheduledLifecycleSymbols;
                        for (int index : triggerSymbols) {
                            executeTriggerLifecycle(harness, template, index);
                            triggerExecutions = Math.incrementExact(triggerExecutions);
                        }
                        if (!lossLifecycleExecuted) {
                            executeLossLifecycle(harness, template);
                            lossLifecycleExecuted = true;
                        }
                    }
                    if (!completeHeavyCycles) {
                        if (template.scaleConfig().boundedSymbolWork()) {
                            for (int index : scheduledLifecycleSymbols) {
                                exerciseLifecycleBounded(harness, template, index, fundingSettlementIds,
                                        fundingCursors, markPriceSequences);
                                fundingTouched[index] = true;
                            }
                        } else if (round < template.symbols().size()) {
                            exerciseLifecycleBounded(harness, template, round, fundingSettlementIds,
                                    fundingCursors, markPriceSequences);
                            fundingTouched[round] = true;
                        }
                    }
                }
                if (completeHeavyCycles) {
                    for (int index = 0; index < template.symbols().size(); index++) {
                        executeTriggerLifecycle(harness, template, index);
                        triggerExecutions = Math.incrementExact(triggerExecutions);
                        exerciseLifecycle(harness, template, index, true,
                                fundingSettlementIds[index], markPriceSequences);
                        fundingTouched[index] = true;
                    }
                }
                if (!lossLifecycleExecuted) {
                    executeLossLifecycle(harness, template);
                    lossLifecycleExecuted = true;
                }
                operations = Math.subtractExact(harness.executedMessages(), batchBefore);
                acceptedOperations = Math.subtractExact(harness.acceptedMessages(), acceptedBefore);
                terminalOperations = Math.subtractExact(harness.terminalMessages(), terminalBefore);
                acceptedCoreMessages = Math.subtractExact(
                        harness.acceptedCoreMessages(), acceptedCoreBefore);
                terminalCoreMessages = Math.subtractExact(
                        harness.terminalCoreMessages(), terminalCoreBefore);
                maxBacklog = harness.maxMatchingBacklog();
                long[] laneOperationsAfter = completedLaneOperations(harness.state());
                laneOperations = 0;
                for (int type = 0; type < laneOperationsByType.length; type++) {
                    laneOperationsByType[type] = Math.subtractExact(
                            laneOperationsAfter[type], laneOperationsBefore[type]);
                    laneOperations = Math.addExact(laneOperations, laneOperationsByType[type]);
                }
                return harness.state().tradingState().businessStateHash();
            }

            private void executeLossLifecycle(Harness target, Template source) {
                var work = target.executionWork();
                if (work.actions().size() != 1
                        || work.actions().getFirst().userId() != source.liquidationUser()) {
                    throw new IllegalStateException("mixed workload expected one dedicated liquidation action: user="
                            + source.liquidationUser() + ", work=" + work + ", scan="
                            + target.state().tradingState().riskState().scans().get(
                                    source.symbols().get(source.liquidationSymbolIndex())));
                }
                var action = work.actions().getFirst();
                liquidationId = action.liquidationId();
                var batchAction = new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                        action.symbol(), action.instrumentVersion(), action.triggerPriceSequence(),
                        action.markPriceTicks(), action.cursorOrderId());
                var batch = new ExecuteLiquidationBatchCommand(List.of(batchAction),
                        ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0);
                target.execute(target.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                        CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(batch)));

                CoreLiquidationState liquidated = liquidation(target, liquidationId);
                long insurance = target.state().tradingState().treasuryState()
                        .insuranceBalances().getOrDefault(SETTLE_ASSET, 0L);
                long coverage = Math.min(insurance, Math.subtractExact(liquidated.deficitUnits(), 1));
                if (liquidated.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED || coverage <= 0) {
                    throw new IllegalStateException("mixed workload did not create an insurable deficit");
                }
                target.execute(target.command(CoreMessageType.RESOLVE_LIQUIDATION, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                                liquidationId, ResolveLiquidationCommand.Resolution.INSURANCE, coverage))));

                CoreLiquidationState adlRequired = liquidation(target, liquidationId);
                int liquidationSymbolIndex = source.liquidationSymbolIndex();
                CorePositionState makerPosition = target.state().tradingState()
                        .user(source.positionMakers().get(liquidationSymbolIndex)).positions()
                        .get(source.symbols().get(liquidationSymbolIndex));
                long profitPerStep = Math.subtractExact(makerPosition.entryPriceTicks(), LIQUIDATION_MARK);
                long closeQuantity = Math.floorDiv(
                        Math.addExact(adlRequired.deficitUnits(), profitPerStep - 1), profitPerStep);
                target.execute(target.command(CoreMessageType.EXECUTE_ADL, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                                liquidationId, source.positionMakers().get(liquidationSymbolIndex),
                                source.symbols().get(liquidationSymbolIndex),
                                CoreMarginMode.CROSS, CorePositionSide.NET,
                                makerPosition.signedQuantitySteps(), makerPosition.entryPriceTicks(),
                                adlRequired.triggerPriceSequence(),
                                closeQuantity, adlRequired.deficitUnits()))));
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
            public int incompleteRiskScans() {
                return (int) harness.state().tradingState().riskState().scans().values().stream()
                        .filter(scan -> !scan.complete()).count();
            }

            @Override
            public int incompleteFundingSettlements() {
                int incomplete = 0;
                for (String symbol : template.symbols()) {
                    var progress = harness.state().tradingState().treasuryState().fundingProgress(symbol);
                    if (progress != null) incomplete++;
                }
                return incomplete;
            }

            @Override
            public int activeOrders() {
                return (int) harness.state().tradingState().orders().values().stream()
                        .filter(order -> order.status() == CoreOrderStatus.OPEN).count();
            }

            @Override
            public int positions() {
                return harness.state().tradingState().users().values().stream()
                        .mapToInt(user -> user.positions().size()).sum();
            }

            @Override
            public int triggerOrders() {
                return harness.state().tradingState().triggerOrders().size();
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
                harness.verifyDeferredBatchResponse();
                TradingCoreState state = harness.state().tradingState();
                long closingFunds = totalFunds(state);
                if (operations <= 0 || acceptedOperations != terminalOperations
                        || terminalOperations != operations || acceptedCoreMessages != terminalCoreMessages
                        || acceptedCoreMessages <= 0 || acceptedCoreMessages > acceptedOperations
                        || closingFunds != template.openingFunds()) {
                    throw new IllegalStateException("mixed workload violated command or funds invariant: operations="
                            + operations + ", openingFunds=" + template.openingFunds()
                            + ", closingFunds=" + closingFunds + ", treasury=" + state.treasuryState());
                }
                CoreLiquidationState liquidation = liquidation(state, liquidationId);
                if (liquidation.status() != CoreLiquidationState.Status.COMPLETED
                        || liquidation.deficitUnits() != 0
                        || completeHeavyCycles && !state.riskState().scan().complete()) {
                    throw new IllegalStateException("mixed workload lifecycle did not complete");
                }
                for (int index = 0; index < template.symbols().size(); index++) {
                    if (!fundingTouched[index]) continue;
                    String symbol = template.symbols().get(index);
                    var progress = state.treasuryState().fundingProgress(symbol);
                    long expectedSettlementId = fundingSettlementIds[index];
                    boolean fundingAdvanced = state.treasuryState().fundingSettlement(symbol)
                            == expectedSettlementId
                            || !completeHeavyCycles && progress != null
                            && progress.settlementId() == expectedSettlementId;
                    if (!fundingAdvanced) {
                        throw new IllegalStateException("funding settlement missing for " + symbol);
                    }
                }
                long triggered = state.triggerOrders().values().stream()
                        .filter(trigger -> trigger.status() == CoreTriggerOrderStatus.TRIGGERED).count();
                if (triggered < triggerExecutions) {
                    throw new IllegalStateException("trigger workload did not complete");
                }
                state.users().values().forEach(user -> user.balances().values().forEach(
                        LinearPerpetualMixedWorkload::requireNonNegative));
            }

            @Override
            public void close() {
                harness.close();
            }

            @Override
            public SnapshotTemplate captureSnapshot() {
                return harness.snapshotTemplate(template.snapshot().accountLanes());
            }
        };
    }

    private static int[] scheduledLifecycleSymbols(int[] candidates, int budget, int cursor) {
        int size = Math.min(candidates.length, budget);
        if (size == candidates.length) return candidates;
        int[] selected = new int[size];
        for (int index = 0; index < size; index++) {
            selected[index] = candidates[(cursor + index) % candidates.length];
        }
        return selected;
    }

    private static long[] completedLaneOperations(CoreProbeState state) {
        long[] total = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
        long[] completed = state.laneMetrics().accountLaneCompletedOperations();
        for (int index = 0; index < completed.length; index++) {
            int type = index % CoreLaneMetrics.OPERATION_TYPE_COUNT;
            total[type] = Math.addExact(total[type], completed[index]);
        }
        return total;
    }

    private static void executeHftBurstsPipelined(Harness harness, Template template, int hftBatchSize,
                                                  int[] symbolIndices) {
        List<List<Long>> quoteOrderIds = new ArrayList<>(symbolIndices.length);
        for (int index : symbolIndices) {
            List<Long> symbolOrderIds = new ArrayList<>(hftBatchSize);
            List<PlaceOrderCommand> orders = new ArrayList<>(hftBatchSize);
            for (int burst = 0; burst < hftBatchSize; burst++) {
                long orderId = harness.nextOrderId();
                symbolOrderIds.add(orderId);
                orders.add(orderCommand(orderId, template.symbols().get(index), CoreOrderSide.SELL,
                        102, 2, CoreTimeInForce.GTC));
            }
            quoteOrderIds.add(List.copyOf(symbolOrderIds));
            harness.submit(harness.batchCommand(CoreMessageType.PLACE_ORDER_BATCH, CommandSource.GATEWAY,
                    template.hftMakers().get(index),
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)), orders.size()));
        }
        harness.drainSubmitted();
        for (int cursor = 0; cursor < symbolIndices.length; cursor++) {
            int index = symbolIndices[cursor];
            List<CancelOrderCommand> orders = quoteOrderIds.get(cursor).stream()
                    .map(CancelOrderCommand::new).toList();
            harness.submit(harness.batchCommand(CoreMessageType.CANCEL_ORDER_BATCH, CommandSource.GATEWAY,
                    template.hftMakers().get(index),
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders)), orders.size()));
        }
        harness.drainSubmitted();
        long[] sellLiquidityOrderIds = new long[symbolIndices.length];
        for (int cursor = 0; cursor < symbolIndices.length; cursor++) {
            int index = symbolIndices[cursor];
            long orderId = harness.nextOrderId();
            sellLiquidityOrderIds[cursor] = orderId;
            harness.submit(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                    template.hftMakers().get(index), order(orderId, template.symbols().get(index),
                            CoreOrderSide.SELL, 101, hftBatchSize * 2L, CoreTimeInForce.GTC)));
        }
        harness.drainSubmitted();
        for (int index : symbolIndices) {
            List<PlaceOrderCommand> orders = new ArrayList<>(hftBatchSize);
            for (int burst = 0; burst < hftBatchSize; burst++) {
                orders.add(orderCommand(harness.nextOrderId(), template.symbols().get(index), CoreOrderSide.BUY,
                        101, 1, CoreTimeInForce.IOC));
            }
            harness.submit(harness.batchCommand(CoreMessageType.PLACE_ORDER_BATCH, CommandSource.GATEWAY,
                    template.hftTakers().get(index),
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)), orders.size()));
        }
        harness.drainSubmitted();
        for (int cursor = 0; cursor < symbolIndices.length; cursor++) {
            int index = symbolIndices[cursor];
            harness.submit(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY,
                    template.hftMakers().get(index), TradingCommandCodec.encodeCancelOrder(
                            new CancelOrderCommand(sellLiquidityOrderIds[cursor]))));
        }
        harness.drainSubmitted();
        long[] buyLiquidityOrderIds = new long[symbolIndices.length];
        for (int cursor = 0; cursor < symbolIndices.length; cursor++) {
            int index = symbolIndices[cursor];
            long orderId = harness.nextOrderId();
            buyLiquidityOrderIds[cursor] = orderId;
            harness.submit(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                    template.hftMakers().get(index), order(orderId, template.symbols().get(index),
                            CoreOrderSide.BUY, 99, hftBatchSize * 2L, CoreTimeInForce.GTC)));
        }
        harness.drainSubmitted();
        for (int cursor = 0; cursor < symbolIndices.length; cursor++) {
            int index = symbolIndices[cursor];
            harness.submit(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY,
                    template.hftMakers().get(index), TradingCommandCodec.encodeCancelOrder(
                            new CancelOrderCommand(buyLiquidityOrderIds[cursor]))));
        }
        harness.drainSubmitted();
        for (int index : symbolIndices) {
            List<PlaceOrderCommand> orders = new ArrayList<>(hftBatchSize);
            for (int burst = 0; burst < hftBatchSize; burst++) {
                orders.add(orderCommand(harness.nextOrderId(), template.symbols().get(index), CoreOrderSide.SELL,
                        99, 1, CoreTimeInForce.IOC));
            }
            harness.submit(harness.batchCommand(CoreMessageType.PLACE_ORDER_BATCH, CommandSource.GATEWAY,
                    template.hftTakers().get(index),
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)), orders.size()));
        }
        harness.drainSubmitted();
    }

    private static void exerciseLifecycle(Harness harness, Template template, int index,
                                          boolean completeHeavyCycles, long settlementId,
                                          long[] markPriceSequences) {
        String symbol = template.symbols().get(index);
        long fundingRate = (index & 1) == 0 ? 100_000 : -100_000;
        long fundingCursor = 0;
        boolean fundingComplete;
        do {
            var fundingResponse = harness.execute(harness.command(
                    CoreMessageType.APPLY_FUNDING, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            settlementId, symbol, 1, fundingRate, fundingCursor, HEAVY_WORK_BATCH_SIZE))));
            var fundingProgress = CoreFundingProgressCodec.decode(fundingResponse.data());
            fundingCursor = fundingProgress.nextCursorUserId();
            fundingComplete = fundingProgress.complete();
        } while (completeHeavyCycles && !fundingComplete);
        boolean liquidationSymbol = !template.scaleConfig().boundedSymbolWork()
                && index == template.liquidationSymbolIndex();
        long markPrice = liquidationSymbol ? LIQUIDATION_MARK : SAFE_MARK;
        long priceSequence = nextMarkPriceSequence(harness, symbol, index, markPriceSequences);
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(symbol, 1, markPrice, priceSequence,
                                harness.nextCommandTimestamp()))));
        while (completeHeavyCycles && !harness.state().tradingState().riskState().scan().complete()) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(
                            new ContinueRiskScanCommand(HEAVY_WORK_BATCH_SIZE))));
        }
    }

    private static void exerciseLifecycleBounded(Harness harness, Template template, int index,
                                                 long[] settlementIds, long[] fundingCursors,
                                                 long[] markPriceSequences) {
        String symbol = template.symbols().get(index);
        long fundingRate = (index & 1) == 0 ? 100_000 : -100_000;
        if (harness.state().tradingState().treasuryState().fundingSettlement(symbol)
                == settlementIds[index]) {
            settlementIds[index] = Math.addExact(settlementIds[index], template.symbols().size());
        }
        var fundingResponse = harness.execute(harness.command(
                CoreMessageType.APPLY_FUNDING, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                        settlementIds[index], symbol, 1, fundingRate,
                        fundingCursors[index], HEAVY_WORK_BATCH_SIZE))));
        var fundingProgress = CoreFundingProgressCodec.decode(fundingResponse.data());
        fundingCursors[index] = fundingProgress.nextCursorUserId();
        if (fundingProgress.complete()) fundingCursors[index] = 0;

        var scan = harness.state().tradingState().riskState().scans().get(symbol);
        if (scan != null && !scan.complete()) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(
                            new ContinueRiskScanCommand(HEAVY_WORK_BATCH_SIZE))));
            return;
        }
        boolean liquidationSymbol = !template.scaleConfig().boundedSymbolWork()
                && index == template.liquidationSymbolIndex();
        long markPrice = liquidationSymbol ? LIQUIDATION_MARK : SAFE_MARK;
        long priceSequence = nextMarkPriceSequence(harness, symbol, index, markPriceSequences);
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                        symbol, 1, markPrice, priceSequence,
                        harness.nextCommandTimestamp()))));
    }

    private static void executeTriggerLifecycle(Harness harness, Template template, int index) {
        String symbol = template.symbols().get(index);
        long taker = template.hftTakers().get(index);
        long triggerId = harness.nextOrderId();
        boolean liquidationSymbol = index == template.liquidationSymbolIndex();
        var mark = harness.state().tradingState().riskState().markPrices().get(symbol);
        CoreTriggerOrderType triggerType = liquidationSymbol
                ? CoreTriggerOrderType.STOP_LOSS : CoreTriggerOrderType.TAKE_PROFIT;
        CoreTriggerCondition triggerCondition = liquidationSymbol
                ? CoreTriggerCondition.LESS_OR_EQUAL : CoreTriggerCondition.GREATER_OR_EQUAL;
        CoreTriggerOrderStateView trigger = new CoreTriggerOrderStateView(triggerId,
                ProductLine.LINEAR_PERPETUAL, taker, "mixed-trigger-" + triggerId, "", symbol,
                CoreOrderSide.SELL, triggerType, triggerCondition, mark.markPriceTicks(),
                0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 110, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "mixed-trace-" + triggerId, 0, 0, 0, 0, 1, 1, 0, 0);
        harness.execute(harness.command(CoreMessageType.PLACE_TRIGGER_ORDER, CommandSource.GATEWAY, taker,
                CoreTriggerOrderCodec.encodeState(trigger)));
        harness.execute(harness.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, CommandSource.OPERATIONS, 0,
                CoreTriggerOrderCodec.encodeExecute(triggerId, mark.priceSequence(),
                        mark.markPriceTicks(), harness.nextCommandTimestamp())));
    }

    private static long[] initialFundingSettlementIds(int symbolCount) {
        long[] settlementIds = new long[symbolCount];
        for (int index = 0; index < symbolCount; index++) settlementIds[index] = 10_000L + index;
        return settlementIds;
    }

    private static long[] initialMarkPriceSequences(Template template) {
        long[] priceSequences = new long[template.symbols().size()];
        for (int index = 0; index < priceSequences.length; index++) {
            priceSequences[index] = index == template.liquidationSymbolIndex() ? 2 : 1;
        }
        return priceSequences;
    }

    private static long nextMarkPriceSequence(Harness harness, String symbol, int index,
                                              long[] markPriceSequences) {
        var current = harness.state().tradingState().riskState().markPrices().get(symbol);
        if (current == null) throw new IllegalStateException("mixed workload mark price is missing: " + symbol);
        long observed = Math.max(markPriceSequences[index], current.priceSequence());
        long next = Math.incrementExact(observed);
        markPriceSequences[index] = next;
        return next;
    }

    private static CoreLiquidationState liquidation(Harness harness, long liquidationId) {
        return liquidation(harness.state().tradingState(), liquidationId);
    }

    private static CoreLiquidationState liquidation(TradingCoreState state, long liquidationId) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(liquidationId);
        if (liquidation == null) throw new IllegalStateException("liquidation state is missing");
        return liquidation;
    }

    private static void verifyPopulation(TradingCoreState state, List<Long> retailUsers,
                                         LinearPerpetualScaleConfig scaleConfig) {
        Set<Long> positionSizes = new HashSet<>();
        Map<Long, Integer> openOrders = new HashMap<>();
        state.orders().values().stream().filter(order -> order.status() == CoreOrderStatus.OPEN)
                .forEach(order -> openOrders.merge(order.userId(), 1, Math::addExact));
        Set<Integer> openOrderCounts = new HashSet<>();
        for (int index = 0; index < retailUsers.size(); index++) {
            long userId = retailUsers.get(index);
            var positions = state.user(userId).positions();
            positions.values().stream()
                    .mapToLong(position -> Math.absExact(position.signedQuantitySteps()))
                    .forEach(positionSizes::add);
            int actualOpenOrders = openOrders.getOrDefault(userId, 0);
            openOrderCounts.add(actualOpenOrders);
            if (scaleConfig.boundedSymbolWork()
                    && (positions.size() != positionCount(index, scaleConfig)
                    || actualOpenOrders != openOrderCount(index, scaleConfig))) {
                throw new IllegalStateException("mixed population density mismatch for user " + userId);
            }
        }
        boolean expectedHeterogeneousOrders = !scaleConfig.boundedSymbolWork()
                || scaleConfig.maxOpenOrdersPerUser() >= 3;
        if (!positionSizes.containsAll(Set.of(1L, 2L, 3L, 4L))
                || expectedHeterogeneousOrders && !openOrderCounts.containsAll(Set.of(0, 1, 2, 3))) {
            throw new IllegalStateException("mixed population is not heterogeneous");
        }
    }

    private static long totalFunds(TradingCoreState state) {
        long total = 0;
        for (var user : state.users().values()) total = Math.addExact(total, user.totalUnits(SETTLE_ASSET));
        CoreTreasuryState treasury = state.treasuryState();
        total = addLedger(total, treasury.feeBalances());
        total = addLedger(total, treasury.insuranceBalances());
        total = addLedger(total, treasury.liquidationFeeBalances());
        total = addLedger(total, treasury.fundingResidualBalances());
        total = addLedger(total, treasury.roundingResidualBalances());
        total = addLedger(total, treasury.clearingPnlBalances());
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(SETTLE_ASSET, 0L));
    }

    private static long addLedger(long total, Map<String, Long> ledger) {
        return Math.addExact(total, ledger.getOrDefault(SETTLE_ASSET, 0L));
    }

    private static void requireNonNegative(AssetBalance balance) {
        if (balance.availableUnits() < 0 || balance.lockedUnits() < 0) {
            throw new IllegalStateException("mixed workload produced a negative user balance");
        }
    }

    private static long positionQuantity(int userIndex) {
        return 1L + (userIndex & 3);
    }

    private static int positionCount(int userIndex, LinearPerpetualScaleConfig config) {
        return config.boundedSymbolWork()
                ? 1 + Math.floorMod(userIndex, config.maxPositionsPerUser()) : 1;
    }

    private static int openOrderCount(int userIndex, LinearPerpetualScaleConfig config) {
        if (!config.boundedSymbolWork()) return userIndex & 3;
        return config.maxOpenOrdersPerUser() == 0
                ? 0 : Math.floorMod(userIndex, config.maxOpenOrdersPerUser() + 1);
    }

    private static int positionSymbolIndex(int userIndex, int position,
                                           LinearPerpetualScaleConfig config) {
        int activeSymbols = config.activeSymbols();
        return switch (config.trafficProfile()) {
            case SINGLE_HOT -> 0;
            case PARETO_80_20 -> {
                int hotSymbols = Math.max(1, activeSymbols / 5);
                if (activeSymbols == hotSymbols || Math.floorMod(userIndex, 5) != 4) {
                    yield Math.floorMod(userIndex + position, hotSymbols);
                }
                yield hotSymbols + Math.floorMod(userIndex / 5 + position, activeSymbols - hotSymbols);
            }
            case UNIFORM, MOSTLY_IDLE, MARK_PRICE_STORM ->
                    Math.floorMod(userIndex + position, activeSymbols);
        };
    }

    private static int[] tradingSymbolIndices(LinearPerpetualScaleConfig config, int round) {
        if (!config.boundedSymbolWork() || config.trafficProfile() == LinearPerpetualTrafficProfile.UNIFORM
                || config.trafficProfile() == LinearPerpetualTrafficProfile.MOSTLY_IDLE) {
            return allIndices(config.activeSymbols());
        }
        if (config.trafficProfile() == LinearPerpetualTrafficProfile.SINGLE_HOT) return new int[]{0};
        if (config.trafficProfile() == LinearPerpetualTrafficProfile.MARK_PRICE_STORM) {
            return allIndices(Math.min(4, config.activeSymbols()));
        }
        int hotSymbols = Math.max(1, config.activeSymbols() / 5);
        if (Math.floorMod(round, 5) != 4 || hotSymbols == config.activeSymbols()) {
            return range(0, hotSymbols);
        }
        return range(hotSymbols, config.activeSymbols());
    }

    private static int[] allIndices(int count) {
        return range(0, count);
    }

    private static int[] range(int start, int end) {
        int[] values = new int[end - start];
        for (int index = 0; index < values.length; index++) values[index] = start + index;
        return values;
    }

    private static List<String> symbols(int count) {
        List<String> symbols = new ArrayList<>(count);
        for (int index = 0; index < count; index++) symbols.add("JMH-MIX-" + index + "-USDT");
        return List.copyOf(symbols);
    }

    private static byte[] order(long orderId, String symbol, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce timeInForce) {
        return TradingCommandCodec.encodePlaceOrder(
                orderCommand(orderId, symbol, side, price, quantity, timeInForce));
    }

    private static PlaceOrderCommand orderCommand(long orderId, String symbol, CoreOrderSide side, long price,
                                                   long quantity, CoreTimeInForce timeInForce) {
        return new PlaceOrderCommand(orderId, symbol, 1, side, price,
                quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                timeInForce, false, "mixed-" + orderId);
    }

    private static UpsertInstrumentCommand instrument(String symbol) {
        int symbolIndex = Integer.parseInt(symbol.substring("JMH-MIX-".length(), symbol.indexOf("-USDT")));
        String baseAsset = "MIX" + symbolIndex;
        return new UpsertInstrumentCommand(symbol, 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                baseAsset, SETTLE_ASSET, SETTLE_ASSET,
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }
}
