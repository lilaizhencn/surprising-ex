package com.surprising.account.provider.model;

import com.surprising.account.api.model.AccountCommandStatus;
import java.util.List;

public record AccountCommandTerminalResult(
        AccountCommandStatus status,
        String resultPayload,
        String errorCode,
        String errorMessage,
        List<LedgerDelta> ledgerDeltas) {

    public AccountCommandTerminalResult {
        if (status == null) {
            throw new IllegalArgumentException("账户命令终态不能为空");
        }
        ledgerDeltas = ledgerDeltas == null ? List.of() : List.copyOf(ledgerDeltas);
    }

    /**
     * 账户命令对产品账户账本产生的不可变净变更。
     *
     * <p>该明细只用于异步审计投影，不参与本地 reducer 裁决。冻结/解冻等可用与锁定之间
     * 的转移不产生净权益变更，因此不会生成账本行。</p>
     */
    public record LedgerDelta(String asset,
                              long amountUnits,
                              long balanceAfterUnits,
                              String referenceType,
                              String referenceId,
                              String reason,
                              String symbol) {

        public LedgerDelta {
            if (asset == null || asset.isBlank() || !asset.trim().toUpperCase().matches("[A-Z0-9]{2,20}")) {
                throw new IllegalArgumentException("账本资产无效");
            }
            asset = asset.trim().toUpperCase();
            if (amountUnits == 0L) {
                throw new IllegalArgumentException("账本变更金额无效");
            }
            if (referenceType == null || referenceType.isBlank()
                    || referenceId == null || referenceId.isBlank()) {
                throw new IllegalArgumentException("账本引用不能为空");
            }
            referenceType = referenceType.trim().toUpperCase();
            referenceId = referenceId.trim();
            reason = reason == null || reason.isBlank() ? referenceType : reason.trim();
            symbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        }
    }
}
