package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessageType;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class OpenLoopBusinessLatencyRecorder {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final int targetOperationsPerSecond;
    private final long intervalNanos;
    private final long firstEntryNanos = System.nanoTime();
    private final EnumMap<BusinessType, Samples> samples = new EnumMap<>(BusinessType.class);
    private long scheduledBusinessOperations;

    private OpenLoopBusinessLatencyRecorder(int targetOperationsPerSecond) {
        this.targetOperationsPerSecond = targetOperationsPerSecond;
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetOperationsPerSecond;
    }

    static OpenLoopBusinessLatencyRecorder createIfEnabled(int targetOperationsPerSecond) {
        if (targetOperationsPerSecond <= 0) throw new IllegalArgumentException("target rate must be positive");
        return new LinearPerpetualBusinessLatencyEvent().isEnabled()
                ? new OpenLoopBusinessLatencyRecorder(targetOperationsPerSecond) : null;
    }

    Token enter(CoreMessageType messageType, int operationWeight) {
        if (operationWeight <= 0) throw new IllegalArgumentException("operation weight must be positive");
        BusinessType type = BusinessType.classify(messageType);
        if (type == null) return null;
        long scheduled = Math.addExact(firstEntryNanos,
                Math.multiplyExact(scheduledBusinessOperations, intervalNanos));
        scheduledBusinessOperations = Math.addExact(scheduledBusinessOperations, operationWeight);
        while (System.nanoTime() < scheduled) Thread.onSpinWait();
        return new Token(type, operationWeight, scheduled);
    }

    void accepted(Token token) {
        if (token != null) token.acceptedNanos = System.nanoTime();
    }

    void terminal(Token token) {
        if (token == null) return;
        long terminal = System.nanoTime();
        if (token.acceptedNanos < token.entryNanos || terminal < token.acceptedNanos) {
            throw new IllegalStateException("invalid open-loop business latency timestamps");
        }
        samples.computeIfAbsent(token.type, ignored -> new Samples()).add(
                terminal - token.entryNanos, token.acceptedNanos - token.entryNanos,
                terminal - token.acceptedNanos, token.operationWeight);
    }

    void commit() {
        for (var entry : samples.entrySet()) entry.getValue().commit(entry.getKey(), targetOperationsPerSecond);
    }

    static final class Token {
        private final BusinessType type;
        private final int operationWeight;
        private final long entryNanos;
        private long acceptedNanos;

        private Token(BusinessType type, int operationWeight, long entryNanos) {
            this.type = type;
            this.operationWeight = operationWeight;
            this.entryNanos = entryNanos;
        }
    }

    enum BusinessType {
        PLACE_ORDER, TAKER_FILL, CANCEL_ORDER, AMEND_ORDER, ORDER_BATCH, TRIGGER_ORDER,
        RISK_SCAN, LIQUIDATION, FUNDING, ADL, SETTLEMENT, SNAPSHOT_RECOVERY;

        static BusinessType classify(CoreMessageType type) {
            return switch (Objects.requireNonNull(type, "message type")) {
                case PLACE_ORDER -> PLACE_ORDER;
                case CANCEL_ORDER -> CANCEL_ORDER;
                case REPLACE_ORDER, AMEND_ORDER -> AMEND_ORDER;
                case PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH -> ORDER_BATCH;
                case PLACE_TRIGGER_ORDER, CANCEL_TRIGGER_ORDER, CLAIM_TRIGGER_ORDER,
                        COMPLETE_TRIGGER_ORDER, UPDATE_TRIGGER_TRAILING, EXPIRE_TRIGGER_ORDER,
                        RETRY_TRIGGER_ORDER, EXECUTE_TRIGGER_ORDER -> TRIGGER_ORDER;
                case APPLY_MARK_PRICE, CONTINUE_RISK_SCAN, UPDATE_RISK_SCAN_CONTROL -> RISK_SCAN;
                case EXECUTE_LIQUIDATION, RESOLVE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH -> LIQUIDATION;
                case APPLY_FUNDING -> FUNDING;
                case EXECUTE_ADL -> ADL;
                case SETTLE_INSTRUMENT -> SETTLEMENT;
                case PROBE_INCREMENT, VERIFY_STATE_HASH, ADJUST_BALANCE, UPSERT_INSTRUMENT,
                        ACK_EXPORT, UPDATE_POSITION_MODE, ADJUST_POSITION_MARGIN,
                        ADJUST_INSURANCE_FUND, UPDATE_LEVERAGE, UPSERT_ALGO_ORDER,
                        UPDATE_CANCEL_ALL_AFTER, UPSERT_FEE_POLICY, TRANSFER_OUT, TRANSFER_IN,
                        COMPLETE_TRANSFER, STATE_HASH_QUERY, BUSINESS_STATE_HASH_QUERY,
                        USER_STATE_HASH_QUERY, ORDER_STATE_HASH_QUERY, USER_STATE_QUERY,
                        ORDER_STATE_QUERY, EXPORT_BATCH_QUERY, EXPORT_STATUS_QUERY,
                        CLIENT_ORDER_STATE_QUERY, TREASURY_STATE_QUERY, ADL_CANDIDATE_QUERY,
                        RISK_STATE_QUERY, OPEN_INTEREST_QUERY, ALGO_ORDER_QUERY,
                        CANCEL_ALL_AFTER_QUERY, ORDER_PREFLIGHT_QUERY, BOOK_STATE_QUERY,
                        LIQUIDATION_WORK_QUERY, USER_OPEN_ORDERS_QUERY, TRIGGER_ORDER_QUERY,
                        USER_OPEN_TRIGGER_ORDERS_QUERY, FUNDING_PROGRESS_QUERY,
                        SETTLEMENT_PROGRESS_QUERY, COMMAND_RESULT_QUERY, RISK_SCAN_CONTROL_QUERY,
                        ORDER_BOOK_BOOTSTRAP_QUERY, PENDING_TRANSFER_QUERY, LANE_METRICS_QUERY,
                        COMMAND_RESULT, STATE_HASH_RESULT, USER_STATE_RESULT, ORDER_STATE_RESULT,
                        TREASURY_STATE_RESULT, ADL_CANDIDATE_RESULT, RISK_STATE_RESULT,
                        OPEN_INTEREST_RESULT, ALGO_ORDER_RESULT, CANCEL_ALL_AFTER_RESULT,
                        ORDER_PREFLIGHT_RESULT, BOOK_STATE_RESULT, LIQUIDATION_WORK_RESULT,
                        USER_OPEN_ORDERS_RESULT, TRIGGER_ORDER_RESULT,
                        USER_OPEN_TRIGGER_ORDERS_RESULT, FUNDING_PROGRESS_RESULT,
                        SETTLEMENT_PROGRESS_RESULT, COMMAND_RESULT_RESULT,
                        RISK_SCAN_CONTROL_RESULT, ORDER_BOOK_BOOTSTRAP_RESULT,
                        PENDING_TRANSFER_RESULT, LANE_METRICS_RESULT, CORE_EVENT -> null;
            };
        }
    }

    private static final class Samples {
        private long[] entryAccepted = new long[64];
        private long[] acceptedTerminal = new long[64];
        private long[] entryTerminal = new long[64];
        private int size;
        private long businessOperations;

        void add(long total, long first, long second, int operationWeight) {
            ensureCapacity();
            entryAccepted[size] = first;
            acceptedTerminal[size] = second;
            entryTerminal[size] = total;
            size++;
            businessOperations = Math.addExact(businessOperations, operationWeight);
        }

        private void ensureCapacity() {
            if (size < entryTerminal.length) return;
            int capacity = Math.multiplyExact(size, 2);
            entryAccepted = Arrays.copyOf(entryAccepted, capacity);
            acceptedTerminal = Arrays.copyOf(acceptedTerminal, capacity);
            entryTerminal = Arrays.copyOf(entryTerminal, capacity);
        }

        void commit(BusinessType type, int targetRate) {
            LinearPerpetualBusinessLatencyEvent event = new LinearPerpetualBusinessLatencyEvent();
            event.businessType = type.name();
            event.loadModel = "OPEN_LOOP_CONSTANT_ARRIVAL";
            event.coordinatedOmissionCorrected = true;
            event.targetOperationsPerSecond = targetRate;
            event.operationsPerInvocation = Math.toIntExact(businessOperations);
            event.scheduledBusinessOperations = businessOperations;
            event.terminalBusinessOperations = businessOperations;
            event.terminalCoreMessages = size;
            event.latencySamples = size;
            event.histogramLowestNanos = 1;
            event.histogramHighestNanos = TIMEOUT_NANOS;
            event.timeoutNanos = TIMEOUT_NANOS;
            event.latencyUnit = "NANOSECONDS";
            event.classificationSource = "EXHAUSTIVE_CORE_MESSAGE_TYPE_SWITCH";
            event.entryAcceptedHistogramCounts = histogram(entryAccepted, size);
            event.acceptedTerminalHistogramCounts = histogram(acceptedTerminal, size);
            event.entryTerminalHistogramCounts = histogram(entryTerminal, size);
            fill(event, entryAccepted, acceptedTerminal, entryTerminal, size);
            event.commit();
        }

        private static String histogram(long[] values, int size) {
            long[] buckets = new long[64];
            for (int index = 0; index < size; index++) {
                long value = Math.max(1, values[index]);
                int bucket = 64 - Long.numberOfLeadingZeros(value - 1);
                buckets[Math.min(bucket, buckets.length - 1)]++;
            }
            StringBuilder encoded = new StringBuilder(128);
            for (int index = 0; index < buckets.length; index++) {
                if (index != 0) encoded.append(',');
                encoded.append(buckets[index]);
            }
            return encoded.toString();
        }

        private static void fill(LinearPerpetualBusinessLatencyEvent event, long[] first,
                                 long[] second, long[] total, int size) {
            event.entryAcceptedP50Nanos = percentile(first, size, .50);
            event.entryAcceptedP90Nanos = percentile(first, size, .90);
            event.entryAcceptedP95Nanos = percentile(first, size, .95);
            event.entryAcceptedP99Nanos = percentile(first, size, .99);
            event.entryAcceptedP999Nanos = percentile(first, size, .999);
            event.entryAcceptedMaxNanos = percentile(first, size, 1);
            event.acceptedTerminalP50Nanos = percentile(second, size, .50);
            event.acceptedTerminalP90Nanos = percentile(second, size, .90);
            event.acceptedTerminalP95Nanos = percentile(second, size, .95);
            event.acceptedTerminalP99Nanos = percentile(second, size, .99);
            event.acceptedTerminalP999Nanos = percentile(second, size, .999);
            event.acceptedTerminalMaxNanos = percentile(second, size, 1);
            event.entryTerminalP50Nanos = percentile(total, size, .50);
            event.entryTerminalP90Nanos = percentile(total, size, .90);
            event.entryTerminalP95Nanos = percentile(total, size, .95);
            event.entryTerminalP99Nanos = percentile(total, size, .99);
            event.entryTerminalP999Nanos = percentile(total, size, .999);
            event.entryTerminalMaxNanos = percentile(total, size, 1);
        }

        private static long percentile(long[] values, int size, double fraction) {
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return sorted[Math.max(0, Math.min((int) Math.ceil(size * fraction) - 1, size - 1))];
        }
    }
}
