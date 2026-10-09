package com.surprising.trading.api.kafka;

import com.surprising.trading.api.TraceContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class KafkaTraceAutoConfigurationTest {
    @AfterEach void clear() { TraceContext.clear(); }
    @Test void capturesRootBeforeProducerThreadHandoffAndKeepsHeaderOnRetransmission() {
        var interceptor = new KafkaTraceAutoConfiguration.TraceProducer();
        ProducerRecord<Object, Object> record = new ProducerRecord<>("product.price", "604", "payload");
        try (var scope = TraceContext.open("request-1")) {
            record = interceptor.onSend(record);
        }
        assertThat(new String(record.headers().lastHeader("X-Trace-Id").value(), StandardCharsets.US_ASCII)).isEqualTo("request-1");
        assertThat(TraceContext.current()).isNull();
        ((org.apache.kafka.common.header.internals.RecordHeaders) record.headers()).setReadOnly();
        try (var scope = TraceContext.open("different-request")) {
            assertThat(interceptor.onSend(record)).isSameAs(record);
        }
        assertThat(record.headers().headers("X-Trace-Id")).hasSize(1);
        assertThat(new String(record.headers().lastHeader("X-Trace-Id").value(), StandardCharsets.US_ASCII)).isEqualTo("request-1");
        assertThat(MDC.get("traceId")).isNull();
    }
    @Test void autoConfigurationInstallsRealTemplateAndListenerBoundaries() {
        var producer = new org.apache.kafka.clients.producer.MockProducer<String, String>(true, null,
                new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer());
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.ProducerFactory<String, String> factory = org.mockito.Mockito.mock(org.springframework.kafka.core.ProducerFactory.class);
        org.mockito.Mockito.when(factory.createProducer()).thenReturn(producer);
        org.mockito.Mockito.when(factory.getConfigurationProperties()).thenReturn(java.util.Map.of());
        var template = new org.springframework.kafka.core.KafkaTemplate<>(factory);
        var listener = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<>();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean("template", org.springframework.kafka.core.KafkaTemplate.class, () -> template)
                .withBean("listener", org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class, () -> listener)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try (var scope = TraceContext.open("template-root")) { template.send("product.trades", "604", "value").join(); }
                    var record = producer.history().getFirst();
                    assertThat(new String(record.headers().lastHeader("X-Trace-Id").value(), StandardCharsets.US_ASCII)).isEqualTo("template-root");
                    assertThat(listener.getRecordInterceptor()).isInstanceOf(KafkaTraceAutoConfiguration.TraceRecords.class);
                    assertThat(listener.getBatchInterceptor()).isInstanceOf(KafkaTraceAutoConfiguration.TraceBatches.class);
                    assertThat(TraceContext.current()).isNull();
                });
        producer.close();
    }
    @Test void restoresContextOnRecordFailureAndAReusedConsumerThread() {
        TraceContext.set("outer");
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("product.trades", 0, 13, "604", "payload");
        record.headers().add("X-Trace-Id", "request-1".getBytes(StandardCharsets.US_ASCII));
        var interceptor = new KafkaTraceAutoConfiguration.TraceRecords();
        assertThat(interceptor.intercept(record, null)).isSameAs(record);
        assertThat(TraceContext.current()).isEqualTo("request-1");
        interceptor.failure(record, new IllegalStateException("SECRET"), null);
        interceptor.afterRecord(record, null);
        assertThat(TraceContext.current()).isEqualTo("outer");
        assertThat(MDC.get("traceId")).isEqualTo("outer");
        interceptor.clearThreadState(null);
        assertThat(TraceContext.current()).isEqualTo("outer");
    }
    @Test void historicalRecordIdentityIsStableAndInvalidHeadersCannotInjectLogs() {
        var record = new ConsumerRecord<>("product.trades", 3, 7, "604", "payload");
        assertThat(KafkaTraceAutoConfiguration.traceId(record)).isEqualTo("kafka-product.trades-3-7");
        record.headers().add("X-Trace-Id", "bad\nSECRET".getBytes(StandardCharsets.US_ASCII));
        assertThat(KafkaTraceAutoConfiguration.traceId(record)).isEqualTo("kafka-product.trades-3-7");
        var longTopic = new ConsumerRecord<>("t".repeat(249), 1, 2, "604", "payload");
        assertThat(KafkaTraceAutoConfiguration.traceId(longTopic)).isEqualTo(KafkaTraceAutoConfiguration.traceId(longTopic))
                .matches("[A-Za-z0-9._:-]{1,128}");
    }
}
