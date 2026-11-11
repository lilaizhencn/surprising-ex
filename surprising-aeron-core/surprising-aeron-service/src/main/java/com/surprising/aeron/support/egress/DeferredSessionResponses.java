package com.surprising.aeron.support.egress;

import com.surprising.aeron.support.egress.ClusterServiceEgress.TransportSession;
import io.aeron.Publication;
import java.util.ArrayDeque;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 交易步骤 6：Cluster 回调线程独占的已编码响应重试队列。 */
final class DeferredSessionResponses {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESPONSES = 8192;
    private static final long TIMEOUT_NS = 1_000_000_000L;
    /** 按会话 ID 保存本地重试队列，只有回调线程访问。 */
    private final LongObjectHashMap<SessionQueue> sessions = new LongObjectHashMap<>();
    private SessionQueue cursor;
    private int bytes, size;
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);

    /** 目标已关闭则清其重试队列。只有可重试背压才按当前单调时间复制字节入队，同会话有积压则直接排尾避免乱序。 */
    void offer(TransportSession session, DirectBuffer source, int length) {
        offer(session, source, length, System::nanoTime);
    }

    /** 共用首次发送与入队流程。 */
    void offer(TransportSession session, DirectBuffer source, int length, java.util.function.LongSupplier clock) {
        if (session.isClosing()) {
            if (size != 0) remove(session.id());
            return;
        }
        SessionQueue queue = size == 0 ? null : sessions.get(session.id());
        if (queue == null) {
            long result = session.offer(source, 0, length);
            if (result >= 0) return;
            if (!retryable(result)) { session.close(); return; }
        }
        enqueueDeferred(session, queue, source, length, clock.getAsLong());
    }

    private void enqueueDeferred(
            TransportSession session, SessionQueue queue, DirectBuffer source, int length, long nowNs) {
        if (length > MAX_BYTES - bytes || size == MAX_RESPONSES) {
            // 这里只处理出口容量和会话，不能回滚已提交资金，也不能生成新的业务拒绝。
            remove(session.id());
            session.close();
            return;
        }
        if (queue == null) {
            queue = new SessionQueue(session);
            sessions.put(session.id(), queue);
            if (cursor == null) { cursor = queue; queue.next = queue.previous = queue; }
            else {
                queue.next = cursor; queue.previous = cursor.previous;
                cursor.previous.next = queue; cursor.previous = queue;
            }
        }
        byte[] encoded = new byte[length];
        source.getBytes(0, encoded);
        queue.responses.addLast(new Response(encoded, nowNs + TIMEOUT_NS));
        bytes += length;
        size++;
    }

    /** 以本轮初始会话数和预算为上限轮询，每次只试一个会话的队首。过期或不可重试错误关闭该会话，成功则扣容量并释放该条。 */
    int poll(long nowNs, int budget) {
        int work = 0;
        int attempts = Math.min(budget, sessions.size());
        for (int attempt = 0; attempt < attempts && cursor != null; attempt++) {
            SessionQueue queue = cursor;
            cursor = queue.next;
            Response response = queue.responses.peekFirst();
            if (queue.session.isClosing() || nowNs - response.deadlineNs >= 0) {
                if (!queue.session.isClosing()) queue.session.close();
                remove(queue.session.id());
                work++;
                continue;
            }
            buffer.wrap(response.encoded);
            long result;
            try { result = queue.session.offer(buffer, 0, response.encoded.length); }
            finally { buffer.wrap(EMPTY); }
            if (result >= 0) {
                work++;
                queue.responses.removeFirst();
                bytes -= response.encoded.length;
                size--;
                if (queue.responses.isEmpty()) remove(queue.session.id());
            } else if (!retryable(result)) {
                work++;
                queue.session.close();
                remove(queue.session.id());
            }
        }
        return work;
    }

    /** 按会话 ID 断开循环链表并丢弃其全部编码响应，同时返还本地字节和条数预算。 */
    void remove(long sessionId) {
        SessionQueue queue = sessions.remove(sessionId);
        if (queue == null) return;
        if (queue.next == queue) cursor = null;
        else {
            queue.previous.next = queue.next;
            queue.next.previous = queue.previous;
            if (cursor == queue) cursor = queue.next;
        }
        queue.next = queue.previous = null;
        discard(queue);
    }

    /** 清空本地会话索引、轮询游标、容量计数和临时缓冲引用。不在此逐一关闭实际会话或回滚已提交业务。 */
    void clear() { sessions.clear(); cursor = null; bytes = size = 0; buffer.wrap(EMPTY); }
    /** 返回当前全部会话保留的已编码响应条数。 */
    int size() { return size; }

    private void discard(SessionQueue queue) {
        for (Response response : queue.responses) { bytes -= response.encoded.length; size--; }
        queue.responses.clear();
    }

    private static boolean retryable(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }

    private static final byte[] EMPTY = new byte[0];
    /** 一个慢会话的传输句柄、响应 FIFO 和轮询链表节点，队列耗尽、关闭或超时后从本地结构移除。响应 FIFO 和循环链表随后由原回调线程维护。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class SessionQueue {
        /** 该队列对应的具体传输会话，只有回调线程操作。 */
        final TransportSession session;
        SessionQueue previous, next;
        final ArrayDeque<Response> responses = new ArrayDeque<>();
    }
    /** 复制后的单条编码响应和固定发送期限。 */
    private record Response(byte[] encoded, long deadlineNs) {}
}
