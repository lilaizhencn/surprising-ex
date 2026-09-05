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
