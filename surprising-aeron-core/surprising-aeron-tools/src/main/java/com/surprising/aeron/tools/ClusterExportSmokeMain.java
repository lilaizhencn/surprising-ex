package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.exporter.ReliableCoreExporter;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ClusterExportSmokeMain {

    private ClusterExportSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        List<String> hosts = Arrays.stream(System.getProperty("surprising.aeron.hostnames").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        String mode = System.getProperty("surprising.aeron.export-mode", "status");
        int batchSize = Integer.parseInt(System.getProperty("surprising.aeron.export-batch-size", "1024"));
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hosts, egress, Duration.ofSeconds(10))) {
            List<Long> published = new ArrayList<>();
            ReliableCoreExporter exporter = new ReliableCoreExporter(productLine, client::submit,
                    (line, events) -> {
                        events.forEach(event -> published.add(
                                CoreExportCodec.decodeEvent(event.payload()).exportSequence()));
                        if ("fail".equalsIgnoreCase(mode)) {
                            throw new IllegalStateException("injected exporter sink failure");
                        }
                    }, batchSize);
            var before = exporter.status();
            if ("status".equalsIgnoreCase(mode)) {
                System.out.printf("exportStatus=PASS productLine=%s ack=%d next=%d pending=%d "
                                + "pendingBytes=%d maxPending=%d maxPendingBytes=%d%n",
                        productLine, before.acknowledgedSequence(), before.nextSequence(), before.pendingCount(),
                        before.pendingBytes(), before.maxPendingCount(), before.maxPendingBytes());
                return;
            }
            if ("fail".equalsIgnoreCase(mode)) {
                try {
                    exporter.exportOnce();
                    throw new IllegalStateException("injected sink failure did not fail export cycle");
                } catch (IllegalStateException expected) {
                    if (!"injected exporter sink failure".equals(expected.getMessage())) {
                        throw expected;
                    }
                }
                var after = exporter.status();
                if (after.acknowledgedSequence() != before.acknowledgedSequence()
                        || after.pendingCount() != before.pendingCount()) {
                    throw new IllegalStateException("failed exporter advanced durable cursor");
                }
                System.out.printf("exportFailure=PASS productLine=%s published=%d ack=%d pending=%d%n",
                        productLine, published.size(), after.acknowledgedSequence(), after.pendingCount());
                return;
            }
            if (!"drain".equalsIgnoreCase(mode)) {
                throw new IllegalArgumentException("unsupported export mode: " + mode);
            }
            var after = exporter.drain(Math.max(1, before.pendingCount() / batchSize + 1));
            System.out.printf("exportDrain=PASS productLine=%s published=%d ack=%d pending=%d%n",
                    productLine, published.size(), after.acknowledgedSequence(), after.pendingCount());
        }
    }
}
