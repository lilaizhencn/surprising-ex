package com.surprising.aeron.service.execution;

/** 交易状态的线程归属策略；不改变日志协议、账户路由或快照内容。 */
public enum TradingExecutionMode {
    /** 一个线程执行准入、撮合、账户变更与提交。 */
    FUSED,
    /** 撮合和账户工作线程通过原有有序完成边界协作。 */
    PIPELINED;

    /** 节点启动参数；同一轮比较须固定策略，运行中的实例不切换。 */
    public static TradingExecutionMode configured() {
        return valueOf(System.getProperty("surprising.aeron.execution-mode", "FUSED"));
    }
}
