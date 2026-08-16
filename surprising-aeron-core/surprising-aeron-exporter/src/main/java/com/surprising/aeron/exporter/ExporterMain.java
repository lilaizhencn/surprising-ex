package com.surprising.aeron.exporter;

import com.surprising.aeron.client.AeronClientPool;

public final class ExporterMain {

    private ExporterMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        try (var client = new AeronClientPool("core-exporter", productLine,
                     ExporterConfiguration.aeronHosts(), ExporterConfiguration.aeronEgressHost(),
                     ExporterConfiguration.aeronTimeout(), 1);
             var sink = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties())) {
            System.out.printf("Aeron exporter started productLine=%s topic=%s%n",
                    productLine, KafkaCoreExportSink.topic(productLine));
            var exporter = new ReliableCoreExporter(productLine, client::submitPrepared, sink,
                    ExporterConfiguration.batchSize());
            var loop = new AdaptiveExportLoop(exporter::exportOnce, Thread::sleep,
                    ExporterConfiguration.idleMillis());
            long failureMillis = AdaptiveExportLoop.MIN_IDLE_MILLIS;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    loop.runOnce();
                    failureMillis = AdaptiveExportLoop.MIN_IDLE_MILLIS;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (Exception exception) {
                    System.err.printf("Aeron exporter cycle failed productLine=%s reason=%s%n",
                            productLine, exception.getMessage());
                    Thread.sleep(failureMillis);
                    failureMillis = AdaptiveExportLoop.nextIdleMillis(failureMillis,
                            AdaptiveExportLoop.MAX_IDLE_MILLIS);
                }
            }
        }
    }
}
