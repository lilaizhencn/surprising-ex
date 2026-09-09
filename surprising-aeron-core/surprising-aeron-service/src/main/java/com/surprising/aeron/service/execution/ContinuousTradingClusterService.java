package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.Header;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.*;

/**
 * Cluster日志与持续交易Owner的线程边界。
 * Aeron线程交付已复制输入、发送不可变终态；Owner独占交易状态并连续收集Lane结果。
 * 快照、角色切换和停止使用FIFO边界，普通命令没有定时器或逐条完成等待。
 */
public final class ContinuousTradingClusterService implements ClusteredService {
    private static final byte[] EMPTY = new byte[0];
    private static final long DEADLINE_NS = 30_000_000_000L;
    private static final long INPUT_BYTES = 64L * 1024 * 1024;
    private static final long OUTPUT_BYTES = 16L * 1024 * 1024;
    /** 单生产者Cluster线程、单消费者Owner；生命周期控制项同样进入FIFO。 */
    private final OneToOneConcurrentArrayQueue<Input> input = new OneToOneConcurrentArrayQueue<>(8192);
    /** 单生产者Owner、单消费者Cluster线程；只携带已编码终态，不携带可变账户。 */
    private final OneToOneConcurrentArrayQueue<Output> output = new OneToOneConcurrentArrayQueue<>(8192);
    /** Cluster线程独占的慢会话重试队列与发送包装器。 */
    private final DeferredSessionResponses deferred = new DeferredSessionResponses();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(new byte[0]);
    /** 只在Aeron线程访问的传输会话；背景出口只标记关闭，日志回调才发布关闭请求。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<TransportSession>
            sessions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private boolean closeRequests;
    /** 输入生产/消费字节水位；跨线程只读对方的volatile水位。 */
    private long inputProduced;
    private volatile long inputConsumed;
    /** 输出生产/消费字节水位；有界出口满时保留业务终态，通过断开会话触发客户端核对。 */
    private long outputProduced;
    private volatile long outputConsumed;
    private volatile boolean outputOverflow;
    /** Owner失败必须使Cluster服务停止，不能继续确认不再执行的日志。 */
    private volatile Throwable failure;
    /** Owner停止状态只由Owner控制；线程句柄和Cluster仅在服务生命周期使用。 */
    private boolean stopping;
    private Thread ownerThread;
    private Cluster cluster;
    private OwnerLogContext logContext;
    private final SurprisingClusteredService processor;
    /** 可替换的会话传输实现，隔离集群业务回调与只读终态出口。 */
    private final EgressFactory egressFactory;
    @FunctionalInterface
    interface EgressFactory { SessionEgress open(Cluster cluster, ClientSession session); }
    interface SessionEgress {
        long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length);
        void close();
    }
    /** 服务角色代次隔离旧leader的排队响应；Owner随FIFO角色事件更新自己的代次。 */
    private long epoch, ownerEpoch;
    /** Aeron日志通知的领导任期，仅服务线程用于标准egress头部。 */
    private long leadershipTermId;

    public ContinuousTradingClusterService(ProductLine productLine) {
        this(productLine, SessionResponsePublication::new);
    }

    ContinuousTradingClusterService(ProductLine productLine, EgressFactory egressFactory) {
        this.egressFactory = egressFactory;
        processor = new SurprisingClusteredService(productLine, this::publishResponse);
    }

    public void onStart(Cluster cluster, Image snapshot) {
        this.cluster = cluster;
        logContext = new OwnerLogContext(cluster.memberId(), cluster.timeUnit(), cluster.role(),
                cluster.time(), cluster.logPosition());
        byte[] restored = readSnapshot(snapshot);
        ownerThread = new Thread(this::runOwner, "trading-owner-" + cluster.memberId());
        ownerThread.start();
        boundary(() -> {
            processor.onStart(logContext, null);
            if (restored != null) processor.restoreSnapshot(restored);
            return null;
        }, false);
    }

    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer,
            int offset, int length, Header header) {
        flushSessionClosures();
        CoreMessage command;
        try { command = CoreMessageFlyweightDecoder.decode(buffer, offset, length); }
        catch (IllegalArgumentException invalid) { return; }
        enqueue(new Input(command, session == null ? null : transportSession(session),
                timestamp, header.position(), null));
        drainResponses();
    }

    public void onSessionOpen(ClientSession session, long timestamp) {
        checkFailure();
        if (session != null) transportSession(session);
        flushSessionClosures();
    }
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        deferred.remove(session.id());
        TransportSession transport = sessions.remove(session.id());
        if (transport != null) transport.disconnect();
        checkFailure();
    }
    /** 旧日志中的定时器不再驱动交易。 */
    public void onTimerEvent(long correlationId, long timestamp) { checkFailure(); }

    /** 仅发送Owner已经完成的不可变响应；不推进或读取交易状态。 */
    public int doBackgroundWork(long nowNs) {
        checkFailure();
        try { return drainResponses(); }
        catch (RuntimeException fatal) { throw new AgentTerminationException(fatal); }
    }

    public void onRoleChange(Cluster.Role role) {
        long next = ++epoch;
        deferred.clear();
        sessions.forEachValue(session -> {
            if (role == Cluster.Role.LEADER) session.connect();
            else session.disconnect();
        });
        boundary(() -> {
            logContext.role = role;
            ownerEpoch = next;
            processor.onRoleChange(role);
            return null;
        }, true);
    }

    public void onNewLeadershipTermEvent(long termId, long logPosition, long timestamp,
            long termBaseLogPosition, int leaderMemberId, int logSessionId,
            java.util.concurrent.TimeUnit timeUnit, int appVersion) {
        leadershipTermId = termId;
        checkFailure();
    }

    public void onTakeSnapshot(ExclusivePublication publication) {
        byte[] snapshot = captureSnapshot();
        UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
        long deadline = System.nanoTime() + DEADLINE_NS;
        for (int offset = 0; offset < snapshot.length;) {
            int length = Math.min(publication.maxPayloadLength(), snapshot.length - offset);
            long result = publication.offer(buffer, offset, length);
            if (result >= 0) offset += length;
            else {
                if (result != io.aeron.Publication.BACK_PRESSURED && result != io.aeron.Publication.ADMIN_ACTION
                        && result != io.aeron.Publication.NOT_CONNECTED)
                    throw new IllegalStateException("snapshot publication failed: " + result);
                awaitProgress(deadline);
            }
        }
    }

    byte[] captureSnapshot() {
        long position = cluster.logPosition(), timestamp = cluster.time();
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return processor.captureSnapshot(Math.max(1, position));
        }, true);
    }

    public void onTerminate(Cluster ignored) {
        if (ownerThread == null) return;
        try {
            if (failure == null && ownerThread.isAlive())
                boundary(() -> { stopping = true; return null; }, true);
        } finally {
            try { ownerThread.join(30_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (ownerThread.isAlive()) {
                ownerThread.interrupt();
                throw new IllegalStateException("trading owner did not terminate");
            }
            input.clear(); output.clear(); deferred.clear();
            sessions.forEachValue(TransportSession::disconnect);
            sessions.clear();
        }
    }

    private void runOwner() {
        var idle = new BackoffIdleStrategy(100, 10, 1_000, 100_000);
        boolean initialized = false;
        try {
            while (!stopping) {
                int work = 0;
                for (int i = 0; i < 64 && !stopping; i++) {
                    Input next = input.poll();
                    if (next == null) break;
                    if (next.action != null) { next.action.run(); initialized = true; }
                    else {
                        logContext.timestamp = next.timestamp;
                        logContext.position = next.position;
                        processor.acceptCommittedCommand(next.session, next.command, next.timestamp, next.position);
                        inputConsumed += next.command.payloadLength();
                    }
                    work++;
                }
                if (initialized && !stopping) work += processor.pollCommands();
                idle.idle(work);
            }
        } catch (Throwable fatal) { failure = fatal; }
        finally {
            try { processor.onTerminate(logContext); }
            catch (Throwable fatal) { if (failure == null) failure = fatal; }
        }
    }

    private void enqueue(Input event) {
        int bytes = event.command == null ? 0 : event.command.payloadLength();
        if (bytes > INPUT_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (bytes > INPUT_BYTES - (inputProduced - inputConsumed) || !input.offer(event))
            awaitProgress(deadline);
        inputProduced += bytes;
    }

    private <T> T boundary(Supplier<T> action, boolean finish) {
        var completion = new CompletableFuture<T>();
        enqueue(new Input(null, null, 0, 0, () -> {
            try {
                if (finish) processor.finishCommands();
                completion.complete(action.get());
            } catch (Throwable fatal) {
                completion.completeExceptionally(fatal);
                throw fatal;
            }
        }));
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!completion.isDone()) awaitProgress(deadline);
        return completion.join();
    }

    private void publishResponse(ClientSession session, DirectBuffer source, int length) {
        if (length > OUTPUT_BYTES - (outputProduced - outputConsumed) || output.remainingCapacity() == 0) {
            outputOverflow = true;
            return;
        }
        byte[] bytes = new byte[length];
        source.getBytes(0, bytes);
        if (!output.offer(new Output(session, bytes, ownerEpoch))) {
            outputOverflow = true;
            return;
        }
        outputProduced += length;
    }

    private int drainResponses() {
        int work = 0;
        if (outputOverflow) {
            outputOverflow = false;
            // 出口全局容量耗尽：关闭当前会话，客户端按commandId核对已提交终态。
            sessions.forEachValue(TransportSession::close);
            deferred.clear();
        }
        for (; work < 256; work++) {
            Output next = output.poll();
            if (next == null) break;
            outputConsumed += next.bytes.length;
            if (next.epoch != epoch || cluster.role() != Cluster.Role.LEADER) continue;
            sendBuffer.wrap(next.bytes);
            try { deferred.offer(next.session, sendBuffer, next.bytes.length, System.nanoTime()); }
            finally { sendBuffer.wrap(EMPTY); }
        }
        return work + deferred.poll(System.nanoTime(), 256);
    }

    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        drainResponses();
        cluster.idleStrategy().idle();
    }

    private void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    private TransportSession transportSession(ClientSession session) {
        TransportSession transport = sessions.get(session.id());
        if (transport == null) {
            transport = new TransportSession(session);
            sessions.put(session.id(), transport);
            if (cluster.role() == Cluster.Role.LEADER) transport.connect();
        }
        return transport;
    }

    private void flushSessionClosures() {
        if (!closeRequests) return;
        closeRequests = false;
        sessions.forEachValue(session -> {
            if (session.closeRequested && !session.actual.isClosing()) session.actual.close();
        });
    }

    /** 实际Aeron会话只在服务线程使用；Owner仅携带此不透明响应目的地。 */
    private final class TransportSession implements ClientSession {
        private final ClientSession actual;
        private boolean closeRequested;
        private SessionEgress egress;
        TransportSession(ClientSession actual) { this.actual = actual; }
        void connect() { if (egress == null) egress = egressFactory.open(cluster, actual); }
        void disconnect() { if (egress != null) { egress.close(); egress = null; } }
        public long id() { return actual.id(); }
        public int responseStreamId() { return actual.responseStreamId(); }
        public String responseChannel() { return actual.responseChannel(); }
        public byte[] encodedPrincipal() { return actual.encodedPrincipal(); }
        public boolean isClosing() { return closeRequested || actual.isClosing(); }
        public void close() { closeRequested = true; closeRequests = true; }
        public long offer(DirectBuffer buffer, int offset, int length) {
            return egress == null ? io.aeron.Publication.NOT_CONNECTED
                    : egress.offer(leadershipTermId, cluster.time(), buffer, offset, length);
        }
        public long offer(io.aeron.DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        public long tryClaim(int length, io.aeron.logbuffer.BufferClaim claim) { throw new UnsupportedOperationException(); }
    }

    private byte[] readSnapshot(Image snapshot) {
        if (snapshot == null) return null;
        var bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!snapshot.isEndOfStream()) {
            int work = snapshot.poll((buffer, offset, length, header) -> {
                SurprisingClusteredService.ensureSnapshotCapacity(bytes.size(), length);
                byte[] chunk = new byte[length];
                buffer.getBytes(offset, chunk);
                bytes.writeBytes(chunk);
            }, 10);
            if (System.nanoTime() > deadline) throw new IllegalStateException("snapshot recovery deadline");
            cluster.idleStrategy().idle(work);
        }
        return bytes.toByteArray();
    }

    /** 输入信封有唯一Owner消费者；action只用于低频生命周期边界。 */
    private record Input(CoreMessage command, ClientSession session, long timestamp, long position, Runnable action) {}
    /** 跨线程结果在发布后不可变，消费后释放编码字节。 */
    private record Output(ClientSession session, byte[] bytes, long epoch) {}
}
