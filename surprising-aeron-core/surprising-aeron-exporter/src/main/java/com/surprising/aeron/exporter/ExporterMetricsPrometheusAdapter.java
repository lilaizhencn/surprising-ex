package com.surprising.aeron.exporter;

import java.util.Objects;

public final class ExporterMetricsPrometheusAdapter {

    private final ExporterMetrics metrics;

    public ExporterMetricsPrometheusAdapter(ExporterMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public String scrape() {
        ExporterMetrics.Snapshot snapshot = metrics.snapshot();
        String labels = "{product_line=\"" + snapshot.productLine().name() + "\"}";
        StringBuilder output = new StringBuilder(1_024);
        gauge(output, "backlog_count", labels, snapshot.backlogCount());
        gauge(output, "backlog_bytes", labels, snapshot.backlogBytes());
        gauge(output, "oldest_event_age_millis", labels, snapshot.oldestEventAgeMillis());
        gauge(output, "last_acknowledged_sequence", labels, snapshot.lastAcknowledgedSequence());
        gauge(output, "export_to_kafka_lag", labels, snapshot.exportToKafkaLag());
        gauge(output, "kafka_to_pg_watermark_lag", labels, snapshot.kafkaToPgWatermarkLag());
        counter(output, "published_events", labels, snapshot.publishedEvents());
        counter(output, "duplicate_events", labels, snapshot.duplicateEvents());
        counter(output, "retries", labels, snapshot.retryCount());
        counter(output, "failures", labels, snapshot.failureCount());
        counter(output, "unknown_results", labels, snapshot.unknownCount());
        counter(output, "reconnects", labels, snapshot.reconnectCount());
        gauge(output, "reconnecting", labels, snapshot.reconnecting() ? 1 : 0);
        counter(output, "queries", labels, snapshot.queryCount());
        gauge(output, "query_rate_per_second", labels, snapshot.queryRatePerSecond());
        return output.toString();
    }

    private static void counter(StringBuilder output, String name, String labels, Number value) {
        metric(output, name + "_total", "counter", labels, value);
    }

    private static void gauge(StringBuilder output, String name, String labels, Number value) {
        metric(output, name, "gauge", labels, value);
    }

    private static void metric(StringBuilder output, String name, String type, String labels, Number value) {
        String metric = "surprising_exporter_" + name;
        output.append("# TYPE ").append(metric).append(' ').append(type).append('\n')
                .append(metric).append(labels).append(' ').append(value).append('\n');
    }
}
