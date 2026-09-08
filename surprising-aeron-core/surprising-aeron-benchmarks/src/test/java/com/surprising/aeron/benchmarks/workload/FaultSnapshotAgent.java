package com.surprising.aeron.benchmarks.workload;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.concurrent.locks.LockSupport;

/** Test-only pause immediately after the first accepted real snapshot publication fragment. */
public final class FaultSnapshotAgent {
    private static Path arm;
    private static Path marker;

    public static void premain(String path, Instrumentation instrumentation) {
        arm = Path.of(path + ".arm");
        marker = Path.of(path + ".paused");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> cls,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("com/surprising/aeron/service/execution/SurprisingClusteredService")) return null;
                return ClassFile.of().transformClass(ClassFile.of().parse(bytes),
                        ClassTransform.transformingMethodBodies(method -> method.methodName().equalsString("onTakeSnapshot"),
                                (builder, element) -> {
                                    builder.with(element);
                                    if (element instanceof InvokeInstruction invoke
                                            && invoke.name().equalsString("offer")) {
                                        builder.invokestatic(ClassDesc.of(FaultSnapshotAgent.class.getName()), "afterOffer",
                                                MethodTypeDesc.ofDescriptor("(J)J"));
                                    }
                                }));
            }
        });
    }

    public static long afterOffer(long position) {
        if (position > 0 && Files.exists(arm)) {
            try {
                Files.writeString(marker, "acceptedSnapshotPosition=" + position);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
            while (true) LockSupport.parkNanos(1_000_000);
        }
        return position;
    }
}
