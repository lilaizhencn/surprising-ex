package com.surprising.trading.order.model;

import java.util.List;

public final class VipTierModels {
    private VipTierModels() {}

    public record VipTierDefinition(
            int tierLevel,
            String tierCode,
            long min30DayVolumeUsd,
            long minAssetBalanceUsd,
            long makerFeeRatePpm,
            long takerFeeRatePpm
    ) {}

    public record UserVipStatusResponse(
            long userId,
            int currentTierLevel,
            String currentTierCode,
            long makerFeeRatePpm,
            long takerFeeRatePpm,
            long rolling30DayVolumeUsd,
            long nextTierMinVolumeUsd,
            long volumeNeededForNextTier
    ) {}

    public static final List<VipTierDefinition> VIP_TIERS = List.of(
            new VipTierDefinition(0, "VIP0", 0L, 0L, 200L, 500L),
            new VipTierDefinition(1, "VIP1", 100_000L, 10_000L, 180L, 450L),
            new VipTierDefinition(2, "VIP2", 1_000_000L, 50_000L, 150L, 400L),
            new VipTierDefinition(3, "VIP3", 5_000_000L, 100_000L, 120L, 350L),
            new VipTierDefinition(4, "VIP4", 10_000_000L, 200_000L, 100L, 300L),
            new VipTierDefinition(5, "VIP5", 20_000_000L, 500_000L, 80L, 250L),
            new VipTierDefinition(6, "VIP6", 50_000_000L, 1_000_000L, 50L, 200L),
            new VipTierDefinition(7, "VIP7", 100_000_000L, 2_000_000L, 20L, 180L),
            new VipTierDefinition(8, "VIP8_MM1", 200_000_000L, 5_000_000L, 0L, 150L),
            new VipTierDefinition(9, "VIP9_MM2", 500_000_000L, 10_000_000L, -50L, 120L)
    );
}
