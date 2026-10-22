package com.surprising.aeron.service.order;

/**
 * 只供测试读取/设置内部命令标记，以及向准入等待容器注入数量故障，生产命令入口不使用该类。
 */
public final class PipelineTestAccess {
    /**
     * 禁止实例化测试用静态内部访问工具。
     */
    private PipelineTestAccess() {}
    /**
     * 直接读取槽的 Cluster 独立执行标记，不触发业务或线程交接。
     * @param slot 测试中已有的命令槽
     */
    public static boolean clusterIndependent(CommandSlot slot) { return slot.clusterIndependent; }
    /**
     * 直接设置标记，测试调用方负责所处阶段。
     * @param slot 测试中已有的命令槽
     * @param value 需要注入的独立执行标记
     */
    public static void clusterIndependent(CommandSlot slot, boolean value) { slot.clusterIndependent = value; }
    /**
     * 把指定数量空项加入准入等待列表，制造容器容量故障；不是构造可执行业务命令。
     * @param admission 当前准入协作入口
     * @param count 要追加的空占位项数量，负值由 nCopies 拒绝
     */
    public static void overflowQueuedMatching(MatchingCommandAdmission admission, int count) {
        admission.queuedMatching.addAll(java.util.Collections.nCopies(count, null));
    }
}
