package com.surprising.aeron.service.execution;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 只用于需要阻塞真实 matcher/Lane 工作线程的协调测试；普通业务测试使用默认融合执行。 */
final class PipelinedExecutionExtension implements BeforeEachCallback, AfterEachCallback {
    private String previous;
    public void beforeEach(ExtensionContext context) {
        previous = System.getProperty("surprising.aeron.execution-mode");
        System.setProperty("surprising.aeron.execution-mode", "PIPELINED");
    }
    public void afterEach(ExtensionContext context) {
        if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
        else System.setProperty("surprising.aeron.execution-mode", previous);
    }
}
