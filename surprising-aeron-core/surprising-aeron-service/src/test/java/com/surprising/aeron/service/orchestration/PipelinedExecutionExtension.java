package com.surprising.aeron.service.orchestration;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 只用于需要阻塞真实 matcher/Lane 工作线程的协调测试；普通业务测试使用默认融合执行。 */
final class PipelinedExecutionExtension implements BeforeEachCallback, AfterEachCallback {
    private String previousMode;
    private String previousLanes;
    @Override
    public void beforeEach(ExtensionContext context) {
        previousMode = System.getProperty("surprising.aeron.execution-mode");
        previousLanes = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.execution-mode", "PIPELINED");
        System.setProperty("surprising.aeron.account-lanes", "4");
    }
    @Override
    public void afterEach(ExtensionContext context) {
        if (previousMode == null) System.clearProperty("surprising.aeron.execution-mode");
        else System.setProperty("surprising.aeron.execution-mode", previousMode);
        if (previousLanes == null) System.clearProperty("surprising.aeron.account-lanes");
        else System.setProperty("surprising.aeron.account-lanes", previousLanes);
    }
}
