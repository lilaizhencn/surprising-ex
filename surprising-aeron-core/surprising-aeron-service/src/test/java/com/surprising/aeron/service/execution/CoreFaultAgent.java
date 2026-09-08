package com.surprising.aeron.service.execution;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Loaded only by Surefire. Production class files and deployment jars are never rewritten. */
public final class CoreFaultAgent {
    static boolean installed;

    public static void premain(String args, Instrumentation instrumentation) {
        installed = true;
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                boolean core = name.equals("com/surprising/aeron/service/execution/CoreProbeState");
                boolean snapshot = name.startsWith("com/surprising/aeron/service/execution/SectionedCoreSnapshotParser$");
                if (!core && !snapshot) return null;
                ClassDesc hooks = ClassDesc.of("com.surprising.aeron.service.execution.CoreFaults");
                return ClassFile.of().transformClass(ClassFile.of().parse(bytes),
                        ClassTransform.transformingMethodBodies(method ->
                                core && (method.methodName().equalsString("finishOrderBatch")
                                        || method.methodName().equalsString("initializeOrderBatchLaneContext"))
                                        || snapshot && method.methodName().equalsString("restore"),
                                (builder, element) -> {
                                    if (snapshot && element instanceof InvokeInstruction invoke
                                            && invoke.name().equalsString("activate")) {
                                        builder.dup().invokestatic(hooks, "observeActivation",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));
                                    }
                                    if (core && element instanceof InvokeInstruction invoke
                                            && invoke.name().equalsString("completePendingReservations")) {
                                        builder.aload(0).aload(1).invokestatic(hooks, "afterSettlement",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                    }
                                    builder.with(element);
                                    if (core && element instanceof InvokeInstruction invoke
                                            && invoke.name().equalsString("result")
                                            && invoke.owner().asInternalName().endsWith("LaneCommandContextRing$Context")) {
                                        builder.aload(0).aload(1).invokestatic(hooks, "afterLaneContext",
                                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                    }
                                }));
            }
        });
    }
}
