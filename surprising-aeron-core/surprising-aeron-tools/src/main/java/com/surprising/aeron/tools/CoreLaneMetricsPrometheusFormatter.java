package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreLaneMetricsView;
import com.surprising.product.api.ProductLine;
import java.util.Objects;

public final class CoreLaneMetricsPrometheusFormatter {

    private static final String[] OPERATION_TYPES = {"command", "settlement", "query", "risk"};

    private CoreLaneMetricsPrometheusFormatter() {
    }

    public static String format(ProductLine productLine, CoreLaneMetricsView metrics) {
        Objects.requireNonNull(productLine, "productLine");
        Objects.requireNonNull(metrics, "metrics");
        String productLabel = "product_line=\"" + productLine.name() + "\"";
        StringBuilder output = new StringBuilder(8_192);
        declareTypes(output);
        gauge(output, "matching_engine_count", productLabel, metrics.matchingEngineCount());
        gauge(output, "account_lane_count", productLabel, metrics.accountLaneCount());
        queue(output, "matcher_dispatch", productLabel, metrics.matcherDispatchDepth(),
                metrics.matcherDispatchCapacity(), metrics.matcherDispatchHighWaterMark());
        queue(output, "matching_completion", productLabel, metrics.matchingCompletionDepth(),
                metrics.matchingCompletionCapacity(), metrics.matchingCompletionHighWaterMark());
        queue(output, "command_context", productLabel, metrics.commandContextDepth(),
                metrics.commandContextCapacity(), metrics.commandContextHighWaterMark());
        gauge(output, "committed_core_sequence", productLabel, metrics.committedCoreSequence());
        long[] revisions = metrics.accountLaneRevisions();
        long[] applied = metrics.accountLaneAppliedSequences();
        long[] committed = metrics.accountLaneCommittedSequences();
        int[] depths = metrics.accountLaneQueueDepths();
        int[] capacities = metrics.accountLaneQueueCapacities();
        int[] highWaterMarks = metrics.accountLaneQueueHighWaterMarks();
        long[] rejected = metrics.accountLaneRejectedSubmissions();
        long[] oldestPending = metrics.accountLaneOldestPendingSequences();
        long[] completed = metrics.accountLaneCompletedOperations();
        long[] latencySamples = metrics.accountLaneLatencySamples();
        long[] totalLatency = metrics.accountLaneTotalLatencyNanos();
        long[] maxLatency = metrics.accountLaneMaxLatencyNanos();
        for (int laneId = 0; laneId < metrics.accountLaneCount(); laneId++) {
            String laneLabels = productLabel + ",lane_type=\"account\",lane_id=\"" + laneId + "\"";
            gauge(output, "account_lane_revision", laneLabels, revisions[laneId]);
            gauge(output, "account_lane_applied_sequence", laneLabels, applied[laneId]);
            gauge(output, "account_lane_committed_sequence", laneLabels, committed[laneId]);
            gauge(output, "account_lane_commit_gap", laneLabels,
                    Math.subtractExact(applied[laneId], committed[laneId]));
            queue(output, "account_lane", laneLabels, depths[laneId], capacities[laneId], highWaterMarks[laneId]);
            counter(output, "account_lane_rejected_submissions", laneLabels, rejected[laneId]);
            gauge(output, "account_lane_oldest_pending_sequence", laneLabels, oldestPending[laneId]);
            for (int operation = 0; operation < OPERATION_TYPES.length; operation++) {
                int offset = laneId * CoreLaneMetricsView.OPERATION_TYPE_COUNT + operation;
                String operationLabels = laneLabels + ",operation=\"" + OPERATION_TYPES[operation] + "\"";
                counter(output, "account_lane_operations", operationLabels, completed[offset]);
                counter(output, "account_lane_latency_samples", operationLabels, latencySamples[offset]);
                counter(output, "account_lane_latency_seconds", operationLabels,
                        totalLatency[offset] / 1_000_000_000.0d);
                gauge(output, "account_lane_latency_seconds_max", operationLabels,
                        maxLatency[offset] / 1_000_000_000.0d);
            }
        }
        return output.toString();
    }

    private static void queue(StringBuilder output, String name, String labels,
                              int depth, int capacity, int highWaterMark) {
        gauge(output, name + "_queue_depth", labels, depth);
        gauge(output, name + "_queue_capacity", labels, capacity);
        gauge(output, name + "_queue_high_water_mark", labels, highWaterMark);
    }

    private static void counter(StringBuilder output, String name, String labels, Number value) {
        metric(output, name + "_total", labels, value);
    }

    private static void gauge(StringBuilder output, String name, String labels, Number value) {
        metric(output, name, labels, value);
    }

    private static void metric(StringBuilder output, String name, String labels, Number value) {
        output.append("surprising_core_").append(name)
                .append('{').append(labels).append("} ").append(value).append('\n');
    }

    private static void declareTypes(StringBuilder output) {
        type(output, "matching_engine_count", "gauge");
        type(output, "account_lane_count", "gauge");
        queueTypes(output, "matcher_dispatch");
        queueTypes(output, "matching_completion");
        queueTypes(output, "command_context");
        type(output, "committed_core_sequence", "gauge");
        type(output, "account_lane_revision", "gauge");
        type(output, "account_lane_applied_sequence", "gauge");
        type(output, "account_lane_committed_sequence", "gauge");
        type(output, "account_lane_commit_gap", "gauge");
        queueTypes(output, "account_lane");
        type(output, "account_lane_rejected_submissions_total", "counter");
        type(output, "account_lane_oldest_pending_sequence", "gauge");
        type(output, "account_lane_operations_total", "counter");
        type(output, "account_lane_latency_samples_total", "counter");
        type(output, "account_lane_latency_seconds_total", "counter");
        type(output, "account_lane_latency_seconds_max", "gauge");
    }

    private static void queueTypes(StringBuilder output, String name) {
        type(output, name + "_queue_depth", "gauge");
        type(output, name + "_queue_capacity", "gauge");
        type(output, name + "_queue_high_water_mark", "gauge");
    }

    private static void type(StringBuilder output, String name, String type) {
        output.append("# TYPE surprising_core_").append(name).append(' ').append(type).append('\n');
    }
}
