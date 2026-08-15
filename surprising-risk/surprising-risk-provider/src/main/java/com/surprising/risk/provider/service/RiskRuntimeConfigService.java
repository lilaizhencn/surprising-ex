package com.surprising.risk.provider.service;

import com.surprising.risk.provider.config.RiskProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一执行风控运行参数查询、校验和更新。
 */
@Service
public class RiskRuntimeConfigService {

    private final RiskProperties properties;

    public RiskRuntimeConfigService(RiskProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> current() {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("enabled", properties.getCalculation().isEnabled());
        calculation.put("scanDelayMs", properties.getCalculation().getScanDelayMs());
        calculation.put("maxMarkAge", properties.getCalculation().getMaxMarkAge().toString());
        calculation.put("scanBatchSize", properties.getCalculation().getScanBatchSize());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("marginPolicySource", "AERON_CORE_INSTRUMENT");
        response.put("calculation", calculation);
        return response;
    }

    public Map<String, Object> update(Boolean calculationEnabled,
                                      Long scanDelayMs,
                                      Integer scanBatchSize) {
        if (calculationEnabled != null) {
            properties.getCalculation().setEnabled(calculationEnabled);
        }
        if (scanDelayMs != null) {
            properties.getCalculation().setScanDelayMs(nonNegative(scanDelayMs, "scanDelayMs"));
        }
        if (scanBatchSize != null) {
            properties.getCalculation().setScanBatchSize(
                    bounded(scanBatchSize, 1, 10_000, "scanBatchSize"));
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
}
