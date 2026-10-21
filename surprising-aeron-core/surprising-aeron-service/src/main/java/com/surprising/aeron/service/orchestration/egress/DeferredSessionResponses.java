package com.surprising.aeron.service.orchestration.egress;

import com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress.TransportSession;
import io.aeron.Publication;
import java.util.ArrayDeque;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 交易步骤 6：Cluster 回调线程独占的已编码响应重试队列。每个慢会话维持 FIFO，会话之间按循环链表公平轮询；只保留发送所需字节和本地期限，不参与账户、资金或命令回放。
 */
final class DeferredSessionResponses {
    /** 全部慢会话最多保留十六 MiB 已编码字节。 */
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 全部会话合计最多保留八千一百九十二条响应。 */
    private static final int MAX_RESPONSES = 8192;
    /** 每条重试响应从入队起一秒的本地发送预算。 */
    private static final long TIMEOUT_NS = 1_000_000_000L;
    /** 按会话 ID 保存本地重试队列，只有回调线程访问。 */
    private final LongObjectHashMap<SessionQueue> sessions = new LongObjectHashMap<>();
    /** 下一轮尝试的循环链表节点，不扫描已经移除的会话。 */
    private SessionQueue cursor;
    /**
     * bytes：当前全部待重试响应占用的编码字节。
     * size：当前全部待重试响应条数。
     */
    private int bytes, size;
    /** 单次重试发送借用的可复用缓冲包装器，调用结束立即解绑。 */
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);

    /**
     * 目标已关闭则清其重试队列；没有积压时先尝试发送一次。只有可重试背压才按当前单调时间复制字节入队，同会话有积压则直接排尾避免乱序。
     * @param session 本条响应目标的实际传输句柄
     * @param source 从偏移零开始含有效响应的原编码缓冲
     * @param length 当前要发送或复制的有效编码字节数
     */
    void offer(TransportSession session, DirectBuffer source, int length) {
        if (session.isClosing()) {
            if (size != 0) remove(session.id());
            return;
        }
        // 当前会话重试队列或轮询节点，空表示需新建
        SessionQueue queue = size == 0 ? null : sessions.get(session.id());
        if (queue == null) {
            // 一次发送尝试的返回值，决定成功、继续背压或关闭会话
            long result = session.offer(source, 0, length);
            if (result >= 0) return;
            if (!retryable(result)) { session.close(); return; }
        }
        enqueueDeferred(session, queue, source, length, System.nanoTime());
    }

    /**
     * 按调用方给定的纳秒时间执行同样的首次发送与入队逻辑，便于测试确定地控制过期边界。
     * @param session 本条响应目标的实际传输句柄
     * @param source 从偏移零开始含有效响应的原编码缓冲
     * @param length 当前要发送或复制的有效编码字节数
     * @param nowNs 本轮单调纳秒时间，用于计算或检查本地发送期限
     */
    void offer(TransportSession session, DirectBuffer source, int length, long nowNs) {
        if (session.isClosing()) {
            if (size != 0) remove(session.id());
            return;
        }
        // 当前会话重试队列或轮询节点，空表示需新建
        SessionQueue queue = size == 0 ? null : sessions.get(session.id());
        if (queue == null) {
            // 一次发送尝试的返回值，决定成功、继续背压或关闭会话
            long result = session.offer(source, 0, length);
            if (result >= 0) return;
            if (!retryable(result)) { session.close(); return; }
        }
        enqueueDeferred(session, queue, source, length, nowNs);
    }

    /**
     * 检查全局字节和条数上限，超限仅移除该会话积压并请求关闭；可容纳时建立或复用会话队列，复制有效编码字节并保存单条发送期限。
     * @param session 本条响应目标的实际传输句柄
     * @param queue 该会话已有的可空队列；丢弃入口要求实际待清理队列
     * @param source 从偏移零开始含有效响应的原编码缓冲
     * @param length 当前要发送或复制的有效编码字节数
     * @param nowNs 本轮单调纳秒时间，用于计算或检查本地发送期限
     */
    private void enqueueDeferred(
            TransportSession session, SessionQueue queue, DirectBuffer source, int length, long nowNs) {
        if (length > MAX_BYTES - bytes || size == MAX_RESPONSES) {
            // 交易已经到达终态；网关重连后应按 commandId 查询核对结果。
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
        // 从原编码缓冲复制的独立有效字节数组
        byte[] encoded = new byte[length];
        source.getBytes(0, encoded);
        queue.responses.addLast(new Response(encoded, nowNs + TIMEOUT_NS));
        bytes += length;
        size++;
    }

    /**
     * 以本轮初始会话数和预算为上限轮询，每次只试一个会话的队首；过期或不可重试错误关闭该会话，成功则扣容量并释放该条。持续背压不计成功工作量，也不阻塞其他会话。
     * @param nowNs 本轮单调纳秒时间，用于计算或检查本地发送期限
     * @param budget 本轮最多尝试的会话队首数量
     */
    int poll(long nowNs, int budget) {
        // 本轮发送成功或清理失败会话的工作次数
        int work = 0;
        // 按预算和轮询开始时会话数确定的尝试次数上限
        int attempts = Math.min(budget, sessions.size());
        for (/* 本轮已尝试的会话队首计数 */ int attempt = 0; attempt < attempts && cursor != null; attempt++) {
            // 本轮从循环游标取出的实际会话队列
            SessionQueue queue = cursor;
            cursor = queue.next;
            // 当前待发送或丢弃的队首编码响应
            Response response = queue.responses.peekFirst();
            if (queue.session.isClosing() || nowNs - response.deadlineNs >= 0) {
                if (!queue.session.isClosing()) queue.session.close();
                remove(queue.session.id());
                work++;
                continue;
            }
            buffer.wrap(response.encoded);
            // 一次发送尝试的返回值，决定成功、继续背压或关闭会话
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

    /**
     * 按会话 ID 断开循环链表并丢弃其全部编码响应，同时返还本地字节和条数预算；会话不存在则无事可做。
     * @param sessionId 要清除本地重试状态的会话 ID
     */
    void remove(long sessionId) {
        // 从索引移除的可空会话队列，空表示没有本地重试状态
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

    /**
     * 清空本地会话索引、轮询游标、容量计数和临时缓冲引用；不在此逐一关闭实际会话或回滚已提交业务。
     */
    void clear() { sessions.clear(); cursor = null; bytes = size = 0; buffer.wrap(EMPTY); }
    /**
     * 返回当前全部会话保留的已编码响应条数。
     */
    int size() { return size; }

    /**
     * 扣除指定队列里每条响应占用的本地预算，再清空该队列，不访问账户状态。
     * @param queue 该会话已有的可空队列；丢弃入口要求实际待清理队列
     */
    private void discard(SessionQueue queue) {
        for (Response /* 当前逐项丢弃的编码响应，逐条返还其占用预算 */ response : queue.responses) { bytes -= response.encoded.length; size--; }
        queue.responses.clear();
    }

    /**
     * 只允许背压、管理动作和尚未连接三类 Publication 返回值进入重试；其他负值按会话发送失败处理。
     * @param result Publication 风格的实际发送返回值
     */
    private static boolean retryable(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }

    /** 解绑临时发送包装器的共享空数组。 */
    private static final byte[] EMPTY = new byte[0];
    /**
     * 一个慢会话的传输句柄、响应 FIFO 和轮询链表节点，队列耗尽、关闭或超时后从本地结构移除。
     */
    private static final class SessionQueue {
        /** 该队列对应的具体传输会话，只有回调线程操作。 */
        final TransportSession session;
        /**
         * previous：活跃重试会话循环链表的前一个节点。
         * next：活跃重试会话循环链表的后一个节点。
         */
        SessionQueue previous, next;
        /** 该会话尚未成功发送的终态字节 FIFO，先完成队首再试后继。 */
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        /**
         * 保存一个具体传输句柄；响应 FIFO 和循环链表随后由原回调线程维护。
         * @param session 本条响应目标的实际传输句柄
         */
        SessionQueue(TransportSession session) { this.session = session; }
    }
    /**
     * 复制后的单条编码响应和固定发送期限；记录引用不变，字节数组仅由本地发送边界持有，不再借用交易对象。
     * @param encoded 本地复制的有效编码字节，不再引用交易响应缓冲
     * @param deadlineNs 入队时固定的单调纳秒发送截止时间
     */
    private record Response(byte[] encoded, long deadlineNs) {}
}
