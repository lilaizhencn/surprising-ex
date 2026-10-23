package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Matcher 命令证据进度表，记录每个撮合分片及控制分片已经发号和发布到哪里，不是资金财库。每个分片由一个所有者写，读侧借 acquire/release 及外部快照栅栏取进度；原生订单簿分片与业务证据分片分别跟踪，不能把不同分片原生序号当成同一全局序号比较。
 */
final class MatcherEvidenceLedger {

    /** 每个逻辑分片在序号数组中占用的十六个 long 间隔，只有首项存该分片值。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 以 acquire/release 访问 long 数组元素的 VarHandle，未使用 CAS 协调同分片多写者。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /** 本产品线撮合分片拓扑，用于检查有效分片范围。 */
    private final LaneTopology topology;
    /** 实际撮合分片数加一个控制分片。 */
    private final int shardCount;
    /** 各业务分片已经分配的序号，恢复时至少达到 sequenceFloor。 */
    private final long[] issuedSequences;
    /** 各业务分片已经绑定或直接发布证据的序号，快照导出此数组。 */
    private final long[] shardSequences;
    /** 本轮运行已观察到的各实际原生分片序号，恢复构造不从历史进度填入。 */
    private final long[] shardNativeSequences;

    /**
     * 以零下限和从控制分片到各撮合分片的全零进度创建新证据账本。
     * @param topology 包含撮合分片数量的有效 Lane 拓扑
     */
    MatcherEvidenceLedger(LaneTopology topology) {
        this(topology, 0, initialProgress(topology));
    }

    /**
     * 按拓扑创建留有间隔的序号数组，恢复每个分片已发布进度，已发号位置取给定下限与原进度较大值；原生序号数组从零开始。输入进度可以不按编号排列，但分片必须齐全且不能重复。
     * @param topology 包含撮合分片数量的有效 Lane 拓扑
     * @param sequenceFloor 仅恢复发号数组使用的非负下限，不直接替换各分片已发布进度
     * @param progress 控制分片及所有撮合分片的完整进度列表
     */
    MatcherEvidenceLedger(LaneTopology topology, long sequenceFloor, List<MatcherShardProgress> progress) {
        if (topology == null || sequenceFloor < 0 || progress == null
                || progress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("invalid matcher evidence restore");
        }
        this.topology = topology;
        this.shardCount = progress.size();
        // 逻辑分片数乘十六后的数组容量，使用精确乘法检查溢出。
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
        // 记录哪些逻辑分片已经装入，检测重复或缺失。
        boolean[] restored = new boolean[progress.size()];
        for (MatcherShardProgress /* 当前需要恢复的分片及其已发布序号。 */ shard : progress) {
            // 当前逻辑分片的零起点下标，负一控制分片映射到零。
            int index = index(shard.matcherShardId());
            if (restored[index]) throw new IllegalArgumentException("duplicate matcher shard progress");
            restored[index] = true;
            // 当前逻辑分片在留间隔数组中的实际偏移。
            int offset = offset(index);
            shardSequences[offset] = shard.matcherSequence();
            issuedSequences[offset] = Math.max(sequenceFloor, shard.matcherSequence());
        }
        for (boolean /* 当前分片是否已经从输入进度恢复。 */ present : restored) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
    }

    /**
     * 为指定业务分片递增已发号序号并发布，精确加法溢出时报错；发号不等于结果已发布，依赖同一分片单所有者调用，不是多生产者 CAS 发号器。
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     */
    long nextSequence(int matcherShardId) {
        // 当前逻辑分片在留间隔数组中的实际偏移。
        int offset = offset(index(matcherShardId));
        // 在当前已发号值上精确加一后的新序号。
        long next = Math.incrementExact((long) LONGS.getAcquire(issuedSequences, offset));
        LONGS.setRelease(issuedSequences, offset, next);
        return next;
    }

    /**
     * 按实际订单簿分片严格推进本轮原生序号，用于控制命令中已发生的原生调用；不改变业务证据发布进度。
     * @param shardId 此次实际原生控制调用所属的订单簿分片
     * @param nativeSequence 本次要严格推进的原生 exchange-core 序号
     */
    void recordControlNative(int shardId, long nativeSequence) {
        advanceStrictly(shardNativeSequences, index(shardId), nativeSequence,
                "matcher shard native sequence is not strictly increasing");
    }

    /**
     * 要求命令 UUID 非空，拆成原语高低位后交给同一证据绑定步骤。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandId 原命令非空 UUID
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     */
    CoreMatchingResult bind(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long sequence,
            int matcherShardId, int nativeMatcherShardId,
            CoreMatchingResult result) {
        if (commandId == null) throw new IllegalArgumentException("matcher evidence is required");
        return bind(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, sequence, matcherShardId, nativeMatcherShardId, result);
    }

    /**
     * 读取结果原生序号，正数时先推进其实际原生分片，再推进业务证据分片，最后原位绑定命令身份。两次序号写入不是事务，后一步失败时这里不回退此前已写进度。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     */
    CoreMatchingResult bind(
            long coreSequence,
            long commandIdMsb, long commandIdLsb,
            long orderId,
            long sequence,
            int matcherShardId, int nativeMatcherShardId,
            CoreMatchingResult result) {
        if (result == null) throw new IllegalArgumentException("matcher evidence is required");
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.nativeSequence();
        // 本次业务证据分片的数组逻辑下标。
        int matcherIndex = index(matcherShardId);
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, matcherIndex, sequence,
                "matcher shard sequence is not strictly increasing");
        return result.bindEvidenceInPlace(coreSequence, commandIdMsb, commandIdLsb, orderId,
                nativeSequence, sequence, matcherShardId);
    }

    /**
     * 要求 UUID 非空，拆成高低位后向现有结算事件直接发布原生成交或撤单结果，沿用原有事件租约。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandId 原命令非空 UUID
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishNative(
            long coreSequence, java.util.UUID commandId, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("native matcher publication is required");
        return publishNative(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, sequence, matcherShardId, nativeMatcherShardId, result, target);
    }

    /**
     * 检查结果和目标非空，正原生序号先按实际订单簿分片推进，再推进业务证据分片并向现有结算事件直接发布原生成交或撤单结果。失败不自动回退已写进度。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishNative(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher publication is required");
        // 本次业务证据分片的数组逻辑下标。
        int matcherIndex = index(matcherShardId);
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, matcherIndex, sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeResult(result, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 要求 UUID 非空，拆成高低位后向现有结算事件发布撤旧与放新两个替换结果，沿用原有事件租约。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandId 原命令非空 UUID
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param cancellation 替换操作中已经发生的撤旧原生结果
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishReplacement(
            long coreSequence, java.util.UUID commandId, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult cancellation,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("native matcher replacement publication is required");
        return publishReplacement(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, sequence, matcherShardId, nativeMatcherShardId, cancellation, result, target);
    }

    /**
     * 检查撤旧、放新结果和目标非空，以最终放新结果的原生序号推进实际分片，再推进业务证据并把两结果交给现有事件。不在此单独登记撤旧结果的原生序号，也不读取 aeronTimestamp，后续失败不回退已写序号。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param cancellation 替换操作中已经发生的撤旧原生结果
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishReplacement(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult cancellation,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (cancellation == null || result == null || target == null)
            throw new IllegalArgumentException("native matcher replacement publication is required");
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeReplacement(cancellation, result, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 要求 UUID 非空，拆成高低位后向现有结算事件指定下标发布一个批次子结果，沿用原有事件租约。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandId 原命令非空 UUID
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishBatchItem(
            long coreSequence, java.util.UUID commandId, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result, int index,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("native matcher batch publication is required");
        return publishBatchItem(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, sequence, matcherShardId, nativeMatcherShardId, result, index, target);
    }

    /**
     * 检查结果和目标非空，推进正原生序号及业务证据序号，再将结果和批次下标交给现有事件；本方法不额外检查目标批次下标，也不读取 aeronTimestamp，失败不回退已写序号。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishBatchItem(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result, int index,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher batch publication is required");
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeBatchItem(result, index, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 要求命令 UUID 非空，拆高低位后发布没有原生成功结果的拒绝事实。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandId 原命令非空 UUID
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param resultCode 要保存的非空拒绝结果码
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    void publishRejection(
            long coreSequence, java.util.UUID commandId, long orderId,
            long sequence, int matcherShardId, String resultCode,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("matcher rejection publication is required");
        publishRejection(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, sequence, matcherShardId, resultCode, target);
    }

    /**
     * 要求拒绝码非空且目标存在，只推进业务证据分片，再向现有事件发布拒绝身份；不推进原生序号数组。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param resultCode 要保存的非空拒绝结果码
     * @param target 当前命令持有租约的原 MatcherSettlementEvent，不新建账户结算状态
     */
    void publishRejection(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, String resultCode,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("matcher rejection publication is required");
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectRejection(resultCode, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
    }

    /**
     * 逐个以 acquire 读取已发布的业务分片进度，按负一控制分片到各撮合分片顺序返回只读列表。跨分片一致切面由调用方原快照栅栏保证，本方法不锁住全部所有者线程。
     */
    List<MatcherShardProgress> snapshot() {
        // 本次快照或初始零进度的列表构建器，返回前固定列表。
        ArrayList<MatcherShardProgress> progress = new ArrayList<>(shardCount);
        for (/* 当前逻辑分片的零起点下标，负一控制分片映射到零。 */ int index = 0; index < shardCount; index++) {
            // 当前逻辑分片在留间隔数组中的实际偏移。
            int offset = offset(index);
            progress.add(new MatcherShardProgress(index - 1,
                    (long) LONGS.getAcquire(shardSequences, offset)));
        }
        return List.copyOf(progress);
    }

    /**
     * 检查分片编号在控制负一到实际撮合分片范围内，再加一转成数组逻辑下标。
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     */
    private int index(int matcherShardId) {
        if (matcherShardId < -1 || matcherShardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return matcherShardId + 1;
    }

    /**
     * 在单所有者约定下读取原值，要求新值严格更大，再以 release 发布；不是 CAS 重试，也不替调用方协调两个线程同时写同一分片。
     * @param values 指定分片的原生或业务序号数组
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     * @param next 本次期望写入的严格递增序号
     * @param message 序号倒退或重复时使用的错误说明
     */
    private static void advanceStrictly(long[] values, int index, long next, String message) {
        // 当前逻辑分片在留间隔数组中的实际偏移。
        int offset = offset(index);
        // 从对应数组以 acquire 取得的已发布原值。
        long previous = (long) LONGS.getAcquire(values, offset);
        if (next <= previous) {
            throw new IllegalStateException(message + " shard=" + (index - 1)
                    + " previous=" + previous + " next=" + next);
        }
        // 每个撮合分片只有一个所有者写线程，以 release 发布即可供原快照栅栏读取；
        // 这里不为每条撮合命令增加 CAS，也不支持两个线程同时写同一分片。
        LONGS.setRelease(values, offset, next);
    }

    /**
     * 把逻辑分片下标乘十六转换为实际数组偏移，给相邻分片写入留间隔。
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     */
    private static int offset(int index) {
        return index * CACHE_LINE_LONGS;
    }

    /**
     * 按拓扑生成负一控制分片及所有撮合分片的零进度，只固定外层列表。
     * @param topology 包含撮合分片数量的有效 Lane 拓扑
     */
    private static List<MatcherShardProgress> initialProgress(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("matcher topology is required");
        // 本次快照或初始零进度的列表构建器，返回前固定列表。
        ArrayList<MatcherShardProgress> progress = new ArrayList<>(topology.matchingEngineCount() + 1);
        for (/* 初始化时从负一控制分片到最后一个撮合分片的编号。 */ int shardId = -1; shardId < topology.matchingEngineCount(); shardId++) {
            progress.add(new MatcherShardProgress(shardId, 0));
        }
        return List.copyOf(progress);
    }
}
