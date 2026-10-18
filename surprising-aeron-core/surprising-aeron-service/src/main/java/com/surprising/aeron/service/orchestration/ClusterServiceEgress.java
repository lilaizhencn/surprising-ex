package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.MessageHeaderEncoder;
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;

/** Owns Cluster-session state and the Owner-to-Aeron response handoff. */
@Component
public final class ClusterServiceEgress {
    /** Replicated messages without a client session never produce a client response. */
    public static final long NO_SESSION_ID = -1L;
    private static final byte[] EMPTY = new byte[0];
    private static final long OUTPUT_BYTES = 16L * 1024 * 1024;
    private static final int OUTPUT_SLOTS = 8192;

    private final OneToOneConcurrentArrayQueue<Output> output = new OneToOneConcurrentArrayQueue<>(OUTPUT_SLOTS);
    /** Aeron returns consumed slots to the Owner; both queues publish the ownership handoff. */
    private final OneToOneConcurrentArrayQueue<Output> availableOutput = new OneToOneConcurrentArrayQueue<>(OUTPUT_SLOTS);
    /** Owner-only slot retained when the output queue rejects its publication. */
    private Output unpublishedOutput;
    private final DeferredSessionResponses deferred = new DeferredSessionResponses();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[0]);
    private byte[] sendScratch = EMPTY;
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<TransportSession>
            sessions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final EgressFactory egressFactory;
    private Cluster cluster;
    private boolean closeRequests;
    private static class PaddedOutputProducedPadding {
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    private static class PaddedOutputProducedValue extends PaddedOutputProducedPadding {
        protected long outputProduced;
    }

    private static class PaddedOutputConsumedPadding extends PaddedOutputProducedValue {
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    private static class PaddedOutputConsumedValue extends PaddedOutputConsumedPadding {
        protected volatile long outputConsumed;
    }

    private static final class PaddedEgressOffsets extends PaddedOutputConsumedValue {
        @SuppressWarnings("unused")
        private long p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p2a, p2b, p2c, p2d, p2e;
    }

    private final PaddedEgressOffsets egressOffsets = new PaddedEgressOffsets();
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

    public ClusterServiceEgress() {
        this(SessionResponsePublication::new);
    }

    public ClusterServiceEgress(EgressFactory egressFactory) {
        this.egressFactory = Objects.requireNonNull(egressFactory);
        for (int i = 0; i < OUTPUT_SLOTS; i++) availableOutput.add(new Output());
    }

    /** Initializes the Aeron-side session boundary after Cluster starts. */
    public void start(Cluster cluster) {
        this.cluster = Objects.requireNonNull(cluster);
        epoch = 0;
        leadershipTermId = 0;
    }

    /** Registers a service-thread session; only its primitive ID crosses to the Owner. */
    public TransportSession transportSession(ClientSession session) {
        TransportSession transport = sessions.get(session.id());
        if (transport == null) {
            transport = new TransportSession(session);
            sessions.put(session.id(), transport);
            if (cluster.role() == Cluster.Role.LEADER) transport.connect();
        }
        return transport;
    }

    public void closeSession(ClientSession session) {
        if (session == null) return;
        deferred.remove(session.id());
        TransportSession transport = sessions.remove(session.id());
        if (transport != null) transport.disconnect();
    }

    public void flushSessionClosures() {
        if (!closeRequests) return;
        closeRequests = false;
        sessions.forEachValue(session -> {
            if (session.closeRequested && !session.actual.isClosing()) session.actual.close();
        });
    }

    public long onRoleChange(Cluster.Role role) {
        long next = ++epoch;
        deferred.clear();
        sessions.forEachValue(session -> {
            if (role == Cluster.Role.LEADER) session.connect();
            else session.disconnect();
        });
        return next;
    }

    public void leadershipTerm(long termId) {
        leadershipTermId = termId;
    }

    /**
     * Owner publishes exceptional close requests; only the Aeron thread closes sessions.
     * Requests are deduplicated until consumed. No collection nodes are created on normal sends.
     */
    private final java.util.Set<Long> overflowSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void handleSessionOverflow(long sessionId) {
        if (sessionId != NO_SESSION_ID) {
            overflowSessions.add(sessionId);
        }
    }

    void publishResponse(TradingCoreOwner processor, long sessionId, CoreMessageHeader header, CoreResponse response,
                         long committedSequence, long ownerEpoch) {
        int length = CoreMessageCodec.encodedResponseLength(header, response);
        if (length > OUTPUT_BYTES - (egressOffsets.outputProduced - egressOffsets.outputConsumed) || output.remainingCapacity() == 0) {
            handleSessionOverflow(sessionId);
            processor.releaseResponse(response);
            return;
        }
        Output slot = unpublishedOutput;
        unpublishedOutput = null;
        if (slot == null) slot = availableOutput.poll();
        if (slot == null) {
            handleSessionOverflow(sessionId);
            processor.releaseResponse(response);
            return;
        }
        header.retainForEgress();
        slot.prepare(processor, sessionId, header, response, committedSequence, length, ownerEpoch);
        if (!output.offer(slot)) {
            handleSessionOverflow(sessionId);
            header.releaseFromEgress();
            processor.releaseResponse(response);
            slot.clear();
            unpublishedOutput = slot;
            return;
        }
        egressOffsets.outputProduced += length;
    }

    int drainResponses(TradingCoreOwner processor) {
        if (output.peek() == null && deferred.size() == 0 && overflowSessions.isEmpty()) {
            return 0;
        }
        int work = 0;
        if (!overflowSessions.isEmpty()) {
            var iterator = overflowSessions.iterator();
            while (iterator.hasNext()) {
                long sid = iterator.next();
                TransportSession session = sessions.get(sid);
                if (session != null) {
                    session.close();
                }
                deferred.remove(sid);
                // Removing just the consumed request preserves other IDs concurrently added by Owner.
                // A repeated request for this ID needs no further action: its session is already closing.
                iterator.remove();
            }
        }
        long consumedBytes = 0;
        for (; work < 256; work++) {
            Output next = output.poll();
            if (next == null) break;
            consumedBytes += next.length;
            try {
                if (next.epoch != epoch || cluster.role() != Cluster.Role.LEADER) continue;
                TransportSession session = sessions.get(next.sessionId);
                if (session == null || session.isClosing()) continue;
                if (sendScratch.length < next.length) {
                    sendScratch = new byte[next.length];
                    sendBuffer.wrap(sendScratch);
                }
                CoreMessageCodec.encodeResponse(next.header, next.response, next.committedSequence, sendScratch);
                deferred.offer(session, sendBuffer, next.length, System.nanoTime());
            } finally {
                try { processor.releaseResponse(next.response); }
                finally {
                    next.header.releaseFromEgress();
                    returnOutput(next);
                }
            }
        }
        if (consumedBytes > 0) {
            egressOffsets.outputConsumed += consumedBytes;
        }
        return work + deferred.poll(System.nanoTime(), 256);
    }

    public void clear() {
        Output next;
        while ((next = output.poll()) != null) {
            try { next.processor.releaseResponse(next.response); }
            finally {
                next.header.releaseFromEgress();
                returnOutput(next);
            }
        }
        deferred.clear();
        overflowSessions.clear();
        sessions.forEachValue(TransportSession::disconnect);
        sessions.clear();
    }

    /** Concrete service-thread response channel; never sent to or invoked by the Owner. */
    public final class TransportSession {
        private final ClientSession actual;
        private final long id;
        private boolean closeRequested;
        private SessionEgress egress;

        private TransportSession(ClientSession actual) {
            this.actual = actual;
            this.id = actual.id();
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

        public long id() { return id; }
        boolean isClosing() { return closeRequested || actual.isClosing(); }

        void close() {
            closeRequested = true;
            closeRequests = true;
        }

        long offer(DirectBuffer buffer, int offset, int length) {
            return egress == null ? io.aeron.Publication.NOT_CONNECTED
                    : egress.offer(leadershipTermId, cluster.time(), buffer, offset, length);
        }
    }

    private void returnOutput(Output slot) {
        slot.clear();
        if (!availableOutput.offer(slot)) throw new IllegalStateException("response output slot returned twice");
    }

    /** Slot is immutable between output publication and the Aeron consumer's final release. */
    private static final class Output {
        private TradingCoreOwner processor;
        private long sessionId = NO_SESSION_ID;
        private CoreMessageHeader header;
        private CoreResponse response;
        private long committedSequence, epoch;
        private int length;

        void prepare(TradingCoreOwner processor, long sessionId, CoreMessageHeader header,
                CoreResponse response, long committedSequence, int length, long epoch) {
            this.processor = processor;
            this.sessionId = sessionId;
            this.header = header;
            this.response = response;
            this.committedSequence = committedSequence;
            this.length = length;
            this.epoch = epoch;
        }

        void clear() {
            processor = null;
            sessionId = NO_SESSION_ID;
            header = null;
            response = null;
        }
    }

    static final class SessionResponsePublication implements SessionEgress {
        private final Publication publication;
        private final UnsafeBuffer header = new UnsafeBuffer(new byte[AeronCluster.SESSION_HEADER_LENGTH]);
        private final SessionMessageHeaderEncoder encoder = new SessionMessageHeaderEncoder();

        SessionResponsePublication(Cluster cluster, ClientSession session) {
            publication = cluster.aeron().addPublication(session.responseChannel(), session.responseStreamId());
            encoder.wrapAndApplyHeader(header, 0, new MessageHeaderEncoder()).clusterSessionId(session.id());
        }

        @Override
        public long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length) {
            encoder.leadershipTermId(leadershipTermId).timestamp(timestamp);
            return publication.offer(header, 0, header.capacity(), source, offset, length, null);
        }

        @Override
        public void close() {
            publication.close();
        }
    }
}
