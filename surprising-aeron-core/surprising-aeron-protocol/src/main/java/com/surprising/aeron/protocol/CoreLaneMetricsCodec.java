package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CoreLaneMetricsCodec {

    private static final int VERSION = 1;
    private static final int FIXED_INT_COUNT = 12;
    private static final int LANE_LONG_COUNT = 5 + CoreLaneMetricsView.OPERATION_TYPE_COUNT * 4;
    private static final int LANE_INT_COUNT = 3;

    private CoreLaneMetricsCodec() {
    }

    public static byte[] encode(CoreLaneMetricsView view) {
        int laneCount = view.accountLaneCount();
        int capacity = Math.addExact(Math.addExact(Math.multiplyExact(FIXED_INT_COUNT, Integer.BYTES), Long.BYTES),
                Math.multiplyExact(laneCount,
                        Math.addExact(Math.multiplyExact(LANE_LONG_COUNT, Long.BYTES),
                                Math.multiplyExact(LANE_INT_COUNT, Integer.BYTES))));
        ByteBuffer buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION)
                .putInt(view.matchingEngineCount())
                .putInt(laneCount)
                .putInt(view.matcherDispatchDepth())
                .putInt(view.matcherDispatchCapacity())
                .putInt(view.matcherDispatchHighWaterMark())
                .putInt(view.matchingCompletionDepth())
                .putInt(view.matchingCompletionCapacity())
                .putInt(view.matchingCompletionHighWaterMark())
                .putInt(view.commandContextDepth())
                .putInt(view.commandContextCapacity())
                .putInt(view.commandContextHighWaterMark())
                .putLong(view.committedCoreSequence());
        put(buffer, view.accountLaneRevisions());
        put(buffer, view.accountLaneAppliedSequences());
        put(buffer, view.accountLaneCommittedSequences());
        put(buffer, view.accountLaneQueueDepths());
        put(buffer, view.accountLaneQueueCapacities());
        put(buffer, view.accountLaneQueueHighWaterMarks());
        put(buffer, view.accountLaneRejectedSubmissions());
        put(buffer, view.accountLaneOldestPendingSequences());
        put(buffer, view.accountLaneCompletedOperations());
        put(buffer, view.accountLaneLatencySamples());
        put(buffer, view.accountLaneTotalLatencyNanos());
        put(buffer, view.accountLaneMaxLatencyNanos());
        return buffer.array();
    }

    /**
     * Query-scoped encoder. The Core owner hands it to one Lane at a time to write its counters
     * behind the Lane task's publication/completion fences. finish() transfers the byte array and
     * seals this writer; no runtime array or mutable Lane state escapes into the response.
     */
    public static final class Encoder {
        private ByteBuffer buffer;
        private final int lanes;
        private long stateWritten;
        private long operationsWritten;

        public Encoder(int matchingEngines, int lanes,
                       int dispatchDepth, int dispatchCapacity, int dispatchHighWater,
                       int completionDepth, int completionCapacity, int completionHighWater,
                       int contextDepth, int contextCapacity, int contextHighWater, long committedSequence) {
            if (matchingEngines <= 0 || lanes <= 0 || lanes > Long.SIZE || committedSequence < 0) {
                throw new IllegalArgumentException("invalid Core lane metrics header");
            }
            checkQueue(dispatchDepth, dispatchCapacity, dispatchHighWater);
            checkQueue(completionDepth, completionCapacity, completionHighWater);
            checkQueue(contextDepth, contextCapacity, contextHighWater);
            this.lanes = lanes;
            buffer = ByteBuffer.allocate(FIXED_INT_COUNT * Integer.BYTES + Long.BYTES
                    + lanes * (LANE_LONG_COUNT * Long.BYTES + LANE_INT_COUNT * Integer.BYTES))
                    .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(VERSION).putInt(matchingEngines).putInt(lanes)
                    .putInt(dispatchDepth).putInt(dispatchCapacity).putInt(dispatchHighWater)
                    .putInt(completionDepth).putInt(completionCapacity).putInt(completionHighWater)
                    .putInt(contextDepth).putInt(contextCapacity).putInt(contextHighWater)
                    .putLong(committedSequence);
        }

        public void writeLane(int lane, long revision, long applied, long committed,
                              int depth, int capacity, int highWater, long rejected, long oldestPending) {
            checkLane(lane);
            checkQueue(depth, capacity, highWater);
            if (revision < 0 || applied < committed || committed < 0 || rejected < 0 || oldestPending < 0) {
                throw new IllegalArgumentException("invalid Account Lane metrics");
            }
            int offset = FIXED_INT_COUNT * Integer.BYTES + Long.BYTES;
            buffer.putLong(offset + lane * Long.BYTES, revision);
            buffer.putLong(offset + (lanes + lane) * Long.BYTES, applied);
            buffer.putLong(offset + (2 * lanes + lane) * Long.BYTES, committed);
            offset += 3 * lanes * Long.BYTES;
            buffer.putInt(offset + lane * Integer.BYTES, depth);
            buffer.putInt(offset + (lanes + lane) * Integer.BYTES, capacity);
            buffer.putInt(offset + (2 * lanes + lane) * Integer.BYTES, highWater);
            offset += 3 * lanes * Integer.BYTES;
            buffer.putLong(offset + lane * Long.BYTES, rejected);
            buffer.putLong(offset + (lanes + lane) * Long.BYTES, oldestPending);
            stateWritten |= 1L << lane;
        }

        public void writeOperations(int lane, long[] completed, long[] samples, long[] total, long[] max) {
            checkLane(lane);
            int count = CoreLaneMetricsView.OPERATION_TYPE_COUNT;
            if (completed == null || samples == null || total == null || max == null
                    || completed.length != count || samples.length != count
                    || total.length != count || max.length != count) {
                throw new IllegalArgumentException("invalid Lane operation counters");
            }
            for (int operation = 0; operation < count; operation++) {
                buffer.putLong(operationOffset(lane, operation, 0), completed[operation]);
                buffer.putLong(operationOffset(lane, operation, 1), samples[operation]);
                buffer.putLong(operationOffset(lane, operation, 2), total[operation]);
                buffer.putLong(operationOffset(lane, operation, 3), max[operation]);
            }
            operationsWritten |= 1L << lane;
        }

        public void addOperation(int lane, int operation, long completed, long samples, long total, long max) {
            checkLane(lane);
            if (operation < 0 || operation >= CoreLaneMetricsView.OPERATION_TYPE_COUNT
                    || (operationsWritten & (1L << lane)) == 0) {
                throw new IllegalStateException("Lane operation counters must be written first");
            }
            int c = operationOffset(lane, operation, 0);
            int s = operationOffset(lane, operation, 1);
            int t = operationOffset(lane, operation, 2);
            int m = operationOffset(lane, operation, 3);
            // Check every sum before changing the payload.
            long nextCompleted = Math.addExact(buffer.getLong(c), completed);
            long nextSamples = Math.addExact(buffer.getLong(s), samples);
            long nextTotal = Math.addExact(buffer.getLong(t), total);
            buffer.putLong(c, nextCompleted).putLong(s, nextSamples).putLong(t, nextTotal);
            buffer.putLong(m, Math.max(buffer.getLong(m), max));
        }

        public byte[] finish() {
            if (buffer == null) throw new IllegalStateException("Lane metrics encoder is sealed");
            long expected = lanes == Long.SIZE ? -1L : (1L << lanes) - 1;
            if (stateWritten != expected || operationsWritten != expected) {
                throw new IllegalStateException("Lane metrics are incomplete");
            }
            byte[] payload = buffer.array();
            buffer = null;
            return payload;
        }

        private int operationOffset(int lane, int operation, int field) {
            return FIXED_INT_COUNT * Integer.BYTES + Long.BYTES
                    + lanes * (5 * Long.BYTES + 3 * Integer.BYTES)
                    + ((field * lanes + lane) * CoreLaneMetricsView.OPERATION_TYPE_COUNT + operation) * Long.BYTES;
        }

        private void checkLane(int lane) {
            if (buffer == null) throw new IllegalStateException("Lane metrics encoder is sealed");
            if (lane < 0 || lane >= lanes) throw new IllegalArgumentException("invalid laneId");
        }

        private static void checkQueue(int depth, int capacity, int highWater) {
            if (depth < 0 || capacity <= 0 || depth > capacity || highWater < depth || highWater > capacity) {
                throw new IllegalArgumentException("invalid Lane queue metrics");
            }
        }
    }

    public static CoreLaneMetricsView decode(byte[] payload) {
        if (payload == null) throw new ProtocolException("Core Lane metrics payload is required");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buffer, FIXED_INT_COUNT * Integer.BYTES + Long.BYTES);
        int version = buffer.getInt();
        if (version != VERSION) throw new ProtocolException("unsupported Core Lane metrics version: " + version);
        int matchingEngineCount = buffer.getInt();
        int laneCount = buffer.getInt();
        if (laneCount <= 0 || laneCount > Long.SIZE) throw new ProtocolException("invalid Account Lane count");
        int matcherDispatchDepth = buffer.getInt();
        int matcherDispatchCapacity = buffer.getInt();
        int matcherDispatchHighWaterMark = buffer.getInt();
        int matchingCompletionDepth = buffer.getInt();
        int matchingCompletionCapacity = buffer.getInt();
        int matchingCompletionHighWaterMark = buffer.getInt();
        int commandContextDepth = buffer.getInt();
        int commandContextCapacity = buffer.getInt();
        int commandContextHighWaterMark = buffer.getInt();
        long committedCoreSequence = buffer.getLong();
        int operationValues = Math.multiplyExact(laneCount, CoreLaneMetricsView.OPERATION_TYPE_COUNT);
        long[] revisions = readLongs(buffer, laneCount);
        long[] applied = readLongs(buffer, laneCount);
        long[] committed = readLongs(buffer, laneCount);
        int[] depths = readInts(buffer, laneCount);
        int[] capacities = readInts(buffer, laneCount);
        int[] highWaterMarks = readInts(buffer, laneCount);
        long[] rejected = readLongs(buffer, laneCount);
        long[] oldestPending = readLongs(buffer, laneCount);
        long[] completed = readLongs(buffer, operationValues);
        long[] latencySamples = readLongs(buffer, operationValues);
        long[] totalLatency = readLongs(buffer, operationValues);
        long[] maxLatency = readLongs(buffer, operationValues);
        if (buffer.hasRemaining()) throw new ProtocolException("trailing Core Lane metrics payload bytes");
        try {
            return new CoreLaneMetricsView(matchingEngineCount, laneCount,
                    matcherDispatchDepth, matcherDispatchCapacity, matcherDispatchHighWaterMark,
                    matchingCompletionDepth, matchingCompletionCapacity, matchingCompletionHighWaterMark,
                    commandContextDepth, commandContextCapacity, commandContextHighWaterMark,
                    committedCoreSequence, revisions, applied, committed, depths, capacities, highWaterMarks,
                    rejected, oldestPending, completed, latencySamples, totalLatency, maxLatency);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("invalid Core Lane metrics payload", exception);
        }
    }

    private static void put(ByteBuffer buffer, long[] values) {
        for (long value : values) buffer.putLong(value);
    }

    private static void put(ByteBuffer buffer, int[] values) {
        for (int value : values) buffer.putInt(value);
    }

    private static long[] readLongs(ByteBuffer buffer, int count) {
        requireRemaining(buffer, Math.multiplyExact(count, Long.BYTES));
        long[] values = new long[count];
        for (int index = 0; index < count; index++) values[index] = buffer.getLong();
        return values;
    }

    private static int[] readInts(ByteBuffer buffer, int count) {
        requireRemaining(buffer, Math.multiplyExact(count, Integer.BYTES));
        int[] values = new int[count];
        for (int index = 0; index < count; index++) values[index] = buffer.getInt();
        return values;
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (length < 0 || buffer.remaining() < length) {
            throw new ProtocolException("truncated Core Lane metrics payload");
        }
    }
}
