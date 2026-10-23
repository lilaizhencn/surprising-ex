package com.surprising.aeron.service.instrument.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.instrument.RuntimeInstrumentStateTransitions;

/**
 * 标的注册、配置修改及维护状态命令入口。解码请求后交给现有标的状态转换，成功返回后请求提交发布；产品线隔离和具体拒绝条件由转换方法处理。
 */
public final class InstrumentConfigurationCommands {
    /** 本产品线唯一交易 Owner，持有标的、身份表和提交发布入口。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，不创建或启动工作线程。
     * @param owner 本产品线交易 Owner，提供命令上下文、权威状态与提交入口
     */
    public InstrumentConfigurationCommands(TradingCoreRuntime owner) { this.owner = java.util.Objects.requireNonNull(owner); }

    /**
     * 解码标的参数并应用注册或更新，转换正常返回后请求发布；相同配置是否需要增加修订由转换方法决定。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeRegisterInstrument(CoreMessage message, long clusterTimestamp) {
        // 本次载荷解出的注册或更新配置
        var command = TradingCommandCodec.decodeRegisterInstrument(message.payloadUnsafe(), owner.runtimeState().productLine());
        RuntimeInstrumentStateTransitions.applyConfiguration(
                owner.runtimeState(), owner.identities(), command);
        owner.requestCommitPublication();
    }

    /**
     * 解码带任务归属的维护命令，交给状态转换检查并修改维护状态，成功后请求发布。
     * @param message 当前借用的请求头和载荷，解码完成前保持缓冲有效
     * @param clusterTimestamp 原命令业务时间参数，本入口当前不直接读取
     */
    public void executeUpdateInstrumentMaintenance(CoreMessage message, long clusterTimestamp) {
        RuntimeInstrumentStateTransitions.updateMaintenance(owner.runtimeState(), owner.identities(),
                com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }
}
