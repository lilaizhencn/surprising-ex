package com.surprising.aeron.service.matcher;

import exchange.core2.core.common.MatcherResult;
import java.util.List;

/**
 * 撮合事实的读取契约，供账户结算和 Owner 完成处理使用。普通结果对象和直接命令的池化结算事件均可实现；只读调用约定不等于对象永久不可变，调用方必须遵守发布与槽位租约期限。
 */
public interface MatchingResult {
    /**
     * 决定如何处理撮合回执的业务分类，区分完全未改状态、已知前缀已执行、接受和无法安全继续的分歧。
     */
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

    /**
     * 返回引擎是否接受此结果；仍需结合 outcome 判断已知前缀或致命分歧。
     */
    boolean accepted();
    /**
     * 返回原撮合结果码文本，不在查询时重新分类。
     */
    String resultCode();
    /**
     * 返回本次按请求顺序收集的控制撤单结果，调用方遵守当前结果或池化槽位的使用期限。
     */
    List<CoreCancellationResult> cancellations();
    /**
     * 返回已分类的接受、拒绝、已执行前缀或致命分歧状态，供后续账户处理选择正确边界。
     */
    Outcome outcome();
    /**
     * 返回关联的 Core 命令序号，零表示当前尚未绑定或已单独清除。
     */
    long nativeCoreSequence();
    /**
     * 返回关联的普通订单编号，零可表示未绑定或没有单一订单。
     */
    long nativeOrderId();
    /**
     * 返回此结果发布到所属证据分片时的业务序号。
     */
    long nativeMatcherSequence();
    /**
     * 返回证据所属 Matcher 分片，控制分片使用负一。
     */
    int nativeMatcherShardId();
    /**
     * 逐半部比较原命令 UUID，不额外检查 Core 序号、订单编号或事件内容。
     * @param most 待比较命令 UUID 的高六十四位
     * @param least 待比较命令 UUID 的低六十四位
     */
    boolean nativeMatches(long most, long least);
    /**
     * 命令 UUID 非空时再比较高低半部，空值直接返回 false。
     * @param commandId 待比较的可空命令 UUID
     */
    boolean nativeMatches(java.util.UUID commandId);
    /**
     * 返回底层 exchange-core 原生结果引用，没有原生结果时可为空。
     */
    MatcherResult nativeMatcherResult();
    /**
     * 返回当前结果关联的原生成交、撤量或拒单事件序列；它是账户结算事实输入，不能据此再次撮合。
     */
    List<MatcherResult.MatcherEvent> matcherEvents();
}
