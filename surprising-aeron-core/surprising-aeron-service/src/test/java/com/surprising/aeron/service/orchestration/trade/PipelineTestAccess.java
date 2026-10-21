package com.surprising.aeron.service.orchestration.trade;

/** 只在测试侧读取槽标记和注入待撮合容量故障。 */
public final class PipelineTestAccess {
    private PipelineTestAccess() {}
    public static boolean clusterIndependent(CommandSlot slot) { return slot.clusterIndependent; }
    public static void clusterIndependent(CommandSlot slot, boolean value) { slot.clusterIndependent = value; }
    public static void overflowQueuedMatching(MatchingCommandAdmission admission, int count) {
        admission.queuedMatching.addAll(java.util.Collections.nCopies(count, null));
    }
}
