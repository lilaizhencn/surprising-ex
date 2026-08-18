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

    private final RiskService riskService;
    private final RiskProperties properties;

    public RiskRuntimeConfigService(RiskService riskService, RiskProperties properties) {
        this.riskService = riskService;
        this.properties = properties;
    }

    public Map<String, Object> current() {
        return response(scanControl(), properties.getCalculation().getMaxMarkAge().toString());
    }

    public Map<String, Object> update(String adminUserId,
                                      Long expectedVersion,
                                      Boolean calculationEnabled,
                                      Long scanDelayMs,
                                      Integer scanBatchSize,
                                      String reason) {
        var updated = riskService.updateRiskRule("RISK_SCAN_CONTROL", adminUserId,
                new RiskService.RiskRuleUpdateCommand(expectedVersion, null, calculationEnabled,
                        scanDelayMs, scanBatchSize, reason));
        return response(updated, properties.getCalculation().getMaxMarkAge().toString());
    }

    private RiskService.RiskRuleResponse scanControl() {
        return riskService.riskRules().rules().stream()
                .filter(rule -> "RISK_SCAN_CONTROL".equals(rule.ruleCode()))
                .findFirst().orElseThrow(() -> new IllegalStateException("risk scan control is missing"));
    }

    private static Map<String, Object> response(RiskService.RiskRuleResponse control, String maxMarkAge) {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("version", control.version());
        calculation.put("enabled", control.enabled());
        calculation.put("scanDelayMs", control.scanDelayMs());
        calculation.put("scanBatchSize", control.scanBatchSize());
        calculation.put("maxMarkAge", maxMarkAge);
        calculation.put("maxMarkAgeSource", "OFFLINE_CONFIG");
        calculation.put("updatedBy", control.adminUserId());
        calculation.put("reason", control.reason());
        calculation.put("updatedAt", control.updatedAt());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "AERON_CORE");
        response.put("marginPolicySource", "AERON_CORE_INSTRUMENT");
        response.put("calculation", calculation);
        return response;
    }
}
