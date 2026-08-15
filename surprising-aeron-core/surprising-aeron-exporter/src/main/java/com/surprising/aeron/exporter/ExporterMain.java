package com.surprising.aeron.exporter;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.client.ResultUnknownException;

public final class ExporterMain {

    private ExporterMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        try (var sink = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties())) {
            System.out.printf("Aeron exporter started productLine=%s topic=%s%n",
                    productLine, KafkaCoreExportSink.topic(productLine));
            while (!Thread.currentThread().isInterrupted()) {
                try (var client = SurprisingAeronClient.connect(productLine, ExporterConfiguration.aeronHosts(),
                        ExporterConfiguration.aeronEgressHost(), ExporterConfiguration.aeronTimeout())) {
                    var exporter = new ReliableCoreExporter(productLine, client::submit, sink,
                            ExporterConfiguration.batchSize());
                    long idleMillis = ExporterConfiguration.idleMillis();
                    long baseIdleMillis = idleMillis;
                    long maxIdleMillis = Math.max(baseIdleMillis, 1_000L);
                    while (!Thread.currentThread().isInterrupted()) {
                        if (exporter.exportOnce().publishedEvents() == 0) {
                            Thread.sleep(idleMillis);
                            idleMillis = nextIdleMillis(idleMillis, maxIdleMillis);
                        } else {
                            idleMillis = baseIdleMillis;
                        }
                    }
                } catch (ResultUnknownException | io.aeron.exceptions.TimeoutException exception) {
                    System.err.printf("Aeron exporter reconnecting productLine=%s reason=%s%n",
                            productLine, exception.getMessage());
                    Thread.sleep(ExporterConfiguration.idleMillis());
                }
            }
        }
    }

    private static long nextIdleMillis(long current, long maximum) {
        return current >= maximum / 2 ? maximum : current * 2;
    }
}
