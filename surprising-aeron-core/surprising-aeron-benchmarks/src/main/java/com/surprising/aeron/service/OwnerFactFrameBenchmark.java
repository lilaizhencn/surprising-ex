package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.RollingBusinessStateHash;
import com.surprising.aeron.service.state.RollingFundsStateHash;
import com.surprising.aeron.service.state.RuntimeFactIndexes;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionState;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimeStateProjector;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.UserRuntime;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {
        "-XX:+UseZGC",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
@Threads(1)
public class OwnerFactFrameBenchmark {

    private static final ProductLine PRODUCT_LINE = ProductLine.LINEAR_PERPETUAL;
    private static final String ASSET = "USDT";
    private static final int OPERATIONS_PER_INVOCATION = 16_384;
    private static final LaneTopology FOUR_LANES = new LaneTopology(
            LaneTopology.ROUTE_VERSION, 1, 0, 0, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,
            LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE, LaneTopology.DEFAULT_QUEUE_CAPACITY,
            LaneTopology.DEFAULT_QUEUE_CAPACITY);

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
    public long ownerCommitSealPublishApply(SealPublishApplyState state, OwnerCommitCounters counters,
                                            OwnerCommitLatencies latencies) {
        latencies.begin(state.operationsPerInvocation, state.targetOperationsPerSecond);
        long checksum = 0;
        for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
            int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
            RuntimeCommitJournal.AdmissionReservation reservation = state.journal.reserveAdmission(
                    end - start, Math.multiplyExact(end - start, 1L << 20));
            for (int index = start; index < end; index++) {
                long entry = latencies.awaitEntry(index);
                latencies.accepted(index, entry, System.nanoTime());
                PreparedDraft prepared = state.drafts.drafts.get(index);
                Draft draft = prepared.draft;
                RuntimeFactFrame patch = draft.builder.seal(draft.changes,
                        prepared.afterBusinessStateHash, prepared.afterFundsStateHash);
                state.journal.publish(reservation, patch, patch.businessStateHash(), patch.fundsStateHash());
                checksum ^= patch.coreSequence() ^ patch.businessStateHash() ^ patch.fundsStateHash();
            }
            long terminal = System.nanoTime();
            latencies.terminalRange(start, end, terminal);
        }
        recordJournalCounters(state.journal, state.operationsPerInvocation, counters);
        latencies.copyTo(counters);
        commitMeasurement("OWNER_COMMIT", latencies, state.operationsPerInvocation);
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
    public long multiLanePatchFanout(MultiLaneFanoutState state, OwnerCommitCounters counters,
                                     OwnerCommitLatencies latencies) {
        latencies.begin(state.operationsPerInvocation, state.targetOperationsPerSecond);
        long checksum = 0;
        for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
            int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
            for (int index = start; index < end; index++) {
                long entry = latencies.awaitEntry(index);
                latencies.accepted(index, entry, System.nanoTime());
                RuntimeFactFrame patch = state.batch.patches.get(index);
                state.indexes.apply(patch);
                long terminal = System.nanoTime();
                latencies.terminal(index, terminal);
                checksum ^= patch.laneMask();
            }
        }
        recordPatchCounters(state.batch, state.operationsPerInvocation, state.maxInFlight, counters);
        latencies.copyTo(counters);
        commitMeasurement("MULTI_LANE_FANOUT", latencies, state.operationsPerInvocation);
        return checksum + state.openInterest.openInterestSteps(state.touchedSymbol)
                + state.activeOrders.ids().size();
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
    public long incrementalHashAgainstCanonical(HashComparisonState state, OwnerCommitCounters counters,
                                                OwnerCommitLatencies latencies) {
        latencies.begin(state.operationsPerInvocation, state.targetOperationsPerSecond);
        long checksum = 0;
        if (state.mode == HashMode.INCREMENTAL) {
            for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
                int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
                for (int index = start; index < end; index++) {
                    long entry = latencies.awaitEntry(index);
                    latencies.accepted(index, entry, System.nanoTime());
                    PreparedDraft prepared = state.drafts.drafts.get(index);
                    long business = state.incrementalBusinessHash.applyFailStop(prepared.draft.changes);
                    long funds = state.incrementalFundsHash.applyFailStop(prepared.draft.changes);
                    if (business != prepared.afterBusinessStateHash
                            || funds != prepared.afterFundsStateHash) {
                        throw new IllegalStateException("incremental hash apply diverged from sealed patch");
                    }
                    checksum ^= state.incrementalBusinessHash.value() ^ state.incrementalFundsHash.value();
                    long terminal = System.nanoTime();
                    latencies.terminal(index, terminal);
                }
            }
        } else {
            for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
                int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
                for (int index = start; index < end; index++) {
                    long entry = latencies.awaitEntry(index);
                    latencies.accepted(index, entry, System.nanoTime());
                    RuntimeFactFrame patch = state.batch.patches.get(index);
                    state.projection.apply(patch);
                    TradingCoreState projected = state.projection.freeze(patch.projectionSequence());
                    long business = RollingBusinessStateHash.compute(projected);
                    long funds = RollingFundsStateHash.compute(projected);
                    if (business != patch.businessStateHash() || funds != patch.fundsStateHash()) {
                        throw new IllegalStateException("canonical hash recomputation diverged from typed patch");
                    }
                    checksum ^= business ^ funds;
                    latencies.terminal(index, System.nanoTime());
                }
            }
            if (state.projection.businessStateHash() != state.batch.finalBusinessStateHash
                    || state.projection.fundsStateHash() != state.batch.finalFundsStateHash) {
                throw new IllegalStateException("incremental and canonical hashes diverged");
            }
        }
        recordPatchCounters(state.batch, state.operationsPerInvocation, state.maxInFlight, counters);
        latencies.copyTo(counters);
        commitMeasurement("HASH_" + state.mode, latencies, state.operationsPerInvocation);
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
    public long batchedPatchProjectionCoreFact(BatchProjectionState state, OwnerCommitCounters counters,
                                               OwnerCommitLatencies latencies) {
        latencies.begin(state.operationsPerInvocation, state.targetOperationsPerSecond);
        long encodedBytes = 0;
        long identityDictionaryVersion = 0;
        for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
            int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
            for (int index = start; index < end; index++) {
                long entry = latencies.awaitEntry(index);
                latencies.accepted(index, entry, System.nanoTime());
            }
            state.projection.apply(state.batch.patches.subList(start, end));
            for (int index = start; index < end; index++) {
                RuntimeFactFrame patch = state.batch.patches.get(index);
                long patchIdentityVersion = patch.identities().dictionaryVersion();
                if (patchIdentityVersion < identityDictionaryVersion) {
                    throw new IllegalStateException("Core Fact identity dictionary version regressed");
                }
                identityDictionaryVersion = patchIdentityVersion;
                state.exportState.append(exportDraft(patch, state.batch.commands.get(index)));
            }
        }
        List<CoreMessage> encoded = state.exportState.pending();
        if (encoded.size() != state.operationsPerInvocation) {
            throw new IllegalStateException("off-owner Core Fact materializer did not publish a complete prefix");
        }
        latencies.terminalRange(0, state.operationsPerInvocation, System.nanoTime());
        for (CoreMessage message : encoded) encodedBytes = Math.addExact(encodedBytes, message.payloadLength());
        recordPatchCounters(state.batch, state.operationsPerInvocation, state.maxInFlight, counters);
        counters.coreFactEncodedBytes += encodedBytes;
        latencies.copyTo(counters);
        commitMeasurement("PROJECTION_CORE_FACT", latencies, state.operationsPerInvocation);
        return encodedBytes ^ state.projection.sequence() ^ identityDictionaryVersion;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
    public long ownerCommitSnapshotRecovery(SnapshotRecoveryState state, OwnerCommitCounters counters,
                                            OwnerCommitLatencies latencies) {
        latencies.begin(state.operationsPerInvocation, state.targetOperationsPerSecond);
        for (int start = 0; start < state.operationsPerInvocation; start += state.maxInFlight) {
            int end = Math.min(state.operationsPerInvocation, start + state.maxInFlight);
            for (int index = start; index < end; index++) {
                long entry = latencies.awaitEntry(index);
                latencies.accepted(index, entry, System.nanoTime());
            }
            state.projection.apply(state.batch.patches.subList(start, end));
        }
        TradingCoreState frozen = state.projection.freeze(state.batch.patches.size());
        byte[] encoded = TradingStateSnapshotCodec.encode(frozen);
        TradingCoreState decoded = TradingStateSnapshotCodec.decode(encoded, PRODUCT_LINE);
        long business = decoded.businessStateHash();
        long funds = RollingFundsStateHash.compute(decoded);
        if (business != state.batch.finalBusinessStateHash || funds != state.batch.finalFundsStateHash) {
            throw new IllegalStateException("snapshot recovery hashes diverged");
        }
        recordPatchCounters(state.batch, state.operationsPerInvocation, state.maxInFlight, counters);
        counters.snapshotBytes += encoded.length;
        RuntimeIdentityRegistry recoveredIdentities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState recovered = RuntimeStateProjector.project(decoded, recoveredIdentities, FOUR_LANES)) {
            TradingCoreState rematerialized = RuntimeStateMaterializer.materialize(recovered, recoveredIdentities);
            long recoveredBusiness = RollingBusinessStateHash.compute(rematerialized);
            long recoveredFunds = RollingFundsStateHash.compute(rematerialized);
            if (!rematerialized.equals(decoded) || recoveredBusiness != business || recoveredFunds != funds) {
                throw new IllegalStateException("snapshot runtime recovery changed state or hashes");
            }
            latencies.terminalRange(0, state.operationsPerInvocation, System.nanoTime());
            latencies.copyTo(counters);
            long checksum = recoveredBusiness ^ recoveredFunds ^ recovered.topology().topologyHash() ^ encoded.length;
            commitMeasurement("SNAPSHOT_RECOVERY", latencies, state.operationsPerInvocation);
            return checksum;
        }
    }

    @State(Scope.Thread)
    public static class SealPublishApplyState extends ScaleContractState {
        private DraftBatch drafts;
        private RuntimeCommitJournal journal;

        @Setup(Level.Invocation)
        public void setUp() {
            requireScale();
            TradingCoreState initial = denseInitial();
            drafts = draftBatch(initial, operationsPerInvocation);
            journal = new RuntimeCommitJournal(PRODUCT_LINE, initial,
                    drafts.initialBusinessStateHash, drafts.initialFundsStateHash);
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            journal.close();
        }
    }

    @State(Scope.Thread)
    public static class MultiLaneFanoutState extends ScaleContractState {
        private Batch batch;
        private RuntimeFactIndexes indexes;
        private OpenInterestIndex openInterest;
        private ActiveOrderIndex activeOrders;
        private String touchedSymbol;

        @Setup(Level.Invocation)
        public void setUp() {
            requireScale();
            batch = batch(operationsPerInvocation);
            TradingCoreState initial = batch.initial;
            RuntimeIdentityRegistry identities = identities(initial);
            touchedSymbol = symbol(initial, 0);
            openInterest = new OpenInterestIndex(initial, identities);
            activeOrders = new ActiveOrderIndex(initial, identities);
            indexes = new RuntimeFactIndexes(
                    new PositionUserIndex(initial, identities), openInterest, new TriggerOrderIndex(initial),
                    new AlgoOrderIndex(initial), new LiquidationIndex(initial), new CancelAllAfterIndex(initial),
                    activeOrders, new AdlPositionIndex(initial, identities), new RiskSnapshotIndex(initial));
        }
    }

    @State(Scope.Thread)
    public static class HashComparisonState extends ScaleContractState {
        @Param({"INCREMENTAL", "CANONICAL"})
        public HashMode mode;

        private Batch batch;
        private DraftBatch drafts;
        private RuntimeProjectionState projection;
        private RollingBusinessStateHash incrementalBusinessHash;
        private RollingFundsStateHash incrementalFundsHash;

        @Setup(Level.Invocation)
        public void setUp() {
            requireScale();
            TradingCoreState initial = denseInitial();
            if (mode == HashMode.INCREMENTAL) {
                drafts = draftBatch(initial, operationsPerInvocation);
                RuntimeIdentityRegistry identities = drafts.drafts.get(0).draft.identities;
                incrementalBusinessHash = RollingBusinessStateHash.create(initial, identities);
                incrementalFundsHash = RollingFundsStateHash.create(initial, identities);
                batch = sealedBatch(drafts);
            } else {
                batch = batch(initial, operationsPerInvocation);
                projection = new RuntimeProjectionState(batch.initial,
                        batch.initialBusinessStateHash, batch.initialFundsStateHash);
            }
        }
    }

    @State(Scope.Thread)
    public static class BatchProjectionState extends ScaleContractState {
        private Batch batch;
        private RuntimeProjectionState projection;
        private CoreExportState exportState;

        @Setup(Level.Invocation)
        public void setUp() {
            requireScale();
            batch = batch(operationsPerInvocation);
            projection = new RuntimeProjectionState(batch.initial,
                    batch.initialBusinessStateHash, batch.initialFundsStateHash);
            exportState = new CoreExportState();
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            exportState.close();
        }
    }

    @State(Scope.Thread)
    public static class SnapshotRecoveryState extends ScaleContractState {
        private Batch batch;
        private RuntimeProjectionState projection;

        @Setup(Level.Invocation)
        public void setUp() {
            requireScale();
            batch = batch(operationsPerInvocation);
            projection = new RuntimeProjectionState(batch.initial,
                    batch.initialBusinessStateHash, batch.initialFundsStateHash);
        }
    }

    @State(Scope.Thread)
    public abstract static class ScaleContractState {
        @Param("10000") public int activeUsers;
        @Param("512") public int listedSymbols;
        @Param("4") public int accountLanes;
        @Param("5") public int positionsPerUser;
        @Param("10") public int ordersPerUser;
        @Param("256") public int maxInFlight;
        @Param("16384") public int operationsPerInvocation;
        @Param("100000") public int targetOperationsPerSecond;

        final void requireScale() {
            LinearPerpetualBenchmarkSupport.OwnerCommitScale expected =
                    LinearPerpetualBenchmarkSupport.OWNER_COMMIT_SCALE;
            if (activeUsers != expected.activeUsers() || listedSymbols != expected.listedSymbols()
                    || accountLanes != expected.accountLanes()
                    || positionsPerUser != expected.positionsPerUser()
                    || ordersPerUser != expected.ordersPerUser() || maxInFlight != expected.maxInFlight()
                    || operationsPerInvocation != expected.operationsPerInvocation()
                    || targetOperationsPerSecond <= 0) {
                throw new IllegalArgumentException("owner commit benchmark scale contract changed");
            }
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class OwnerCommitCounters {
        public long acceptedBusinessOperations;
        public long terminalBusinessOperations;
        public long acceptedCoreMessages;
        public long terminalCoreMessages;
        public long fills;
        public long trades;
        public long batches;
        public long batchItems;
        public long acceptedTerminalBusinessGap;
        public long acceptedTerminalCoreGap;
        public long unfinishedBusinessOperations;
        public long unfinishedCoreMessages;
        public long maximumBacklog;
        public long endBacklog;
        public long rejectedOperations;
        public long errorOperations;
        public long timeoutOperations;
        public long patchItems;
        public long patchBytes;
        public long snapshotBytes;
        public long coreFactEncodedBytes;
        public long totalBatchSize;
        public long maximumBatchSize;
        public long entryAcceptedP50Nanos;
        public long entryAcceptedP90Nanos;
        public long entryAcceptedP95Nanos;
        public long entryAcceptedP99Nanos;
        public long entryAcceptedP999Nanos;
        public long entryAcceptedMaxNanos;
        public long acceptedTerminalP50Nanos;
        public long acceptedTerminalP90Nanos;
        public long acceptedTerminalP95Nanos;
        public long acceptedTerminalP99Nanos;
        public long acceptedTerminalP999Nanos;
        public long acceptedTerminalMaxNanos;
        public long entryTerminalP50Nanos;
        public long entryTerminalP90Nanos;
        public long entryTerminalP95Nanos;
        public long entryTerminalP99Nanos;
        public long entryTerminalP999Nanos;
        public long entryTerminalMaxNanos;
    }

    @State(Scope.Thread)
    public static class OwnerCommitLatencies {
        private long[] entries = new long[OPERATIONS_PER_INVOCATION];
        private long[] accepted = new long[OPERATIONS_PER_INVOCATION];
        private long[] terminal = new long[OPERATIONS_PER_INVOCATION];
        private long scheduledStart;
        private long intervalNanos;

        void begin(int operations, int targetOperationsPerSecond) {
            if (operations != OPERATIONS_PER_INVOCATION || targetOperationsPerSecond <= 0) {
                throw new IllegalArgumentException("invalid owner commit open-loop contract");
            }
            scheduledStart = System.nanoTime();
            intervalNanos = Math.max(1, 1_000_000_000L / targetOperationsPerSecond);
            Arrays.fill(accepted, 0);
            Arrays.fill(terminal, 0);
        }

        long awaitEntry(int index) {
            long scheduled = Math.addExact(scheduledStart, Math.multiplyExact(index, intervalNanos));
            while (System.nanoTime() < scheduled) Thread.onSpinWait();
            entries[index] = scheduled;
            return scheduled;
        }

        long entry(int index) { return entries[index]; }

        void accepted(int index, long entryNanos, long acceptedNanos) {
            entries[index] = entryNanos;
            accepted[index] = acceptedNanos;
        }

        void terminal(int index, long terminalNanos) { terminal[index] = terminalNanos; }

        void terminalRange(int start, int end, long terminalNanos) {
            Arrays.fill(terminal, start, end, terminalNanos);
        }

        void copyTo(OwnerCommitCounters counters) {
            long[] entryAccepted = durations(entries, accepted);
            long[] acceptedTerminal = durations(accepted, terminal);
            long[] entryTerminal = durations(entries, terminal);
            counters.entryAcceptedP50Nanos += percentile(entryAccepted, 0.50);
            counters.entryAcceptedP90Nanos += percentile(entryAccepted, 0.90);
            counters.entryAcceptedP95Nanos += percentile(entryAccepted, 0.95);
            counters.entryAcceptedP99Nanos += percentile(entryAccepted, 0.99);
            counters.entryAcceptedP999Nanos += percentile(entryAccepted, 0.999);
            counters.entryAcceptedMaxNanos += percentile(entryAccepted, 1.0);
            counters.acceptedTerminalP50Nanos += percentile(acceptedTerminal, 0.50);
            counters.acceptedTerminalP90Nanos += percentile(acceptedTerminal, 0.90);
            counters.acceptedTerminalP95Nanos += percentile(acceptedTerminal, 0.95);
            counters.acceptedTerminalP99Nanos += percentile(acceptedTerminal, 0.99);
            counters.acceptedTerminalP999Nanos += percentile(acceptedTerminal, 0.999);
            counters.acceptedTerminalMaxNanos += percentile(acceptedTerminal, 1.0);
            counters.entryTerminalP50Nanos += percentile(entryTerminal, 0.50);
            counters.entryTerminalP90Nanos += percentile(entryTerminal, 0.90);
            counters.entryTerminalP95Nanos += percentile(entryTerminal, 0.95);
            counters.entryTerminalP99Nanos += percentile(entryTerminal, 0.99);
            counters.entryTerminalP999Nanos += percentile(entryTerminal, 0.999);
            counters.entryTerminalMaxNanos += percentile(entryTerminal, 1.0);
        }

        private static long[] durations(long[] before, long[] after) {
            long[] values = new long[OPERATIONS_PER_INVOCATION];
            for (int index = 0; index < values.length; index++) {
                values[index] = Math.max(0, after[index] - before[index]);
            }
            Arrays.sort(values);
            return values;
        }

        private static long percentile(long[] sorted, double fraction) {
            int index = Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1);
            return sorted[index];
        }

        long percentile(Stage stage, double fraction) {
            return percentile(switch (stage) {
                case ENTRY_ACCEPTED -> durations(entries, accepted);
                case ACCEPTED_TERMINAL -> durations(accepted, terminal);
                case ENTRY_TERMINAL -> durations(entries, terminal);
            }, fraction);
        }

        String histogram(Stage stage) {
            long[] values = switch (stage) {
                case ENTRY_ACCEPTED -> durations(entries, accepted);
                case ACCEPTED_TERMINAL -> durations(accepted, terminal);
                case ENTRY_TERMINAL -> durations(entries, terminal);
            };
            long[] buckets = new long[64];
            for (long value : values) {
                int bucket = 64 - Long.numberOfLeadingZeros(Math.max(1, value) - 1);
                buckets[Math.min(bucket, buckets.length - 1)]++;
            }
            StringBuilder encoded = new StringBuilder(128);
            for (int index = 0; index < buckets.length; index++) {
                if (index != 0) encoded.append(',');
                encoded.append(buckets[index]);
            }
            return encoded.toString();
        }
    }

    public enum HashMode { INCREMENTAL, CANONICAL }
    private enum Stage { ENTRY_ACCEPTED, ACCEPTED_TERMINAL, ENTRY_TERMINAL }

    private static void commitMeasurement(String businessType, OwnerCommitLatencies latencies,
                                          int operationsPerInvocation) {
        OwnerCommitMeasurementEvent event = new OwnerCommitMeasurementEvent();
        if (!event.isEnabled()) return;
        event.businessType = businessType;
        event.loadModel = "OPEN_LOOP_CONSTANT_ARRIVAL";
        event.coordinatedOmissionCorrected = true;
        event.operationsPerInvocation = operationsPerInvocation;
        event.scheduledBusinessOperations = operationsPerInvocation;
        event.terminalBusinessOperations = operationsPerInvocation;
        event.terminalCoreMessages = operationsPerInvocation;
        event.classificationSource = "OWNER_PATCH_STAGE";
        event.entryAcceptedHistogramCounts = latencies.histogram(Stage.ENTRY_ACCEPTED);
        event.acceptedTerminalHistogramCounts = latencies.histogram(Stage.ACCEPTED_TERMINAL);
        event.entryTerminalHistogramCounts = latencies.histogram(Stage.ENTRY_TERMINAL);
        event.entryAcceptedP50Nanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 0.50);
        event.entryAcceptedP90Nanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 0.90);
        event.entryAcceptedP95Nanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 0.95);
        event.entryAcceptedP99Nanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 0.99);
        event.entryAcceptedP999Nanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 0.999);
        event.entryAcceptedMaxNanos = latencies.percentile(Stage.ENTRY_ACCEPTED, 1.0);
        event.acceptedTerminalP50Nanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 0.50);
        event.acceptedTerminalP90Nanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 0.90);
        event.acceptedTerminalP95Nanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 0.95);
        event.acceptedTerminalP99Nanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 0.99);
        event.acceptedTerminalP999Nanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 0.999);
        event.acceptedTerminalMaxNanos = latencies.percentile(Stage.ACCEPTED_TERMINAL, 1.0);
        event.entryTerminalP50Nanos = latencies.percentile(Stage.ENTRY_TERMINAL, 0.50);
        event.entryTerminalP90Nanos = latencies.percentile(Stage.ENTRY_TERMINAL, 0.90);
        event.entryTerminalP95Nanos = latencies.percentile(Stage.ENTRY_TERMINAL, 0.95);
        event.entryTerminalP99Nanos = latencies.percentile(Stage.ENTRY_TERMINAL, 0.99);
        event.entryTerminalP999Nanos = latencies.percentile(Stage.ENTRY_TERMINAL, 0.999);
        event.entryTerminalMaxNanos = latencies.percentile(Stage.ENTRY_TERMINAL, 1.0);
        event.commit();
    }

    @Name("com.surprising.OwnerCommitMeasurement")
    @Label("Typed owner commit measurement")
    @Category({"Surprising", "Benchmark"})
    static final class OwnerCommitMeasurementEvent extends Event {
        String businessType;
        String loadModel;
        boolean coordinatedOmissionCorrected;
        int operationsPerInvocation;
        long scheduledBusinessOperations;
        long terminalBusinessOperations;
        long terminalCoreMessages;
        String classificationSource;
        String entryAcceptedHistogramCounts;
        String acceptedTerminalHistogramCounts;
        String entryTerminalHistogramCounts;
        long entryAcceptedP50Nanos;
        long entryAcceptedP90Nanos;
        long entryAcceptedP95Nanos;
        long entryAcceptedP99Nanos;
        long entryAcceptedP999Nanos;
        long entryAcceptedMaxNanos;
        long acceptedTerminalP50Nanos;
        long acceptedTerminalP90Nanos;
        long acceptedTerminalP95Nanos;
        long acceptedTerminalP99Nanos;
        long acceptedTerminalP999Nanos;
        long acceptedTerminalMaxNanos;
        long entryTerminalP50Nanos;
        long entryTerminalP90Nanos;
        long entryTerminalP95Nanos;
        long entryTerminalP99Nanos;
        long entryTerminalP999Nanos;
        long entryTerminalMaxNanos;
    }

    static QualificationResult exerciseSmallScale(int operations, int maxInFlight) {
        if (operations < 2 || maxInFlight < 1 || maxInFlight > operations) {
            throw new IllegalArgumentException("invalid small owner-commit qualification scale");
        }
        TradingCoreState initial = initialState(16, 8, 2, 2);
        DraftBatch drafts = draftBatch(initial, operations);
        ArrayList<RuntimeFactFrame> sealed = new ArrayList<>(operations);
        long maximumBacklog = 0;
        boolean fingerprintsExact = true;
        boolean nonZeroFingerprints = true;
        String firstExpectedFingerprint = null;
        String firstActualFingerprint = null;
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(PRODUCT_LINE, initial,
                drafts.initialBusinessStateHash, drafts.initialFundsStateHash)) {
            for (int start = 0; start < operations; start += maxInFlight) {
                int end = Math.min(operations, start + maxInFlight);
                RuntimeCommitJournal.AdmissionReservation reservation = journal.reserveAdmission(
                        end - start, Math.multiplyExact(end - start, 1L << 20));
                for (int index = start; index < end; index++) {
                    PreparedDraft prepared = drafts.drafts.get(index);
                    RuntimeFactFrame patch = prepared.draft.builder.seal(prepared.draft.changes,
                            prepared.afterBusinessStateHash, prepared.afterFundsStateHash);
                    journal.publish(reservation, patch, patch.businessStateHash(), patch.fundsStateHash());
                    sealed.add(patch);
                    CommandFingerprint expected = CommandFingerprint.of(prepared.draft.command);
                    CommandFingerprint actual = patch.coreFactMetadata().commandFingerprint();
                    if (firstExpectedFingerprint == null) {
                        firstExpectedFingerprint = expected.toString();
                        firstActualFingerprint = actual.toString();
                    }
                    fingerprintsExact &= expected.equals(actual);
                    nonZeroFingerprints &= hasNonZeroByte(actual.bytes());
                }
                maximumBacklog = Math.max(maximumBacklog, journal.metrics().maxBacklog());
            }
            if (journal.metrics().currentBacklog() != 0) {
                throw new IllegalStateException("small qualification journal did not drain");
            }
        }

        RuntimeIdentityRegistry identities = identities(initial);
        RuntimeFactIndexes indexes = indexes(initial, identities);
        int fanoutOperations = 0;
        for (RuntimeFactFrame patch : sealed) {
            indexes.apply(patch);
            fanoutOperations++;
        }

        RuntimeProjectionState projection = new RuntimeProjectionState(initial,
                drafts.initialBusinessStateHash, drafts.initialFundsStateHash);
        long encodedBytes = 0;
        int projectedOperations = 0;
        int encodedEvents = 0;
        boolean encodedFingerprintsExact = true;
        boolean encodedFactsExact = true;
        try (CoreExportState exportState = new CoreExportState()) {
            for (int index = 0; index < sealed.size(); index++) {
                RuntimeFactFrame patch = sealed.get(index);
                projection.apply(patch);
                projectedOperations++;
                exportState.append(exportDraft(patch, drafts.drafts.get(index).draft.command));
            }
            List<CoreMessage> encodedMessages = exportState.pending();
            if (encodedMessages.size() != operations) {
                throw new IllegalStateException("small qualification Core Fact prefix is incomplete");
            }
            for (int index = 0; index < encodedMessages.size(); index++) {
                CoreMessage encodedMessage = encodedMessages.get(index);
                CoreExportEvent event = CoreExportCodec.decodeEvent(encodedMessage.payloadUnsafe());
                encodedBytes = Math.addExact(encodedBytes, encodedMessage.payloadLength());
                encodedFingerprintsExact &= event.commandFingerprint().equals(
                        CommandFingerprint.of(drafts.drafts.get(index).draft.command));
                RuntimeFactFrame patch = sealed.get(index);
                encodedFactsExact &= event.businessStateHash() == patch.businessStateHash()
                        && event.beforeBusinessStateHash() == patch.beforeBusinessStateHash()
                        && event.fundsStateHash() == patch.fundsStateHash()
                        && event.beforeFundsStateHash() == patch.beforeFundsStateHash()
                        && patch.previousCoreSequence() == patch.previousProjectionSequence()
                        && patch.coreSequence() == patch.projectionSequence()
                        && event.projectionSequence() == patch.projectionSequence()
                        && event.commandId().equals(patch.coreFactMetadata().commandId())
                        && !event.changedUsers().isEmpty() && !event.changedOrders().isEmpty();
                encodedEvents++;
            }
        }
        TradingCoreState finalState = projection.freeze(operations);
        long canonicalFundsHash = RollingFundsStateHash.compute(finalState);
        if (finalState.businessStateHash() != drafts.finalBusinessStateHash
                || canonicalFundsHash != drafts.finalFundsStateHash) {
            throw new IllegalStateException("small qualification hash parity failed");
        }
        byte[] snapshot = TradingStateSnapshotCodec.encode(finalState);
        TradingCoreState decoded = TradingStateSnapshotCodec.decode(snapshot, PRODUCT_LINE);
        try (TradingRuntimeState recovered = RuntimeStateProjector.project(
                decoded, new RuntimeIdentityRegistry(), FOUR_LANES)) {
            return new QualificationResult(operations, maxInFlight, sealed.size(), encodedBytes,
                    fanoutOperations, projectedOperations, encodedEvents, maximumBacklog,
                    LinearPerpetualMixedWorkload.totalFunds(initial),
                    LinearPerpetualMixedWorkload.totalFunds(decoded), positionCount(initial),
                    positionCount(decoded), initial.orders().size(), decoded.orders().size(),
                    finalState.businessStateHash(), decoded.businessStateHash(), canonicalFundsHash,
                    RollingFundsStateHash.compute(decoded), recovered.topology().topologyHash(), snapshot.length,
                    recovered.topology().topologyHash() == FOUR_LANES.topologyHash(),
                    fingerprintsExact, encodedFingerprintsExact, encodedFactsExact, nonZeroFingerprints,
                    firstExpectedFingerprint, firstActualFingerprint);
        }
    }

    static HashQualificationResult exerciseHashComparisonSmallScale(int operations) {
        if (operations < 2) throw new IllegalArgumentException("hash qualification requires at least two operations");
        TradingCoreState initial = initialState(16, 8, 2, 2);
        DraftBatch drafts = draftBatch(initial, operations);
        Batch batch = sealedBatch(drafts);
        RuntimeIdentityRegistry identities = drafts.drafts.get(0).draft.identities;
        RollingBusinessStateHash business = RollingBusinessStateHash.create(initial, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(initial, identities);
        RuntimeProjectionState projection = new RuntimeProjectionState(
                initial, drafts.initialBusinessStateHash, drafts.initialFundsStateHash);
        int businessApplies = 0;
        int fundsApplies = 0;
        int canonicalComputes = 0;
        boolean everyOperationEquivalent = true;
        for (int index = 0; index < operations; index++) {
            PreparedDraft prepared = drafts.drafts.get(index);
            business.applyFailStop(prepared.draft.changes);
            businessApplies++;
            funds.applyFailStop(prepared.draft.changes);
            fundsApplies++;
            RuntimeFactFrame patch = batch.patches.get(index);
            projection.apply(patch);
            TradingCoreState projected = projection.freeze(patch.projectionSequence());
            long canonicalBusiness = RollingBusinessStateHash.compute(projected);
            long canonicalFunds = RollingFundsStateHash.compute(projected);
            canonicalComputes += 2;
            everyOperationEquivalent &= business.value() == canonicalBusiness
                    && funds.value() == canonicalFunds
                    && business.value() == patch.businessStateHash()
                    && funds.value() == patch.fundsStateHash();
        }
        return new HashQualificationResult(operations, businessApplies, fundsApplies,
                canonicalComputes, projection.sequence(), business.value(), funds.value(),
                batch.finalBusinessStateHash, batch.finalFundsStateHash, everyOperationEquivalent);
    }

    static record QualificationResult(int requestedOperations, int requestedMaxInFlight,
                                      int terminalOperations, long encodedBytes, int fanoutOperations,
                                      int projectedOperations, int encodedEvents, long maximumBacklog,
                                      long initialFunds, long recoveredFunds, int initialPositions,
                                      int recoveredPositions, int initialOrders, int recoveredOrders,
                                      long businessHash, long recoveredBusinessHash, long fundsHash,
                                      long recoveredFundsHash, long topologyHash, int snapshotBytes,
                                      boolean recoveryTopologyExact,
                                      boolean fingerprintsExact, boolean encodedFingerprintsExact,
                                      boolean encodedFactsExact,
                                      boolean nonZeroFingerprints,
                                      String firstExpectedFingerprint, String firstActualFingerprint) { }

    static record HashQualificationResult(int operations, int businessApplies, int fundsApplies,
                                          int canonicalComputes,
                                          long projectedSequence, long incrementalBusinessHash,
                                          long incrementalFundsHash, long canonicalBusinessHash,
                                          long canonicalFundsHash, boolean everyOperationEquivalent) { }

    private static void recordJournalCounters(RuntimeCommitJournal journal, int operations,
                                              OwnerCommitCounters counters) {
        RuntimeCommitJournal.Metrics metrics = journal.metrics();
        counters.acceptedBusinessOperations += operations;
        counters.terminalBusinessOperations += operations;
        counters.acceptedCoreMessages += operations;
        counters.terminalCoreMessages += operations;
        counters.batches += metrics.batchCount();
        counters.batchItems += metrics.batchItems();
        counters.maximumBacklog += metrics.maxBacklog();
        counters.endBacklog += metrics.currentBacklog();
        counters.rejectedOperations += metrics.rejectionCount();
        counters.errorOperations += metrics.errorCount();
        counters.timeoutOperations += metrics.timeoutCount();
        counters.patchItems += metrics.batchItems();
        counters.patchBytes += metrics.batchBytes();
        counters.totalBatchSize += metrics.batchItems();
        counters.maximumBatchSize += Math.min(operations, LinearPerpetualBenchmarkSupport.OWNER_COMMIT_SCALE.maxInFlight());
    }

    private static void recordPatchCounters(Batch batch, int operations, int maxInFlight,
                                            OwnerCommitCounters counters) {
        counters.acceptedBusinessOperations += operations;
        counters.terminalBusinessOperations += operations;
        counters.acceptedCoreMessages += operations;
        counters.terminalCoreMessages += operations;
        counters.batches += (operations + maxInFlight - 1L) / maxInFlight;
        counters.batchItems += operations;
        counters.totalBatchSize += operations;
        counters.maximumBatchSize += Math.min(operations, maxInFlight);
        for (RuntimeFactFrame patch : batch.patches) {
            counters.patchItems += patch.coreFactItemCount();
            counters.patchBytes += estimatedPatchBytes(patch);
        }
    }

    private static long estimatedPatchBytes(RuntimeFactFrame patch) {
        return 384L + 128L * patch.accountLaneGroups().size() + 96L * patch.fundsPostings().size()
                + 80L * patch.matcherEvidence().size() + 32L * patch.coreFactItemCount();
    }

    private static RuntimeFactIndexes indexes(TradingCoreState initial, RuntimeIdentityRegistry identities) {
        return new RuntimeFactIndexes(
                new PositionUserIndex(initial, identities), new OpenInterestIndex(initial, identities),
                new TriggerOrderIndex(initial), new AlgoOrderIndex(initial), new LiquidationIndex(initial),
                new CancelAllAfterIndex(initial), new ActiveOrderIndex(initial, identities),
                new AdlPositionIndex(initial, identities), new RiskSnapshotIndex(initial));
    }

    private static boolean hasNonZeroByte(byte[] value) {
        for (byte item : value) if (item != 0) return true;
        return false;
    }

    private static int positionCount(TradingCoreState state) {
        return state.users().values().stream().mapToInt(user -> user.positions().size()).sum();
    }

    private static DraftBatch draftBatch(TradingCoreState initial, int size) {
        RuntimeIdentityRegistry identities = identities(initial);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(initial, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(initial, identities);
        long orderIdBase = maximumOrderId(initial);
        ArrayList<PreparedDraft> drafts = new ArrayList<>(size);
        long initialBusiness = business.value();
        long initialFunds = funds.value();
        long userSeed = 10_000_000;
        for (long sequence = 1; sequence <= size; sequence++) {
            Draft draft = draftWithHashes(
                    initial, identities, business, funds, sequence, userSeed, orderIdBase);
            long businessAfter = business.applyFailStop(draft.changes);
            long fundsAfter = funds.applyFailStop(draft.changes);
            drafts.add(new PreparedDraft(draft, businessAfter, fundsAfter));
            userSeed += 64;
        }
        return new DraftBatch(List.copyOf(drafts), initialBusiness, initialFunds,
                business.value(), funds.value());
    }

    private static RuntimeFactFrame seal(Draft draft) {
        long business = draft.businessHash.applyFailStop(draft.changes);
        long funds = draft.fundsHash.applyFailStop(draft.changes);
        return draft.builder.seal(draft.changes, business, funds);
    }

    private static Batch batch(int size) {
        return batch(denseInitial(), size);
    }

    private static Batch batch(TradingCoreState initial, int size) {
        RuntimeIdentityRegistry identities = identities(initial);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(initial, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(initial, identities);
        long orderIdBase = maximumOrderId(initial);
        RuntimeProjectionState buildProjection = new RuntimeProjectionState(initial, business.value(), funds.value());
        ArrayList<RuntimeFactFrame> patches = new ArrayList<>(size);
        ArrayList<CoreMessage> commands = new ArrayList<>(size);
        long userSeed = 10_000_000;
        for (long sequence = 1; sequence <= size; sequence++) {
            Draft draft = draftWithHashes(
                    initial, identities, business, funds, sequence, userSeed, orderIdBase);
            RuntimeFactFrame patch = seal(draft);
            patches.add(patch);
            commands.add(draft.command);
            buildProjection.apply(patch);
            userSeed += 64;
        }
        TradingCoreState finalState = buildProjection.freeze(size);
        long finalBusinessStateHash = finalState.businessStateHash();
        long finalFundsStateHash = RollingFundsStateHash.compute(finalState);
        if (finalBusinessStateHash != business.value() || finalFundsStateHash != funds.value()) {
            throw new IllegalStateException("typed patch batch hashes diverged from canonical state");
        }
        return new Batch(initial, List.copyOf(patches), List.copyOf(commands), finalState,
                RollingBusinessStateHash.create(initial, identities).value(),
                RollingFundsStateHash.create(initial, identities).value(),
                finalBusinessStateHash, finalFundsStateHash);
    }

    private static Batch sealedBatch(DraftBatch drafts) {
        TradingCoreState initial = drafts.drafts.get(0).draft.initial;
        RuntimeProjectionState projection = new RuntimeProjectionState(
                initial, drafts.initialBusinessStateHash, drafts.initialFundsStateHash);
        ArrayList<RuntimeFactFrame> patches = new ArrayList<>(drafts.drafts.size());
        ArrayList<CoreMessage> commands = new ArrayList<>(drafts.drafts.size());
        for (PreparedDraft prepared : drafts.drafts) {
            RuntimeFactFrame patch = prepared.draft.builder.seal(prepared.draft.changes,
                    prepared.afterBusinessStateHash, prepared.afterFundsStateHash);
            patches.add(patch);
            commands.add(prepared.draft.command);
            projection.apply(patch);
        }
        TradingCoreState finalState = projection.freeze(patches.size());
        if (finalState.businessStateHash() != drafts.finalBusinessStateHash
                || RollingFundsStateHash.compute(finalState) != drafts.finalFundsStateHash) {
            throw new IllegalStateException("sealed hash qualification batch diverged from canonical state");
        }
        return new Batch(initial, List.copyOf(patches), List.copyOf(commands), finalState,
                drafts.initialBusinessStateHash, drafts.initialFundsStateHash,
                drafts.finalBusinessStateHash, drafts.finalFundsStateHash);
    }

    private static Draft draftWithHashes(TradingCoreState initial, RuntimeIdentityRegistry identities,
                                         RollingBusinessStateHash business, RollingFundsStateHash funds,
                                         long sequence, long userSeed, long orderIdBase) {
        RuntimeFactFrame.Builder builder = RuntimeFactFrame.builder(
                PRODUCT_LINE, sequence - 1, sequence)
                .matcherTransition(CoreMatcherTransition.unchanged(0, 0));
        int assetId = identities.assetId(ASSET);
        long laneMask = 0;
        long candidate = userSeed;
        long commandUserId = 0;
        String commandSymbol = null;
        long commandOrderId = 0;
        for (int laneId = 0; laneId < FOUR_LANES.accountLaneCount(); laneId++) {
            long userId = nextUserForLane(candidate, laneId);
            candidate = userId + 1;
            String symbol = symbol(initial, Math.toIntExact((sequence - 1) * 4 + laneId));
            int symbolId = identities.symbolId(symbol);
            long positionKey = identities.positionKey(userId, symbol);
            long revision = Math.addExact(initial.revision(), sequence);
            UserRuntime user = new UserRuntime(PRODUCT_LINE, userId, revision,
                    com.surprising.aeron.protocol.CorePositionMode.ONE_WAY);
            PositionRuntime position = new PositionRuntime(userId, symbolId, assetId, CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 1, 100, 100, 0, 25);
            long orderId = Math.addExact(orderIdBase,
                    Math.addExact(Math.multiplyExact(sequence - 1, FOUR_LANES.accountLaneCount()), laneId + 1L));
            OrderRuntime order = new OrderRuntime(orderId, userId, symbolId,
                    1, CoreOrderSide.BUY, 100, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0, 1, 0, 1, false);
            if (laneId == 0) {
                commandUserId = userId;
                commandSymbol = symbol;
                commandOrderId = order.orderId();
            }
            CoreOrderState businessOrder = com.surprising.aeron.service.state.RuntimeStateMaterializer
                    .orderSnapshot(order, identities);
            builder.recordUser(laneId, null, user)
                    .recordBalance(laneId, userId, assetId, null,
                            new RuntimeFactFrame.UserBalance(0, position.positionMarginUnits(), 0))
                    .recordPosition(laneId, positionKey, null, position)
                    .recordOrder(laneId, null, order, null, businessOrder);
            laneMask |= 1L << laneId;
        }
        builder.laneMask(laneMask);
        long clearingBefore = Math.negateExact(Math.multiplyExact(sequence - 1, 100L));
        long clearingAfter = Math.negateExact(Math.multiplyExact(sequence, 100L));
        builder.recordTreasuryAsset(assetId,
                treasuryAsset(initial, clearingBefore), treasuryAsset(initial, clearingAfter));
        CoreMessage command = benchmarkCommand(sequence, commandUserId, commandSymbol, commandOrderId);
        RuntimeFactFrame.CoreFactMetadata factMetadata = new RuntimeFactFrame.CoreFactMetadata(
                command.header().commandId(), CommandFingerprint.of(command),
                command.header().messageType().wireCode(), command.header().userId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE,
                sequence, sequence, FOUR_LANES.topologyHash(), sequence, false);
        RuntimeFactFrame.PreparedChanges changes = builder.prepare(new RuntimeFactFrame.PrepareMetadata(
                Math.addExact(initial.revision(), sequence - 1),
                Math.addExact(initial.revision(), sequence), business.value(), funds.value(), laneMask,
                factMetadata, false), identities);
        return new Draft(initial, identities, builder, changes, business, funds, command);
    }

    private static long maximumOrderId(TradingCoreState state) {
        long maximum = 0;
        for (long orderId : state.orders().keySet()) maximum = Math.max(maximum, orderId);
        return maximum;
    }

    static int exerciseDenseBatchSetup(int operations) {
        return batch(operations).patches().size();
    }

    private static RuntimeFactFrame.TreasuryAssetValue treasuryAsset(
            TradingCoreState initial, long clearingAdjustment) {
        var treasury = initial.treasuryState();
        return new RuntimeFactFrame.TreasuryAssetValue(
                treasury.feeBalances().getOrDefault(ASSET, 0L),
                treasury.insuranceBalances().getOrDefault(ASSET, 0L),
                treasury.insuranceDeficits().getOrDefault(ASSET, 0L),
                treasury.liquidationFeeBalances().getOrDefault(ASSET, 0L),
                treasury.fundingResidualBalances().getOrDefault(ASSET, 0L),
                treasury.roundingResidualBalances().getOrDefault(ASSET, 0L),
                Math.addExact(treasury.clearingPnlBalances().getOrDefault(ASSET, 0L), clearingAdjustment));
    }

    private static CoreMessage benchmarkCommand(long sequence, long userId, String symbol, long orderId) {
        PlaceOrderCommand order = new PlaceOrderCommand(orderId, symbol, 1, CoreOrderSide.BUY,
                100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "owner-commit-" + orderId);
        UUID commandId = new UUID(0x4f574e4552434f4dL, sequence);
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                PRODUCT_LINE, CommandSource.GATEWAY, 7, sequence, userId,
                LinearPerpetualBenchmarkSupport.benchmarkTimestamp(sequence), sequence);
        return new CoreMessage(header, TradingCommandCodec.encodePlaceOrder(order));
    }

    private static long nextUserForLane(long start, int laneId) {
        long userId = Math.max(1, start);
        while (FOUR_LANES.accountLaneId(userId) != laneId) userId++;
        return userId;
    }

    private static TradingCoreState denseInitial() {
        return DenseInitialHolder.STATE;
    }

    private static TradingCoreState initialState(int users, int symbols, int positions, int orders) {
        LinearPerpetualScaleConfig config = LinearPerpetualScaleConfig.scale(
                symbols, symbols, positions, orders, LinearPerpetualTrafficProfile.UNIFORM, symbols);
        LinearPerpetualMixedWorkload.Template template = LinearPerpetualMixedWorkload.template(4, users, config);
        try (LinearPerpetualBenchmarkSupport.Harness harness =
                     LinearPerpetualBenchmarkSupport.Harness.restore(template.snapshot())) {
            return harness.state().tradingState();
        }
    }

    private static RuntimeIdentityRegistry identities(TradingCoreState initial) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState ignored = RuntimeStateProjector.project(initial, identities, FOUR_LANES)) {
            return identities;
        }
    }

    private static String symbol(TradingCoreState initial, int index) {
        if (initial.instruments().isEmpty()) return "OWNER-" + Math.floorMod(index, 512);
        int target = Math.floorMod(index, initial.instruments().size());
        return new ArrayList<>(initial.instruments().keySet()).get(target);
    }

    private static CoreExportState.Draft exportDraft(RuntimeFactFrame patch, CoreMessage command) {
        RuntimeFactFrame.CoreFactMetadata metadata = patch.coreFactMetadata();
        long[] terminalOrderIds = patch.terminalIds().orderIds().stream().mapToLong(Long::longValue).toArray();
        return new CoreExportState.Draft(command, metadata.status(), metadata.resultCode(),
                metadata.appliedCommandCount(), patch.businessStateHash(), patch.beforeBusinessStateHash(),
                patch.beforeFundsStateHash(), patch.fundsStateHash(), metadata.topologyHash(),
                metadata.laneRevisionHash(), patch.matcherTransition(), metadata.clusterPosition(),
                patch.projectionSequence(), patch.coreFactItemCount(), terminalOrderIds,
                new CoreExportState.FactChain(patch, null, factPermit(patch)),
                CoreCommandDelta.empty(), patch.fundsDelta(), metadata);
    }

    private static CoreAdmissionReservation.FactPermit factPermit(RuntimeFactFrame patch) {
        var budget = new CoreAdmissionReservation.FactBudget(
                1, Math.max(1, patch.coreFactItemCount()), Math.max(1, patch.estimatedCoreFactBytes()));
        var permit = budget.reserveFrame();
        permit.consume(patch);
        return permit;
    }

    private record Draft(TradingCoreState initial, RuntimeIdentityRegistry identities,
                         RuntimeFactFrame.Builder builder, RuntimeFactFrame.PreparedChanges changes,
                         RollingBusinessStateHash businessHash, RollingFundsStateHash fundsHash,
                         CoreMessage command) {}

    private record PreparedDraft(Draft draft, long afterBusinessStateHash, long afterFundsStateHash) {}

    private record DraftBatch(List<PreparedDraft> drafts, long initialBusinessStateHash,
                              long initialFundsStateHash, long finalBusinessStateHash,
                              long finalFundsStateHash) {}

    private record Batch(TradingCoreState initial, List<RuntimeFactFrame> patches, List<CoreMessage> commands,
                         TradingCoreState finalState, long initialBusinessStateHash,
                         long initialFundsStateHash, long finalBusinessStateHash,
                         long finalFundsStateHash) {}

    private static final class DenseInitialHolder {
        private static final TradingCoreState STATE = create();

        private static TradingCoreState create() {
            LinearPerpetualScaleConfig config = LinearPerpetualScaleConfig.scale(
                    512, 512, 5, 10, LinearPerpetualTrafficProfile.UNIFORM, 512);
            LinearPerpetualMixedWorkload.Template template = LinearPerpetualMixedWorkload.template(4, 10_000, config);
            try (LinearPerpetualBenchmarkSupport.Harness harness =
                         LinearPerpetualBenchmarkSupport.Harness.restore(template.snapshot())) {
                return harness.state().tradingState();
            }
        }
    }
}
