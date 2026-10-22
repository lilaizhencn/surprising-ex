package com.surprising.aeron.service.core;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 给需要真实 Matcher 和账户工作线程的 JUnit 场景临时设置 PIPELINED 模式及四个账户 Lane；每个测试结束后还原进入前的两项 JVM 属性。只负责测试配置，不启动或关闭 Core；全局属性没有额外并发隔离。
 */
public final class PipelinedExecutionExtension implements BeforeEachCallback, AfterEachCallback {
    /** 当前测试开始前的执行模式属性，空表示原来未配置。 */
    private String previousMode;
    /** 当前测试开始前的账户 Lane 数属性，空表示原来未配置。 */
    private String previousLanes;
    /**
     * 先保存两项原始属性值，再设置流水模式和四个账户 Lane，供之后创建的测试 Core 读取。
     * @param context JUnit 传入的本次测试上下文，当前配置切换不读取
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        previousMode = System.getProperty("surprising.aeron.execution-mode");
        previousLanes = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.execution-mode", "PIPELINED");
        System.setProperty("surprising.aeron.account-lanes", "4");
    }
    /**
     * 按保存值恢复两项 JVM 属性；原先没有配置就清除，原先有值则原样写回。
     * @param context JUnit 传入的本次测试上下文，当前配置切换不读取
     */
    @Override
    public void afterEach(ExtensionContext context) {
        if (previousMode == null) System.clearProperty("surprising.aeron.execution-mode");
        else System.setProperty("surprising.aeron.execution-mode", previousMode);
        if (previousLanes == null) System.clearProperty("surprising.aeron.account-lanes");
        else System.setProperty("surprising.aeron.account-lanes", previousLanes);
    }
}
