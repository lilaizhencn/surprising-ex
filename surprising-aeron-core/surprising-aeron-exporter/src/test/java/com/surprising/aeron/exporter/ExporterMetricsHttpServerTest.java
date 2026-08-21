package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExporterMetricsHttpServerTest {

    @Test
    void servesCurrentExporterSnapshotOnMetricsEndpoint() throws Exception {
        ExporterMetrics metrics = new ExporterMetrics(ProductLine.LINEAR_PERPETUAL);
        metrics.recordRetry();
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

        try (ExporterMetricsHttpServer server = ExporterMetricsHttpServer.start(metrics, address)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + server.address().getPort() + "/metrics"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValue("text/plain; version=0.0.4; charset=utf-8");
            assertThat(response.body()).contains(
                    "surprising_exporter_retries_total{product_line=\"LINEAR_PERPETUAL\"} 1");
        }
    }
}
