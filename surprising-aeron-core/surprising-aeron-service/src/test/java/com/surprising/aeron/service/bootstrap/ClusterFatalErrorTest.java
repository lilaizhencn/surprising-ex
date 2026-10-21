package com.surprising.aeron.service.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.DriverTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.AgentTerminationException;
import org.junit.jupiter.api.Test;

/** 检查节点错误处理是否正确区分致命故障和可恢复警告，测试中用记录退出码代替真正退出 JVM。 */
class ClusterFatalErrorTest {
    /** 致命驱动超时和工作线程终止都必须请求结束进程，不能继续访问可能失效的共享内存。 */
    @Test
    void fatalConductorFailureStopsTheProcessBeforeMappedResourceCleanup() {
        // 记录错误处理器请求的退出码，避免测试把自己所在的 JVM 直接结束。
        AtomicInteger exit = new AtomicInteger();
        // 把生产中的终止动作替换成写入退出码，其他错误分类规则保持不变。
        var handler = AeronCoreLifecycle.errorHandler("test", exit::set);
        handler.onError(new DriverTimeoutException("paused beyond driver liveness"));
        assertThat(exit).hasValue(1);
        exit.set(0);
        handler.onError(new AgentTerminationException("lane failed"));
        assertThat(exit).hasValue(1);
    }

    /** 选主中的 WARN 只应记录警告，不能误杀仍可继续运行的节点。 */
    @Test
    void recoverableElectionWarningDoesNotStopTheProcess() {
        // 保持初始值 0，借此检查警告处理没有请求终止进程。
        AtomicInteger exit = new AtomicInteger();
        AeronCoreLifecycle.errorHandler("test", exit::set)
                .onError(new AeronException("leader heartbeat timeout", AeronException.Category.WARN));
        assertThat(exit).hasValue(0);
    }
}
