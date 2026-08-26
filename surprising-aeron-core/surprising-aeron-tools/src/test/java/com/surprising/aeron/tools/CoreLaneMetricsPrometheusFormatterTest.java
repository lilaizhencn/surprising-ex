package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreLaneMetricsView;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class CoreLaneMetricsPrometheusFormatterTest {

    @Test
    void exposesQueueBarrierRejectionAndLatencyMetricsWithBoundedLabels() {
        CoreLaneMetricsView view = new CoreLaneMetricsView(4, 1,
                0, 16, 2, 0, 16, 1, 0, 16, 1, 21,
                new long[]{9}, new long[]{21}, new long[]{20},
                new int[]{0}, new int[]{16}, new int[]{4},
                new long[]{3}, new long[]{0},
                new long[]{8, 7, 6, 5}, new long[]{1, 1, 1, 1},
                new long[]{80, 70, 60, 50},
                new long[]{20, 20, 20, 20});

        String scrape = CoreLaneMetricsPrometheusFormatter.format(ProductLine.SPOT, view);

        assertThat(scrape)
                .contains("surprising_core_matcher_dispatch_queue_capacity{product_line=\"SPOT\"} 16")
                .contains("surprising_core_account_lane_commit_gap{product_line=\"SPOT\",lane_type=\"account\",lane_id=\"0\"} 1")
                .contains("surprising_core_account_lane_rejected_submissions_total{product_line=\"SPOT\",lane_type=\"account\",lane_id=\"0\"} 3")
                .contains("operation=\"settlement\"")
                .containsOnlyOnce("# TYPE surprising_core_account_lane_operations_total counter")
                .containsOnlyOnce("# TYPE surprising_core_account_lane_latency_seconds_total counter")
                .doesNotContain("user_id=")
                .doesNotContain("symbol=");
    }
}
