package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.*;
import com.surprising.trading.api.model.*;

import org.apache.kafka.clients.producer.*;

import tools.jackson.databind.ObjectMapper;

import java.nio.*;
import java.time.Instant;
import java.util.*;

final class ReliableTradeKafkaSink implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReliableTradeKafkaSink.class);
    private final Producer<String, String> producer;
    private final String topic;
    private final ProductLine product;
    private final ObjectMapper mapper = new ObjectMapper();
    private long tradeSequence;

    ReliableTradeKafkaSink(
            Producer<String, String> producer, ProductLine product, long tradeSequence) {
        this.producer = producer;
        this.product = product;
        this.tradeSequence = tradeSequence;
        topic = ProductTopicNames.of(product).matchTradesTopic();
        try {
            producer.initTransactions();
        } catch (RuntimeException failure) {
            // A failed constructor is not closed by try-with-resources; retries must not leak producers.
            try { producer.close(java.time.Duration.ofSeconds(10)); }
            catch (RuntimeException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    void publish(List<RealtimeFrame> frames) {
        if (frames.isEmpty()) return;
        producer.beginTransaction();
        try {
            for (var frame : frames) {
                if (frame.productLine() != product
                        || frame.kind() != RealtimeFrame.Kind.TRADE
                        || frame.payloadLength() != 25)
                    throw new IllegalArgumentException("invalid reliable trade");
                var b = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
                long price = b.getLong(), quantity = b.getLong();
                b.getLong();
                int side = b.get();
                var event =
                        new PublicTradeEvent(
                                frame.entityId(),
                                Math.incrementExact(tradeSequence),
                                frame.instrumentId(),
                                OrderSide.valueOf(CoreOrderSide.values()[side].name()),
                                price,
                                quantity,
                                Instant.ofEpochMilli(frame.timestamp()),
                                frame.traceId().isEmpty() ? "core-" + product + "-" + frame.sequence() : frame.traceId());
                tradeSequence = event.sequence();
                var record = new ProducerRecord<String, String>(topic, frame.instrumentId(), mapper.writeValueAsString(event));
                record.headers().add("X-Trace-Id", event.traceId().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                producer.send(record);
            }
            producer.commitTransaction();
            // Log success only after the transaction is committed, never in the send callback.
            for (var frame : frames)
                log.info("trade.kafka.committed traceId={} productLine={} tradeId={} topic={} logPosition={}",
                        frame.traceId(), product, frame.entityId(), topic, frame.sequence());
        } catch (RuntimeException failure) {
            for (var frame : frames)
                log.warn("trade.kafka.failed traceId={} productLine={} tradeId={} error={}",
                        frame.traceId(), product, frame.entityId(), failure.getClass().getSimpleName());
            try {
                producer.abortTransaction();
            } catch (RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            throw failure;
        }
    }

    long tradeSequence() {
        return tradeSequence;
    }

    @Override
    public void close() {
        producer.close(java.time.Duration.ofSeconds(10));
    }
}
