package com.surprising.aeron.protocol;

public record BalanceAdjustmentCommand(String asset, long deltaUnits) {

    public BalanceAdjustmentCommand {
        // 零金额用于以原指令身份建立账户记录，不增加或扣减资金。
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("balance adjustment requires asset");
        }
    }
}
