package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.Publication;
import io.aeron.logbuffer.Header;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

public final class SurprisingAeronClient implements AutoCloseable, EgressListener {

    private final ProductLine productLine;
    private final Duration responseTimeout;
    private final MediaDriver mediaDriver;
    private final boolean closeMediaDriver;
    private final AeronCluster cluster;
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
    private final Map<Long, CoreResponse> responses = new HashMap<>();
    private RuntimeException sessionFailure;

    private SurprisingAeronClient(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            MediaDriver mediaDriver,
            boolean closeMediaDriver) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.mediaDriver = Objects.requireNonNull(mediaDriver, "mediaDriver");
        this.closeMediaDriver = closeMediaDriver;
        try {
            cluster = AeronCluster.connect(new AeronCluster.Context()
                    .clientName("surprising-" + productLine.name().toLowerCase())
                    .messageTimeoutNs(responseTimeout.toNanos())
                    .newLeaderTimeoutNs(responseTimeout.toNanos())
                    .egressListener(this)
                    .egressChannel("aeron:udp?endpoint=" + egressHostname + ":0")
                    .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                    .ingressChannel("aeron:udp")
                    .ingressEndpoints(ProductLineClusterLayout.ingressEndpoints(productLine, hostnames)));
        } catch (RuntimeException exception) {
            if (closeMediaDriver) {
                mediaDriver.close();
            }
            throw exception;
        }
    }

    public static SurprisingAeronClient connect(ProductLine productLine, List<String> hostnames) {
        return connect(productLine, hostnames, "localhost", Duration.ofSeconds(5));
    }

    public static SurprisingAeronClient connect(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout) {
        if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        MediaDriver mediaDriver = newMediaDriver();
        return new SurprisingAeronClient(productLine, hostnames, egressHostname, responseTimeout,
                mediaDriver, true);
    }

    static SurprisingAeronClient connect(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            MediaDriver mediaDriver) {
        if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        return new SurprisingAeronClient(productLine, hostnames, egressHostname, responseTimeout,
                mediaDriver, false);
    }

    static MediaDriver newMediaDriver() {
        return newMediaDriver(null);
    }

    static MediaDriver newMediaDriver(String aeronDirectoryName) {
        MediaDriver.Context context = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        if (aeronDirectoryName != null && !aeronDirectoryName.isBlank()) {
            context.aeronDirectoryName(aeronDirectoryName);
        }
        return MediaDriver.launchEmbedded(context);
    }

    public CoreResponse submit(CoreMessage message) {
        if (message.header().productLine() != productLine) {
            throw new IllegalArgumentException("client and message product line differ");
        }
        byte[] encoded = CoreMessageCodec.encode(message);
        UnsafeBuffer buffer = new UnsafeBuffer(encoded);
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        idleStrategy.reset();
        while (true) {
            long offerResult = cluster.offer(buffer, 0, encoded.length);
            if (offerResult >= 0) {
                break;
            }
            if (offerResult == Publication.CLOSED || offerResult == Publication.MAX_POSITION_EXCEEDED) {
                throw resultUnknown(message, "Aeron Cluster publication is no longer writable: " + offerResult);
            }
            pollAndCheckSession();
            if (System.nanoTime() >= deadline) {
                throw resultUnknown(message, "timed out while offering command to Aeron Cluster");
            }
            idleStrategy.idle();
        }
        while (System.nanoTime() < deadline) {
            pollAndCheckSession();
            CoreResponse response = responses.remove(message.header().correlationId());
            if (response != null) {
                return response;
            }
            idleStrategy.idle();
        }
        throw resultUnknown(message, "timed out waiting for committed command result");
    }

    @Override
    public void onMessage(
            long clusterSessionId,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        try {
            byte[] encoded = new byte[length];
            buffer.getBytes(offset, encoded);
            CoreMessage message = CoreMessageCodec.decode(encoded);
            if (message.header().productLine() != productLine) {
                sessionFailure = new IllegalStateException("received response from another product line");
                return;
            }
            responses.put(message.header().correlationId(), CoreProtocol.decodeResponse(message.payload()));
        } catch (RuntimeException exception) {
            sessionFailure = new IllegalStateException("failed to decode Aeron Cluster egress response", exception);
        }
    }

    @Override
    public void onSessionEvent(
            long correlationId,
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            EventCode code,
            String detail) {
        if (code != EventCode.OK) {
            sessionFailure = new IllegalStateException("Aeron session event " + code + ": " + detail);
        }
    }

    @Override
    public void onNewLeader(
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            String ingressEndpoints) {
        sessionFailure = null;
    }

    @Override
    public void close() {
        try {
            cluster.close();
        } finally {
            if (closeMediaDriver) {
                mediaDriver.close();
            }
        }
    }

    private void pollAndCheckSession() {
        cluster.pollEgress();
        if (sessionFailure != null) {
            throw sessionFailure;
        }
    }

    private ResultUnknownException resultUnknown(CoreMessage message, String detail) {
        return new ResultUnknownException(message.header().commandId(), detail
                + "; retry or query with the same commandId=" + message.header().commandId());
    }
}
