package com.surprising.aeron.exporter;

import com.surprising.aeron.client.SurprisingAeronClient;

public final class ExporterMain {

    private ExporterMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        try (var client = SurprisingAeronClient.connect(productLine, ExporterConfiguration.aeronHosts(),
                     ExporterConfiguration.aeronEgressHost(), ExporterConfiguration.aeronTimeout());
             var sink = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties())) {
            var exporter = new ReliableCoreExporter(productLine, client::submit, sink,
                    ExporterConfiguration.batchSize());
            System.out.printf("Aeron exporter started productLine=%s topic=%s%n",
                    productLine, KafkaCoreExportSink.topic(productLine));
            while (!Thread.currentThread().isInterrupted()) {
                if (exporter.exportOnce().publishedEvents() == 0) {
                    Thread.sleep(ExporterConfiguration.idleMillis());
                }
            }
        }
    }
}
