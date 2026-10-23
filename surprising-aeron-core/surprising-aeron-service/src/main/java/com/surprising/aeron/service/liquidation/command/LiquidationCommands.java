package com.surprising.aeron.service.liquidation.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import java.util.Collection;

/**
 * 强平命令的状态入口：交易编排先完成计划、价格、游标及 Matcher 撤单检查，再把真实撤单结果交给此处处理持仓和资金。分段撤单推进与保险等结果确认仍沿用各自原命令的提交边界。
 */
public final class LiquidationCommands {
    /** 本产品线唯一交易 Owner，持有账户运行时、活跃强平集合及控制槽。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，复用其强平控制任务和提交边界。
     * @param owner 本产品线交易 Owner，提供权威运行时、身份表、控制槽和提交入口
     */
    public LiquidationCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 解码强平结果确认；异步准备原结果处理任务并交接控制槽，冷路径直接应用确认后请求发布。保险分配、状态合法性和剩余亏空由结果处理器决定。
     * @param message 当前借用的请求头与载荷，解码期间保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeResolveLiquidation(CoreMessage message, long clusterTimestamp) {
        // 解码得到的强平任务身份、确认方式及需要核对的覆盖金额
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            // 按原运行时及活跃强平集合准备的可续步结果确认任务，完成条件由控制槽继续轮询
            var work = RuntimeLiquidationResolution.begin(owner.directCommand.reusableLiquidationResolutionWork(), command,
                    owner.runtimeState(), owner.identities(), owner.activeLiquidationIds());
            owner.directCommand.deferLiquidationResolutionControl(work);
        } else {
            RuntimeLiquidationResolution.applyRuntime(command, owner.runtimeState(),
                    owner.identities(), owner.activeLiquidationIds());
            owner.requestCommitPublication();
        }
    }

    /**
     * 将已核对的执行命令及真实撤单清单交给强平执行器处理，正常返回后请求发布；不在这里重新向 Matcher 发撤单。
     * @param command 交易编排已准备的强平执行参数，包含计划身份、触发价序列和本段撤单边界
     * @param canceledOrders Matcher 已实际撤掉的订单路由清单，供释放预留及推进业务事实
     */
    public void executeLiquidationRuntime(ExecuteLiquidationCommand command,
                                          Collection<CancellationOrder> canceledOrders) {
        RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(command, canceledOrders,
                owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

    /**
     * 把本段真实撤单结果及下一订单游标交给原强平推进逻辑，正常返回后请求发布，持仓是否执行由后续步骤判断。
     * @param command 交易编排已准备的强平执行参数，包含计划身份、触发价序列和本段撤单边界
     * @param canceledOrders Matcher 已实际撤掉的订单路由清单，供释放预留及推进业务事实
     * @param nextCursorOrderId 本段撤单完成后保存的下一订单游标
     */
    public void advanceLiquidationCancellationRuntime(ExecuteLiquidationCommand command,
                                                       Collection<CancellationOrder> canceledOrders,
                                                       long nextCursorOrderId) {
        RuntimeDerivativeLiquidationProcessor.applyCancellationAdvanceRuntime(command, canceledOrders,
                nextCursorOrderId, owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

}
