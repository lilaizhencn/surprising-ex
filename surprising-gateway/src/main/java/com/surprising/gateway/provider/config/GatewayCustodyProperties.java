package com.surprising.gateway.provider.config;

import java.math.BigDecimal;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * Custody wallet, withdrawal, and cross-product transfer configuration models.
 */
public final class GatewayCustodyProperties {

    private GatewayCustodyProperties() {
    }

    @Getter
    public static class CustodyWallet {
        @Setter
        private boolean enabled = false;
        @Setter
        private String baseUrl = "http://localhost:8002";
        @Setter
        private String apiKey = "";
        @Setter
        private String apiSecret = "";
        @Setter
        private String webhookSecret = "";
        private Duration requestTimeout = Duration.ofSeconds(10);

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null || requestTimeout.isNegative()
                    || requestTimeout.isZero() ? Duration.ofSeconds(10) : requestTimeout;
        }
    }

    @Getter
    public static class Withdrawal {
        @Setter
        private BigDecimal singleApprovalThresholdUsdt = new BigDecimal("10000");
        @Setter
        private BigDecimal dailyLimitUsdt = new BigDecimal("50000");
        private Duration valuationMaxAge = Duration.ofSeconds(30);
        private Duration failureReconciliationDelay = Duration.ofSeconds(30);

        public void setValuationMaxAge(Duration value) {
            this.valuationMaxAge = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }

        public void setFailureReconciliationDelay(Duration value) {
            this.failureReconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(30) : value;
        }
    }

    @Getter
    public static class ProductTransfer {
        private Duration reconciliationDelay = Duration.ofSeconds(5);
        private int reconciliationBatchSize = 100;
        private BigDecimal verificationThresholdUsdt = new BigDecimal("10000");

        public void setReconciliationDelay(Duration value) {
            reconciliationDelay = value == null || value.isZero() || value.isNegative()
                    ? Duration.ofSeconds(5) : value;
        }

        public void setReconciliationBatchSize(int value) {
            reconciliationBatchSize = value <= 0 ? 100 : Math.min(value, 1000);
        }

        public void setVerificationThresholdUsdt(BigDecimal value) {
            verificationThresholdUsdt = value == null || value.signum() < 0
                    ? new BigDecimal("10000") : value;
        }
    }
}
