package com.surprising.aeron.config;

/**
 * 交易状态的线程执行策略。
 * 
 * <p>FUSED: 单一交易线程执行准入、撮合与账户变更；资金持久化仍通过独立账本边界。
 * <p>PIPELINED: 撮合和账户工作交给各自的工作线程，Owner 按确定顺序收集完成结果。
 */
public enum TradingExecutionMode {
    /** 单一交易线程确定性执行准入、撮合与账户变更，保留独立账本。 */
    FUSED,
    /** 撮合与账户工作线程通过原有队列和栅栏边界协作。 */
    PIPELINED;

    /** 节点启动策略配置；默认为 FUSED 单线程状态机，可通过 -Dsurprising.aeron.execution-mode=PIPELINED 切换为多线程流水线模式。 */
    public static TradingExecutionMode configured() {
        return valueOf(System.getProperty("surprising.aeron.execution-mode", "FUSED"));
    }
}
