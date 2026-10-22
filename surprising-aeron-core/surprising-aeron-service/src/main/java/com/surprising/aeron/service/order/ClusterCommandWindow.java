package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import lombok.Getter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * Owner 独占的有界响应 FIFO，记录已准入命令的会话、路由和提交元数据。入口依赖只看同 userId 的旧命令与撤单引用的精确订单号；Lane 位和 Matcher 分片是提交路由，不用于推测对手账户。条目解码槽和候选解码器交换复用，旧索引通过队首绝对水位失效，不保存另一份订单簿或资金事实。
 */
public final class ClusterCommandWindow {
    /** 未配置系统属性时的入口窗口容量二百五十六。 */
    public static final int DEFAULT_CAPACITY = 256;
    /** 一条准入命令允许登记的最大精确订单数，取两类批量命令上限较大值。 */
    private static final int MAX_TRACKED_ORDER_IDS = Math.max(
            PlaceOrderBatchCommand.MAX_ORDERS, CancelOrderBatchCommand.MAX_ORDERS);

    /** 保留的四千零九十六常量，当前构造未读取它，实际阈值按窗口容量计算。 */
    private static final int ORDER_INDEX_ROTATION_THRESHOLD = 4096;

    /**
     * 入口依赖原因，按精确订单和同用户旧命令区分；ACCOUNT 不代表所有同 Lane 用户相互冲突。
     */
    public enum Conflict {
        /** 当前没有需要退休的依赖前缀。 */
        NONE,
        /** 需要先退休引用同一订单的旧命令前缀。 */
        ORDER,
        /** 同一用户的旧命令形成更长依赖前缀。 */
        ACCOUNT
    }

    /**  最近计算的当前候选阻塞原因。 返回最近一次冲突计算的原因，resetCandidate 和下一次计算会重置。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private Conflict conflict = Conflict.NONE;
    /** 固定环形条目数组，各条目持有可交换复用的解码槽。 */
    private final Entry[] entries;
    /** 容量减一，用于物理下标环绕。 */
    private final int indexMask;
    /** 当前 FIFO 队首物理下标。 */
    private int head;
    /**  尚未退休的条目数。 返回当前 FIFO 尚未退休的条目数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    /** 当前有效队首的绝对位置水位，初始一，小于它的索引记录视为过期。 */
    private long headAbsSeq = 1L;
    /** 每次 add 增加的尾部绝对位置，不是 Core 命令序号。 */
    private long tailAbsSeq;
    /**  最后一次非零绑定且尚未退休的 Core 序号。 返回窗口中最后一次绑定非零 Core 序号且尚未退休的记录，不计算数值最大值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long lastMatchingSequence;
    /** 最后非零绑定条目对应的窗口绝对位置，用于退休后失效。 */
    private long lastMatchingAbsSeq;

    /** 用户号到最近窗口绝对位置的派生索引，只用于同用户依赖判断。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap userLastAbsSeq;
    /** 当前代订单号到最近准入位置的派生索引。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap activeOrderLastAbsSeq;
    /** 上一代仍可能含有效旧命令的订单位置索引。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap previousOrderLastAbsSeq;
    /** 由订单 Map 初始容量确定的轮换写入次数阈值。 */
    private final int orderIndexRotationThreshold;
    /** 上一代可能包含的最大绝对位置，队首越过后才可清掉轮换。 */
    private long previousOrderMaxAbsSeq;
    /** 当前代订单登记写入次数，重复键写入也累计。 */
    private int activeOrderInserts;

    /** 下一入口准备使用的用户号。 */
    private long candidateUser;
    /** 候选提交用户的 Lane 位值，仅作为路由保存。 */
    private long candidateUserLaneBit;
    /** 候选静态 Matcher 分片，未路由时负一。 */
    private int candidateMatcherShard = -1;
    /** 候选静态路由是否已保存。 */
    private boolean candidateRouted;
    /** 产生候选路由时的解码来源请求引用，防止旧解码被误用于新请求。 */
    private CoreMessage routedSource;
    /** 当前解码缓存对应的请求对象引用。 */
    private CoreMessage decodedSource;
    /** 当前可用解码值，可能已交换给窗口条目。 */
    private DecodedMatchingCommand decoded;
    /** Owner 准备下一入口时使用的可复用解码槽，准入时与 Entry 备用槽交换。 */
    private DecodedMatchingCommand candidateDecoder = DecodedMatchingCommand.commandSlot();

    /**
     * 从系统属性读取窗口容量，未配置默认二百五十六，再按显式容量构造；非法数字直接抛解析异常。
     */
    public ClusterCommandWindow() {
        this(Integer.parseInt(System.getProperty("surprising.aeron.owner-command-window",
                Integer.toString(DEFAULT_CAPACITY))));
    }

    /**
     * 要求容量是六十四至四千零九十六之间的二次幂，预分配条目及解码槽，按容量配置用户/双代订单索引初始容量；这些 Map 可自行扩容，不等于固定数组上限。
     * @param capacity 六十四至四千零九十六之间的二次幂容量
     */
    public ClusterCommandWindow(int capacity) {
        if (capacity < 64 || capacity > 4096 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("owner command window must be a power of two in [64,4096]");
        entries = new Entry[capacity];
        indexMask = capacity - 1;
        // 按窗口容量确定的用户依赖索引初始容量。
        int userMapCapacity = Math.max(512, capacity << 1);
        // 按窗口容量确定的订单索引初始容量及轮换阈值。
        int orderMapCapacity = Math.max(4096, capacity << 4);
        userLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(userMapCapacity);
        activeOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        previousOrderLastAbsSeq = new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(orderMapCapacity);
        orderIndexRotationThreshold = orderMapCapacity;
        for (/* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    /**
     * 设置下一入口用户并清掉候选路由及冲突标记；保留已解码请求，便于阻塞队首重试不重复解码。
     * @param userId 下一候选提交用户号
     */
    public void resetCandidate(long userId) {
        candidateUser = userId;
        candidateUserLaneBit = 0;
        candidateMatcherShard = -1;
        candidateRouted = false;
        routedSource = null;
        conflict = Conflict.NONE;
    }

    /**
     * 保存当前用户的静态 Matcher 分片和非零 Lane 位，并关联当时 decodedSource；只检查用户为正、分片非负及位值非零，不核验拓扑上界或恰好一位。
     * @param matcherShard 调用方确定的非负静态 Matcher 分片号
     * @param userLaneBit 调用方提供的非零用户 Lane 位值
     */
    public void route(int matcherShard, long userLaneBit) {
        if (candidateUser <= 0 || matcherShard < 0 || userLaneBit == 0)
            throw new IllegalArgumentException("invalid ingress route");
        routedSource = decodedSource;
        candidateMatcherShard = matcherShard;
        candidateUserLaneBit = userLaneBit;
        candidateRouted = true;
    }

    /**
     * 返回当前候选是否已保存静态路由。
     */
    public boolean hasRoute() { return candidateRouted; }
    /**
     * 已路由时返回候选用户，否则返回零。
     */
    public long routeUserId() { return candidateRouted ? candidateUser : 0; }
    /**
     * 已路由时返回候选 Matcher 分片，否则返回负一。
     */
    public int routeMatcherShard() { return candidateRouted ? candidateMatcherShard : -1; }
    /**
     * 已路由时返回调用方保存的用户 Lane 位值，否则返回零。
     */
    public long routeUserLaneBit() { return candidateRouted ? candidateUserLaneBit : 0; }

    /**
     * 重新计算必须退休的依赖前缀，非零即表示当前入口需要等待，同时更新 conflict。
     */
    public boolean conflicts() { return conflictingPrefixSize() != 0; }

    /**
     * 计算撤单精确订单依赖前缀，再比较同 userId 最近命令的位置；用户前缀更长才标 ACCOUNT，否则有订单依赖则标 ORDER。不同用户即使路由同一 Lane，也不会仅凭 Lane 位在这里互斥。
     */
    public int conflictingPrefixSize() {
        conflict = Conflict.NONE;
        if (size == 0) return 0;
        // 由撤单精确订单依赖确定的前缀长度。
        int exactPrefix = exactOrderPrefix(0);
        // 有静态路由时的候选用户号，否则零，不检查同 Lane 的其他用户。
        long candidateUserId = candidateRouted ? candidateUser : 0;
        if (candidateUserId != 0) {
            // 该用户最近准入位置，低于队首水位时已过期。
            long userAbsSeq = userLastAbsSeq.get(candidateUserId);
            if (userAbsSeq >= headAbsSeq) {
                // 覆盖该用户旧命令所需退休的前缀长度。
                int userPrefix = (int) (userAbsSeq - headAbsSeq + 1L);
                if (userPrefix > exactPrefix) {
                    conflict = Conflict.ACCOUNT;
                    return userPrefix;
                }
            }
        }
        if (exactPrefix != 0) conflict = Conflict.ORDER;
        return exactPrefix;
    }

    /**
     * 只在路由与当前解码请求仍按对象身份相同时，检查 CANCEL_ORDER 或 CANCEL_ORDER_BATCH 引用的订单；其他类型不增加精确订单依赖。
     * @param prefix 已找到的依赖前缀长度
     */
    private int exactOrderPrefix(int prefix) {
        if (routedSource == null || routedSource != decodedSource || decoded == null || size == 0) return prefix;
        // 当前解码请求的协议消息类型。
        CoreMessageType type = decodedSource.header().messageType();
        switch (type) {
            case CANCEL_ORDER -> prefix = maxOrderPrefix(prefix, decoded.cancelOrder().orderId());
            case CANCEL_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.cancelOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    prefix = maxOrderPrefix(prefix, orders.get(i).orderId());
                }
            }
            default -> { }
        }
        return prefix;
    }

    /**
     * 查当前和仍有效的上一代订单索引，若该订单旧命令尚在窗口，返回覆盖它的更长 FIFO 前缀；非正订单号直接跳过。
     * @param prefix 已找到的依赖前缀长度
     * @param orderId 需要查找或登记的精确订单编号
     */
    private int maxOrderPrefix(int prefix, long orderId) {
        if (orderId <= 0) return prefix;
        // 从当前代或仍有效上一代索引读到的旧订单命令位置。
        long orderAbsSeq = activeOrderLastAbsSeq.get(orderId);
        if (orderAbsSeq < headAbsSeq && previousOrderMaxAbsSeq >= headAbsSeq) {
            orderAbsSeq = previousOrderLastAbsSeq.get(orderId);
        }
        if (orderAbsSeq >= headAbsSeq) {
            return Math.max(prefix, (int) (orderAbsSeq - headAbsSeq + 1L));
        }
        return prefix;
    }

    /**
     * 按请求对象身份缓存解码，换请求时使用候选预分配槽 decodeInto，同一请求重试直接返回；不按消息内容比较是否相同。
     * @param request 需要解码或加入窗口的完整 Core 请求
     */
    public DecodedMatchingCommand decoded(CoreMessage request) {
        if (decodedSource != request) {
            decodedSource = request;
            decoded = null;
        }
        if (decoded == null) {
            candidateDecoder.decodeInto(request);
            decoded = candidateDecoder;
        }
        return decoded;
    }

    /**
     * 只有请求对象与缓存来源相同才返回已解码引用，不触发新解码。
     * @param request 需要解码或加入窗口的完整 Core 请求
     */
    public DecodedMatchingCommand decodedIfPresent(CoreMessage request) {
        return decodedSource == request ? decoded : null;
    }

    /**
     * 若解码器仍归候选就清它，若已交换给条目则保留条目内容；清除当前来源和路由来源关联。
     */
    public void releaseDecoded() {
        if (decoded == candidateDecoder) candidateDecoder.clearCommandSlot();
        if (routedSource == decodedSource) routedSource = null;
        decodedSource = null;
        decoded = null;
    }

    /**
     * 仅当当前阻塞队首仍是同一请求对象时保留缓存，否则释放候选解码关联。
     * @param pendingHead 当前仍被阻塞的入口队首对象，空表示不再保留
     */
    public void retainDecoded(CoreMessage pendingHead) {
        if (pendingHead == null || decodedSource != pendingHead) releaseDecoded();
    }

    /**
     * 检查队满和候选路由存在后，填入尾条目、登记用户及当前解码中的订单索引，并把候选解码槽与条目备用槽交换。调用方保证 request 与准备的路由/解码对应；内部后续异常不自动回滚已写字段。
     * @param sessionId 最终响应的目标 Cluster 会话号
     * @param request 需要解码或加入窗口的完整 Core 请求
     * @param timestamp 该命令在 Cluster 中的业务时间
     * @param position 该命令的 Cluster 日志位置
     */
    public Entry add(long sessionId, CoreMessage request, long timestamp, long position) {
        if (size == entries.length) throw new IllegalStateException("cluster command window is full");
        if (!candidateRouted) throw new IllegalStateException("ingress route is missing");
        // 当前填充或清理的已有窗口条目。
        Entry entry = get(size++);
        // 新尾条目分配的绝对位置。
        long absSeq = ++tailAbsSeq;
        entry.absSeq = absSeq;
        entry.sessionId = sessionId;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.routeUserId = candidateUser;
        entry.routeMatcherShard = candidateMatcherShard;
        entry.routeUserLaneBit = candidateUserLaneBit;
        entry.routePresent = true;
        // 新尾条目对应的环形数组物理下标。
        int physical = (head + size - 1) & indexMask;
        userLastAbsSeq.put(candidateUser, absSeq);
        indexTrackedOrderIds(absSeq);
        if (decodedSource == request && decoded == candidateDecoder) {
            // 条目原来的备用解码槽，交换后交还候选使用。
            var spare = entry.decodedSlot;
            entry.decodedSlot = candidateDecoder;
            candidateDecoder = spare;
        }
        entry.physicalSlot = physical;
        return entry;
    }

    /**
     * 对当前解码的单笔/批量下单或撤单逐订单登记；写入次数达到阈值且上一代已全失效时轮换两张索引表，不给每条命令另存订单号数组。其他命令类型不登记精确订单键。
     * @param absSeq 当前准入条目的窗口绝对位置
     */
    private void indexTrackedOrderIds(long absSeq) {
        if (routedSource == null || routedSource != decodedSource || decoded == null) return;
        if (activeOrderInserts >= orderIndexRotationThreshold && headAbsSeq > previousOrderMaxAbsSeq) {
            previousOrderLastAbsSeq.clear();
            // 已清空的上一代订单索引，轮换后作为新当前代复用。
            var swap = previousOrderLastAbsSeq;
            previousOrderLastAbsSeq = activeOrderLastAbsSeq;
            activeOrderLastAbsSeq = swap;
            previousOrderMaxAbsSeq = absSeq - 1L;
            activeOrderInserts = 0;
        }
        // 本次命令已登记的精确订单数量。
        int count = 0;
        switch (decodedSource.header().messageType()) {
            case PLACE_ORDER -> count = trackOrderId(absSeq, count, decoded.placeOrder().orderId());
            case CANCEL_ORDER -> count = trackOrderId(absSeq, count, decoded.cancelOrder().orderId());
            case PLACE_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.placeOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            case CANCEL_ORDER_BATCH -> {
                // 当前批量下单或撤单中已经解码的订单项。
                var orders = decoded.cancelOrderBatch().orders();
                for (/* 当前批量订单的固定数量，循环内不重复读取。 */ /* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0, n = orders.size(); i < n; i++) {
                    count = trackOrderId(absSeq, count, orders.get(i).orderId());
                }
            }
            default -> { }
        }
    }

    /**
     * 跳过非正订单号，按本命令累计数量检查上限后记录该订单最新绝对位置；重复键也会增加插入计数。
     * @param absSeq 当前准入条目的窗口绝对位置
     * @param count 本条命令已经登记的订单身份数量，退休入口则为待移除前缀数量
     * @param orderId 需要查找或登记的精确订单编号
     */
    private int trackOrderId(long absSeq, int count, long orderId) {
        if (orderId <= 0) return count;
        if (count >= MAX_TRACKED_ORDER_IDS) throw new IllegalArgumentException("too many tracked order IDs");
        activeOrderLastAbsSeq.put(orderId, absSeq);
        activeOrderInserts++;
        return count + 1;
    }

    /**
     * 给已有物理条目绑定非负 Core 序号，零表示无需记录异步撮合水位；非零同时更新最后一次撮合记录。不在此核对条目是否属于本窗口或仍是当前请求。
     * @param entry 已经加入窗口的条目对象
     * @param sequence 要绑定的非负 Core 序号，零不更新最近撮合记录
     */
    public void bindSequence(Entry entry, long sequence) {
        if (entry == null || entry.physicalSlot < 0 || sequence < 0)
            throw new IllegalArgumentException("invalid command window sequence binding");
        entry.sequence = sequence;
        if (sequence == 0) return;
        lastMatchingSequence = sequence;
        lastMatchingAbsSeq = entry.absSeq;
    }

    /**
     * 返回固定条目数组容量。
     */
    public int capacity() { return entries.length; }
    /**
     * 按队首相对偏移与掩码取得条目，不检查 index 是否在当前 size 范围内；调用方只传有效相对下标。
     * @param index 相对 FIFO 队首的有效条目偏移
     */
    public Entry get(int index) { return entries[(head + index) & indexMask]; }

    /**
     * 退休当前全部条目，清候选路由与解码关联；绝对序号继续累计，索引按水位及既有清理条件失效，不重置所有历史字段。
     */
    public void clear() {
        removePrefix(size);
        resetCandidate(0);
        releaseDecoded();
    }

    /**
     * 退休给定数量队首：清条目引用、归还请求解码槽并推进绝对水位；最近撮合记录已出队时清除。用户索引只在窗口空且条目数超过阈值时清表，订单索引不逐键删除。
     * @param count 本条命令已经登记的订单身份数量，退休入口则为待移除前缀数量
     */
    public void removePrefix(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("invalid window prefix");
        for (/* 当前预分配、批量订单遍历或退休前缀的下标。 */ int i = 0; i < count; i++) {
            // 当前填充或清理的已有窗口条目。
            Entry entry = get(i);
            entry.decodedSlot.clearCommandSlot();
            entry.sessionId = NO_SESSION_ID;
            if (decodedSource == entry.request) releaseDecoded();
            if (entry.request != null) entry.request.releaseDecoderSlot();
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = entry.absSeq = 0;
            entry.routeUserId = entry.routeUserLaneBit = 0;
            entry.routeMatcherShard = -1;
            entry.routePresent = false;
            entry.physicalSlot = -1;
        }
        head = (head + count) & indexMask;
        size -= count;
        headAbsSeq += count;
        if (lastMatchingAbsSeq < headAbsSeq) {
            lastMatchingSequence = 0;
            lastMatchingAbsSeq = 0;
        }
        if (size == 0 && userLastAbsSeq.size() > orderIndexRotationThreshold) {
            userLastAbsSeq.clear();
        }
    }

    /**
     * 固定响应窗口条目，保存该请求的路由、会话、序号和解码租约，退休后原位清理并复用，不是永久命令历史。
     */
    public static final class Entry {
        /** 当前条目拥有的解码槽，退休时清理，下一次准入可与候选交换。 */
        private DecodedMatchingCommand decodedSlot = DecodedMatchingCommand.commandSlot();
        /** 该条目此次准入的窗口绝对位置。 */
        private long absSeq;
        /** 条目准入时保存的提交用户号。 */
        public long routeUserId;
        /** 条目准入时保存的提交用户 Lane 位。 */
        public long routeUserLaneBit;
        /** 条目静态 Matcher 分片，清理后负一。 */
        public int routeMatcherShard = -1;
        /** 条目是否保有准入路由。 */
        public boolean routePresent;
        /** 响应要发回的 Cluster 会话编号，清理后为无会话标记。 */
        public long sessionId = NO_SESSION_ID;
        /** 条目对应的完整入口请求引用。 */
        public CoreMessage request;
        /** 等待按 FIFO 发出的终态响应引用。 */
        public CoreResponse response;
        /**
         * sequence：绑定的 Core 命令序号，零可表示没有异步撮合。
         * timestamp：该条目记录的 Cluster 业务时间。
         * position：该条目记录的 Cluster 日志位置。
         */
        public long sequence, timestamp, position;
        /** 该条目在数组中的物理位置，未使用或退休后负一。 */
        public int physicalSlot = -1;
    }
}
