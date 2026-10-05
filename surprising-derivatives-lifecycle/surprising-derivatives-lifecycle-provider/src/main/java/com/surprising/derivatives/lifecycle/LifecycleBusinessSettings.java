package com.surprising.derivatives.lifecycle;

/** 后台保存的生命周期业务参数；连接、线程和节点身份不属于该记录。 */
public record LifecycleBusinessSettings(Funding funding, Liquidation liquidation, Insurance insurance, Adl adl) {
    public LifecycleBusinessSettings {
        if (funding == null || liquidation == null || insurance == null || adl == null)
            throw new IllegalArgumentException("complete lifecycle business settings are required");
    }

    public record Funding(boolean calculationEnabled, boolean settlementEnabled, boolean coordinationEnabled,
            long publishDelayMs, long maxMarkAgeMs, long maxRateAgeMs, long settleDelayMs, int batchSize, int maxPagesPerRun,
            long leaseDurationMs) {
        public Funding {
            range("funding.publishDelayMs", publishDelayMs, 25, 3_600_000);
            range("funding.maxMarkAgeMs", maxMarkAgeMs, 1, 600_000);
            range("funding.maxRateAgeMs", maxRateAgeMs, 1, 600_000);
            range("funding.settleDelayMs", settleDelayMs, 25, 3_600_000);
            range("funding.batchSize", batchSize, 1, 10_000);
            range("funding.maxPagesPerRun", maxPagesPerRun, 1, 1_000);
            range("funding.leaseDurationMs", leaseDurationMs, 1_000, 600_000);
        }
    }

    public record Liquidation(boolean enabled, long feeRatePpm, long delayMs, int workBatchSize,
            int maxPagesPerRun, int maxWorkBytes) {
        public Liquidation {
            range("liquidation.feeRatePpm", feeRatePpm, 0, 1_000_000);
            range("liquidation.delayMs", delayMs, 25, 3_600_000);
            range("liquidation.workBatchSize", workBatchSize, 1, 1_000);
            range("liquidation.maxPagesPerRun", maxPagesPerRun, 1, 1_000);
            range("liquidation.maxWorkBytes", maxWorkBytes, 256, 1_048_576);
        }
    }

    public record Insurance(boolean enabled, long scanDelayMs, int batchSize) {
        public Insurance {
            range("insurance.scanDelayMs", scanDelayMs, 25, 3_600_000);
            range("insurance.batchSize", batchSize, 1, 10_000);
        }
    }

    public record Adl(boolean enabled, long scanDelayMs, int batchSize, int maxDeleveragesPerDeficit,
            int candidateMultiplier) {
        public Adl {
            range("adl.scanDelayMs", scanDelayMs, 25, 3_600_000);
            range("adl.batchSize", batchSize, 1, 1_000);
            range("adl.maxDeleveragesPerDeficit", maxDeleveragesPerDeficit, 1, 1_000);
            range("adl.candidateMultiplier", candidateMultiplier, 1, 1_000);
            if ((long) maxDeleveragesPerDeficit * candidateMultiplier > 1_000)
                throw new IllegalArgumentException("ADL candidate count must not exceed 1000");
        }
    }

    public static LifecycleBusinessSettings initial() {
        return new LifecycleBusinessSettings(new Funding(true, true, true, 1_000, 10_000, 5_000, 1_000, 20, 8, 15_000),
                new Liquidation(true, 3_000, 25, 256, 8, 1_048_576),
                new Insurance(true, 1_000, 100), new Adl(true, 1_000, 50, 20, 5));
    }

    private static void range(String field, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(field + " must be in [" + minimum + "," + maximum + "]");
    }
}
