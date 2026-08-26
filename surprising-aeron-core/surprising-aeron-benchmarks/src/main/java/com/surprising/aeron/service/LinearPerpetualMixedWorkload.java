package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
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
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
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
    static final int HFT_BURSTS_PER_SYMBOL = 8;
    static final int OPEN_ORDER_USER_CAP = 128;
    private static final String SETTLE_ASSET = "USDT";
    private static final long ENTRY_PRICE = 100;
    private static final long SAFE_MARK = 99;
    private static final long LIQUIDATION_MARK = 1;
    private static final long SAFE_BALANCE = 1_000_000_000L;
    private static final long LIQUIDATION_BALANCE = 100;
    private static final long INSURANCE_SEED = 25;
    private static final long BASE_EPOCH_MILLIS = 1_700_000_000_000L;

    private LinearPerpetualMixedWorkload() {
    }

    record Template(
            SnapshotTemplate snapshot,
            int activeUsers,
            List<String> symbols,
            List<Long> positionMakers,
            List<Long> hftMakers,
            List<Long> hftTakers,
            long liquidationUser,
            long openingFunds) {

        Template {
            symbols = List.copyOf(symbols);
            positionMakers = List.copyOf(positionMakers);
            hftMakers = List.copyOf(hftMakers);
            hftTakers = List.copyOf(hftTakers);
            if (activeUsers < 1 || symbols.size() < 2 || symbols.size() != positionMakers.size()
                    || symbols.size() != hftMakers.size() || symbols.size() != hftTakers.size()
                    || liquidationUser <= 0 || openingFunds <= 0) {
                throw new IllegalArgumentException("invalid mixed workload template");
            }
        }
    }

    static Template template(int accountLanes, int activeUsers, int symbolCount) {
        if (activeUsers < accountLanes || activeUsers > LinearPerpetualBenchmarkSupport.MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("activeUsers must cover every lane and be at most 10000");
        }
        if (symbolCount < 2 || symbolCount > 16 || activeUsers < symbolCount) {
            throw new IllegalArgumentException("symbolCount must be in [2,16] and not exceed activeUsers");
        }
        List<String> symbols = symbols(symbolCount);
        Harness harness = Harness.create(accountLanes);
        try {
            for (String symbol : symbols) {
                harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeUpsertInstrument(instrument(symbol))));
                harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE,
                        CommandSource.KAFKA_INPUT_BRIDGE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand(symbol, 1, ENTRY_PRICE, 1, BASE_EPOCH_MILLIS))));
            }

            int infrastructureUsers = Math.addExact(Math.multiplyExact(symbolCount, 3), 1);
            List<Long> users = LinearPerpetualBenchmarkSupport.usersAcrossLanes(
                    accountLanes, Math.addExact(activeUsers, infrastructureUsers), 100_000);
            List<Long> retailUsers = users.subList(0, activeUsers);
            int cursor = activeUsers;
            List<Long> positionMakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> hftMakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            List<Long> hftTakers = List.copyOf(users.subList(cursor, cursor += symbolCount));
            long liquidationUser = users.get(cursor);

            long[] aggregateQuantities = new long[symbolCount];
            for (int index = 0; index < activeUsers; index++) {
                int symbolIndex = index % symbolCount;
                aggregateQuantities[symbolIndex] = Math.addExact(
                        aggregateQuantities[symbolIndex], positionQuantity(index));
            }
            aggregateQuantities[symbolCount - 1] = Math.addExact(
                    aggregateQuantities[symbolCount - 1], 10);

            for (int index = 0; index < symbolCount; index++) {
                long maker = positionMakers.get(index);
                harness.adjust(maker, SAFE_BALANCE);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                        order(harness.nextOrderId(), symbols.get(index), CoreOrderSide.SELL, ENTRY_PRICE,
                                aggregateQuantities[index], CoreTimeInForce.GTC)));
                harness.adjust(hftMakers.get(index), SAFE_BALANCE);
                harness.adjust(hftTakers.get(index), SAFE_BALANCE);
            }

            for (int index = 0; index < activeUsers; index++) {
                long userId = retailUsers.get(index);
                String symbol = symbols.get(index % symbolCount);
                harness.adjust(userId, SAFE_BALANCE);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        order(harness.nextOrderId(), symbol, CoreOrderSide.BUY, ENTRY_PRICE,
                                positionQuantity(index), CoreTimeInForce.IOC)));
            }
            harness.adjust(liquidationUser, LIQUIDATION_BALANCE);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, liquidationUser,
                    order(harness.nextOrderId(), symbols.getLast(), CoreOrderSide.BUY, ENTRY_PRICE,
                            10, CoreTimeInForce.IOC)));

            int openOrderUsers = Math.min(activeUsers, OPEN_ORDER_USER_CAP);
            for (int index = 0; index < openOrderUsers; index++) {
                long userId = retailUsers.get(index);
                String symbol = symbols.get(index % symbolCount);
                for (int open = 0; open < (index & 3); open++) {
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                            order(harness.nextOrderId(), symbol, CoreOrderSide.BUY, 90 - open,
                                    1, CoreTimeInForce.GTC)));
                }
            }
            harness.execute(harness.command(CoreMessageType.ADJUST_INSURANCE_FUND, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new AdjustInsuranceFundCommand(SETTLE_ASSET, INSURANCE_SEED))));
            verifyPopulation(harness.state().tradingState(), retailUsers);
            long openingFunds = totalFunds(harness.state().tradingState());
            return new Template(harness.snapshotTemplate(accountLanes), activeUsers, symbols,
                    positionMakers, hftMakers, hftTakers, liquidationUser, openingFunds);
        } finally {
            harness.close();
        }
    }

    static Scenario scenario(Template template) {
        Harness harness = Harness.restore(template.snapshot());
        return new Scenario() {
            private long operations;
            private long liquidationId;

            @Override
            public long run() {
                long batchBefore = harness.executedMessages();
                for (int burst = 0; burst < HFT_BURSTS_PER_SYMBOL; burst++) {
                    for (int index = 0; index < template.symbols().size(); index++) {
                        executeHftBurst(harness, template, index);
                    }
                }
                for (int index = 0; index < template.symbols().size(); index++) {
                    exerciseLifecycle(harness, template, index);
                }
                executeLossLifecycle(harness, template);
                operations = Math.subtractExact(harness.executedMessages(), batchBefore);
                return harness.state().tradingState().businessStateHash();
            }

            private void executeLossLifecycle(Harness target, Template source) {
                var work = target.executionWork();
                if (work.actions().size() != 1
                        || work.actions().getFirst().userId() != source.liquidationUser()) {
                    throw new IllegalStateException("mixed workload expected one dedicated liquidation action");
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
                CorePositionState makerPosition = target.state().tradingState()
                        .user(source.positionMakers().getLast()).positions().get(source.symbols().getLast());
                long profitPerStep = Math.subtractExact(makerPosition.entryPriceTicks(), LIQUIDATION_MARK);
                long closeQuantity = Math.floorDiv(
                        Math.addExact(adlRequired.deficitUnits(), profitPerStep - 1), profitPerStep);
                target.execute(target.command(CoreMessageType.EXECUTE_ADL, CommandSource.OPERATIONS, 0,
                        TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                                liquidationId, source.positionMakers().getLast(), source.symbols().getLast(),
                                CoreMarginMode.CROSS, CorePositionSide.NET,
                                makerPosition.signedQuantitySteps(), makerPosition.entryPriceTicks(), 2,
                                closeQuantity, adlRequired.deficitUnits()))));
            }

            @Override
            public long operations() {
                return operations;
            }

            @Override
            public void verify() {
                TradingCoreState state = harness.state().tradingState();
                long closingFunds = totalFunds(state);
                if (operations <= 0 || closingFunds != template.openingFunds()) {
                    throw new IllegalStateException("mixed workload violated command or funds invariant: operations="
                            + operations + ", openingFunds=" + template.openingFunds()
                            + ", closingFunds=" + closingFunds + ", treasury=" + state.treasuryState());
                }
                CoreLiquidationState liquidation = liquidation(state, liquidationId);
                if (liquidation.status() != CoreLiquidationState.Status.COMPLETED
                        || liquidation.deficitUnits() != 0
                        || !state.riskState().scan().complete()) {
                    throw new IllegalStateException("mixed workload lifecycle did not complete");
                }
                for (int index = 0; index < template.symbols().size(); index++) {
                    String symbol = template.symbols().get(index);
                    if (state.treasuryState().fundingSettlement(symbol) != 10_000L + index) {
                        throw new IllegalStateException("funding settlement missing for " + symbol);
                    }
                }
                long triggered = state.triggerOrders().values().stream()
                        .filter(trigger -> trigger.status() == CoreTriggerOrderStatus.TRIGGERED).count();
                if (triggered != template.symbols().size()) {
                    throw new IllegalStateException("trigger workload did not complete");
                }
                state.users().values().forEach(user -> user.balances().values().forEach(
                        LinearPerpetualMixedWorkload::requireNonNegative));
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    private static void executeHftBurst(Harness harness, Template template, int index) {
        String symbol = template.symbols().get(index);
        long maker = template.hftMakers().get(index);
        long taker = template.hftTakers().get(index);
        long makerOrderId = harness.nextOrderId();
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                order(makerOrderId, symbol, CoreOrderSide.SELL, 101, 2, CoreTimeInForce.GTC)));
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, taker,
                order(harness.nextOrderId(), symbol, CoreOrderSide.BUY, 101, 1, CoreTimeInForce.IOC)));
        harness.execute(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY, maker,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(makerOrderId))));
    }

    private static void exerciseLifecycle(Harness harness, Template template, int index) {
        String symbol = template.symbols().get(index);
        long taker = template.hftTakers().get(index);
        long triggerId = harness.nextOrderId();
        CoreTriggerOrderStateView trigger = new CoreTriggerOrderStateView(triggerId,
                ProductLine.LINEAR_PERPETUAL, taker, "mixed-trigger-" + triggerId, "", symbol,
                CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, ENTRY_PRICE,
                0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 110, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "mixed-trace-" + triggerId, 0, 0, 0, 0, 1, 1, 0, 0);
        harness.execute(harness.command(CoreMessageType.PLACE_TRIGGER_ORDER, CommandSource.GATEWAY, taker,
                CoreTriggerOrderCodec.encodeState(trigger)));
        harness.execute(harness.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, CommandSource.OPERATIONS, 0,
                CoreTriggerOrderCodec.encodeExecute(triggerId, 1, ENTRY_PRICE, BASE_EPOCH_MILLIS + 1)));
        long fundingRate = (index & 1) == 0 ? 100_000 : -100_000;
        harness.execute(harness.command(CoreMessageType.APPLY_FUNDING, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeApplyFunding(
                        new ApplyFundingCommand(10_000L + index, symbol, 1, fundingRate, 0, 4096))));
        long markPrice = index == template.symbols().size() - 1 ? LIQUIDATION_MARK : SAFE_MARK;
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(symbol, 1, markPrice, 2, BASE_EPOCH_MILLIS + 1))));
        while (!harness.state().tradingState().riskState().scan().complete()) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(256))));
        }
    }

    private static CoreLiquidationState liquidation(Harness harness, long liquidationId) {
        return liquidation(harness.state().tradingState(), liquidationId);
    }

    private static CoreLiquidationState liquidation(TradingCoreState state, long liquidationId) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(liquidationId);
        if (liquidation == null) throw new IllegalStateException("liquidation state is missing");
        return liquidation;
    }

    private static void verifyPopulation(TradingCoreState state, List<Long> retailUsers) {
        Set<Long> positionSizes = new HashSet<>();
        Map<Long, Integer> openOrders = new HashMap<>();
        state.orders().values().stream().filter(order -> order.status() == CoreOrderStatus.OPEN)
                .forEach(order -> openOrders.merge(order.userId(), 1, Math::addExact));
        Set<Integer> openOrderCounts = new HashSet<>();
        for (long userId : retailUsers) {
            state.user(userId).positions().values().stream()
                    .mapToLong(position -> Math.absExact(position.signedQuantitySteps()))
                    .forEach(positionSizes::add);
            openOrderCounts.add(openOrders.getOrDefault(userId, 0));
        }
        if (!positionSizes.containsAll(Set.of(1L, 2L, 3L, 4L))
                || !openOrderCounts.containsAll(Set.of(0, 1, 2, 3))) {
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

    private static List<String> symbols(int count) {
        List<String> symbols = new ArrayList<>(count);
        for (int index = 0; index < count; index++) symbols.add("JMH-MIX-" + index + "-USDT");
        return List.copyOf(symbols);
    }

    private static byte[] order(long orderId, String symbol, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce timeInForce) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, symbol, 1, side, price,
                quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                timeInForce, false, "mixed-" + orderId));
    }

    private static UpsertInstrumentCommand instrument(String symbol) {
        int symbolIndex = Integer.parseInt(symbol.substring("JMH-MIX-".length(), symbol.indexOf("-USDT")));
        String baseAsset = "MIX" + symbolIndex;
        return new UpsertInstrumentCommand(symbol, 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                baseAsset, SETTLE_ASSET, SETTLE_ASSET,
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }
}
