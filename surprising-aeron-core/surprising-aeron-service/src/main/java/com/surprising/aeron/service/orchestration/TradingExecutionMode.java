package com.surprising.aeron.service.orchestration;

/**
 * 交易状态的线程执行策略。
 * 
 * <p>FUSED: 单线程确定性状态机，由单一线程执行准入、撮合、清算与提交，消除跨核队列与上下文切换。
 * <p>PIPELINED: 多阶段流水线，撮合与账户由独立线程池协作完成。
 */
public enum TradingExecutionMode {
    /** 单一线程确定性执行全部准入、撮合、账户变更与提交。 */
    FUSED,
    /** 撮合与账户工作线程通过原有队列和栅栏边界协作。 */
    PIPELINED;

    /** 节点启动策略配置；默认为 FUSED 极速单线程状态机。 */
    public static TradingExecutionMode configured() {
        return valueOf(System.getProperty("surprising.aeron.execution-mode", "FUSED"));
    }
}
