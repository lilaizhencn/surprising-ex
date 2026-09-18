package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/** Owns Cluster-session state and the Owner-to-Aeron response handoff. */
public final class ClusterServiceEgress {
    private static final byte[] EMPTY = new byte[0];
    private static final long OUTPUT_BYTES = 16L * 1024 * 1024;

    private final OneToOneConcurrentArrayQueue<Output> output = new OneToOneConcurrentArrayQueue<>(8192);
    private final DeferredSessionResponses deferred = new DeferredSessionResponses();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[0]);
    private byte[] sendScratch = EMPTY;
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<TransportSession>
            sessions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final EgressFactory egressFactory;
    private Cluster cluster;
    private boolean closeRequests;
    private long outputProduced;
    private volatile long outputConsumed;
    private volatile boolean outputOverflow;
    private long epoch;
    private long leadershipTermId;

    @FunctionalInterface
    public interface EgressFactory {
        SessionEgress open(Cluster cluster, ClientSession session);
    }

    public interface SessionEgress {
        long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length);
        void close();
    }

    public ClusterServiceEgress(EgressFactory egressFactory) {
        this.egressFactory = Objects.requireNonNull(egressFactory);
    }

    void start(Cluster cluster) {
        this.cluster = Objects.requireNonNull(cluster);
        epoch = 0;
        leadershipTermId = 0;
    }

    ClientSession transportSession(ClientSession session) {
        TransportSession transport = sessions.get(session.id());
        if (transport == null) {
            transport = new TransportSession(session);
            sessions.put(session.id(), transport);
            if (cluster.role() == Cluster.Role.LEADER) transport.connect();
        }
        return transport;
    }

    void closeSession(ClientSession session) {
        if (session == null) return;
        deferred.remove(session.id());
        TransportSession transport = sessions.remove(session.id());
        if (transport != null) transport.disconnect();
    }

    void flushSessionClosures() {
        if (!closeRequests) return;
        closeRequests = false;
        sessions.forEachValue(session -> {
            if (session.closeRequested && !session.actual.isClosing()) session.actual.close();
        });
    }

    long onRoleChange(Cluster.Role role) {
        long next = ++epoch;
        deferred.clear();
        sessions.forEachValue(session -> {
            if (role == Cluster.Role.LEADER) session.connect();
            else session.disconnect();
        });
        return next;
    }

    void leadershipTerm(long termId) {
        leadershipTermId = termId;
    }

    void publishResponse(TradingCoreOwner processor, ClientSession session, CoreMessageHeader header, CoreResponse response,
                         long committedSequence, long ownerEpoch) {
        int length = CoreMessageCodec.encodedResponseLength(response);
        if (length > OUTPUT_BYTES - (outputProduced - outputConsumed) || output.remainingCapacity() == 0) {
            outputOverflow = true;
            processor.releaseResponse(response);
            return;
        }
        if (!output.offer(new Output(session, header, response, committedSequence, length,
                ownerEpoch, CoreMatchingPhaseMetrics.sampleStart(header)))) {
            outputOverflow = true;
            processor.releaseResponse(response);
            return;
        }
        outputProduced += length;
    }

    int drainResponses(TradingCoreOwner processor) {
        int work = 0;
        if (outputOverflow) {
            outputOverflow = false;
            sessions.forEachValue(TransportSession::close);
            deferred.clear();
        }
        for (; work < 256; work++) {
            Output next = output.poll();
            if (next == null) break;
            outputConsumed += next.length;
            try {
                if (next.epoch != epoch || cluster.role() != Cluster.Role.LEADER) continue;
                if (sendScratch.length < next.length) {
                    sendScratch = new byte[next.length];
                    sendBuffer.wrap(sendScratch);
                }
                CoreMatchingPhaseMetrics.recordBoundary("ownerToEgress", next.header, next.publishedNanos);
                CoreMessageCodec.encodeResponse(next.header, next.response, next.committedSequence, sendScratch);
                deferred.offer(next.session, sendBuffer, next.length, System.nanoTime());
            } finally {
                processor.releaseResponse(next.response);
            }
        }
        return work + deferred.poll(System.nanoTime(), 256);
    }

    void clear() {
        output.clear();
        deferred.clear();
        sessions.forEachValue(TransportSession::disconnect);
        sessions.clear();
    }

    private final class TransportSession implements ClientSession {
        private final ClientSession actual;
        private boolean closeRequested;
        private SessionEgress egress;

        private TransportSession(ClientSession actual) {
            this.actual = actual;
        }

        private void connect() {
            if (egress == null) egress = egressFactory.open(cluster, actual);
        }

        private void disconnect() {
            if (egress != null) {
                egress.close();
                egress = null;
            }
        }

        public long id() { return actual.id(); }
        public int responseStreamId() { return actual.responseStreamId(); }
        public String responseChannel() { return actual.responseChannel(); }
        public byte[] encodedPrincipal() { return actual.encodedPrincipal(); }
        public boolean isClosing() { return closeRequested || actual.isClosing(); }

        public void close() {
            closeRequested = true;
            closeRequests = true;
        }

        public long offer(DirectBuffer buffer, int offset, int length) {
            return egress == null ? io.aeron.Publication.NOT_CONNECTED
                    : egress.offer(leadershipTermId, cluster.time(), buffer, offset, length);
        }

        public long offer(io.aeron.DirectBufferVector[] vectors) {
            throw new UnsupportedOperationException();
        }

        public long tryClaim(int length, io.aeron.logbuffer.BufferClaim claim) {
            throw new UnsupportedOperationException();
        }
    }

    private record Output(ClientSession session, CoreMessageHeader header, CoreResponse response,
                          long committedSequence, int length, long epoch, long publishedNanos) {}
}
