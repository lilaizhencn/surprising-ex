package com.surprising.aeron.service.risk;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.product.option.OptionRiskRules;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 衍生品标记价和风险续扫的命令入口。先校验行情序号及期权风险价格，写行情并安排扫描；后续由 RiskScanCoordinator 准备用户页并交所属账户 Lane 估值。 构造约定：只提供风险命令入口，不创建额外的账户或风险状态容器。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeDerivativeRiskProcessor {

    /**
     * 拒绝未注册币对、旧价格序号或缺失的期权风险价格，写入新行情并重建本币对扫描；旧轮未完成时保留已完成用户边界，丢弃旧用户累计值，最后推进市场与全局修订号。
     * @param command 包含币对、标记价、指数价、远期价、最新价及价格序号的本次行情命令
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public static void applyMarkPriceRuntime(ApplyMarkPriceCommand command, TradingAccountRuntime runtime,
                                             RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        // 运行时已经注册的币对配置，提供结算资产和期权价格校验条件。
        CoreInstrument instrument = requireInstrument(runtime, command.instrumentId());
        // 当前币对在运行时身份表中的整数编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 该币对上一版行情，新命令的价格序号必须严格大于它。
        MarkPriceRuntime current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        OptionRiskRules.requireOptionRiskPrices(instrument, command.indexPriceTicks(), command.forwardPriceTicks());
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, instrument, command.markPriceTicks(),
                command.indexPriceTicks(), command.forwardPriceTicks(), command.priceSequence(),
                command.generatedAtEpochMillis(), command.lastPriceTicks()));
        // 该币对已有的扫描进度，未完成时需要保留旧轮的已完成用户边界。
        RiskScanRuntime currentScan = runtime.riskScan(symbolId);
        // 当前轮最早启动的价格序号；旧轮没完时保留，用于完成后判断是否需要重扫最新价格。
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        // 旧轮尚未结束时的当前 Lane 编号，否则从 Lane 0 开始。
        int accountLaneId = currentScan != null && !currentScan.riskComplete()
                ? currentScan.accountLaneId() : 0;
        // 当前风险扫描控制是否停用；停用时新扫描直接标为风险已完成。
        boolean disabled = !runtime.riskScanControl().enabled();
        // 新价格对应的扫描记录，先重置当前用户累计值，再补回各 Lane 的已完成边界。
        RiskScanRuntime nextScan = new RiskScanRuntime(symbolId, accountLaneId,
                command.priceSequence(), scanStart, disabled,
                true, 0, 0, 0, 0, 0, 0, 0, 0,
                currentScan == null ? 0 : currentScan.lastScheduledRevision(), java.util.List.of());
        if (!disabled && currentScan != null && !currentScan.riskComplete() && !currentScan.laneProgress().isEmpty()) {
            // 新价格使当前用户累计值失效；保留每个 Lane 已完成用户和轮次边界。
            var progress = new java.util.ArrayList<RiskLaneProgress>(
                    currentScan.laneProgress().size());
            for (var /* 当前复制的一个 Lane 旧游标，只沿用其最后完成用户与轮次完成标记。 */ lane : currentScan.laneProgress()) progress.add(
                    new RiskLaneProgress(lane.lastUserId(), lane.complete(),
                            0, 0, "-", 0, 0, 0, 0, 0));
            nextScan = nextScan.withLaneProgress(progress);
        }
        runtime.setMarketRevision(Math.incrementExact(runtime.revision()));
        runtime.putRiskScan(nextScan);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    /**
     * 校验预算和用户来源，扫描停用或没有未完成币对时直接返回，否则选一份扫描交给指定币对入口。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param indexedUserIds 调用方提供的有序用户编号集合；没有持仓用户索引时据此筛选各 Lane 的候选用户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public static void applyContinuationRuntime(int maxWork, Iterable<Long> indexedUserIds,
                                                TradingAccountRuntime runtime,
                                                RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || indexedUserIds == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!runtime.riskScanControl().enabled()) return;
        // 选中或指定币对当前未完成的扫描，没有待处理风险时直接结束本入口。
        RiskScanRuntime sourceScan = runtime.firstRiskIncompleteScan();
        if (sourceScan == null) return;
        applyContinuationRuntime(maxWork, sourceScan.symbolId(), indexedUserIds, runtime, identities);
    }

    /**
     * 对指定币对核对当前行情与扫描序号，按调用上限和自适应预算中较小值继续一片，使用调用方有序用户集合，最后增加全局修订号并返回工作量。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param symbolId 要继续扫描的币对整数编号
     * @param indexedUserIds 调用方提供的有序用户编号集合；没有持仓用户索引时据此筛选各 Lane 的候选用户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public static int applyContinuationRuntime(int maxWork, int symbolId, Iterable<Long> indexedUserIds,
                                               TradingAccountRuntime runtime,
                                               RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || indexedUserIds == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!runtime.riskScanControl().enabled()) return 0;
        // 选中或指定币对当前未完成的扫描，没有待处理风险时直接结束本入口。
        RiskScanRuntime sourceScan = runtime.riskScan(symbolId);
        if (sourceScan == null || sourceScan.riskComplete()) return 0;
        // 从整数币对编号还原的外部编号，或同步扫描映射中的币对键。
        String instrumentId = identities.instrumentId(sourceScan.symbolId());
        // 运行时已经注册的币对配置，提供结算资产和期权价格校验条件。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        // 本币对当前行情，价格序号必须与选中扫描一致。
        MarkPriceRuntime mark = runtime.markPrice(sourceScan.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != sourceScan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        // 统一续扫流程本片实际消耗的工作量，回给共享预算调用方。
        int completedWork = RiskScanCoordinator.runSlice(Math.min(maxWork, RiskScanCoordinator.adaptiveBudget(runtime.riskScanControl().scanBatchSize(),
                        runtime.incompleteRiskScanCount())), sourceScan.symbolId(), null, indexedUserIds, runtime, identities);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return completedWork;
    }

    /**
     * 对指定币对继续一片，使用持仓用户索引按 Lane 定位候选账户；输入、预算和行情序号通过校验后执行，再增加修订号并返回工作量。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param symbolId 要继续扫描的币对整数编号
     * @param positionUsers 按币对和 Lane 定位有持仓用户的索引，避免为每轮风险扫描遍历全体账户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public static int applyContinuationRuntime(int maxWork, int symbolId, PositionUserIndex positionUsers,
                                               TradingAccountRuntime runtime,
                                               RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || positionUsers == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!runtime.riskScanControl().enabled()) return 0;
        // 选中或指定币对当前未完成的扫描，没有待处理风险时直接结束本入口。
        RiskScanRuntime sourceScan = runtime.riskScan(symbolId);
        if (sourceScan == null || sourceScan.riskComplete()) return 0;
        // 从整数币对编号还原的外部编号，或同步扫描映射中的币对键。
        String instrumentId = identities.instrumentId(sourceScan.symbolId());
        // 运行时已经注册的币对配置，提供结算资产和期权价格校验条件。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        // 本币对当前行情，价格序号必须与选中扫描一致。
        MarkPriceRuntime mark = runtime.markPrice(sourceScan.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != sourceScan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        // 统一续扫流程本片实际消耗的工作量，回给共享预算调用方。
        int completedWork = RiskScanCoordinator.runSlice(Math.min(maxWork, RiskScanCoordinator.adaptiveBudget(runtime.riskScanControl().scanBatchSize(),
                        runtime.incompleteRiskScanCount())), symbolId, positionUsers, null, runtime, identities);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return completedWork;
    }

    /**
     * 要求当前运行时已经注册该币对，缺失时返回 INSTRUMENT_NOT_FOUND 业务拒绝。
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param instrumentId 对外的币对或合约编号，需要已注册到当前运行时
     */
    private static CoreInstrument requireInstrument(TradingAccountRuntime runtime, String instrumentId) {
        // 运行时已经注册的币对配置，提供结算资产和期权价格校验条件。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        return instrument;
    }

}
