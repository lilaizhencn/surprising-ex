package com.surprising.aeron.service.matcher;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 一条普通撮合回执，保留接受状态、撤单前缀、原生事件和命令证据。Matcher 在发布前可原位补齐身份，避免仅为绑定再分配结果；发布后按只读契约消费，事件和行情引用不深拷贝。它不负责修改余额、持仓或推进 Core 提交。
 */
public final class CoreMatchingResult implements MatchingResult {

    /** 无行情回执共用的空盘口数据，买卖侧列表为空且数量为零。 */
    private static final MatcherResult.MarketData EMPTY_MARKET_DATA =
            new MatcherResult.MarketData(List.of(), List.of(), 0, 0);
    /**  构造时确定的接受标记，既可来自原生结果，也可由控制调用方提供。 返回引擎是否接受此结果；仍需结合 outcome 判断已知前缀或致命分歧。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final boolean accepted;
    /**  构造时保存的非空结果码文本。 返回原撮合结果码文本，不在查询时重新分类。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String resultCode;
    /**  本次控制撤单逐项结果，空列表可能按现规则直接借用。 返回本次按请求顺序收集的控制撤单结果，调用方遵守当前结果或池化槽位的使用期限。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final List<CoreCancellationResult> cancellations;
    /**  传入的连续成功撤单数量，构造时只检查不超过列表大小。 返回批量控制撤单在首个失败前已经成功的连续前缀数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int successfulPrefixCount;
    /**  适配器显式登记的前缀状态变化标记，不等于所有原生订单簿变化的观测值。 返回适配器显式登记的状态变化标记，主要用于已执行撤单前缀；原生成功结果也可能保留 false，不能单靠此标记判断订单簿是否变化。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final boolean matcherStateChanged;
    /**  构造时按结果码、接受与前缀标记确定的业务分类。 返回已分类的接受、拒绝、已执行前缀或致命分歧状态，供后续账户处理选择正确边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Outcome outcome;
    /*
     * Matcher 先创建结果，再绑定命令证据，最后才发布完成回执。
     * 下列原生身份标量在这个构造窗口内保留可写，绑定时复用同一结果对象。
     */
    /**  Matcher 发布前绑定的 Core 命令序号，clearCoreSequence 只清此字段。 返回关联的 Core 命令序号，零表示当前尚未绑定或已单独清除。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long nativeCoreSequence;
    /**  原命令 UUID 高六十四位，发布前绑定。 返回保存的命令 UUID 高六十四位。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long nativeCommandIdMostSignificantBits;
    /**  原命令 UUID 低六十四位，发布前绑定。 返回保存的命令 UUID 低六十四位。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long nativeCommandIdLeastSignificantBits;
    /**  本结果关联的普通订单编号。 返回关联的普通订单编号，零可表示未绑定或没有单一订单。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long nativeOrderId;
    /** 显式保存的原生 exchange-core 序号，零时可从原生结果回读。 */
    private long nativeSequenceValue;
    /**  此回执在证据分片内的业务序号。 返回此结果发布到所属证据分片时的业务序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long nativeMatcherSequence;
    /**  绑定证据时保存的 Cluster 时间戳。 返回绑定时保存的 Cluster 业务时间戳。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long nativeAeronTimestamp;
    /**  本回执所属业务证据分片，控制分片为负一。 返回证据所属 Matcher 分片，控制分片使用负一。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int nativeMatcherShardId;
    /**  原生结果引用，没有原生回执的人工结果可为空。 返回底层 exchange-core 原生结果引用，没有原生结果时可为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final MatcherResult nativeMatcherResult;
    /**  原生事件列表引用或组合片段视图，构造时不统一复制。 返回当前结果关联的原生成交、撤量或拒单事件序列；它是账户结算事实输入，不能据此再次撮合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final List<MatcherResult.MatcherEvent> matcherEvents;
    /**  原生行情引用或共用空行情，不在此深拷贝。 返回此结果携带的原生市场数据引用，空行情使用约定的空对象。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final MatcherResult.MarketData marketData;

    /**
     * 创建只有接受标记和结果码的回执，撤单前缀为空、显式状态变化标记为 false。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     */
    public CoreMatchingResult(boolean accepted, String resultCode) {
        this(accepted, resultCode, List.of(), 0, false);
    }

    /**
     * 保存撤单列表和成功前缀，显式状态变化标记默认 false，其余身份尚未绑定。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     * @param cancellations 本次有序撤单结果列表
     * @param successfulPrefixCount 首个失败之前成功的连续撤单数
     */
    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount) {
        this(accepted, resultCode, cancellations, successfulPrefixCount, false);
    }

    /**
     * 创建带撤单前缀及显式状态变化的回执，其余原生身份默认零、未绑定分片默认负一，使用空事件和空行情。绑定后负一也用于控制分片。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     * @param cancellations 本次有序撤单结果列表
     * @param successfulPrefixCount 首个失败之前成功的连续撤单数
     * @param matcherStateChanged 是否明确知道已有状态变化，例如撤单前缀已执行
     */
    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged) {
        this(accepted, resultCode, cancellations, successfulPrefixCount, matcherStateChanged,
                0, 0, 0, 0, 0, 0, 0, -1, null,
                List.of(), EMPTY_MARKET_DATA);
    }

    /**
     * 检查结果码、列表、前缀范围及原生标量基本合法后保存并分类。撤单列表按 immutable 的现规则处理，事件和行情直接保留引用；这里不核验每条撤单是否与传入成功前缀一致。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     * @param cancellations 本次有序撤单结果列表
     * @param successfulPrefixCount 首个失败之前成功的连续撤单数
     * @param matcherStateChanged 是否明确知道已有状态变化，例如撤单前缀已执行
     * @param nativeCoreSequence 已绑定的 Core 序号，零可表示未绑定
     * @param nativeCommandIdMostSignificantBits 原命令 UUID 高六十四位
     * @param nativeCommandIdLeastSignificantBits 原命令 UUID 低六十四位
     * @param nativeOrderId 关联订单编号，未绑定或控制结果可为零
     * @param nativeSequence 原生 exchange-core 序号，零允许稍后从原生结果取得
     * @param nativeMatcherSequence 业务证据分片发布序号
     * @param nativeAeronTimestamp Cluster 业务时间戳
     * @param nativeMatcherShardId 业务证据分片编号，控制分片为负一
     * @param nativeMatcherResult 可空的原生结果引用
     * @param matcherEvents 后续账户处理使用的原生事件列表引用
     * @param marketData 对应原生行情引用，不能为空
     */
    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged,
                              long nativeCoreSequence,
                              long nativeCommandIdMostSignificantBits,
                              long nativeCommandIdLeastSignificantBits,
                              long nativeOrderId,
                              long nativeSequence,
                              long nativeMatcherSequence,
                              long nativeAeronTimestamp,
                              int nativeMatcherShardId,
                              MatcherResult nativeMatcherResult,
                              List<MatcherResult.MatcherEvent> matcherEvents,
                              MatcherResult.MarketData marketData) {
        if (resultCode == null || resultCode.isBlank() || cancellations == null
                || successfulPrefixCount < 0 || successfulPrefixCount > cancellations.size()
                || nativeCoreSequence < 0 || nativeOrderId < 0 || nativeSequence < 0
                || nativeMatcherSequence < 0 || nativeAeronTimestamp < 0 || nativeMatcherShardId < -1
                || matcherEvents == null || marketData == null) {
            throw new IllegalArgumentException("invalid matching result");
        }
        this.accepted = accepted;
        this.resultCode = resultCode;
        this.cancellations = immutable(cancellations);
        this.successfulPrefixCount = successfulPrefixCount;
        this.matcherStateChanged = matcherStateChanged;
        this.outcome = classify(accepted, resultCode, matcherStateChanged);
        this.nativeCoreSequence = nativeCoreSequence;
        this.nativeCommandIdMostSignificantBits = nativeCommandIdMostSignificantBits;
        this.nativeCommandIdLeastSignificantBits = nativeCommandIdLeastSignificantBits;
        this.nativeOrderId = nativeOrderId;
        this.nativeSequenceValue = nativeSequence;
        this.nativeMatcherSequence = nativeMatcherSequence;
        this.nativeAeronTimestamp = nativeAeronTimestamp;
        this.nativeMatcherShardId = nativeMatcherShardId;
        this.nativeMatcherResult = nativeMatcherResult;
        this.matcherEvents = matcherEvents;
        this.marketData = marketData;
    }

    /**
     * 把原生结果包装为尚未绑定命令身份的回执，原生序号读取时仍可从原结果取得。
     * @param result 非空 exchange-core 原生结果
     */
    static CoreMatchingResult fromNative(MatcherResult result) {
        return new CoreMatchingResult(result, 0, 0, 0, 0, 0, 0, 0, -1);
    }

    /**
     * 从非空原生结果创建回执，只有 SUCCESS 或 ACCEPTED 算接受；直接保留原生事件和行情，显式状态变化标记为 false，再检查身份标量。
     * @param result 非空 exchange-core 原生结果
     * @param coreSequence 要保存或补齐的 Core 命令序号
     * @param commandIdMostSignificantBits 待绑定命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits 待绑定命令 UUID 低六十四位
     * @param orderId 要绑定的普通订单编号
     * @param nativeSequence 原生 exchange-core 序号，零允许稍后从原生结果取得
     * @param matcherSequence 要绑定的业务分片证据序号
     * @param aeronTimestamp 要绑定的 Cluster 时间戳
     * @param matcherShardId 要绑定的业务证据分片编号
     */
    private CoreMatchingResult(MatcherResult result, long coreSequence,
                                long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                                long orderId, long nativeSequence,
                                long matcherSequence, long aeronTimestamp, int matcherShardId) {
        nativeMatcherResult = Objects.requireNonNull(result, "matcher result");
        accepted = result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
        resultCode = result.resultCode().name();
        cancellations = List.of();
        successfulPrefixCount = 0;
        matcherStateChanged = false;
        outcome = classify(accepted, resultCode, false);
        if (coreSequence < 0 || orderId < 0 || nativeSequence < 0
                || matcherSequence < 0 || aeronTimestamp < 0 || matcherShardId < -1) {
            throw new IllegalArgumentException("invalid native command identity");
        }
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        matcherEvents = Objects.requireNonNull(result.events(), "matcher events");
        marketData = Objects.requireNonNull(result.marketData(), "matcher market data");
    }

    /**
     * 仅供 Matcher 在完成环发布之前写入 Core 序号、UUID、订单和分片证据，并返回同一对象；方法自身不另检查线程、已发布状态或标量合法性，调用方负责该构造窗口。
     * @param coreSequence 要保存或补齐的 Core 命令序号
     * @param commandIdMostSignificantBits 待绑定命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits 待绑定命令 UUID 低六十四位
     * @param orderId 要绑定的普通订单编号
     * @param nativeSequence 原生 exchange-core 序号，零允许稍后从原生结果取得
     * @param matcherSequence 要绑定的业务分片证据序号
     * @param aeronTimestamp 要绑定的 Cluster 时间戳
     * @param matcherShardId 要绑定的业务证据分片编号
     */
    CoreMatchingResult bindEvidenceInPlace(
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long nativeSequence, long matcherSequence,
            long aeronTimestamp, int matcherShardId) {
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        return this;
    }

    /**
     * 要求正 Core 序号；相同序号直接复用，原来已绑定其他非零序号则拒绝，只允许从零首次绑定。
     * @param coreSequence 要保存或补齐的 Core 命令序号
     */
    public CoreMatchingResult withCoreSequenceInPlace(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        if (nativeCoreSequence == coreSequence) return this;
        if (nativeCoreSequence != 0) throw new IllegalStateException("matching result sequence mismatch");
        nativeCoreSequence = coreSequence;
        return this;
    }

    /**
     * 只把 Core 序号清零，其余 UUID、订单、原生序号和事件引用不清除。
     */
    public void clearCoreSequence() {
        nativeCoreSequence = 0;
    }

    /**
     * 按 first 再 second 的顺序组合事件；任一空时直接借用另一列表，否则只组合片段引用，不复制每个事件。
     * @param first 组合顺序中的第一段事件
     * @param second 组合顺序中的第二段事件
     */
    static List<MatcherResult.MatcherEvent> concatenateEvents(
            List<MatcherResult.MatcherEvent> first,
            List<MatcherResult.MatcherEvent> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        // 当前仅保存内层列表引用的片段构建器，随后固定外层列表。
        ArrayList<List<MatcherResult.MatcherEvent>> segments = new ArrayList<>(4);
        appendSegments(segments, first);
        appendSegments(segments, second);
        return new SegmentedEvents(segments);
    }

    /**
     * 按输入结果顺序收集并展开已有片段，空集合返回空列表、单片段直接借用，多片段创建索引视图。
     * @param results 需要按当前迭代顺序拼接事件的结果集合
     */
    static List<MatcherResult.MatcherEvent> concatenateEvents(
            Iterable<CoreMatchingResult> results) {
        // 当前仅保存内层列表引用的片段构建器，随后固定外层列表。
        ArrayList<List<MatcherResult.MatcherEvent>> segments = new ArrayList<>();
        for (CoreMatchingResult /* 当前待收集其事件的普通撮合回执。 */ result : results) {
            appendSegments(segments, result.matcherEvents());
        }
        if (segments.isEmpty()) return List.of();
        if (segments.size() == 1) return segments.getFirst();
        return new SegmentedEvents(segments);
    }

    /**
     * 跳过空来源，已有 SegmentedEvents 就把其片段展开一层加入目标，否则加入来源列表引用，避免片段视图递归嵌套。
     * @param destination 仅本次构造使用的可写片段列表
     * @param source 保持稳定的来源事件列表，可能本身已经是片段视图
     */
    private static void appendSegments(
            List<List<MatcherResult.MatcherEvent>> destination,
            List<MatcherResult.MatcherEvent> source) {
        if (source.isEmpty()) return;
        if (source instanceof SegmentedEvents /* 已经存在的片段视图，直接展开它的外层引用。 */ segmented) {
            destination.addAll(segmented.segments);
        } else {
            destination.add(source);
        }
    }

    /**
     * 优先返回显式证据中的原生序号；其为零且有原生结果时读取原生 sequence，不新建占位身份。
     */
    long nativeSequence() {
        return nativeSequenceValue == 0 && nativeMatcherResult != null
                ? nativeMatcherResult.sequence() : nativeSequenceValue;
    }
    /**
     * 逐半部比较原命令 UUID，不额外检查 Core 序号、订单编号或事件内容。
     * @param most 待比较 UUID 高六十四位
     * @param least 待比较 UUID 低六十四位
     */
    @Override public boolean nativeMatches(long most, long least) {
        return nativeCommandIdMostSignificantBits == most
                && nativeCommandIdLeastSignificantBits == least;
    }
    /**
     * 命令 UUID 非空时再比较高低半部，空值直接返回 false。
     * @param commandId 待比较的可空命令 UUID
     */
    @Override public boolean nativeMatches(java.util.UUID commandId) {
        return commandId != null
                && nativeMatches(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /**
     * 先把 EXCHANGE_CORE_FAILURE 与 MATCHING_TIMEOUT 归为致命分歧；其他接受结果为 APPLIED，拒绝结果再根据显式状态变化区分已执行前缀或完全未改。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     * @param matcherStateChanged 是否明确知道已有状态变化，例如撤单前缀已执行
     */
    private static Outcome classify(boolean accepted, String resultCode, boolean matcherStateChanged) {
        if ("EXCHANGE_CORE_FAILURE".equals(resultCode) || "MATCHING_TIMEOUT".equals(resultCode)) {
            return Outcome.FATAL_DIVERGENCE;
        }
        if (accepted) return Outcome.APPLIED;
        return matcherStateChanged ? Outcome.KNOWN_PREFIX_APPLIED : Outcome.REJECTED_UNCHANGED;
    }

    /**
     * 空列表直接复用，即使传入的是可变空表；类名属于 JDK ImmutableCollections 的列表也复用，其余用 List.copyOf。调用方不得在发布后修改仍被借用的列表。
     * @param <T> 当前列表条目的类型
     * @param values 需按现规则复用或复制的列表
     */
    private static <T> List<T> immutable(List<T> values) {
        if (values.isEmpty() || values.getClass().getName().startsWith("java.util.ImmutableCollections$")) {
            return values;
        }
        return List.copyOf(values);
    }

    /**
     * 共享原生事件段的只读索引视图。只固定外层段列表和长度，内层列表及事件引用不深拷贝；来源必须在使用期间保持稳定。
     */
    private static final class SegmentedEvents extends AbstractList<MatcherResult.MatcherEvent>
            implements RandomAccess {
        /** 只读复制的外层事件片段列表，内层各段仍保留原引用。 */
        private final List<List<MatcherResult.MatcherEvent>> segments;
        /** 每个片段的全局排他结束下标，用于二分定位。 */
        private final int[] ends;
        /**  全部事件片段的固定总长度。 返回构造时固定的所有片段事件总数。 */
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private final int size;

        /**
         * 复制外层片段列表并精确累计每片段结束下标，内部事件列表仍共享；总长度超过 int 范围时精确加法抛异常。
         * @param segments 按事件顺序排列的内层列表引用
         */
        private SegmentedEvents(List<List<MatcherResult.MatcherEvent>> segments) {
            this.segments = List.copyOf(segments);
            this.ends = new int[segments.size()];
            // 已累计的事件数量，使用精确 int 加法建立结束下标。
            int total = 0;
            for (/* 当前正在登记累计结束位置的片段下标。 */ int index = 0; index < segments.size(); index++) {
                total = Math.addExact(total, segments.get(index).size());
                ends[index] = total;
            }
            this.size = total;
        }

        /**
         * 先检查全局下标，再二分累计结束位置定位片段，换算片段内下标后取原事件；实现 RandomAccess 标记不代表这里使用常数时间寻址。
         * @param index 当前查询的全局事件下标
         */
        @Override
        public MatcherResult.MatcherEvent get(int index) {
            Objects.checkIndex(index, size);
            // 二分搜索尚未排除的最左片段下标。
            int low = 0;
            // 二分搜索尚未排除的最右片段下标。
            int high = ends.length - 1;
            while (low < high) {
                // 本次比较的中间片段下标。
                int middle = (low + high) >>> 1;
                if (index < ends[middle]) high = middle;
                else low = middle + 1;
            }
            // 目标片段之前的累计事件数，用来换算局部下标。
            int offset = low == 0 ? 0 : ends[low - 1];
            return segments.get(low).get(index - offset);
        }

    }
}
