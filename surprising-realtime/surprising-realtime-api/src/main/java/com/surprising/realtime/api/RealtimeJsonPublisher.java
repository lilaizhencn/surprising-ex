package com.surprising.realtime.api;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** Peripheral price/candle producer. Never installed in the trading Core. */
@Component
@ConditionalOnProperty(name = "surprising.realtime.publish-json", havingValue = "true")
public final class RealtimeJsonPublisher implements AutoCloseable {
    private final RealtimeOutbox outbox = new RealtimeOutbox(2048, 8 * 1024 * 1024);
    private final AeronRealtimeSender sender;
    private final ObjectMapper mapper;
    private final java.util.concurrent.locks.ReentrantLock lock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.atomic.LongAdder dropped =
            new java.util.concurrent.atomic.LongAdder();

    public RealtimeJsonPublisher(Environment env, ObjectMapper mapper) {
        this.mapper = mapper;
        sender =
                new AeronRealtimeSender(
                        outbox,
                        env.getProperty(
                                "surprising.realtime.directory",
                                io.aeron.CommonContext.getAeronDirectoryName()),
                        env.getRequiredProperty("surprising.realtime.channel"),
                        env.getProperty("surprising.realtime.stream", Integer.class, 2101));
    }

    public void publish(
            ProductLine product,
            RealtimeFrame.Kind kind,
            String symbol,
            String entity,
            long sequence,
            Instant time,
            Object payload) {
        if (!lock.tryLock()) {
            dropped.increment();
            return;
        }
        try {
            outbox.begin();
            outbox.stage(
                    RealtimeFrameCodec.encode(
                            new RealtimeFrame(
                                    product,
                                    kind,
                                    0,
                                    Math.max(0, sequence),
                                    0,
                                    time.toEpochMilli(),
                                    0,
                                    symbol,
                                    entity,
                                    mapper.writeValueAsBytes(payload))));
            outbox.commit();
        } catch (RuntimeException failure) {
            outbox.abort();
            dropped.increment();
        } finally {
            lock.unlock();
        }
    }

    public long dropped() {
        return dropped.sum() + outbox.droppedBatches() + sender.dropped();
    }

    @PreDestroy
    @Override
    public void close() {
        sender.close();
    }
}
