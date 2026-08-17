package com.surprising.aeron.exporter;

import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.client.SurprisingAeronClient;
import io.aeron.exceptions.TimeoutException;

public final class ExporterMain {

    private ExporterMain() {
    }

    public static void main(String[] args) throws Exception {
        var productLine = ExporterConfiguration.productLine();
        long reconnectMillis = AdaptiveExportLoop.MIN_IDLE_MILLIS;
        System.out.printf("Aeron exporter started productLine=%s topic=%s%n",
                productLine, KafkaCoreExportSink.topic(productLine));
        while (!Thread.currentThread().isInterrupted()) {
            try (var sink = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties());
                    var client = SurprisingAeronClient.connect(productLine, ExporterConfiguration.aeronHosts(),
                            ExporterConfiguration.aeronEgressHost(), ExporterConfiguration.aeronTimeout())) {
                    var exporter = new ReliableCoreExporter(productLine, client::submit, sink,
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
                            if (isKafkaFailure(exception)) {
                                System.err.printf("Aeron exporter reconnecting productLine=%s reason=%s%n",
                                        productLine, exception.getMessage());
                                Thread.sleep(failureMillis);
                                failureMillis = AdaptiveExportLoop.nextIdleMillis(failureMillis,
                                        AdaptiveExportLoop.MAX_IDLE_MILLIS);
                                continue;
                            }
                            throw exception;
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ResultUnknownException | TimeoutException exception) {
                    System.err.printf("Aeron exporter reconnecting productLine=%s reason=%s%n",
                            productLine, exception.getMessage());
                    Thread.sleep(reconnectMillis);
                    reconnectMillis = AdaptiveExportLoop.nextIdleMillis(reconnectMillis,
                            AdaptiveExportLoop.MAX_IDLE_MILLIS);
                } catch (Exception exception) {
                    System.err.printf("Aeron exporter reconnecting productLine=%s reason=%s%n",
                            productLine, exception.getMessage());
                    Thread.sleep(reconnectMillis);
                    reconnectMillis = AdaptiveExportLoop.nextIdleMillis(reconnectMillis,
                            AdaptiveExportLoop.MAX_IDLE_MILLIS);
            }
        }
    }

    private static boolean isKafkaFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof org.apache.kafka.common.KafkaException) {
                return true;
            }
        }
        return false;
    }
}
