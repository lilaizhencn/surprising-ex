package com.surprising.aeron.service.execution;

import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import java.util.ArrayDeque;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** 已提交响应的有界重试队列；仅集群日志回调访问，不参与资金状态或命令重放。 */
final class DeferredSessionResponses {
    /** 慢出口最多占用的编码字节数，避免把客户端背压转成无限堆增长。 */
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 全部会话合计最多保留的响应数。 */
    private static final int MAX_RESPONSES = 8192;
    /** 维持原有一秒出口期限，但不让交易线程在期限内循环等待。 */
    private static final long TIMEOUT_NS = 1_000_000_000L;
    /** 每个会话保持自己的 FIFO，慢会话不阻塞其他会话。 */
    private final LongObjectHashMap<SessionQueue> sessions = new LongObjectHashMap<>();
    /** 循环链表的下一会话，实现有界公平轮询，不扫描已关闭会话。 */
    private SessionQueue cursor;
    /** 当前保留的编码字节数与消息数，仅 owner 修改。 */
    private int bytes, size;
    /** 复用的发送包装器，只在一次 offer 调用期间引用消息。 */
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);

    void offer(ClientSession session, DirectBuffer source, int length, long nowNs) {
        if (session.isClosing()) { remove(session.id()); return; }
        SessionQueue queue = sessions.get(session.id());
        if (queue == null) {
            long result = session.offer(source, 0, length);
            if (result >= 0) return;
            if (!retryable(result)) { session.close(); return; }
        }
        if (length > MAX_BYTES - bytes || size == MAX_RESPONSES) {
            // Execution is already terminal. The gateway must reconcile by commandId after reconnect.
            // Never roll back committed funds or report a new business rejection here.
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

    int poll(long nowNs, int budget) {
        int work = 0;
        int attempts = Math.min(budget, sessions.size());
        while (work < attempts && cursor != null) {
            SessionQueue queue = cursor;
            cursor = queue.next;
            Response response = queue.responses.peekFirst();
            work++;
            if (queue.session.isClosing() || nowNs - response.deadlineNs >= 0) {
                if (!queue.session.isClosing()) queue.session.close();
                remove(queue.session.id());
                continue;
            }
            buffer.wrap(response.encoded);
            long result;
            try { result = queue.session.offer(buffer, 0, response.encoded.length); }
            finally { buffer.wrap(EMPTY); }
            if (result >= 0) {
                queue.responses.removeFirst();
                bytes -= response.encoded.length;
                size--;
                if (queue.responses.isEmpty()) remove(queue.session.id());
            } else if (!retryable(result)) {
                queue.session.close();
                remove(queue.session.id());
            }
        }
        return work;
    }

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

    void clear() { sessions.clear(); cursor = null; bytes = size = 0; buffer.wrap(EMPTY); }
    int size() { return size; }

    private void discard(SessionQueue queue) {
        for (Response response : queue.responses) { bytes -= response.encoded.length; size--; }
        queue.responses.clear();
    }

    private static boolean retryable(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }

    /** 空包装器，清除最后一条响应的强引用。 */
    private static final byte[] EMPTY = new byte[0];
    /** 一个慢会话的 FIFO；会话关闭、期限到达或发送完毕后释放。 */
    private static final class SessionQueue {
        /** 会话句柄只能在允许调用 ClientSession 的集群回调内使用。 */
        final ClientSession session;
        /** 活跃会话的循环链表，由同一 owner 维护。 */
        SessionQueue previous, next;
        /** 仅保存尚未发送的终态响应，头部完成前不会发送同会话后续结果。 */
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        SessionQueue(ClientSession session) { this.session = session; }
    }
    /** 不可变已编码结果及本地发送期限；不携带可变交易对象。 */
    private record Response(byte[] encoded, long deadlineNs) {}
}
