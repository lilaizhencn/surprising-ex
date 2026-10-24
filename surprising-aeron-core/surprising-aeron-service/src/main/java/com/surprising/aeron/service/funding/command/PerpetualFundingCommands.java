package com.surprising.aeron.service.funding.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor;

/**
 * 永续资金费命令入口：解码请求并取得持仓用户索引，一页工作交给原命令槽续步，完成后填入提交结果，并按需通知独立提交回放的明细观察者。U 本位与币本位的金额计算仍由各自产品规则负责。
 */
public final class PerpetualFundingCommands {
    /** 当前产品线交易 Owner，持有原运行时、索引、命令槽和结果构建器。 */
    private final TradingCoreRuntime owner;
    // 仅由独立的已提交日志回放注册；在线 Owner/Lane 异步执行没有此导出监听器，
    // 本入口不访问数据库，也不在这里发布历史记录。
    /** 独立提交回放设置的页明细回调，在线异步执行通常为空。 */
    private java.util.function.Consumer<RuntimePerpetualFundingProcessor.FundingResult> replayObserver;

    /**
     * 绑定本产品线唯一的交易 Owner 运行时，空引用直接拒绝。
     * @param owner 本产品线交易编排入口
     */
    public PerpetualFundingCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 注册提交回放专用的收付明细观察者，重复调用会替换原观察者；在线异步链路不在本入口发布数据库历史。
     * @param observer 非空的回放页收付明细回调
     */
    public void observeReplayPayments(java.util.function.Consumer<RuntimePerpetualFundingProcessor.FundingResult> observer) {
        this.replayObserver = java.util.Objects.requireNonNull(observer);
    }

    /**
     * 解码资金费参数并取得全局游标之后的持仓用户索引；复用工作对象后交给 directCommand 轮询；在线与独立回放都在实际完成后收集结果。
     * @param message 已路由到本产品线的资金费命令及其 UUID
     */
    public void executeApplyFunding(CoreMessage message) {
        // 从协议载荷解出的资金费编号、费率与用户分页参数。
        var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
        // 本币对全局用户游标之后的持仓用户来源，具体页由账本阶段选择。
        Iterable<Long> indexedUserIds = owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId());
        // 命令槽持有的一页资金费工作，Owner 后续轮询收齐账户和账本回执。
        var work = RuntimePerpetualFundingProcessor.prepare(owner.directCommand.reusableFundingWork(), command,
                indexedUserIds, owner.runtimeState(), owner.identities());
        owner.directCommand.deferFundingControl(work);
    }

    /**
     * 有回放观察者时直接通知本页结果，异常按原调用链向上传递；没有观察者时不处理。
     * @param result 已经应用账户和财库的本页资金费结果
     */
    public void notifyReplayObserver(RuntimePerpetualFundingProcessor.FundingResult result) {
        if (replayObserver != null) replayObserver.accept(result);
    }

}
