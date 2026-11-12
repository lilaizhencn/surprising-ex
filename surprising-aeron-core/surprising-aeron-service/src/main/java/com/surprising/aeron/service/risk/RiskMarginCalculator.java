package com.surprising.aeron.service.risk;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RiskMarginCalculator {

    /** 拒绝未注册币对、旧价格序号或缺失的期权风险价格，写入新行情并重建本币对扫描。 */
    public static void applyMarkPriceRuntime(ApplyMarkPriceCommand command, AccountBook accountBook,
                                             IdIndex identities) {
        if (command == null || accountBook == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        CoreInstrument instrument = accountBook.requireInstrument(command.instrumentId());
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        MarkPrice current = accountBook.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        requireOptionRiskPrices(instrument, command.indexPriceTicks(), command.forwardPriceTicks());
        accountBook.putMarkPrice(new MarkPrice(symbolId, instrument, command.markPriceTicks(),
                command.indexPriceTicks(), command.forwardPriceTicks(), command.priceSequence(),
                command.generatedAtEpochMillis(), command.lastPriceTicks()));
        RiskScanner.State currentScan = accountBook.riskScan(symbolId);
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        int accountLaneId = currentScan != null && !currentScan.riskComplete()
                ? currentScan.accountLaneId() : 0;
        boolean disabled = !accountBook.riskScanControl().enabled();
        var progress = !disabled && currentScan != null && !currentScan.riskComplete()
                ? currentScan.laneProgress() : java.util.List.<com.surprising.aeron.model.RiskLaneProgress>of();
        RiskScanner.State nextScan = new RiskScanner.State(symbolId, accountLaneId,
                command.priceSequence(), scanStart, disabled,
                true, 0, 0, 0, 0, 0, 0, 0, 0,
                currentScan == null ? 0 : currentScan.lastScheduledRevision(), progress);
        accountBook.setMarketRevision(Math.incrementExact(accountBook.revision()));
        accountBook.putRiskScan(nextScan);
        accountBook.setMetadata(accountBook.productLine(), Math.incrementExact(accountBook.revision()));
    }

    private static void requireOptionRiskPrices(CoreInstrument instrument, long indexPriceTicks,
                                               long forwardPriceTicks) {
        if (instrument.contractType().isOption() && (indexPriceTicks <= 0 || forwardPriceTicks <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
    }

    /** 空仓无需强平。 */
    public static boolean canLiquidate(com.surprising.instrument.api.model.ContractType type, long quantity) {
        return quantity != 0 && !(type.isOption() && quantity > 0);
    }

    public record Snapshot(long userId, int symbolId, CorePositionSide positionSide,
                                  long priceSequence, long equityUnits, long unrealizedPnlUnits,
                                  long maintenanceMarginUnits, long marginRatioPpm) {
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
