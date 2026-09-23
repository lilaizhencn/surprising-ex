package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.metrics.CoreLaneMetrics;
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
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
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

    static DenseResidentBook denseResidentBook(int accountLanes, int priceLevels, int ordersPerLevel) {
        int residentOrders = Math.multiplyExact(priceLevels, ordersPerLevel);
        if (priceLevels <= 0 || ordersPerLevel <= 0 || residentOrders > 2_000_000) {
            throw new IllegalArgumentException("dense resident book requires 1..2000000 orders");
        }
        Harness harness = base(accountLanes);
        try {
            int makerCount = Math.max(accountLanes, Math.min(residentOrders, 5_000));
            List<Long> makers = usersAcrossLanes(accountLanes, makerCount, 20_000);
            for (long maker : makers) harness.adjust(maker, SAFE_BALANCE);
            for (int level = 0; level < priceLevels; level++) {
                long price = ENTRY_PRICE + 1 + level;
                for (int index = 0; index < ordersPerLevel; index++) {
                    long maker = makers.get((level * ordersPerLevel + index) % makers.size());
                    harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                            order(harness.nextOrderId(), CoreOrderSide.SELL, price, 1, CoreTimeInForce.GTC)));
                }
            }
            // Population setup is deliberately synchronous.  The measured 256-in-flight path must
            // use the same cluster admission boundary as production; otherwise the Harness queues
            // callers asynchronously while Core still executes the legacy synchronous result path.
            harness.useClusterMatchingPipeline();
            return new DenseResidentBook(harness, List.copyOf(makers), ENTRY_PRICE + 1, residentOrders);
        } catch (RuntimeException failure) {
            harness.close();
            throw failure;
        }
    }

    static final class DenseResidentBook implements AutoCloseable {
        private static final int MAX_IN_FLIGHT = 256;
        private static final int COMPLETION_BATCH_SIZE = 64;
        private static final long MARK_HEARTBEAT_INTERVAL_NANOS = 1_000_000_000L;

        private final Harness harness;
        private final List<Long> users;
        private final long price;
        private final int residentOrders;
        private final long[] activeOrderIds;
        private final boolean[] cancelInFlight;
        private final int[] readyUsers;
        private final int[] inFlightUsers = new int[MAX_IN_FLIGHT];
        private final int maxInFlight;
        private final int completionBatchSize;
        private int readyHead;
        private int readyTail;
        private int readySize;
        private int inFlightHead;
        private int inFlightTail;
        private int inFlightSize;
        private long nextMarkHeartbeatNanos;
        private long windowSamples;
        private long fullWindowSamples;
        private long refillOperations;
        private long producerStarvationSamples;

        private DenseResidentBook(Harness harness, List<Long> users, long price, int residentOrders) {
            this.harness = harness;
            this.users = users;
            this.price = price;
            this.residentOrders = residentOrders;
            this.activeOrderIds = new long[users.size()];
            this.cancelInFlight = new boolean[users.size()];
            this.readyUsers = new int[users.size()];
            this.maxInFlight = Math.min(MAX_IN_FLIGHT, users.size());
            this.completionBatchSize = Math.min(COMPLETION_BATCH_SIZE, maxInFlight);
            for (int userIndex = 0; userIndex < users.size(); userIndex++) enqueueReady(userIndex);
            this.nextMarkHeartbeatNanos = System.nanoTime() + MARK_HEARTBEAT_INTERVAL_NANOS;
        }

        long runAsync(int operations) {
            if (operations < maxInFlight || operations % completionBatchSize != 0) {
                throw new IllegalArgumentException("async dense-book operations must cover the window in batches");
            }
            int scheduled = 0;
            int completed = 0;
            while (completed < operations) {
                publishMarkHeartbeatIfDue();
                while (scheduled < operations && inFlightSize < maxInFlight) {
                    submitNextReadyUser();
                    scheduled++;
                }
                windowSamples++;
                if (inFlightSize == maxInFlight) fullWindowSamples++;
                if (scheduled < operations && inFlightSize == 0) producerStarvationSamples++;
                int retired = 0;
                long deadline = System.nanoTime() + MATCH_TIMEOUT_NANOS;
                while (retired == 0) {
                    retired = harness.awaitReadyMatching(
                            Math.min(completionBatchSize, operations - completed), this::completeUserCommand);
                    if (retired == 0) {
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException("async dense-book window made no progress");
                        }
                        Thread.onSpinWait();
                    }
                }
                completed += retired;
            }
            if (scheduled != operations || inFlightSize != 0 || harness.pendingSubmissions() != 0) {
                throw new IllegalStateException("async dense-book invocation did not fully retire");
            }
            publishMarkHeartbeatIfDue();
            return completed;
        }

        private void submitNextReadyUser() {
            int userIndex = dequeueReady();
            long userId = users.get(userIndex);
            long orderId = activeOrderIds[userIndex];
            cancelInFlight[userIndex] = orderId != 0;
            CoreMessage command;
            if (orderId == 0) {
                orderId = harness.nextOrderId();
                activeOrderIds[userIndex] = orderId;
                command = harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        order(orderId, CoreOrderSide.SELL, price, 1, CoreTimeInForce.GTC));
            } else {
                command = harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY, userId,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
            }
            int pendingBefore = harness.pendingSubmissions();
            harness.submit(command);
            if (harness.pendingSubmissions() != pendingBefore + 1) {
                throw new IllegalStateException("async dense-book command did not enter the matching window");
            }
            inFlightUsers[inFlightTail] = userIndex;
            inFlightTail = (inFlightTail + 1) % inFlightUsers.length;
            inFlightSize++;
            if (pendingBefore != 0) refillOperations++;
        }

        private void completeUserCommand(long userId, long entryNanos, long acceptedNanos, long terminalNanos) {
            if (inFlightSize == 0) throw new IllegalStateException("async dense-book completion underflow");
            int userIndex = inFlightUsers[inFlightHead];
            inFlightHead = (inFlightHead + 1) % inFlightUsers.length;
            inFlightSize--;
            if (users.get(userIndex) != userId) {
                throw new IllegalStateException("async dense-book completion crossed FIFO user order");
            }
            if (cancelInFlight[userIndex]) activeOrderIds[userIndex] = 0;
            cancelInFlight[userIndex] = false;
            enqueueReady(userIndex);
        }

        private void publishMarkHeartbeatIfDue() {
            long now = System.nanoTime();
            if (now < nextMarkHeartbeatNanos || inFlightSize != 0) return;
            harness.publishMarkPriceHeartbeat(SYMBOL);
            nextMarkHeartbeatNanos = now + MARK_HEARTBEAT_INTERVAL_NANOS;
        }

        private void enqueueReady(int userIndex) {
            if (readySize == readyUsers.length) throw new IllegalStateException("dense-book ready queue overflow");
            readyUsers[readyTail] = userIndex;
            readyTail = (readyTail + 1) % readyUsers.length;
            readySize++;
        }

        private int dequeueReady() {
            if (readySize == 0) throw new IllegalStateException("dense-book ready queue exhausted");
            int userIndex = readyUsers[readyHead];
            readyHead = (readyHead + 1) % readyUsers.length;
            readySize--;
            return userIndex;
        }

        long acceptedMessages() {
            return harness.acceptedMessages();
        }

        long terminalMessages() {
            return harness.terminalMessages();
        }

        long acceptedCoreMessages() {
            return harness.acceptedCoreMessages();
        }

        long terminalCoreMessages() {
            return harness.terminalCoreMessages();
        }

        long windowSamples() {
            return windowSamples;
        }

        long fullWindowSamples() {
            return fullWindowSamples;
        }

        long refillOperations() {
            return refillOperations;
        }

        long producerStarvationSamples() {
            return producerStarvationSamples;
        }

        void verify() {
            if (inFlightSize != 0 || harness.pendingSubmissions() != 0
                    || harness.acceptedMessages() != harness.terminalMessages()
                    || harness.acceptedCoreMessages() != harness.terminalCoreMessages()
                    || harness.maxMatchingBacklog() != maxInFlight) {
                throw new IllegalStateException("async dense-book completion invariant failed");
            }
            for (int userIndex = 0; userIndex < activeOrderIds.length; userIndex++) {
                long orderId = activeOrderIds[userIndex];
                if (orderId == 0) continue;
                harness.submit(harness.command(CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY,
                        users.get(userIndex), TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId))));
                activeOrderIds[userIndex] = 0;
                if (harness.pendingSubmissions() == maxInFlight) harness.drainSubmitted();
            }
            harness.drainSubmitted();
            if (harness.state().tradingState().orders().size() != residentOrders) {
                throw new IllegalStateException("dense resident book size changed");
            }
            SnapshotTemplate snapshot = harness.snapshotTemplate(
                    harness.state().laneTopology().accountLaneCount());
            try (Harness restored = Harness.restore(snapshot)) {
                if (restored.state().tradingState().businessStateHash() != snapshot.businessStateHash()) {
                    throw new IllegalStateException("async dense-book snapshot recovery mismatch");
                }
            }
        }

        @Override
        public void close() {
            harness.close();
        }
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
                try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(
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
                        new ApplyMarkPriceCommand(SYMBOL, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS)));
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
                return response.appliedCommandCount();
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    static SnapshotTemplate stablePositionScanTemplate(int accountLanes, int positionUsers) {
        if (positionUsers < 1 || positionUsers > MAX_BENCHMARK_SCALE) {
            throw new IllegalArgumentException("positionUsers must be positive and at most "
                    + MAX_BENCHMARK_SCALE);
        }
        Harness harness = base(accountLanes);
        try {
            List<Long> users = usersAcrossLanes(accountLanes, positionUsers + 1, 70_000);
            long shortUser = users.getFirst();
            harness.adjust(shortUser, SAFE_BALANCE);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, shortUser,
                    order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE,
                            positionUsers, CoreTimeInForce.GTC)));
            for (int index = 1; index <= positionUsers; index++) {
                long longUser = users.get(index);
                harness.adjust(longUser, SAFE_BALANCE);
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, longUser,
                        order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, 1, CoreTimeInForce.IOC)));
            }
            return harness.snapshotTemplate(accountLanes);
        } finally {
            harness.close();
        }
    }

    static Scenario stablePositionScan(SnapshotTemplate template, int positionUsers) {
        Harness harness = Harness.restore(template);
        CoreMessage mark = harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, 99, 2, BASE_EPOCH_MILLIS + 1)));
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
                if (!harness.executionWork().actions().isEmpty()) {
                    throw new IllegalStateException("safe mark price unexpectedly produced liquidation work");
                }
                return response.appliedCommandCount();
            }

            @Override public long operations() { return positionUsers; }
            @Override public long terminalLifecycleOperations() { return positionUsers; }
            @Override public int positions() { return positionUsers + 1; }
            @Override public void verify() { verifySnapshot(harness); }
            @Override public void close() { harness.close(); }
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
                        new ApplyMarkPriceCommand(SYMBOL, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS))));
        while (!harness.state.tradingState().riskState().scan().complete()) {
            int maxUsers = harness.state.tradingState().riskState().scanControl().scanBatchSize();
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers))));
        }
        CoreLiquidationActionView action = harness.executionWork().actions().getFirst();
        var batchAction = new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(), action.symbol(),
                action.triggerPriceSequence(), action.markPriceTicks(),
                action.cursorOrderId());
        var batch = new ExecuteLiquidationBatchCommand(List.of(batchAction),
                ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0);
        CoreMessage command = harness.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, CommandSource.OPERATIONS,
                0, TradingCommandCodec.encodeExecuteLiquidationBatch(batch));
        return new Scenario() {
            @Override
            public long run() {
                return harness.execute(command).appliedCommandCount();
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
        if (liquidationUsers < 1 || liquidationUsers > ExecuteLiquidationBatchCommand.MAX_ACTIONS
                || openOrders < 0 || openOrders > 256) {
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
                        new ApplyMarkPriceCommand(SYMBOL, ADVERSE_PRICE, 2, BASE_EPOCH_MILLIS + 1))));
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
                        action.symbol(), action.triggerPriceSequence(),
                        action.markPriceTicks(), action.cursorOrderId()))
                .toList();
        CoreMessage command = harness.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, CommandSource.OPERATIONS,
                0, TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                        batchActions, ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS, 0, null, 0)));
        return new Scenario() {
            @Override public long run() { return harness.execute(command).appliedCommandCount(); }
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
                        new ApplyMarkPriceCommand(SYMBOL, 1, 2, BASE_EPOCH_MILLIS + 1))));
        while (!harness.state.runtimeRiskScanComplete(SYMBOL)) {
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
        }
        List<CoreLiquidationActionView> actions = harness.executionWork().actions();
        List<ExecuteLiquidationBatchAction> batchActions = actions.stream()
                .map(action -> new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                        action.symbol(), action.triggerPriceSequence(),
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
                return harness.execute(executeAdl).appliedCommandCount();
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
        try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(snapshot.productLine(), snapshot.bytes())) {
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
            private TradingCoreRuntime restored;

            @Override
            public long run() {
                restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, template.bytes());
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
                return harness.execute(command).appliedCommandCount();
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
                return harness.execute(command).appliedCommandCount();
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
        Harness harness = new Harness(new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL), new Sequences());
        if (harness.state.laneTopology().accountLaneCount() != accountLanes) {
            harness.close();
            throw new IllegalStateException("Core did not start with requested account lane count");
        }
        harness.execute(harness.command(CoreMessageType.REGISTER_INSTRUMENT, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeRegisterInstrument(instrument())));
        harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand(SYMBOL, ENTRY_PRICE, 1, BASE_EPOCH_MILLIS))));
        return harness;
    }

    private static byte[] order(long orderId, CoreOrderSide side, long price, long quantity,
                                CoreTimeInForce timeInForce) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, SYMBOL, side, price,
                quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                timeInForce, false, "jmh-" + orderId));
    }

    private static byte[] reduceOnlyOrder(long orderId, long price) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, SYMBOL,
                CoreOrderSide.SELL, price, 1, true, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "jmh-ro-" + orderId));
    }

    private static RegisterInstrumentCommand instrument() {
        return new RegisterInstrumentCommand(SYMBOL, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
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
        private final TradingCoreRuntime state;
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
        /** Saturation runs use the same asynchronous command scope as Cluster ingress. */
        private boolean clusterMatchingPipeline;
        private ClusterCommandWindow clusterCommandWindow;
        private byte[] deferredBatchResponseData;
        private int deferredBatchOperationWeight;
        private OpenLoopBusinessLatencyRecorder businessLatencies;

        private Harness(TradingCoreRuntime state, Sequences sequences) {
            this.state = state;
            this.sequences = sequences;
        }

        static Harness create(int accountLanes) {
            return create(accountLanes, ProductLine.LINEAR_PERPETUAL);
        }

        static Harness create(int accountLanes, ProductLine productLine) {
            configureAccountLanes(accountLanes);
            Harness harness = new Harness(new TradingCoreRuntime(productLine), new Sequences());
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
            TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(template.productLine(), template.bytes());
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

        void advanceClockTo(long epochMillis) {
            if (epochMillis < nextCommandTimestamp()) throw new IllegalArgumentException("clock cannot go backwards");
            sequences.clusterPosition = Math.max(sequences.clusterPosition,
                    Math.multiplyExact(Math.subtractExact(epochMillis, BASE_EPOCH_MILLIS),
                            COMMANDS_PER_LOGICAL_MILLISECOND));
        }

        void refreshMarkPricesIfDue(List<String> symbols) {
            long now = nextCommandTimestamp();
            for (String symbol : symbols) {
                var mark = state.runtimeMarkPrice(symbol);
                if (mark == null) throw new IllegalStateException("workload mark price is missing: " + symbol);
                if (now - mark.generatedAtEpochMillis() < 1_000) continue;
                execute(command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(symbol,
                                mark.markPriceTicks(),
                                Math.incrementExact(mark.priceSequence()), now))));
            }
        }

        void publishMarkPriceHeartbeat(String symbol) {
            long now = nextCommandTimestamp();
            var mark = state.runtimeMarkPrice(symbol);
            if (mark == null) throw new IllegalStateException("workload mark price is missing: " + symbol);
            submit(command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(symbol,
                            mark.markPriceTicks(), Math.incrementExact(mark.priceSequence()), now))));
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

        void useClusterMatchingPipeline() {
            clusterMatchingPipeline = true;
            clusterCommandWindow = new ClusterCommandWindow(256);
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
            executedMessages = Math.addExact(executedMessages, operationWeight);
            acceptedMessages = Math.addExact(acceptedMessages, operationWeight);
            acceptedCoreMessages = Math.incrementExact(acceptedCoreMessages);
            int pendingBefore = state.pendingMatchingCount();
            ClusterCommandWindow.Entry windowEntry = null;
            CoreResponse response;
            if (clusterMatchingPipeline
                    && TradingCoreRuntime.isMatchingCommand(command.header().messageType())) {
                if (!state.prepareClusterPipelineScope(command, clusterCommandWindow)
                        || clusterCommandWindow.conflictingPrefixSize() != 0) {
                    throw new IllegalStateException("saturation command is not independently pipelineable");
                }
                windowEntry = clusterCommandWindow.add(null, command,
                        command.header().submittedAtEpochMillis(), command.header().correlationId());
                response = state.applyClusterCommand(command, command.header().submittedAtEpochMillis(),
                        command.header().correlationId(), clusterCommandWindow.decoded(command));
            } else {
                response = state.apply(command);
            }
            if (businessLatencies != null) businessLatencies.accepted(businessLatency);
            long acceptedAtNanos = submittedAtNanos == 0 ? 0 : System.nanoTime();
            long sequence = state.matchingSequence(command.header().commandId());
            if (windowEntry != null) clusterCommandWindow.bindSequence(windowEntry, sequence);
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
                try {
                    validateTerminal(command, response, operationWeight, "");
                    if (windowEntry != null) retireClusterWindowCommand();
                    terminalMessages = Math.addExact(terminalMessages, operationWeight);
                    terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
                    if (businessLatencies != null) businessLatencies.terminal(businessLatency);
                } finally {
                    // The local Harness is the terminal transport consumer. Production egress
                    // releases the same arena lease after encoding the response; retaining it
                    // here would exhaust all 512 slots and turn the benchmark into an allocation
                    // test. CoreResponse status/count fields remain safe for scenario assertions.
                    state.releaseResponse(response);
                }
            }
            return pending;
        }

        void drainSubmitted() {
            long deadline = System.nanoTime() + MATCH_TIMEOUT_NANOS;
            int idle = 0;
            while (!submittedMatching.isEmpty()) {
                int completed = commitReadyMatching(
                        Math.min(256, submittedMatching.size()), true,
                        (userId, entryNanos, acceptedNanos, terminalNanos) -> { });
                if (completed == 0) {
                    // Production Owner polling is deliberately non-blocking.  The benchmark
                    // driver owns the wait boundary, so retry the pump while the Matcher/Lane
                    // workers publish their independent completion facts.
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("matching completion pump made no progress");
                    if (idle++ < 2_048) Thread.onSpinWait();
                    else LockSupport.parkNanos(1_000L);
                } else {
                    idle = 0;
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
                MatchingResult matching = BenchmarkMatchingAwait.awaitMatchingResult(state, pending.sequence);
                if (matching == null) continue;
                nativeMatchingResult = matching.resultCode();
                pending.response = state.completeMatching(pending.sequence, matching,
                        pending.command.header().submittedAtEpochMillis(),
                        pending.command.header().correlationId());
                matchingCompleted = pending.response != null
                        && pending.response.resultCode() != CoreResultCode.MATCHING_PENDING;
            } while (!matchingCompleted);
            submittedMatching.removeFirst();
            retireClusterWindowCommand();
            try {
                validateTerminal(pending.command, pending.response, pending.operationWeight, nativeMatchingResult);
                terminalMessages = Math.addExact(terminalMessages, pending.operationWeight);
                terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
                if (businessLatencies != null) businessLatencies.terminal(pending.businessLatency);
            } finally {
                state.releaseResponse(pending.response);
            }
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

        int pollReadyMatching(int maxCompletions, MatchingCompletionConsumer completionConsumer) {
            if (maxCompletions <= 0 || completionConsumer == null) {
                throw new IllegalArgumentException("matching batch requires a positive limit and consumer");
            }
            return commitReadyMatching(maxCompletions, false, completionConsumer);
        }

        private int commitReadyMatching(int maxCompletions, boolean awaitFirst,
                                        MatchingCompletionConsumer completionConsumer) {
            int completed = state.commits.commitReadyMatching(maxCompletions, benchmarkTimestamp(sequences.clusterPosition),
                    sequences.clusterPosition, awaitFirst, (sequence, response) -> {
                        PendingCommand pending = submittedMatching.peekFirst();
                        if (pending == null || pending.sequence != sequence) {
                            throw new IllegalStateException("matching batch completion crossed submission order: actual="
                                    + sequence + ", expected=" + (pending == null ? 0 : pending.sequence)
                                    + ", expectedType=" + (pending == null ? "none" : pending.command.header().messageType())
                                    + ", coreHead=" + state.firstPendingMatchingSequence()
                                    + ", corePending=" + state.pendingMatchingCount()
                                    + ", submitted=" + submittedMatching.size());
                        }
                        pending.response = response;
                        submittedMatching.removeFirst();
                        retireClusterWindowCommand();
                        try {
                            validateTerminal(pending.command, response, pending.operationWeight, "");
                            terminalMessages = Math.addExact(terminalMessages, pending.operationWeight);
                            terminalCoreMessages = Math.incrementExact(terminalCoreMessages);
                            if (businessLatencies != null) businessLatencies.terminal(pending.businessLatency);
                            long terminalAtNanos = System.nanoTime();
                            completionConsumer.accept(pending.command.header().userId(),
                                    pending.submittedAtNanos, pending.acceptedAtNanos, terminalAtNanos);
                        } finally {
                            state.releaseResponse(response);
                        }
                    });
            return completed;
        }

        private void retireClusterWindowCommand() {
            if (clusterCommandWindow != null) clusterCommandWindow.removePrefix(1);
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
                if (TradingOrderBatchCodec.firstNonAppliedItem(response, operationWeight) >= 0) {
                    validateBatchResponse(response.data(), operationWeight);
                }
                if (deferBatchResponseValidation) {
                    // The terminal consumer releases the arena lease after this method returns.
                    // Keep an owned payload for verification after subsequent commands reuse that slot.
                    deferredBatchResponseData = response.data();
                    deferredBatchOperationWeight = operationWeight;
                } else validateBatchResponse(response.data(), operationWeight);
            }
        }

        void verifyDeferredBatchResponse() {
            if (!deferBatchResponseValidation || deferredBatchResponseData == null) {
                return;
            }
            validateBatchResponse(deferredBatchResponseData, deferredBatchOperationWeight);
        }

        private static void validateBatchResponse(byte[] responseData, int operationWeight) {
            var result = TradingOrderBatchCodec.decodeResult(responseData);
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

        TradingCoreRuntime state() {
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
