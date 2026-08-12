package com.surprising.insurance.provider.service;

import com.surprising.insurance.provider.config.InsuranceProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一执行保险基金运行参数查询、校验和更新。
 */
@Service
public class InsuranceRuntimeConfigService {

    private final InsuranceProperties properties;

    public InsuranceRuntimeConfigService(InsuranceProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> current() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("enabled", properties.getCoverage().isEnabled());
        coverage.put("scanDelayMs", properties.getCoverage().getScanDelayMs());
        coverage.put("batchSize", properties.getCoverage().getBatchSize());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("coverage", coverage);
        return response;
    }

    public Map<String, Object> update(Boolean coverageEnabled,
                                      Long scanDelayMs,
                                      Integer batchSize) {
        if (coverageEnabled != null) {
            properties.getCoverage().setEnabled(coverageEnabled);
        }
        if (scanDelayMs != null) {
            properties.getCoverage().setScanDelayMs(nonNegative(scanDelayMs, "scanDelayMs"));
        }
        if (batchSize != null) {
            properties.getCoverage().setBatchSize(bounded(batchSize, 1, 10_000, "batchSize"));
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
