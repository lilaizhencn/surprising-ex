package com.surprising.aeron.service.matcher;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 订单撮合事实的读取契约与控制撤单/合成回执实现，供账户结算和 Owner 完成处理使用。 */
public interface OrderMatchingResult {
    /** 决定如何处理撮合回执的业务分类，区分完全未改状态、已知前缀已执行、接受和无法安全继续的分歧。 */
    enum Outcome {
        /** 请求被拒绝且没有登记已执行的状态前缀。 */
        REJECTED_UNCHANGED,
        /** 后续操作失败，但已知撤单前缀已经执行，必须按实际事实处理。 */
        KNOWN_PREFIX_APPLIED,
        /** 引擎接受此请求，后续仍需完成对应账户与财库结算。 */
        APPLIED,
        /** 原生故障或超时等导致无法安全继续，需要按致命分歧处理。 */
        FATAL_DIVERGENCE
    }

    /** 返回引擎是否接受此结果。 */
    boolean accepted();
    /** 返回原撮合结果码文本，不在查询时重新分类。 */
    String resultCode();
    /** 返回本次按请求顺序收集的控制撤单结果，调用方遵守当前结果或池化槽位的使用期限。 */
    List<OrderCancelResult> cancellations();
    /** 返回已分类的接受、拒绝、已执行前缀或致命分歧状态，供后续账户处理选择正确边界。 */
    Outcome outcome();
    /** 返回关联的 Core 命令序号，零表示当前尚未绑定或已单独清除。 */
    long nativeCoreSequence();
    /** 返回此结果发布到所属证据分片时的业务序号。 */
    long nativeMatcherSequence();
    /** 返回证据所属 Matcher 分片，控制分片使用负一。 */
    int nativeMatcherShardId();
    /** 逐半部比较原命令 UUID，不额外检查 Core 序号、订单编号或事件内容。 */
    boolean nativeMatches(long most, long least);
    /** 返回当前结果关联的原生成交、撤量或拒单事件序列。它是账户结算事实输入，不能据此再次撮合。 */
    List<MatcherResult.MatcherEvent> matcherEvents();

    /** 一条控制撤单或合成回执，保留接受状态、撤单前缀、事件和命令证据。Matcher 在发布前可原位补齐身份，避免仅为绑定再分配结果。 */
    final class Control implements OrderMatchingResult {

        @Accessors(fluent = true, chain = false)
        @Getter
        private final boolean accepted;
        @Accessors(fluent = true, chain = false)
        @Getter
        private final String resultCode;
        @Accessors(fluent = true, chain = false)
        @Getter
        private final List<OrderCancelResult> cancellations;
        @Accessors(fluent = true, chain = false)
        @Getter
        private final Outcome outcome;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long nativeCoreSequence;
        private long nativeCommandIdMostSignificantBits;
        private long nativeCommandIdLeastSignificantBits;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long nativeMatcherSequence;
        @Accessors(fluent = true, chain = false)
        @Getter
        private final List<MatcherResult.MatcherEvent> matcherEvents;

        /** 仅保存接受状态和结果码，其余证据尚未绑定且没有撤单或成交事件。 */
        public Control(boolean accepted, String resultCode) {
            this(accepted, resultCode, List.of(), false, List.of());
        }

        /** 保存控制撤单的状态、成功前缀和事件。 */
        public Control(boolean accepted, String resultCode,
                       List<OrderCancelResult> cancellations,
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

        /** 只提取控制撤单实际消费的接受状态、结果码和事件，不保留完整原生结果。 */
        public static Control fromNative(MatcherResult result) {
            Objects.requireNonNull(result, "matcher result");
            boolean accepted = result.resultCode() == CommandResultCode.SUCCESS
                    || result.resultCode() == CommandResultCode.ACCEPTED;
            return new Control(accepted, result.resultCode().name(), List.of(), false,
                    Objects.requireNonNull(result.events(), "matcher events"));
        }

        @Override public int nativeMatcherShardId() { return -1; }

        public Control bindEvidenceInPlace(
                long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                long matcherSequence) {
            nativeCoreSequence = coreSequence;
            nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
            nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
            nativeMatcherSequence = matcherSequence;
            return this;
        }

        public Control withCoreSequenceInPlace(long coreSequence) {
            if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
            if (nativeCoreSequence == coreSequence) return this;
            if (nativeCoreSequence != 0) throw new IllegalStateException("matching result sequence mismatch");
            nativeCoreSequence = coreSequence;
            return this;
        }

        public void clearCoreSequence() {
            nativeCoreSequence = 0;
        }

        public static List<MatcherResult.MatcherEvent> concatenateEvents(Iterable<Control> results) {
            ArrayList<MatcherResult.MatcherEvent> events = new ArrayList<>();
            for (Control result : results) appendEvents(events, result.matcherEvents());
            return java.util.Collections.unmodifiableList(events);
        }

        public static void appendEvents(List<MatcherResult.MatcherEvent> destination,
                                        List<MatcherResult.MatcherEvent> source) {
            for (int index = 0; index < source.size(); index++) destination.add(source.get(index));
        }

        @Override public boolean nativeMatches(long most, long least) {
            return nativeCommandIdMostSignificantBits == most
                    && nativeCommandIdLeastSignificantBits == least;
        }

        private static Outcome classify(boolean accepted, String resultCode, boolean matcherStateChanged) {
            if ("EXCHANGE_CORE_FAILURE".equals(resultCode) || "MATCHING_TIMEOUT".equals(resultCode)) {
                return Outcome.FATAL_DIVERGENCE;
            }
            if (accepted) return Outcome.APPLIED;
            return matcherStateChanged ? Outcome.KNOWN_PREFIX_APPLIED : Outcome.REJECTED_UNCHANGED;
        }

        private static <T> List<T> immutable(List<T> values) {
            if (values.isEmpty() || values.getClass().getName().startsWith("java.util.ImmutableCollections$")) {
                return values;
            }
            return List.copyOf(values);
        }
    }
}
