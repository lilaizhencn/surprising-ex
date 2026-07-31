package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一执行强平运行参数查询、校验和更新。
 */
@Service
public class LiquidationRuntimeConfigService {

    private final LiquidationProperties properties;

    public LiquidationRuntimeConfigService(LiquidationProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> current() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("enabled", properties.getExecution().isEnabled());
        execution.put("liquidationFeeRatePpm", properties.getExecution().getLiquidationFeeRatePpm());

        Map<String, Object> sizing = new LinkedHashMap<>();
        sizing.put("normalCloseRatioPpm", properties.getSizing().getNormalCloseRatioPpm());
        sizing.put("severeMarginRatioPpm", properties.getSizing().getSevereMarginRatioPpm());
        sizing.put("severeCloseRatioPpm", properties.getSizing().getSevereCloseRatioPpm());
        sizing.put("fullCloseMarginRatioPpm", properties.getSizing().getFullCloseMarginRatioPpm());
        sizing.put("minCloseSteps", properties.getSizing().getMinCloseSteps());

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("maxMarkAge", properties.getRisk().getMaxMarkAge().toString());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("execution", execution);
        response.put("sizing", sizing);
        response.put("risk", risk);
        return response;
    }

    public Map<String, Object> update(Boolean executionEnabled,
                                      Long liquidationFeeRatePpm,
                                      Long normalCloseRatioPpm,
                                      Long severeCloseRatioPpm,
                                      Long fullCloseMarginRatioPpm,
                                      Long minCloseSteps) {
        if (executionEnabled != null) {
            properties.getExecution().setEnabled(executionEnabled);
        }
        if (liquidationFeeRatePpm != null) {
            properties.getExecution().setLiquidationFeeRatePpm(
                    nonNegative(liquidationFeeRatePpm, "liquidationFeeRatePpm"));
        }
        if (normalCloseRatioPpm != null) {
            properties.getSizing().setNormalCloseRatioPpm(
                    nonNegative(normalCloseRatioPpm, "normalCloseRatioPpm"));
        }
        if (severeCloseRatioPpm != null) {
            properties.getSizing().setSevereCloseRatioPpm(
                    nonNegative(severeCloseRatioPpm, "severeCloseRatioPpm"));
        }
        if (fullCloseMarginRatioPpm != null) {
            properties.getSizing().setFullCloseMarginRatioPpm(
                    nonNegative(fullCloseMarginRatioPpm, "fullCloseMarginRatioPpm"));
        }
        if (minCloseSteps != null) {
            properties.getSizing().setMinCloseSteps(positive(minCloseSteps, "minCloseSteps"));
        }
        return current();
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
