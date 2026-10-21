package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.orchestration.trade.CommandSlot;
import com.surprising.aeron.service.orchestration.trade.OrderBatchExecutor;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotParser;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * 仅供测试 JVM 使用的类加载插桩：在真实批量订单结算与快照激活边界调用 CoreFaults，让测试验证实际故障分支。只改加载中的字节码，不改生产源文件或磁盘 JAR。
 */
public final class CoreFaultAgent {
    /** 测试 premain 已进入的全局标记，在注册转换器之前设为真，当前 JVM 内不自动重置。 */
    public static boolean installed;

    /**
     * 登记已进入测试 agent，再安装类转换器；installed 置真只说明 premain 已执行，不证明所有目标类已加载或插桩成功。
     * @param args JVM 传入的 agent 参数，当前实现不读取
     * @param instrumentation JVM 提供的类转换器注册入口
     */
    public static void premain(String args, Instrumentation instrumentation) {
        installed = true;
        instrumentation.addTransformer(/* 测试类加载转换器，只处理已指定的核心边界，其余类保持原字节码 */ new ClassFileTransformer() {
            /**
             * 只转换批量订单执行器及快照解析器内部类的目标方法；在结算调用前、命令槽结果调用后或恢复激活前插入测试钩子，原指令仍按顺序保留。其他类返回 null。
             * @param loader 目标类加载器，当前筛选不读取
             * @param name 使用斜线分隔的 JVM 类内部名称
             * @param type 重定义时的已有 Class，当前转换逻辑不读取
             * @param domain 目标保护域，当前转换逻辑不读取
             * @param bytes 当前目标类的原始字节码
             */
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                // 是否是实际批量订单执行器类
                boolean core = name.equals("com/surprising/aeron/service/orchestration/trade/OrderBatchExecutor");
                // 是否是分块快照解析器的内部类
                boolean snapshot = name.startsWith("com/surprising/aeron/service/orchestration/snapshot/SectionedCoreSnapshotParser$");
                if (!core && !snapshot) return null;
                // 测试故障钩子类的常量描述符
                ClassDesc hooks = ClassDesc.of("com.surprising.aeron.service.orchestration.CoreFaults");
                return ClassFile.of().transformClass(ClassFile.of().parse(bytes),
                        ClassTransform.transformingMethodBodies(/* 当前待筛选的方法模型，按方法名称选择边界 */ method ->
                                core && (method.methodName().equalsString("finishOrderBatch")
                                        || method.methodName().equalsString("initializeOrderBatchLaneContext"))
                                        || snapshot && method.methodName().equalsString("restore"),
                                (/* 写入复制指令及测试钩子的代码构建器 */ builder, /* 当前原始代码元素，无论是否插钩子都继续复制 */ element) -> {
                                    if (snapshot && element instanceof InvokeInstruction /* 当前匹配到的调用指令，用其名称及必要的所属类筛选插入位置 */ invoke
                                            && invoke.name().equalsString("activate")) {
                                        builder.dup().invokestatic(hooks, "observeActivation",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));
                                    }
                                    if (core && element instanceof InvokeInstruction /* 当前匹配到的调用指令，用其名称及必要的所属类筛选插入位置 */ invoke
                                            && invoke.name().equalsString("completePendingReservations")) {
                                        builder.aload(0).aload(1).invokestatic(hooks, "afterSettlement",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                    }
                                    builder.with(element);
                                    if (core && element instanceof InvokeInstruction /* 当前匹配到的调用指令，用其名称及必要的所属类筛选插入位置 */ invoke
                                            && invoke.name().equalsString("result")
                                            && invoke.owner().asInternalName().endsWith("CommandSlot")) {
                                        builder.aload(0).aload(1).invokestatic(hooks, "afterLaneContext",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                    }
                                }));
            }
        });
    }
}
