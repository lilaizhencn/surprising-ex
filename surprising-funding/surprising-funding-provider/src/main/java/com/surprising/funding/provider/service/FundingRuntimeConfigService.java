package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一执行资金费运行参数查询、校验和更新。
 */
@Service
public class FundingRuntimeConfigService {

    private final FundingProperties properties;

    public FundingRuntimeConfigService(FundingProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> current() {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("enabled", properties.getCalculation().isEnabled());
        calculation.put("publishDelayMs", properties.getCalculation().getPublishDelayMs());
        calculation.put("maxMarkAge", properties.getCalculation().getMaxMarkAge().toString());

        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("enabled", properties.getSettlement().isEnabled());
        settlement.put("settleDelayMs", properties.getSettlement().getSettleDelayMs());
        settlement.put("batchSize", properties.getSettlement().getBatchSize());
        settlement.put("paymentPageSize", properties.getSettlement().getPaymentPageSize());
        settlement.put("maxPagesPerRun", properties.getSettlement().getMaxPagesPerRun());
        settlement.put("reconcileBatchSize", properties.getSettlement().getReconcileBatchSize());

        Map<String, Object> coordination = new LinkedHashMap<>();
        coordination.put("enabled", properties.getCoordination().isEnabled());
        coordination.put("nodeId", properties.getCoordination().getNodeId());
        coordination.put("leaseDuration", properties.getCoordination().getLeaseDuration().toString());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("calculation", calculation);
        response.put("settlement", settlement);
        response.put("coordination", coordination);
        return response;
    }

    public Map<String, Object> update(Boolean calculationEnabled,
                                      Boolean settlementEnabled,
                                      Boolean coordinationEnabled,
                                      Long calculationPublishDelayMs,
                                      Long settleDelayMs,
                                      Integer settlementBatchSize,
                                      Integer paymentPageSize,
                                      Integer maxPagesPerRun,
                                      Integer reconcileBatchSize) {
        if (calculationEnabled != null) {
            properties.getCalculation().setEnabled(calculationEnabled);
        }
        if (settlementEnabled != null) {
            properties.getSettlement().setEnabled(settlementEnabled);
        }
        if (coordinationEnabled != null) {
            properties.getCoordination().setEnabled(coordinationEnabled);
        }
        if (calculationPublishDelayMs != null) {
            properties.getCalculation().setPublishDelayMs(
                    nonNegative(calculationPublishDelayMs, "calculationPublishDelayMs"));
        }
        if (settleDelayMs != null) {
            properties.getSettlement().setSettleDelayMs(positive(settleDelayMs, "settleDelayMs"));
        }
        if (settlementBatchSize != null) {
            properties.getSettlement().setBatchSize(
                    bounded(settlementBatchSize, 1, 10_000, "settlementBatchSize"));
        }
        if (paymentPageSize != null) {
            properties.getSettlement().setPaymentPageSize(
                    bounded(paymentPageSize, 1, 10_000, "paymentPageSize"));
        }
        if (maxPagesPerRun != null) {
            properties.getSettlement().setMaxPagesPerRun(
                    bounded(maxPagesPerRun, 1, 1_000, "maxPagesPerRun"));
        }
        if (reconcileBatchSize != null) {
            properties.getSettlement().setReconcileBatchSize(
                    bounded(reconcileBatchSize, 1, 10_000, "reconcileBatchSize"));
        }
        return current();
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
