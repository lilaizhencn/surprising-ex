package com.surprising.trading.api.kafka;

import com.surprising.trading.api.TraceContext;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.ProducerListener;

/** Trace metadata at Kafka handoffs; acknowledgement/transaction semantics remain Kafka-owned. */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaTraceAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaTraceAutoConfiguration.class);

    public static String traceId(ConsumerRecord<?, ?> record) {
        String identity = "kafka-" + record.topic() + "-" + record.partition() + "-" + record.offset();
        if (!TraceContext.isValid(identity)) identity = "kafka-" + record.partition() + "-" + record.offset() + "-"
                + java.util.UUID.nameUUIDFromBytes(record.topic().getBytes(StandardCharsets.UTF_8));
        return headerId(record.headers(), identity);
    }

    private static String headerId(Headers headers, String absentId) {
        var header = headers.lastHeader(TraceContext.TRACE_ID_HEADER);
        if (header == null || header.value() == null) return TraceContext.normalizeOrCreate(absentId);
        // Invalid/untrusted metadata may not inject content into application logs.
        String explicit = new String(header.value(), StandardCharsets.US_ASCII);
        return TraceContext.isValid(explicit) ? explicit : TraceContext.normalizeOrCreate(absentId);
    }

    @Bean
    static BeanPostProcessor kafkaTraceBoundaries() {
        return new BeanPostProcessor() {
            @Override @SuppressWarnings({"rawtypes", "unchecked"})
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KafkaTemplate template) {
                    template.setProducerInterceptor(new TraceProducer());
                    template.setProducerListener(new TraceAcknowledgements());
                }
                if (bean instanceof AbstractKafkaListenerContainerFactory factory) {
                    // Existing factories have no interceptors. Fail explicitly if another owner is added.
                    if (factory.getRecordInterceptor() != null || factory.getBatchInterceptor() != null)
                        throw new IllegalStateException("Kafka trace interceptor ownership conflict: " + name);
                    factory.setRecordInterceptor(new TraceRecords());
                    factory.setBatchInterceptor(new TraceBatches());
                }
                return bean;
            }
        };
    }

    static final class TraceProducer implements ProducerInterceptor<Object, Object> {
        @Override public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            var existing = record.headers().lastHeader(TraceContext.TRACE_ID_HEADER);
            String explicit = existing == null || existing.value() == null ? null
                    : new String(existing.value(), StandardCharsets.US_ASCII);
            String id;
            if (TraceContext.isValid(explicit)) {
                // A queued/retried record already owns its root, even after Kafka freezes its headers.
                id = explicit;
            } else {
                id = TraceContext.normalizeOrCreate(TraceContext.current());
                var headers = new org.apache.kafka.common.header.internals.RecordHeaders(record.headers().toArray());
                headers.remove(TraceContext.TRACE_ID_HEADER);
                headers.add(TraceContext.TRACE_ID_HEADER, id.getBytes(StandardCharsets.US_ASCII));
                record = new ProducerRecord<>(record.topic(), record.partition(), record.timestamp(),
                        record.key(), record.value(), headers);
            }
            log.info("kafka.publish traceId={} topic={}", id, record.topic());
            return record;
        }
        @Override public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}
        @Override public void close() {}
        @Override public void configure(Map<String, ?> config) {}
    }

    static final class TraceAcknowledgements implements ProducerListener<Object, Object> {
        @Override public void onSuccess(ProducerRecord<Object, Object> record, RecordMetadata metadata) {
            log.info("kafka.ack traceId={} topic={} partition={} offset={}",
                    headerId(record.headers(), "untraced"), metadata.topic(), metadata.partition(), metadata.offset());
        }
        @Override public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata, Exception failure) {
            log.warn("kafka.failed traceId={} topic={} error={}", headerId(record.headers(), "untraced"),
                    record.topic(), failure.getClass().getSimpleName());
        }
    }

    static final class TraceRecords implements RecordInterceptor<Object, Object> {
        private final ThreadLocal<TraceContext.Scope> scopes = new ThreadLocal<>();
        @Override public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                                  Consumer<Object, Object> consumer) {
            clearThreadState(consumer);
            scopes.set(TraceContext.open(traceId(record)));
            log.info("kafka.consume.start topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return record;
        }
        @Override public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            log.info("kafka.consume.end topic={} partition={} offset={} result=OK", record.topic(), record.partition(), record.offset());
        }
        @Override public void failure(ConsumerRecord<Object, Object> record, Exception failure, Consumer<Object, Object> consumer) {
            log.warn("kafka.consume.end topic={} partition={} offset={} result=FAILED error={}",
                    record.topic(), record.partition(), record.offset(), failure.getClass().getSimpleName());
        }
        @Override public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) { clearThreadState(consumer); }
        @Override public void clearThreadState(Consumer<?, ?> consumer) {
            var scope = scopes.get(); scopes.remove(); if (scope != null) scope.close();
        }
    }

    static final class TraceBatches implements BatchInterceptor<Object, Object> {
        @Override public ConsumerRecords<Object, Object> intercept(ConsumerRecords<Object, Object> records,
                                                                    Consumer<Object, Object> consumer) {
            for (var record : records) logRecord("kafka.batch.start", record, "PENDING");
            return records;
        }
        @Override public void success(ConsumerRecords<Object, Object> records, Consumer<Object, Object> consumer) {
            for (var record : records) logRecord("kafka.batch.end", record, "OK");
        }
        @Override public void failure(ConsumerRecords<Object, Object> records, Exception failure, Consumer<Object, Object> consumer) {
            // A failed batch can include already-applied records; never label every record as unprocessed.
            for (var record : records) logRecord("kafka.batch.end", record, "UNKNOWN:" + failure.getClass().getSimpleName());
        }
        private void logRecord(String stage, ConsumerRecord<Object, Object> record, String result) {
            log.info("{} traceId={} topic={} partition={} offset={} result={}", stage, traceId(record),
                    record.topic(), record.partition(), record.offset(), result);
        }
    }
}
