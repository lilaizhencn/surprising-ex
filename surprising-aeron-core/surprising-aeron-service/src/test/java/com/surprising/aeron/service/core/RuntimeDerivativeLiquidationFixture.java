package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;
import java.util.Collection;
import java.util.List;

/**
 * 强平、保险补亏和 ADL 一致性测试的独立运行时辅助器，先投影业务状态，再调用对应真实账户及账本步骤，返回结果供测试比对。
 */
public final class RuntimeDerivativeLiquidationFixture {
    /**
     * 用空撤单列表模拟最终强平执行，转到接收撤单回执的统一辅助入口。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次最终执行的强平命令，没有额外撤单回执
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static AccountBook simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        IdIndex identities) {
        return simulateExecution(before, command, List.of(), identities);
    }

    /**
     * 校验衍生品输入，从原业务状态投影独立运行时，将业务订单记录转成撤单路由后执行真实强平结算。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次最终执行的强平命令，配合已经完成的撤单回执处理
     * @param canceledOrders 本次 Matcher 已撤订单的业务记录，转换为撤单路由后交账户步骤解冻
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static AccountBook simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        Collection<CoreOrderState> canceledOrders,
                                                        IdIndex identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual liquidation simulation");
        }
        // 从本次业务状态投影的独立运行时，接收真实强平、补亏或 ADL 变更。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);
        return applyExecutionRuntime(command, RuntimeTestStateTransitions.cancellationRoutes(canceledOrders), accountBook, identities);
    }

    /**
     * 校验正的下一撤单编号，在独立运行时应用本批已撤订单回执并保存继续游标，供分批撤单测试比对。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次只推进撤单阶段的强平命令，暂不执行最终平仓
     * @param canceledOrders 本次 Matcher 已撤订单的业务记录，转换为撤单路由后交账户步骤解冻
     * @param nextCursorOrderId 这批撤单之后仍待撤的下一订单编号，继续撤单时必须为正
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static AccountBook simulateCancellationAdvance(TradingCoreState before,
                                                                  ExecuteLiquidationCommand command,
                                                                  Collection<CoreOrderState> canceledOrders,
                                                                  long nextCursorOrderId,
                                                                  IdIndex identities) {
        if (before == null || command == null || identities == null || nextCursorOrderId <= 0
                || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation cancellation simulation");
        }
        // 从本次业务状态投影的独立运行时，接收真实强平、补亏或 ADL 变更。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);
        return applyCancellationAdvanceRuntime(command, RuntimeTestStateTransitions.cancellationRoutes(canceledOrders), nextCursorOrderId, accountBook, identities);
    }

    /**
     * 在独立运行时执行保险补亏或完成决议，使用原业务状态的全部强平编号准备同资产分配候选。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次保险补亏或完成决议命令
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static AccountBook simulateResolution(TradingCoreState before,
                                                         ResolveLiquidationCommand command,
                                                         IdIndex identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution simulation");
        }
        // 从本次业务状态投影的独立运行时，接收真实强平、补亏或 ADL 变更。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);
        return RuntimeDerivativeLiquidationFixture.applyResolution(command, accountBook, identities,
                before.riskState().liquidations().keySet());
    }

    /**
     * 在独立运行时执行真实 ADL 的目标减仓、源亏空更新和资金差额处理，用于与业务状态参照做一致性比对。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次目标减仓并覆盖源强平亏空的 ADL 命令
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static AccountBook simulateAdl(TradingCoreState before, ExecuteAdlCommand command,
                                                  IdIndex identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL simulation");
        }
        // 从本次业务状态投影的独立运行时，接收真实强平、补亏或 ADL 变更。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);
        return RuntimeDerivativeLiquidationFixture.applyAdl(command, accountBook, identities);
    }

    /** 在测试侧推进实际强平工作，账户和账本计算仍由原生产实现完成。 */
    public static AccountBook applyExecutionRuntime(ExecuteLiquidationCommand command,
            Collection<com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder> orders,
            AccountBook accountBook, IdIndex identities) {
        var work = com.surprising.aeron.service.liquidation.LiquidationService.beginExecution(
                null, command, orders, 0, accountBook, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work);
        return accountBook;
    }

    public static AccountBook applyCancellationAdvanceRuntime(ExecuteLiquidationCommand command,
            Collection<com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder> orders,
            long nextCursorOrderId, AccountBook accountBook, IdIndex identities) {
        if (nextCursorOrderId <= 0) throw new IllegalArgumentException("invalid liquidation cancellation cursor");
        var work = com.surprising.aeron.service.liquidation.LiquidationService.beginExecution(
                null, command, orders, nextCursorOrderId, accountBook, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work);
        return accountBook;
    }

    public static AccountBook applyResolution(ResolveLiquidationCommand command,
            AccountBook accountBook, IdIndex identities, Iterable<Long> candidateIds) {
        var work = LiquidationService.beginResolution(null, command, accountBook, identities, candidateIds);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work);
        return accountBook;
    }

    public static AccountBook applyAdl(ExecuteAdlCommand command,
            AccountBook accountBook, IdIndex identities) {
        var work = AdlService.begin(null, command, accountBook, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work);
        return accountBook;
    }
}
