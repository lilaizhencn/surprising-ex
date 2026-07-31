package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一执行 ADL 运行参数查询、校验和更新。
 */
@Service
public class AdlRuntimeConfigService {

    private final AdlProperties properties;

    public AdlRuntimeConfigService(AdlProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> current() {
        Map<String, Object> scanner = new LinkedHashMap<>();
        scanner.put("enabled", properties.getScanner().isEnabled());
        scanner.put("scanDelayMs", properties.getScanner().getScanDelayMs());
        scanner.put("minDeficitAgeMs", properties.getScanner().getMinDeficitAgeMs());
        scanner.put("maxMarkAgeMs", properties.getScanner().getMaxMarkAgeMs());
        scanner.put("batchSize", properties.getScanner().getBatchSize());
        scanner.put("maxDeleveragesPerDeficit", properties.getScanner().getMaxDeleveragesPerDeficit());
        scanner.put("candidateMultiplier", properties.getScanner().getCandidateMultiplier());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("scanner", scanner);
        return response;
    }

    public Map<String, Object> update(Boolean scannerEnabled,
                                      Long scanDelayMs,
                                      Long minDeficitAgeMs,
                                      Long maxMarkAgeMs,
                                      Integer batchSize,
                                      Integer maxDeleveragesPerDeficit,
                                      Integer candidateMultiplier) {
        if (scannerEnabled != null) {
            properties.getScanner().setEnabled(scannerEnabled);
        }
        if (scanDelayMs != null) {
            properties.getScanner().setScanDelayMs(nonNegative(scanDelayMs, "scanDelayMs"));
        }
        if (minDeficitAgeMs != null) {
            properties.getScanner().setMinDeficitAgeMs(nonNegative(minDeficitAgeMs, "minDeficitAgeMs"));
        }
        if (maxMarkAgeMs != null) {
            properties.getScanner().setMaxMarkAgeMs(nonNegative(maxMarkAgeMs, "maxMarkAgeMs"));
        }
        if (batchSize != null) {
            properties.getScanner().setBatchSize(bounded(batchSize, 1, 10_000, "batchSize"));
        }
        if (maxDeleveragesPerDeficit != null) {
            properties.getScanner().setMaxDeleveragesPerDeficit(
                    bounded(maxDeleveragesPerDeficit, 1, 1_000, "maxDeleveragesPerDeficit"));
        }
        if (candidateMultiplier != null) {
            properties.getScanner().setCandidateMultiplier(
                    bounded(candidateMultiplier, 1, 1_000, "candidateMultiplier"));
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
