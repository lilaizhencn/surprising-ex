package com.surprising.aeron.service.matcher;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.journaling.SnapshotDescriptor;
import java.io.IOException;
import java.util.NavigableMap;
import java.util.function.Function;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 独立同步撮合引擎的序列化编号适配器：只把本地撮合模块零号映射成全局 Matcher 分片号，避免多个独立引擎快照模块相互覆盖。风险等其他模块编号以及日志/回放接口均沿用原委托，不新增快照数据或日志权威来源。
 */
final class ShardSerializationProcessor implements ISerializationProcessor {
    /** 真正负责快照存储和日志回放的原序列化处理器。 */
    private final ISerializationProcessor delegate;
    /** 此独立引擎在全局 Matcher 拓扑中的非负分片编号。 */
    private final int shardId;

    /**
     * 要求委托存在且全局分片编号非负；编号上界由组装方拓扑保证，包装器不另检查。
     * @param delegate 原序列化处理器实例
     * @param shardId 全局撮合分片编号
     */
    ShardSerializationProcessor(ISerializationProcessor delegate, int shardId) {
        if (delegate == null || shardId < 0) throw new IllegalArgumentException("invalid shard serializer");
        this.delegate = delegate;
        this.shardId = shardId;
    }

    /**
     * 撮合路由模块必须使用本地实例零，转换为本包装器全局分片号；其他模块类型保留原实例编号。
     * @param type 当前序列化模块类型
     * @param localInstance 原引擎给出的本地模块实例编号，撮合模块必须为零
     */
    private int globalInstance(SerializedModuleType type, int localInstance) {
        if (type == SerializedModuleType.MATCHING_ENGINE_ROUTER) {
            if (localInstance != 0) throw new IllegalStateException("synchronous matcher local module must be zero");
            return shardId;
        }
        return localInstance;
    }

    /**
     * 先映射当前模块实例编号，再把同一快照、水位、时间和写入器交给原处理器，返回委托写入结果。
     * @param snapshotId 本次模块所属快照编号
     * @param sequence 原引擎模块保存的序号水位
     * @param timestampNs 原模块记录时间，纳秒
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     * @param writer 原模块内容写入函数
     */
    @Override
    public boolean storeData(long snapshotId, long sequence, long timestampNs, SerializedModuleType type,
                             int instanceId, WriteBytesMarshallable writer) {
        return delegate.storeData(snapshotId, sequence, timestampNs, type,
                globalInstance(type, instanceId), writer);
    }

    /**
     * 按同一实例映射规则读取模块，业务对象解析函数原样交给委托。
     * @param <T> 模块载荷恢复出的业务对象类型
     * @param snapshotId 本次模块所属快照编号
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     * @param reader 从模块字节恢复业务对象的函数
     */
    @Override
    public <T> T loadData(long snapshotId, SerializedModuleType type, int instanceId,
                          Function<BytesIn, T> reader) {
        return delegate.loadData(snapshotId, type, globalInstance(type, instanceId), reader);
    }

    /**
     * 按同一实例映射规则向委托检查模块是否存在，避免检查本地零号造成跨分片混淆。
     * @param snapshotId 本次模块所属快照编号
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     */
    @Override
    public boolean checkSnapshotExists(long snapshotId, SerializedModuleType type, int instanceId) {
        return delegate.checkSnapshotExists(snapshotId, type, globalInstance(type, instanceId));
    }

    /**
     * 把原命令、日志序号和批次结束标记原样委托，不在此复制命令或重排日志。
     * @param cmd 要写入原引擎日志的命令
     * @param dSeq 原处理器使用的日志序号
     * @param eob 是否本批次最后一条日志命令
     */
    @Override public void writeToJournal(OrderCommand cmd, long dSeq, boolean eob) throws IOException {
        delegate.writeToJournal(cmd, dSeq, eob);
    }
    /**
     * 把原启用序号和引擎 API 交给委托，日志启用边界沿用原处理器。
     * @param afterSeq 交给委托的日志启用序号边界
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void enableJournaling(long afterSeq, ExchangeApi api) {
        delegate.enableJournaling(afterSeq, api);
    }
    /**
     * 直接返回委托发现的快照点有序表，不复制或额外固定为只读。
     */
    @Override public NavigableMap<Long, SnapshotDescriptor> findAllSnapshotPoints() {
        return delegate.findAllSnapshotPoints();
    }
    /**
     * 把指定快照及序号范围交给委托分段回放，范围包含规则由原处理器决定。
     * @param snapshotId 本次模块所属快照编号
     * @param seqFrom 分段回放起始序号，边界规则沿用委托
     * @param seqTo 分段回放结束序号，边界规则沿用委托
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void replayJournalStep(long snapshotId, long seqFrom, long seqTo, ExchangeApi api) {
        delegate.replayJournalStep(snapshotId, seqFrom, seqTo, api);
    }
    /**
     * 按初始状态配置回放委托日志，并返回其报告的回放序号。
     * @param initialState 全量回放使用的快照及启动状态配置
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public long replayJournalFull(InitialStateConfiguration initialState, ExchangeApi api) {
        return delegate.replayJournalFull(initialState, api);
    }
    /**
     * 由委托完成全量日志回放后启用日志；保留原接口方法名称和行为。
     * @param initialState 全量回放使用的快照及启动状态配置
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void replayJournalFullAndThenEnableJouraling(
            InitialStateConfiguration initialState, ExchangeApi api) {
        delegate.replayJournalFullAndThenEnableJouraling(initialState, api);
    }
}
