package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

final class LinearPerpetualBenchmarkSupport {

    static final int DEFAULT_ACCOUNT_LANES = 4;
    static final int DEFAULT_MAKER_DEPTH = 16;
    static final int DEFAULT_RISK_USERS = 32;
    static final int MAX_BENCHMARK_SCALE = 10_000;
    private static final String SYMBOL = "JMH-BTC-USDT";
    private static final String SETTLE_ASSET = "USDT";
    private static final long ENTRY_PRICE = 100;
    private static final long ADVERSE_PRICE = 80;
    private static final long BASE_EPOCH_MILLIS = 1_700_000_000_000L;
    private static final long SAFE_BALANCE = 1_000_000_000L;
    private static final long LIQUIDATION_BALANCE = 230;
    private static final long MATCH_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int PROJECTION_ADMISSION_HEADROOM = 3;
    private static final int COMMANDS_PER_LOGICAL_MILLISECOND = 1_024;

    private LinearPerpetualBenchmarkSupport() {
    }

    interface Scenario extends AutoCloseable {
        long run();

        default long operations() {
            return 1;
        }

        default long acceptedOperations() {
            return operations();
        }

        default long terminalOperations() {
            return operations();
        }

        default long acceptedCoreMessages() {
            return acceptedOperations();
        }

        default long terminalCoreMessages() {
            return terminalOperations();
        }

        default long maxBacklog() {
            return 0;
        }

        default long terminalTrades() {
            return 0;
        }

        default long terminalTradingOperations() {
            return terminalOperations();
        }

        default long terminalLifecycleOperations() {
            return 0;
        }

        default int incompleteRiskScans() {
            return 0;
        }

        default int incompleteFundingSettlements() {
            return 0;
        }

        default int activeOrders() {
            return 0;
        }

        default int positions() {
            return 0;
        }

        default int triggerOrders() {
            return 0;
        }

        default int terminalTombstones() {
            return 0;
        }

        default long laneOperations() {
            return 0;
        }

        default long laneOperations(int operationType) {
            return 0;
        }

        default void verify() {
        }

        @Override
        void close();
    }

    record SnapshotTemplate(byte[] bytes, long businessStateHash, int accountLanes,
                            ProductLine productLine, long nextClusterPosition) {
        SnapshotTemplate {
            bytes = bytes.clone();
            if (businessStateHash == 0 || accountLanes < 2 || productLine == null
                    || nextClusterPosition <= 0) {
                throw new IllegalArgumentException("invalid benchmark snapshot template");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        int sizeBytes() {
            return bytes.length;
        }
    }

    record OrderContinuationTemplate(SnapshotTemplate snapshot, long[] userIds, long[] orderIds) {
        OrderContinuationTemplate {
            userIds = userIds.clone();
            orderIds = orderIds.clone();
            if (snapshot == null || userIds.length != 256 || orderIds.length != 256) {
                throw new IllegalArgumentException("order continuation template requires 256 orders");
            }
        }
    }

    static void configureAccountLanes(int accountLanes) {
        if (accountLanes < 2 || accountLanes > Long.SIZE
                || (accountLanes & (accountLanes - 1)) != 0) {
            throw new IllegalArgumentException("accountLanes must be a power of two in [2,64]");
        }
        System.setProperty("surprising.aeron.account-lanes", Integer.toString(accountLanes));
    }

    static long benchmarkTimestamp(long correlationId) {
        return BASE_EPOCH_MILLIS + correlationId / COMMANDS_PER_LOGICAL_MILLISECOND;
    }

    static Scenario limitOrderPlacement(int accountLanes) {
        Harness harness = base(accountLanes);
        long userId = usersAcrossLanes(accountLanes, 1, 1_000).getFirst();
        harness.adjust(userId, SAFE_BALANCE);
        CoreMessage command = harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                order(harness.nextOrderId(), CoreOrderSide.SELL, 101, 1, CoreTimeInForce.GTC));
        return commandScenario(harness, command);
    }

    static Scenario cancelRestingOrder(int accountLanes) {
        Harness harness = base(accountLanes);
        long userId = usersAcrossLanes(accountLanes, 1, 2_000).getFirst();
        harness.adjust(userId, SAFE_BALANCE);
        long orderId = harness.nextOrderId();
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                order(orderId, CoreOrderSide.SELL, 101, 1, CoreTimeInForce.GTC)));
        CoreMessage command = harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY, userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        return commandScenario(harness, command);
    }

    static Scenario amendRestingOrder(int accountLanes) {
        Harness harness = base(accountLanes);
        long userId = usersAcrossLanes(accountLanes, 1, 2_500).getFirst();
        harness.adjust(userId, SAFE_BALANCE);
        long originalOrderId = harness.nextOrderId();
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                order(originalOrderId, CoreOrderSide.SELL, 101, 1, CoreTimeInForce.GTC)));
        CoreMessage command = harness.command(CoreMessageType.AMEND_ORDER, CommandSource.GATEWAY, userId,
                TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(
                        originalOrderId, harness.nextOrderId(), "", 102L, 1L,
                        CoreTimeInForce.GTC, null)));
        return commandScenario(harness, command);
    }

    static OrderContinuationTemplate orderContinuationTemplate(int accountLanes) {
        Harness harness = base(accountLanes);
        long[] users = new long[256];
        long[] orders = new long[256];
        List<Long> selected = usersAcrossLanes(accountLanes, 256, 30_000);
        try {
            for (int index = 0; index < users.length; index++) {
                users[index] = selected.get(index);
                harness.adjust(users[index], SAFE_BALANCE);
                orders[index] = harness.nextOrderId();
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, users[index],
                        order(orders[index], CoreOrderSide.SELL, 101, 1, CoreTimeInForce.GTC)));
            }
            return new OrderContinuationTemplate(harness.snapshotTemplate(accountLanes), users, orders);
        } finally {
            harness.close();
        }
    }

    static Scenario cancelBurst256(OrderContinuationTemplate template) {
        Harness harness = Harness.restore(template.snapshot());
        CoreMessage[] commands = new CoreMessage[256];
        for (int index = 0; index < commands.length; index++) {
            commands[index] = harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY,
                    template.userIds()[index], TradingCommandCodec.encodeCancelOrder(
                            new CancelOrderCommand(template.orderIds()[index])));
        }
        return burstScenario(harness, commands);
    }

    static SnapshotTemplate deepFillBurstTemplate(int accountLanes, int makerDepth) {
        validateScale("makerDepth", accountLanes, makerDepth);
        try (Harness harness = base(accountLanes)) {
            List<Long> users = usersAcrossLanes(accountLanes, accountLanes + 256, 50_000);
            for (long user : users) harness.adjust(user, SAFE_BALANCE);
            for (int index = 0; index < Math.multiplyExact(256, makerDepth); index++) {
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                        users.get(index % accountLanes),
                        order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, 1, CoreTimeInForce.GTC)));
            }
            return harness.snapshotTemplate(accountLanes);
        }
    }

    static Scenario deepFillBurst256(SnapshotTemplate template, int makerDepth) {
        Harness harness = Harness.restore(template);
        List<Long> users = usersAcrossLanes(template.accountLanes(), template.accountLanes() + 256, 50_000);
        CoreMessage[] commands = new CoreMessage[256];
        for (int index = 0; index < commands.length; index++) {
            commands[index] = harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                    users.get(template.accountLanes() + index),
                    order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, makerDepth, CoreTimeInForce.IOC));
        }
        Scenario burst = burstScenario(harness, commands);
        return new Scenario() {
            @Override public long run() { return burst.run(); }
            @Override public long operations() { return 256; }
            @Override public long maxBacklog() { return burst.maxBacklog(); }
            @Override public long terminalTrades() { return 256L * makerDepth; }
            @Override public void verify() {
                burst.verify();
                var state = harness.state().tradingState();
                long balances = 0;
                long netPosition = 0;
                for (var user : state.users().values()) {
                    var balance = user.balances().get(SETTLE_ASSET);
                    if (balance != null) balances = Math.addExact(balances,
                            Math.addExact(balance.availableUnits(), balance.lockedUnits()));
                    for (var position : user.positions().values()) {
                        netPosition = Math.addExact(netPosition, position.signedQuantitySteps());
                    }
                    if (!user.reservations().isEmpty()) {
                        throw new IllegalStateException("deep fill retained terminal reservations");
                    }
                }
                long fees = state.treasuryState().feeBalances().getOrDefault(SETTLE_ASSET, 0L);
                if (Math.addExact(balances, fees) != Math.multiplyExact(users.size(), SAFE_BALANCE)
                        || netPosition != 0 || harness.state().activeOrderCount() != 0) {
                    throw new IllegalStateException("deep fill violated funds, positions or terminal orders");
                }
            }
            @Override public void close() { burst.close(); }
        };
    }

    static Scenario amendBurst256(OrderContinuationTemplate template) {
        Harness harness = Harness.restore(template.snapshot());
        CoreMessage[] commands = new CoreMessage[256];
        for (int index = 0; index < commands.length; index++) {
            commands[index] = harness.command(CoreMessageType.AMEND_ORDER, CommandSource.GATEWAY,
                    template.userIds()[index], TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(
                            template.orderIds()[index], harness.nextOrderId(), "", 102L, 1L,
                            CoreTimeInForce.GTC, null)));
        }
        return burstScenario(harness, commands);
    }

    private static Scenario burstScenario(Harness harness, CoreMessage[] commands) {
        return new Scenario() {
            @Override
            public long run() {
                for (CoreMessage command : commands) harness.submit(command);
                if (harness.pendingSubmissions() != 256) {
                    throw new IllegalStateException("order continuation window did not reach 256");
                }
                harness.drainSubmitted();
                return harness.state().stateHash();
            }

            @Override public long operations() { return 256; }
            @Override public long maxBacklog() { return harness.maxMatchingBacklog(); }
            @Override
            public void verify() {
                if (harness.pendingSubmissions() != 0) {
                    throw new IllegalStateException("order continuation commands remain unfinished");
                }
                SnapshotTemplate completed = harness.snapshotTemplate(
                        harness.state().laneTopology().accountLaneCount());
                try (CoreProbeState restored = CoreProbeState.fromSnapshot(
                        completed.productLine(), completed.bytes())) {
                    if (restored.tradingState().businessStateHash() != completed.businessStateHash()) {
                        throw new IllegalStateException("order continuation snapshot recovery mismatch");
                    }
                }
            }
            @Override public void close() { harness.close(); }
        };
    }

    static Scenario fullTakerFill(int accountLanes) {
        return fill(accountLanes, 10, 10, 3_000);
    }

    static Scenario partialFill(int accountLanes) {
        return fill(accountLanes, 10, 4, 4_000);
    }

    static Scenario multiLaneMatching(int accountLanes, int makerDepth) {
        return multiLaneMatching(multiLaneMatchingTemplate(accountLanes, makerDepth), makerDepth);
    }

    static SnapshotTemplate multiLaneMatchingTemplate(int accountLanes, int makerDepth) {
        validateScale("makerDepth", accountLanes, makerDepth);
        Harness harness = base(accountLanes);
        try {
            List<Long> users = usersAcrossLanes(accountLanes, accountLanes + 1, 5_000);
            for (int lane = 0; lane < accountLanes; lane++) {
                harness.adjust(users.get(lane), SAFE_BALANCE);
            }
            for (int index = 0; index < makerDepth; index++) {
                long maker = users.get(index & (accountLanes - 1));
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                        order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, 1, CoreTimeInForce.GTC)));
            }
            harness.adjust(users.getLast(), SAFE_BALANCE);
            return harness.snapshotTemplate(accountLanes);
        } finally {
            harness.close();
        }
    }

    static Scenario multiLaneMatching(SnapshotTemplate template, int makerDepth) {
        validateScale("makerDepth", template.accountLanes(), makerDepth);
        Harness harness = Harness.restore(template);
        long taker = usersAcrossLanes(template.accountLanes(), template.accountLanes() + 1, 5_000).getLast();
        CoreMessage command = harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, taker,
                order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, makerDepth, CoreTimeInForce.IOC));
        return commandScenario(harness, command);
    }

    static SnapshotTemplate riskScanTemplate(int accountLanes, int riskUsers) {
        Harness harness = positionedUsers(accountLanes, riskUsers);
        try {
            return harness.snapshotTemplate(accountLanes);
        } finally {
            harness.close();
        }
    }

    static Scenario riskScan(SnapshotTemplate template) {
        Harness harness = Harness.restore(template);
        CoreMessage mark = harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 1, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS)));
        return new Scenario() {
            @Override
            public long run() {
                CoreResponse response = harness.execute(mark);
                while (!harness.state.runtimeRiskScanComplete(SYMBOL)) {
                    int maxUsers = CoreRiskState.defaultScanControl().scanBatchSize();
                    response = harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN,
                            CommandSource.OPERATIONS, 0,
                            TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers))));
                }
                if (harness.executionWork().actions().isEmpty()) {
                    throw new IllegalStateException("risk scan produced no liquidation work");
                }
                return response.stateHash();
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    static Scenario liquidationExecution(int accountLanes) {
        Harness harness = base(accountLanes);
        List<Long> users = usersAcrossLanes(accountLanes, 2, 30_000);
        long shortUser = users.getFirst();
        long longUser = users.getLast();
        harness.adjust(shortUser, LIQUIDATION_BALANCE);
        harness.adjust(longUser, LIQUIDATION_BALANCE);
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, shortUser,
                order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, 10, CoreTimeInForce.GTC)));
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, longUser,
                order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, 10, CoreTimeInForce.GTC)));
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 1, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS))));
        while (!harness.state.tradingState().riskState().scan().complete()) {
            int maxUsers = harness.state.tradingState().riskState().scanControl().scanBatchSize();
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers))));
        }
        CoreLiquidationActionView action = harness.executionWork().actions().getFirst();
        var batchAction = new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(), action.symbol(),
                action.instrumentVersion(), action.triggerPriceSequence(), action.markPriceTicks(),
                action.cursorOrderId());
        var batch = new ExecuteLiquidationBatchCommand(List.of(batchAction),
                ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0);
        CoreMessage command = harness.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, CommandSource.OPERATIONS,
                0, TradingCommandCodec.encodeExecuteLiquidationBatch(batch));
        return new Scenario() {
            @Override
            public long run() {
                return harness.execute(command).stateHash();
            }

            @Override
            public void verify() {
                verifyLiquidationState(harness, action.userId(), shortUser);
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    static Scenario liquidationBatchExecution(int accountLanes, int liquidationUsers, int openOrders) {
        if (liquidationUsers < 1 || liquidationUsers > 256 || openOrders < 0 || openOrders > 256) {
            throw new IllegalArgumentException("invalid liquidation batch benchmark scale");
        }
        Harness harness = positionedUsers(accountLanes, liquidationUsers, Math.max(10, openOrders));
        List<Long> users = usersAcrossLanes(accountLanes, liquidationUsers + 1, 20_000);
        long cancellationUser = users.get(1);
        for (int index = 0; index < openOrders; index++) {
            long orderId = harness.nextOrderId();
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, cancellationUser,
                    reduceOnlyOrder(orderId, 110 + index)));
        }
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 1, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS + 1))));
        while (!harness.state.runtimeRiskScanComplete(SYMBOL)) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
        }
        List<CoreLiquidationActionView> actions = harness.executionWork().actions();
        if (actions.size() != liquidationUsers) {
            throw new IllegalStateException("expected " + liquidationUsers + " liquidation actions, got "
                    + actions.size());
        }
        List<ExecuteLiquidationBatchAction> batchActions = actions.stream()
                .map(action -> new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                        action.symbol(), action.instrumentVersion(), action.triggerPriceSequence(),
                        action.markPriceTicks(), action.cursorOrderId()))
                .toList();
        CoreMessage command = harness.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, CommandSource.OPERATIONS,
                0, TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                        batchActions, ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0)));
        return new Scenario() {
            @Override public long run() { return harness.execute(command).stateHash(); }
            @Override public long operations() { return actions.size(); }
            @Override public long maxBacklog() { return harness.maxMatchingBacklog(); }
            @Override public void verify() {
                for (CoreLiquidationActionView action : actions) {
                    CoreLiquidationState value = harness.state.tradingState().riskState().liquidations()
                            .get(action.liquidationId());
                    if (value == null || value.status() != CoreLiquidationState.Status.COMPLETED
                            && value.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                        throw new IllegalStateException("liquidation batch did not reach a settlement boundary");
                    }
                }
                verifySnapshot(harness);
            }
            @Override public void close() { harness.close(); }
        };
    }

    static Scenario insuranceShortfall(int accountLanes, int liquidationUsers, boolean resolveToAdl) {
        Harness harness = positionedUsers(accountLanes, liquidationUsers);
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 1, 1, 2, BASE_EPOCH_MILLIS + 1))));
        while (!harness.state.runtimeRiskScanComplete(SYMBOL)) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
        }
        List<CoreLiquidationActionView> actions = harness.executionWork().actions();
        List<ExecuteLiquidationBatchAction> batchActions = actions.stream()
                .map(action -> new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                        action.symbol(), action.instrumentVersion(), action.triggerPriceSequence(),
                        action.markPriceTicks(), action.cursorOrderId()))
                .toList();
        harness.execute(harness.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                        batchActions, ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0))));
        if (!resolveToAdl) {
            return new Scenario() {
                private CoreLiquidationWorkView work;
                @Override public long run() { work = harness.insuranceWork(); return work.resolutions().size(); }
                @Override public long operations() { return liquidationUsers; }
                @Override public void verify() {
                    if (work == null || work.resolutions().size() != liquidationUsers
                            || work.resolutions().stream().mapToLong(
                                    CoreLiquidationWorkView.Resolution::recommendedCoveredUnits).sum() <= 0) {
                        throw new IllegalStateException("insurance shortfall allocation was not produced");
                    }
                    verifySnapshot(harness);
                }
                @Override public void close() { harness.close(); }
            };
        }
        CoreLiquidationWorkView.Resolution resolution = harness.insuranceWork().resolutions().stream()
                .min(Comparator.comparingLong(CoreLiquidationWorkView.Resolution::triggerPriceSequence)
                        .thenComparingLong(CoreLiquidationWorkView.Resolution::userId)
                        .thenComparing(CoreLiquidationWorkView.Resolution::symbol)
                        .thenComparingInt(value -> value.positionSide().ordinal())
                        .thenComparingLong(CoreLiquidationWorkView.Resolution::liquidationId))
                .orElseThrow();
        long adlUser = usersAcrossLanes(accountLanes, liquidationUsers + 1, 20_000).getFirst();
        var adlPosition = harness.state.tradingState().user(adlUser).positions().get(SYMBOL);
        long residual = Math.subtractExact(
                harness.state.tradingState().riskState().liquidations().get(resolution.liquidationId()).deficitUnits(),
                resolution.recommendedCoveredUnits());
        long profitPerStep = Math.subtractExact(ENTRY_PRICE, 1);
        long closeQuantity = Math.floorDiv(Math.addExact(residual, profitPerStep - 1), profitPerStep);
        CoreMessage resolve = harness.command(CoreMessageType.RESOLVE_LIQUIDATION, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                        resolution.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE,
                        resolution.recommendedCoveredUnits())));
        CoreMessage executeAdl = harness.command(CoreMessageType.EXECUTE_ADL, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                        resolution.liquidationId(), adlUser, SYMBOL, CoreMarginMode.CROSS, CorePositionSide.NET,
                        adlPosition.signedQuantitySteps(), adlPosition.entryPriceTicks(), 2,
                        closeQuantity, residual)));
        return new Scenario() {
            @Override public long run() {
                harness.execute(resolve);
                return harness.execute(executeAdl).stateHash();
            }
            @Override public long operations() { return 2; }
            @Override public void verify() {
                CoreLiquidationState value = harness.state.tradingState().riskState().liquidations()
                        .get(resolution.liquidationId());
                if (value == null || value.status() != CoreLiquidationState.Status.COMPLETED
                        || value.deficitUnits() != 0) {
                    throw new IllegalStateException("insurance shortfall did not complete through ADL");
                }
                verifySnapshot(harness);
            }
            @Override public void close() { harness.close(); }
        };
    }

    private static void verifySnapshot(Harness harness) {
        SnapshotTemplate snapshot = harness.snapshotTemplate(harness.state.laneTopology().accountLaneCount());
        try (CoreProbeState restored = CoreProbeState.fromSnapshot(snapshot.productLine(), snapshot.bytes())) {
            if (restored.tradingState().businessStateHash() != snapshot.businessStateHash()) {
                throw new IllegalStateException("liquidation benchmark snapshot recovery mismatch");
            }
        }
    }

    static SnapshotTemplate recoveryTemplate(int accountLanes, int makerDepth) {
        try (Scenario scenario = multiLaneMatching(accountLanes, makerDepth)) {
            scenario.run();
        }
        Harness harness = base(accountLanes);
        try {
            List<Long> users = usersAcrossLanes(accountLanes, makerDepth, 10_000);
            for (long userId : users) {
                harness.adjust(userId, SAFE_BALANCE);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        order(harness.nextOrderId(), CoreOrderSide.SELL, 101, 1, CoreTimeInForce.GTC)));
            }
            return harness.snapshotTemplate(accountLanes);
        } finally {
            harness.close();
        }
    }

    static Scenario snapshotRecovery(SnapshotTemplate template) {
        return new Scenario() {
            private CoreProbeState restored;

            @Override
            public long run() {
                restored = CoreProbeState.fromSnapshot(ProductLine.LINEAR_PERPETUAL, template.bytes());
                long actualHash = restored.tradingState().businessStateHash();
                if (actualHash != template.businessStateHash()
                        || restored.laneTopology().accountLaneCount() != template.accountLanes()) {
                    throw new IllegalStateException("snapshot recovery changed state or lane topology");
                }
                return actualHash;
            }

            @Override
            public void close() {
                if (restored != null) restored.close();
            }
        };
    }

    private static Scenario fill(int accountLanes, long makerQuantity, long takerQuantity, long startUserId) {
        Harness harness = base(accountLanes);
        List<Long> users = usersAcrossLanes(accountLanes, 2, startUserId);
        long maker = users.getFirst();
        long taker = users.getLast();
        harness.adjust(maker, SAFE_BALANCE);
        harness.adjust(taker, SAFE_BALANCE);
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, makerQuantity,
                        CoreTimeInForce.GTC)));
        CoreMessage command = harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, taker,
                order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, takerQuantity, CoreTimeInForce.IOC));
        return new Scenario() {
            @Override
            public long run() {
                return harness.execute(command).stateHash();
            }

            @Override
            public void verify() {
                CoreLaneMetrics metrics = harness.state().laneMetrics();
                int parallelLanes = 0;
                for (int highWaterMark : metrics.accountLaneQueueHighWaterMarks()) {
                    if (highWaterMark > 0) parallelLanes++;
                }
                if (parallelLanes < 2) {
                    throw new IllegalStateException("cross-lane fill did not exercise parallel settlement");
                }
                for (int depth : metrics.accountLaneQueueDepths()) {
                    if (depth != 0) throw new IllegalStateException("Account Lane queue did not drain");
                }
                for (long rejected : metrics.accountLaneRejectedSubmissions()) {
                    if (rejected != 0) throw new IllegalStateException("Account Lane rejected settlement work");
                }
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    private static Scenario commandScenario(Harness harness, CoreMessage command) {
        return new Scenario() {
            @Override
            public long run() {
                return harness.execute(command).stateHash();
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    private static void verifyLiquidationState(Harness harness, long liquidatedUserId, long shortUserId) {
        var state = harness.state.tradingState();
        var liquidated = state.user(liquidatedUserId);
        var liquidatedBalance = liquidated.balances().get(SETTLE_ASSET);
        var liquidatedPosition = liquidated.positions().get(SYMBOL);
        var shortUser = state.user(shortUserId);
        var shortBalance = shortUser.balances().get(SETTLE_ASSET);
        var shortPosition = shortUser.positions().get(SYMBOL);
        long insurance = state.treasuryState().insuranceBalances().getOrDefault(SETTLE_ASSET, 0L);
        if (liquidatedUserId == shortUserId
                || liquidatedBalance == null || liquidatedBalance.availableUnits() != 30
                || liquidatedBalance.lockedUnits() != 0
                || liquidatedPosition == null || liquidatedPosition.signedQuantitySteps() != 0
                || liquidatedPosition.positionMarginUnits() != 0
                || shortBalance == null || shortBalance.availableUnits() != 130
                || shortBalance.lockedUnits() != 100
                || shortPosition == null || shortPosition.signedQuantitySteps() != -10
                || shortPosition.positionMarginUnits() != 100
                || insurance != 200) {
            throw new IllegalStateException("liquidation benchmark state failed funds or position verification");
        }
    }

    private static Harness positionedUsers(int accountLanes, int riskUsers) {
        return positionedUsers(accountLanes, riskUsers, 10);
    }

    private static Harness positionedUsers(int accountLanes, int riskUsers, int positionQuantity) {
        if (riskUsers < 1 || riskUsers > MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("riskUsers must be positive and at most " + MAX_BENCHMARK_SCALE);
        }
        if (positionQuantity < 1 || positionQuantity > MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("positionQuantity must be positive and at most "
                    + MAX_BENCHMARK_SCALE);
        }
        Harness harness = base(accountLanes);
        List<Long> users = usersAcrossLanes(accountLanes, riskUsers + 1, 20_000);
        long safeShort = users.getFirst();
        harness.adjust(safeShort, SAFE_BALANCE);
        harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, safeShort,
                order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE,
                        Math.multiplyExact(riskUsers, (long) positionQuantity), CoreTimeInForce.GTC)));
        for (int index = 1; index <= riskUsers; index++) {
            long vulnerableLong = users.get(index);
            harness.adjust(vulnerableLong, Math.floorDiv(
                    Math.multiplyExact(LIQUIDATION_BALANCE, positionQuantity), 10));
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, vulnerableLong,
                    order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE,
                            positionQuantity, CoreTimeInForce.IOC)));
        }
        return harness;
    }

    private static void validateScale(String parameter, int accountLanes, int value) {
        if (value < accountLanes || value > MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException(parameter + " must cover every lane and be at most "
                    + MAX_BENCHMARK_SCALE);
        }
    }

    private static Harness base(int accountLanes) {
        configureAccountLanes(accountLanes);
        Harness harness = new Harness(new CoreProbeState(ProductLine.LINEAR_PERPETUAL), new Sequences());
        if (harness.state.laneTopology().accountLaneCount() != accountLanes) {
            harness.close();
            throw new IllegalStateException("Core did not start with requested account lane count");
        }
        harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeUpsertInstrument(instrument())));
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 1, ENTRY_PRICE, 1, BASE_EPOCH_MILLIS))));
        return harness;
    }

    private static byte[] order(long orderId, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce timeInForce) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, SYMBOL, 1, side, price,
                quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                timeInForce, false, "jmh-" + orderId));
    }

    private static byte[] reduceOnlyOrder(long orderId, long price) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, SYMBOL, 1,
                CoreOrderSide.SELL, price, 1, true, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "jmh-ro-" + orderId));
    }

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
                SETTLE_ASSET, 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }

    static List<Long> usersAcrossLanes(int accountLanes, int count, long startUserId) {
        configureAccountLanes(accountLanes);
        LaneTopology topology = LaneTopology.configured(false);
        List<Long> users = new ArrayList<>(count);
        long candidate = startUserId;
        for (int index = 0; index < count; index++) {
            int targetLane = index & (accountLanes - 1);
            while (topology.accountLaneId(candidate) != targetLane) candidate++;
            users.add(candidate++);
        }
        return List.copyOf(users);
    }

    static final class Harness implements AutoCloseable {
        private final CoreProbeState state;
        private final Sequences sequences;
        private long executedMessages;
        private long acceptedMessages;
        private long terminalMessages;
        private long acceptedCoreMessages;
        private long terminalCoreMessages;
        private int maxMatchingBacklog;
        private final ArrayDeque<PendingCommand> submittedMatching = new ArrayDeque<>();
        private final IdentityHashMap<CoreMessage, Integer> batchOperationWeights = new IdentityHashMap<>();
        private boolean deferBatchResponseValidation;
        private CoreResponse deferredBatchResponse;
        private int deferredBatchOperationWeight;
        private OpenLoopBusinessLatencyRecorder businessLatencies;
        private Runnable admissionBackpressureDrain;

        private Harness(CoreProbeState state, Sequences sequences) {
            this.state = state;
            this.sequences = sequences;
        }

        static Harness create(int accountLanes) {
            return create(accountLanes, ProductLine.LINEAR_PERPETUAL);
        }

        static Harness create(int accountLanes, ProductLine productLine) {
            configureAccountLanes(accountLanes);
            Harness harness = new Harness(new CoreProbeState(productLine), new Sequences());
            if (harness.state.laneTopology().accountLaneCount() != accountLanes) {
                harness.close();
                throw new IllegalStateException("Core did not start with requested account lane count");
            }
            return harness;
        }

        static Harness restore(SnapshotTemplate template) {
            return restore(template, false);
        }

        static Harness restore(SnapshotTemplate template, boolean deferBatchResponseValidation) {
            configureAccountLanes(template.accountLanes());
            CoreProbeState restored = CoreProbeState.fromSnapshot(template.productLine(), template.bytes());
            Harness harness = new Harness(restored, Sequences.after(
                    restored.appliedCommandCount(), template.nextClusterPosition()));
            harness.deferBatchResponseValidation = deferBatchResponseValidation;
            return harness;
        }

        void adjust(long userId, long units) {
            adjust(userId, SETTLE_ASSET, units);
        }

        void adjust(long userId, String asset, long units) {
            execute(command(CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, userId,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units))));
        }

        long nextOrderId() {
            return sequences.orderId++;
        }

        long nextCommandTimestamp() {
            return benchmarkTimestamp(sequences.clusterPosition);
        }

        void beginBusinessLatencies(int targetOperationsPerSecond) {
            businessLatencies = OpenLoopBusinessLatencyRecorder.createIfEnabled(targetOperationsPerSecond);
        }

        void commitBusinessLatencies() {
            if (businessLatencies != null) businessLatencies.commit();
            businessLatencies = null;
        }

        CoreMessage command(CoreMessageType type, CommandSource source, long userId, byte[] payload) {
            long sourceSequence = sequences.next(source);
            long correlationId = sequences.clusterPosition++;
            return new CoreMessage(CoreMessageHeader.command(type,
                    new UUID(source.ordinal() + 1L, sourceSequence), productLine(),
                    source, sourceId(source), sourceSequence, userId, benchmarkTimestamp(correlationId),
                    correlationId), payload);
        }

        CoreMessage batchCommand(CoreMessageType type, CommandSource source, long userId,
                                 byte[] payload, int operationWeight) {
            if ((type != CoreMessageType.PLACE_ORDER_BATCH
                    && type != CoreMessageType.CANCEL_ORDER_BATCH
                    && type != CoreMessageType.AMEND_ORDER_BATCH) || operationWeight <= 0) {
                throw new IllegalArgumentException("invalid benchmark batch operation weight");
            }
            CoreMessage command = command(type, source, userId, payload);
            batchOperationWeights.put(command, operationWeight);
            return command;
        }

        CoreResponse execute(CoreMessage command) {
            PendingCommand submitted = submitCommand(command);
            drainSubmitted();
            return submitted.response;
        }

        void submit(CoreMessage command) {
            submitCommand(command);
        }

        void submitTimed(CoreMessage command) {
            submitCommand(command, System.nanoTime());
        }

        void submitScheduled(CoreMessage command, long scheduledEntryNanos) {
            if (scheduledEntryNanos <= 0) throw new IllegalArgumentException("scheduled entry must be positive");
            submitCommand(command, scheduledEntryNanos);
        }

        private PendingCommand submitCommand(CoreMessage command) {
            return submitCommand(command, 0);
        }

        private PendingCommand submitCommand(CoreMessage command, long submittedAtNanos) {
            while (submittedAtNanos != 0 && System.nanoTime() < submittedAtNanos) Thread.onSpinWait();
            Integer batchWeight = batchOperationWeights.remove(command);
            int operationWeight = batchWeight == null ? 1 : batchWeight;
            OpenLoopBusinessLatencyRecorder.Token businessLatency = businessLatencies == null
                    ? null : businessLatencies.enter(command.header().messageType(), operationWeight);
            awaitProjectionAdmissionCapacity();
            executedMessages = Math.addExact(executedMessages, operationWeight);
            acceptedMessages = Math.addExact(acceptedMessages, operationWeight);
            acceptedCoreMessages = Math.incrementExact(acceptedCoreMessages);
            int pendingBefore = state.pendingMatchingCount();
            CoreResponse response = state.apply(command);
            if (businessLatencies != null) businessLatencies.accepted(businessLatency);
            long acceptedAtNanos = submittedAtNanos == 0 ? 0 : System.nanoTime();
            long sequence = state.matchingSequence(command.header().commandId());
            boolean indirectSequence = false;
            if (sequence == 0 && pendingBefore == 0 && state.pendingMatchingCount() != 0) {
                sequence = state.firstPendingMatchingSequence();
                indirectSequence = true;
            }
            PendingCommand pending = new PendingCommand(
                    command, sequence, operationWeight, response, indirectSequence,
                    submittedAtNanos, acceptedAtNanos, businessLatency);
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING || sequence != 0) {
                if (sequence == 0) throw new IllegalStateException("matching sequence was not registered");
                submittedMatching.addLast(pending);
                maxMatchingBacklog = Math.max(maxMatchingBacklog, submittedMatching.size());
            } else {
                validateTerminal(command, response, operationWeight, "");
                terminalMessages = Math.addExact(terminalMessages, operationWeight);
                terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
                if (businessLatencies != null) businessLatencies.terminal(businessLatency);
            }
            return pending;
        }

        private void awaitProjectionAdmissionCapacity() {
            long deadline = System.nanoTime() + MATCH_TIMEOUT_NANOS;
            int idle = 0;
            while (!state.hasProjectionAdmissionCapacity(PROJECTION_ADMISSION_HEADROOM)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("commit journal admission remained saturated for 30 seconds");
                }
                if (!submittedMatching.isEmpty()) {
                    int pendingBefore = submittedMatching.size();
                    if (admissionBackpressureDrain == null) drainOldestLatencyNanos();
                    else admissionBackpressureDrain.run();
                    if (submittedMatching.size() >= pendingBefore) {
                        throw new IllegalStateException("commit admission drain made no matching progress");
                    }
                    idle = 0;
                    continue;
                }
                if (idle++ < 1_024) Thread.onSpinWait();
                else LockSupport.parkNanos(1_000L);
            }
        }

        void admissionBackpressureDrain(Runnable drain) {
            admissionBackpressureDrain = drain;
        }

        void drainSubmitted() {
            while (!submittedMatching.isEmpty()) {
                int completed = commitReadyMatching(
                        Math.min(256, submittedMatching.size()), true,
                        (userId, entryNanos, acceptedNanos, terminalNanos) -> { });
                if (completed == 0) {
                    throw new IllegalStateException("matching completion pump made no progress");
                }
            }
        }

        int pendingSubmissions() {
            return submittedMatching.size();
        }

        long drainOldestLatencyNanos() {
            PendingCommand pending = submittedMatching.peekFirst();
            if (pending == null) throw new IllegalStateException("no matching submission to drain");
            long deadline = System.nanoTime() + MATCH_TIMEOUT_NANOS;
            boolean matchingCompleted = false;
            String nativeMatchingResult = "";
            do {
                long registeredSequence = pending.indirectSequence ? pending.sequence
                        : state.matchingSequence(pending.command.header().commandId());
                if (registeredSequence == 0) {
                    CoreResponse refreshed = state.apply(pending.command);
                    if (refreshed.resultCode() != CoreResultCode.MATCHING_PENDING) {
                        pending.response = refreshed;
                        matchingCompleted = true;
                        continue;
                    }
                    throw new IllegalStateException("pending matching command lost its sequence");
                }
                if (registeredSequence != pending.sequence) {
                    throw new IllegalStateException("matching command sequence changed from "
                            + pending.sequence + " to " + registeredSequence);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IllegalStateException("matching timed out for "
                            + pending.command.header().messageType()
                            + " sequence=" + pending.sequence
                            + " firstPending=" + state.firstPendingMatchingSequence()
                            + " pendingCount=" + state.pendingMatchingCount()
                            + " submittedCount=" + submittedMatching.size());
                }
                CoreMatchingResult matching = state.awaitMatchingResult(pending.sequence);
                if (matching == null) continue;
                nativeMatchingResult = matching.resultCode();
                pending.response = state.completeMatching(pending.sequence, matching,
                        pending.command.header().submittedAtEpochMillis(),
                        pending.command.header().correlationId());
                matchingCompleted = pending.response != null
                        && pending.response.resultCode() != CoreResultCode.MATCHING_PENDING;
            } while (!matchingCompleted);
            submittedMatching.removeFirst();
            validateTerminal(pending.command, pending.response, pending.operationWeight, nativeMatchingResult);
            terminalMessages = Math.addExact(terminalMessages, pending.operationWeight);
            terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
            if (businessLatencies != null) businessLatencies.terminal(pending.businessLatency);
            return pending.submittedAtNanos == 0 ? 0 : System.nanoTime() - pending.submittedAtNanos;
        }

        int drainReadyMatching(int maxCompletions, java.util.function.LongConsumer latencyRecorder) {
            if (maxCompletions <= 0 || latencyRecorder == null) {
                throw new IllegalArgumentException("matching drain batch requires a positive limit and recorder");
            }
            return commitReadyMatching(maxCompletions, true,
                    (userId, entryNanos, acceptedNanos, terminalNanos) ->
                            latencyRecorder.accept(entryNanos == 0 ? 0 : terminalNanos - entryNanos));
        }

        int awaitReadyMatching(int maxCompletions, MatchingCompletionConsumer completionConsumer) {
            if (maxCompletions <= 0 || completionConsumer == null) {
                throw new IllegalArgumentException("matching batch requires a positive limit and consumer");
            }
            return commitReadyMatching(maxCompletions, true, completionConsumer);
        }

        private int commitReadyMatching(int maxCompletions, boolean awaitFirst,
                                        MatchingCompletionConsumer completionConsumer) {
            int completed = state.commitReadyMatching(maxCompletions, benchmarkTimestamp(sequences.clusterPosition),
                    sequences.clusterPosition, awaitFirst, (sequence, response) -> {
                        PendingCommand pending = submittedMatching.peekFirst();
                        if (pending == null || pending.sequence != sequence) {
                            throw new IllegalStateException("matching batch completion crossed submission order");
                        }
                        pending.response = response;
                        submittedMatching.removeFirst();
                        validateTerminal(pending.command, response, pending.operationWeight, "");
                        terminalMessages = Math.addExact(terminalMessages, pending.operationWeight);
                        terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
                        if (businessLatencies != null) businessLatencies.terminal(pending.businessLatency);
                        long terminalAtNanos = System.nanoTime();
                        completionConsumer.accept(pending.command.header().userId(),
                                pending.submittedAtNanos, pending.acceptedAtNanos, terminalAtNanos);
                    });
            return completed;
        }

        @FunctionalInterface
        interface MatchingCompletionConsumer {
            void accept(long userId, long scheduledEntryNanos,
                        long acceptedAtNanos, long terminalAtNanos);
        }

        private void validateTerminal(
                CoreMessage command, CoreResponse response, int operationWeight, String nativeMatchingResult) {
            if (response.commandStatus() != ResponseStatus.APPLIED
                    && response.commandStatus() != ResponseStatus.OK) {
                throw new IllegalStateException("benchmark command rejected type=" + command.header().messageType()
                        + " userId=" + command.header().userId()
                        + " status=" + response.status() + '/' + response.commandStatus()
                        + " result=" + response.resultCode()
                        + (nativeMatchingResult.isEmpty() ? "" : " nativeMatching=" + nativeMatchingResult)
                        + " applied=" + state.appliedCommandCount()
                        + " users=" + state.tradingState().users().size()
                        + " orders=" + state.tradingState().orders().size());
            }
            if (command.header().messageType() == CoreMessageType.PLACE_ORDER_BATCH
                    || command.header().messageType() == CoreMessageType.CANCEL_ORDER_BATCH
                    || command.header().messageType() == CoreMessageType.AMEND_ORDER_BATCH) {
                if (deferBatchResponseValidation) {
                    deferredBatchResponse = response;
                    deferredBatchOperationWeight = operationWeight;
                } else validateBatchResponse(response, operationWeight);
            }
        }

        void verifyDeferredBatchResponse() {
            if (!deferBatchResponseValidation || deferredBatchResponse == null) {
                return;
            }
            validateBatchResponse(deferredBatchResponse, deferredBatchOperationWeight);
        }

        private static void validateBatchResponse(CoreResponse response, int operationWeight) {
            var result = TradingOrderBatchCodec.decodeResult(response.data());
            if (result.items().size() != operationWeight
                    || result.items().stream().anyMatch(item -> item.status() != ResponseStatus.APPLIED)) {
                throw new IllegalStateException("benchmark order batch did not complete every item: " + result);
            }
        }

        CoreLiquidationWorkView executionWork() {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                    new UUID(99, sequences.clusterPosition++), productLine(),
                    CommandSource.OPERATIONS, sourceId(CommandSource.OPERATIONS), 0, 0,
                    benchmarkTimestamp(sequences.clusterPosition), sequences.clusterPosition),
                    CoreLiquidationWorkCodec.encodeQuery(productLine(),
                            CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1_000, 1_048_576));
            executedMessages++;
            CoreResponse response = state.apply(query);
            acceptedMessages++;
            terminalMessages++;
            acceptedCoreMessages++;
            terminalCoreMessages++;
            if (response.status() != ResponseStatus.OK) {
                throw new IllegalStateException("liquidation work query failed: " + response.resultCode());
            }
            return CoreLiquidationWorkCodec.decodeWork(response.data());
        }

        CoreLiquidationWorkView insuranceWork() {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                    new UUID(100, sequences.clusterPosition++), productLine(),
                    CommandSource.OPERATIONS, sourceId(CommandSource.OPERATIONS), 0, 0,
                    benchmarkTimestamp(sequences.clusterPosition), sequences.clusterPosition),
                    CoreLiquidationWorkCodec.encodeQuery(productLine(),
                            CoreLiquidationWorkView.Purpose.INSURANCE, 0, 1_000, 1_048_576));
            executedMessages++;
            CoreResponse response = state.apply(query);
            acceptedMessages++;
            terminalMessages++;
            acceptedCoreMessages++;
            terminalCoreMessages++;
            if (response.status() != ResponseStatus.OK) {
                throw new IllegalStateException("insurance work query failed: " + response.resultCode());
            }
            return CoreLiquidationWorkCodec.decodeWork(response.data());
        }

        SnapshotTemplate snapshotTemplate(int accountLanes) {
            byte[] snapshot = state.snapshot();
            return new SnapshotTemplate(snapshot, state.tradingState().businessStateHash(), accountLanes,
                    productLine(), sequences.clusterPosition);
        }

        private ProductLine productLine() {
            return state.productLine();
        }

        CoreProbeState state() {
            return state;
        }

        long executedMessages() {
            return executedMessages;
        }

        long acceptedMessages() {
            return acceptedMessages;
        }

        long terminalMessages() {
            return terminalMessages;
        }

        long acceptedCoreMessages() {
            return acceptedCoreMessages;
        }

        long terminalCoreMessages() {
            return terminalCoreMessages;
        }

        int maxMatchingBacklog() {
            return maxMatchingBacklog;
        }

        int matchingCompletionHighWaterMark() {
            return state.matchingCompletionHighWaterMark();
        }

        int matchingCompletionCapacity() {
            return state.matchingCompletionCapacity();
        }

        int dispatchedSettlementHighWaterMark() {
            return state.dispatchedSettlementHighWaterMark();
        }

        long terminalTradeCount() {
            return state.terminalTradeCount();
        }

        @Override
        public void close() {
            state.close();
        }

        private static int sourceId(CommandSource source) {
            return switch (source) {
                case OPERATIONS -> 9;
                case KAFKA_INPUT_BRIDGE -> 89;
                default -> 7;
            };
        }

        private static final class PendingCommand {
            private final CoreMessage command;
            private final long sequence;
            private final int operationWeight;
            private final boolean indirectSequence;
            private final long submittedAtNanos;
            private final long acceptedAtNanos;
            private final OpenLoopBusinessLatencyRecorder.Token businessLatency;
            private CoreResponse response;

            private PendingCommand(CoreMessage command, long sequence, int operationWeight,
                                   CoreResponse response, boolean indirectSequence,
                                   long submittedAtNanos, long acceptedAtNanos,
                                   OpenLoopBusinessLatencyRecorder.Token businessLatency) {
                this.command = command;
                this.sequence = sequence;
                this.operationWeight = operationWeight;
                this.response = response;
                this.indirectSequence = indirectSequence;
                this.submittedAtNanos = submittedAtNanos;
                this.acceptedAtNanos = acceptedAtNanos;
                this.businessLatency = businessLatency;
            }
        }
    }

    private static final class Sequences {
        private long gateway = 1;
        private long operations = 1;
        private long kafka = 1;
        private long clusterPosition = 1;
        private long orderId = 1_000_000;

        static Sequences after(long appliedCommandCount, long nextClusterPosition) {
            Sequences sequences = new Sequences();
            long next = Math.addExact(appliedCommandCount, 1);
            sequences.gateway = next;
            sequences.operations = next;
            sequences.kafka = next;
            sequences.clusterPosition = nextClusterPosition;
            sequences.orderId = 2_000_000 + appliedCommandCount;
            return sequences;
        }

        long next(CommandSource source) {
            return switch (source) {
                case OPERATIONS -> operations++;
                case KAFKA_INPUT_BRIDGE -> kafka++;
                default -> gateway++;
            };
        }
    }
}
