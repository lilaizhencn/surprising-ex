package com.surprising.aeron.service.risk;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RiskMarginCalculator {

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
        MarkPrice current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        requireOptionRiskPrices(instrument, command.indexPriceTicks(), command.forwardPriceTicks());
        runtime.putMarkPrice(new MarkPrice(symbolId, instrument, command.markPriceTicks(),
                command.indexPriceTicks(), command.forwardPriceTicks(), command.priceSequence(),
                command.generatedAtEpochMillis(), command.lastPriceTicks()));
        // 该币对已有的扫描进度，未完成时需要保留旧轮的已完成用户边界。
        RiskScanner.State currentScan = runtime.riskScan(symbolId);
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
        RiskScanner.State nextScan = new RiskScanner.State(symbolId, accountLaneId,
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

    /**
     * 空仓无需强平；当前支持的非组合保证金模型下，已全额支付权利金的期权多头也不进入保证金强平。
     * @param type 用来区分期权和其他产品的合约类型
     * @param quantity 带方向的持仓数量步数，正数多头、负数空头、0 为空仓
     */
    public static boolean canLiquidate(com.surprising.instrument.api.model.ContractType type, long quantity) {
        return quantity != 0 && !(type.isOption() && quantity > 0);
    }


    public record Snapshot(long userId, int symbolId, CorePositionSide positionSide,
                                  long priceSequence, long equityUnits, long unrealizedPnlUnits,
                                  long maintenanceMarginUnits, long marginRatioPpm) {
    /**
     * 检查用户、币对整数编号、持仓腿和价格序号有效，维持保证金与风险占比非负。
     */
    public Snapshot {
        if (userId <= 0 || symbolId < 0 || positionSide == null || priceSequence <= 0
                || maintenanceMarginUnits < 0 || marginRatioPpm < 0) {
            throw new IllegalArgumentException("invalid runtime risk snapshot");
        }
    }

    /** 按捕获的风险比例读取等级，保持原固定阈值规则。 */
    public CoreRiskStatus status() {
        return CoreRiskStatus.of(marginRatioPpm);
    }
}

}
