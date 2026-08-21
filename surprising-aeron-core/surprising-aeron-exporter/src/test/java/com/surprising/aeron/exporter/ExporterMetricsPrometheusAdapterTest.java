package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExporterMetricsPrometheusAdapterTest {

    @Test
    void exposesEveryInternalExporterSnapshotFieldAsPrometheusMetrics() {
        ExporterMetrics metrics = new ExporterMetrics(ProductLine.LINEAR_PERPETUAL, () -> 2_000L);
        metrics.observeBatch(new CoreExportStatus(7, 11, 3, 144, 100, 10_000), List.of());
        metrics.recordPublished(2, 9);
        metrics.recordProjectionWatermark(8);
        metrics.recordDuplicate(1);
        metrics.recordRetry();
        metrics.recordFailure();
        metrics.recordUnknown();
        metrics.recordReconnect(true);
        metrics.recordQuery();

        String scrape = new ExporterMetricsPrometheusAdapter(metrics).scrape();

        assertThat(scrape)
                .contains("surprising_exporter_backlog_count{product_line=\"LINEAR_PERPETUAL\"} 3")
                .contains("surprising_exporter_backlog_bytes{product_line=\"LINEAR_PERPETUAL\"} 144")
                .contains("surprising_exporter_oldest_event_age_millis{product_line=\"LINEAR_PERPETUAL\"} 0")
                .contains("surprising_exporter_last_acknowledged_sequence{product_line=\"LINEAR_PERPETUAL\"} 7")
                .contains("surprising_exporter_export_to_kafka_lag{product_line=\"LINEAR_PERPETUAL\"} 3")
                .contains("surprising_exporter_kafka_to_pg_watermark_lag{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_published_events_total{product_line=\"LINEAR_PERPETUAL\"} 2")
                .contains("surprising_exporter_duplicate_events_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_retries_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_failures_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_unknown_results_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_reconnects_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_reconnecting{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_queries_total{product_line=\"LINEAR_PERPETUAL\"} 1")
                .contains("surprising_exporter_query_rate_per_second{product_line=\"LINEAR_PERPETUAL\"} 0.0");
    }
}
