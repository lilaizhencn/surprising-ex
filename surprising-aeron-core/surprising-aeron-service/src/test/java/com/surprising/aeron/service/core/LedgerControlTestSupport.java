package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/**
 * 控制命令测试等待与结果物化辅助方法。账户 await 有五秒截止点；commit 请求结束变化范围后按既有读取交接物化冷状态，其中取得账户访问权的循环没有单独截止点。
 */
public final class LedgerControlTestSupport {
    /**
     * 轮询既有完成续步，未完成时检查运行时已记录的工作线程故障并有界忙等，超过五秒让测试失败。
     * @param runtime 当前测试独占并推进控制命令的运行时
     * @param completion 用于继续投递或检查当前业务完成的 BooleanSupplier
     */
    public static void await(TradingAccountRuntime runtime, BooleanSupplier completion) {
        // 从单调时钟计算的本次五秒轮询截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completion.getAsBoolean()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger control completion timed out");
            Thread.onSpinWait();
        }
    }

    /**
     * 先同步验证资金，再清理运行时变化以请求提交关闭；需要时取得账户读取所有权后物化冷状态，finally 交回本次临时取得的访问权。
     * @param runtime 当前测试独占并推进控制命令的运行时
     * @param identities 冷状态物化使用的资产、币对及持仓身份字典
     * @param external 是否允许外部资金调整，传给原守恒验证入口
     */
    public static TradingCoreState commit(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, boolean external) {
        runtime.validateControlFunds(external);
        runtime.clearChangedKeys();
        // 本次是否需要临时取得账户读取所有权，真正取得在后续轮询结束时完成。
        boolean acquired = runtime.accountLanesStarted() && !runtime.ownerLaneAccess();
        try {
            if (acquired) while (!runtime.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            return RuntimeStateMaterializer.materialize(runtime, identities);
        } finally { if (acquired) runtime.releaseOwnerLaneAccess(); }
    }
}
