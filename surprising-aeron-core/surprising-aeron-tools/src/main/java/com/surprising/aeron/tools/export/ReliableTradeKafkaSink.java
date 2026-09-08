package com.surprising.aeron.tools.export;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.*;
import com.surprising.trading.api.model.*;

import org.apache.kafka.clients.producer.*;

import tools.jackson.databind.ObjectMapper;

import java.nio.*;
import java.time.Instant;
import java.util.*;

final class ReliableTradeKafkaSink implements AutoCloseable {
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
        producer.initTransactions();
    }

    void publish(List<RealtimeFrame> frames) {
        if (frames.isEmpty()) return;
        producer.beginTransaction();
        try {
            for (var frame : frames) {
                if (frame.productLine() != product
                        || frame.kind() != RealtimeFrame.Kind.TRADE
                        || frame.payloadLength() != 33)
                    throw new IllegalArgumentException("invalid reliable trade");
                var b = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
                long instrument = b.getLong(), price = b.getLong(), quantity = b.getLong();
                b.getLong();
                int side = b.get();
                var event =
                        new PublicTradeEvent(
                                frame.entityId(),
                                Math.incrementExact(tradeSequence),
                                frame.symbol(),
                                instrument,
                                OrderSide.valueOf(CoreOrderSide.values()[side].name()),
                                price,
                                quantity,
                                Instant.ofEpochMilli(frame.timestamp()),
                                "core-" + frame.sequence());
                tradeSequence = event.sequence();
                producer.send(
                        new ProducerRecord<>(
                                topic, frame.symbol(), mapper.writeValueAsString(event)));
            }
            producer.commitTransaction();
        } catch (RuntimeException failure) {
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
