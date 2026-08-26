package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
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
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LinearPerpetualBenchmarkSupport {

    static final int DEFAULT_ACCOUNT_LANES = 4;
    static final int DEFAULT_MAKER_DEPTH = 16;
    static final int DEFAULT_RISK_USERS = 32;
    private static final String SYMBOL = "JMH-BTC-USDT";
    private static final String SETTLE_ASSET = "USDT";
    private static final long ENTRY_PRICE = 100;
    private static final long ADVERSE_PRICE = 80;
    private static final long BASE_EPOCH_MILLIS = 1_700_000_000_000L;
    private static final long SAFE_BALANCE = 1_000_000_000L;
    private static final long LIQUIDATION_BALANCE = 230;
    private static final long MATCH_TIMEOUT_NANOS = 30_000_000_000L;

    private LinearPerpetualBenchmarkSupport() {
    }

    interface Scenario extends AutoCloseable {
        long run();

        default void verify() {
        }

        @Override
        void close();
    }

    record SnapshotTemplate(byte[] bytes, long businessStateHash, int accountLanes) {
        SnapshotTemplate {
            bytes = bytes.clone();
            if (businessStateHash == 0 || accountLanes < 2) {
                throw new IllegalArgumentException("invalid benchmark snapshot template");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    static void configureAccountLanes(int accountLanes) {
        if (accountLanes < 2 || accountLanes > Long.SIZE
                || (accountLanes & (accountLanes - 1)) != 0) {
            throw new IllegalArgumentException("accountLanes must be a power of two in [2,64]");
        }
        System.setProperty("surprising.aeron.account-lanes", Integer.toString(accountLanes));
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

    static Scenario fullTakerFill(int accountLanes) {
        return fill(accountLanes, 10, 10, 3_000);
    }

    static Scenario partialFill(int accountLanes) {
        return fill(accountLanes, 10, 4, 4_000);
    }

    static Scenario multiLaneMatching(int accountLanes, int makerDepth) {
        if (makerDepth < accountLanes || makerDepth > 1_000) {
            throw new IllegalArgumentException("makerDepth must cover every lane and be at most 1000");
        }
        Harness harness = base(accountLanes);
        List<Long> users = usersAcrossLanes(accountLanes, makerDepth + 1, 5_000);
        for (int index = 0; index < makerDepth; index++) {
            long maker = users.get(index);
            harness.adjust(maker, SAFE_BALANCE);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, maker,
                    order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, 1, CoreTimeInForce.GTC)));
        }
        long taker = users.getLast();
        harness.adjust(taker, SAFE_BALANCE);
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
                while (!harness.state.tradingState().riskState().scan().complete()) {
                    response = harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN,
                            CommandSource.OPERATIONS, 0,
                            TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(256))));
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
            harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(256))));
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
        return commandScenario(harness, command);
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
        if (riskUsers < accountLanes || riskUsers > 96) {
            throw new IllegalArgumentException("riskUsers must cover every lane and be at most 96");
        }
        Harness harness = base(accountLanes);
        List<Long> users = usersAcrossLanes(accountLanes, riskUsers + 1, 20_000);
        long safeShort = users.getFirst();
        harness.adjust(safeShort, SAFE_BALANCE);
        for (int index = 1; index <= riskUsers; index++) {
            long vulnerableLong = users.get(index);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, safeShort,
                    order(harness.nextOrderId(), CoreOrderSide.SELL, ENTRY_PRICE, 10,
                            CoreTimeInForce.GTC)));
            harness.adjust(vulnerableLong, LIQUIDATION_BALANCE);
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, vulnerableLong,
                    order(harness.nextOrderId(), CoreOrderSide.BUY, ENTRY_PRICE, 10, CoreTimeInForce.IOC)));
        }
        return harness;
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

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
                SETTLE_ASSET, 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }

    private static List<Long> usersAcrossLanes(int accountLanes, int count, long startUserId) {
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

    private static final class Harness implements AutoCloseable {
        private final CoreProbeState state;
        private final Sequences sequences;

        private Harness(CoreProbeState state, Sequences sequences) {
            this.state = state;
            this.sequences = sequences;
        }

        static Harness restore(SnapshotTemplate template) {
            configureAccountLanes(template.accountLanes());
            CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.LINEAR_PERPETUAL, template.bytes());
            return new Harness(restored, Sequences.after(restored.appliedCommandCount()));
        }

        void adjust(long userId, long units) {
            execute(command(CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, userId,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(SETTLE_ASSET, units))));
        }

        long nextOrderId() {
            return sequences.orderId++;
        }

        CoreMessage command(CoreMessageType type, CommandSource source, long userId, byte[] payload) {
            long sourceSequence = sequences.next(source);
            long correlationId = sequences.clusterPosition++;
            return new CoreMessage(CoreMessageHeader.command(type,
                    new UUID(source.ordinal() + 1L, sourceSequence), ProductLine.LINEAR_PERPETUAL,
                    source, sourceId(source), sourceSequence, userId, BASE_EPOCH_MILLIS + correlationId,
                    correlationId), payload);
        }

        CoreResponse execute(CoreMessage command) {
            CoreResponse response = state.apply(command);
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                long sequence = state.matchingSequence(command.header().commandId());
                CoreMatchingResult matching = null;
                long deadline = System.nanoTime() + MATCH_TIMEOUT_NANOS;
                while (matching == null && System.nanoTime() < deadline) {
                    matching = state.takeMatchingResult(sequence);
                    if (matching == null) Thread.onSpinWait();
                }
                if (matching == null) throw new IllegalStateException("matching timed out for " + command.header().messageType());
                response = state.completeMatching(sequence, matching,
                        command.header().submittedAtEpochMillis(), command.header().correlationId());
                if (response == null) throw new IllegalStateException("matching completion was lost");
            }
            if (response.status() != ResponseStatus.APPLIED && response.status() != ResponseStatus.OK) {
                throw new IllegalStateException("benchmark command rejected type=" + command.header().messageType()
                        + " status=" + response.status() + " result=" + response.resultCode());
            }
            return response;
        }

        CoreLiquidationWorkView executionWork() {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                    new UUID(99, sequences.clusterPosition++), ProductLine.LINEAR_PERPETUAL,
                    CommandSource.OPERATIONS, sourceId(CommandSource.OPERATIONS), 0, 0,
                    BASE_EPOCH_MILLIS + sequences.clusterPosition, sequences.clusterPosition),
                    CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,
                            CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1_000, 1_048_576));
            CoreResponse response = state.apply(query);
            if (response.status() != ResponseStatus.OK) {
                throw new IllegalStateException("liquidation work query failed: " + response.resultCode());
            }
            return CoreLiquidationWorkCodec.decodeWork(response.data());
        }

        SnapshotTemplate snapshotTemplate(int accountLanes) {
            byte[] snapshot = state.snapshot();
            return new SnapshotTemplate(snapshot, state.tradingState().businessStateHash(), accountLanes);
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
    }

    private static final class Sequences {
        private long gateway = 1;
        private long operations = 1;
        private long kafka = 1;
        private long clusterPosition = 1;
        private long orderId = 1_000_000;

        static Sequences after(long appliedCommandCount) {
            Sequences sequences = new Sequences();
            long next = Math.addExact(appliedCommandCount, 1);
            sequences.gateway = next;
            sequences.operations = next;
            sequences.kafka = next;
            sequences.clusterPosition = next;
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
