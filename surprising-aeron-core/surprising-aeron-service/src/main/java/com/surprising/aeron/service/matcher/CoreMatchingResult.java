package com.surprising.aeron.service.matcher;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 一条控制撤单或合成回执，保留接受状态、撤单前缀、事件和命令证据。Matcher 在发布前可原位补齐身份，避免仅为绑定再分配结果；发布后按只读契约消费，事件引用不深拷贝。它不负责修改余额、持仓或推进 Core 提交。
 */
public final class CoreMatchingResult implements MatchingResult {

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
    private long nativeCommandIdMostSignificantBits;
    /**  原命令 UUID 低六十四位，发布前绑定。 返回保存的命令 UUID 低六十四位。 */
    private long nativeCommandIdLeastSignificantBits;
    /**  此回执在证据分片内的业务序号。 返回此结果发布到所属证据分片时的业务序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long nativeMatcherSequence;
    /**  原生事件列表引用或扁平引用列表，事件值不复制。 返回当前结果关联的原生成交、撤量或拒单事件序列；它是账户结算事实输入，不能据此再次撮合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final List<MatcherResult.MatcherEvent> matcherEvents;

    /** 仅保存接受状态和结果码，其余证据尚未绑定且没有撤单或成交事件。 */
    public CoreMatchingResult(boolean accepted, String resultCode) {
        this(accepted, resultCode, List.of(), false, List.of());
    }

    /**
     * 保存控制撤单的状态、成功前缀和事件；命令证据由 Matcher 发布前统一绑定。
     * @param accepted 引擎是否接受当前请求
     * @param resultCode 非空撮合结果码文本
     * @param cancellations 本次有序撤单结果列表
     * @param matcherStateChanged 是否已执行撤单前缀
     * @param matcherEvents 后续账户处理使用的原生事件列表
     */
    CoreMatchingResult(boolean accepted, String resultCode,
                       List<CoreCancellationResult> cancellations,
                       boolean matcherStateChanged, List<MatcherResult.MatcherEvent> matcherEvents) {
        if (resultCode == null || resultCode.isBlank() || cancellations == null || matcherEvents == null) {
            throw new IllegalArgumentException("invalid matching result");
        }
        this.accepted = accepted;
        this.resultCode = resultCode;
        this.cancellations = immutable(cancellations);
        this.outcome = classify(accepted, resultCode, matcherStateChanged);
        this.matcherEvents = matcherEvents;
    }

    /**
     * 只提取控制撤单实际消费的接受状态、结果码和事件，不保留完整原生结果。
     * @param result 非空的本次原生撤单结果
     */
    static CoreMatchingResult fromNative(MatcherResult result) {
        Objects.requireNonNull(result, "matcher result");
        boolean accepted = result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
        return new CoreMatchingResult(accepted, result.resultCode().name(), List.of(), false,
                Objects.requireNonNull(result.events(), "matcher events"));
    }

    /** 控制及合成回执固定使用控制证据分片，不保存重复的逐结果字段。 */
    @Override public int nativeMatcherShardId() { return -1; }

    /**
     * 仅供 Matcher 在完成环发布之前写入 Core 序号、UUID 和控制业务序号，并返回同一对象；方法自身不另检查线程、已发布状态或标量合法性，调用方负责该构造窗口。
     * @param coreSequence 要保存或补齐的 Core 命令序号
     * @param commandIdMostSignificantBits 待绑定命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits 待绑定命令 UUID 低六十四位
     * @param matcherSequence 要绑定的业务分片证据序号
     */
    CoreMatchingResult bindEvidenceInPlace(
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long matcherSequence) {
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeMatcherSequence = matcherSequence;
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
     * 只把 Core 序号清零，其余 UUID、原生序号和事件引用不清除。
     */
    public void clearCoreSequence() {
        nativeCoreSequence = 0;
    }

    /** 按结果顺序一次收集事件引用，不复制事件值，不保留分段二分索引。 */
    static List<MatcherResult.MatcherEvent> concatenateEvents(Iterable<CoreMatchingResult> results) {
        ArrayList<MatcherResult.MatcherEvent> events = new ArrayList<>();
        for (CoreMatchingResult result : results) appendEvents(events, result.matcherEvents());
        return java.util.Collections.unmodifiableList(events);
    }

    /** 直接追加稳定事件引用，避免 addAll 对来源列表的临时数组物化。 */
    static void appendEvents(List<MatcherResult.MatcherEvent> destination,
                             List<MatcherResult.MatcherEvent> source) {
        for (int index = 0; index < source.size(); index++) destination.add(source.get(index));
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

}
