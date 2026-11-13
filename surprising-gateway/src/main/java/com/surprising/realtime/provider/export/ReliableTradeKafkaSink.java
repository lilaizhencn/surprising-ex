package com.surprising.realtime.provider.export;

import com.surprising.product.api.utils.ProductTopicNames;

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
    private long nextFailureLogNanos;

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
            catch (RuntimeException closing) { if (closing != failure) failure.addSuppressed(closing); }
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
                                Instant.ofEpochMilli(frame.timestamp()));
                tradeSequence = event.sequence();
                var record = new ProducerRecord<String, String>(topic, frame.instrumentId(), mapper.writeValueAsString(event));
                producer.send(record);
            }
            producer.commitTransaction();
            // Log success only after the transaction is committed, never in the send callback.
            if (log.isDebugEnabled()) for (var frame : frames)
                log.debug("trade.kafka.committed productLine={} tradeId={} topic={} logPosition={}",
                        product, frame.entityId(), topic, frame.sequence());
        } catch (RuntimeException failure) {
            long now = System.nanoTime();
            if (nextFailureLogNanos == 0 || now >= nextFailureLogNanos) {
                nextFailureLogNanos = now + 5_000_000_000L;
                log.warn("trade.kafka.failed productLine={} count={} firstLogPosition={} error={}",
                        product, frames.size(), frames.getFirst().sequence(), failure.getClass().getSimpleName());
            }
            try {
                producer.abortTransaction();
            } catch (RuntimeException abortFailure) {
                if (abortFailure != failure) failure.addSuppressed(abortFailure);
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
