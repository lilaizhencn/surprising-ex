package com.surprising.aeron.service.state;

/** Lane 准备的发布收据；Owner 只开放可见性，Lane 回收旧版本，无提交等待阶段。 */
final class LanePublication implements SettlementLaneWorker.Command {
    /** 已准备的值在此标记开放前对 Owner 不可见。 */
    volatile boolean visible;
    /** 准入命令序号，用于定位账户 Lane 的批量预留收据；普通发布为0。 */
    long admissionSequence;
    /** 仅准备 Lane 链接，发布后交回同一 Lane 清理；不引用可复用结算事件。 */
    LanePublishedMap.Version<?> first;

    void add(LanePublishedMap.Version<?> version) {
        version.cleanupNext = first;
        first = version;
    }

    @Override public void execute(AccountLaneState lane) {
        lane.assertOwner();
        if (!visible) throw new IllegalStateException("cannot reclaim an unpublished result");
        var current = first;
        first = null;
        while (current != null) {
            var next = current.cleanupNext;
            current.cleanupNext = null;
            current.reclaim();
            current = next;
        }
    }
}
