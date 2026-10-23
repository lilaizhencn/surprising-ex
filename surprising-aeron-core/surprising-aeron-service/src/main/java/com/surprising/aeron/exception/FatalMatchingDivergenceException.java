package com.surprising.aeron.exception;

/**
 * 表示撮合执行或证据已经与预期状态对不上，不能把它当作一笔普通拒单继续处理。
 * 异常消息保留动作、命令和快照位置，供故障处理沿着原日志定位并恢复一致状态。
 */
public final class FatalMatchingDivergenceException extends IllegalStateException {

    /**
     * 记录无法继续安全执行的动作和位置，供上层触发停止或恢复流程。
     * @param operation 发生分歧的动作名称
     * @param coreSequence 故障所关联的 Core 命令序号
     * @param snapshotId 故障所关联的快照编号
     * @param detail 说明哪项结果或状态不一致的具体原因
     */
    public FatalMatchingDivergenceException(
            String operation,
            long coreSequence,
            long snapshotId,
            String detail) {
        super(operation + " failed at coreSequence=" + coreSequence + ", snapshotId=" + snapshotId
                + ": " + detail);
    }

    /**
     * 记录分歧上下文，同时保留最先导致失败的底层异常。
     * @param operation 发生分歧的动作名称
     * @param coreSequence 故障所关联的 Core 命令序号
     * @param snapshotId 故障所关联的快照编号
     * @param detail 具体的不一致原因
     * @param cause 导致本次故障的原始异常
     */
    public FatalMatchingDivergenceException(
            String operation,
            long coreSequence,
            long snapshotId,
            String detail,
            Throwable cause) {
        super(operation + " failed at coreSequence=" + coreSequence + ", snapshotId=" + snapshotId
                + ": " + detail, cause);
    }

}
