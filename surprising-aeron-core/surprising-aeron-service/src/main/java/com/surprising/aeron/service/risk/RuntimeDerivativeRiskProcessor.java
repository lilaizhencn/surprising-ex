package com.surprising.aeron.service.risk;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
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
        CoreInstrument instrument = runtime.requireInstrument(command.instrumentId());
        // 当前币对在运行时身份表中的整数编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 该币对上一版行情，新命令的价格序号必须严格大于它。
        MarkPriceRuntime current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        requireOptionRiskPrices(instrument, command.indexPriceTicks(), command.forwardPriceTicks());
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
        // 旧游标是不可变值。新市场修订号使其中的用户部分累计失效，所属 Lane
        // 在首次续扫时按 marketRevision 重置；这里沿用已完成用户边界，不逐 Lane 复制。
        var progress = !disabled && currentScan != null && !currentScan.riskComplete()
                ? currentScan.laneProgress() : java.util.List.<com.surprising.aeron.model.RiskLaneProgress>of();
        RiskScanRuntime nextScan = new RiskScanRuntime(symbolId, accountLaneId,
                command.priceSequence(), scanStart, disabled,
                true, 0, 0, 0, 0, 0, 0, 0, 0,
                currentScan == null ? 0 : currentScan.lastScheduledRevision(), progress);
        runtime.setMarketRevision(Math.incrementExact(runtime.revision()));
        runtime.putRiskScan(nextScan);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    private static void requireOptionRiskPrices(CoreInstrument instrument, long indexPriceTicks,
                                               long forwardPriceTicks) {
        if (instrument.contractType().isOption() && (indexPriceTicks <= 0 || forwardPriceTicks <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
    }
}
