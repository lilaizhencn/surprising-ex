package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LiquidationRuntimeConfigService {

    private final LiquidationProperties properties;
    private final LiquidationAeronGateway aeron;

    public LiquidationRuntimeConfigService(LiquidationProperties properties, LiquidationAeronGateway aeron) {
        this.properties = properties;
        this.aeron = aeron;
    }

    public Map<String, Object> current() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("enabled", properties.getExecution().isEnabled());
        execution.put("liquidationFeeRatePpm", properties.getExecution().getLiquidationFeeRatePpm());
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("mode", "AERON_TAKEOVER");
        coordinator.put("workBatchSize", properties.getCoordinator().getWorkBatchSize());
        var scanControl = aeron.riskScanControl();
        coordinator.put("riskScanControlVersion", scanControl.version());
        coordinator.put("riskScanEnabled", scanControl.enabled());
        coordinator.put("riskScanDelayMs", scanControl.scanDelayMs());
        coordinator.put("riskScanBatchSize", scanControl.scanBatchSize());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", properties.getProductLine().name());
        response.put("execution", execution);
        response.put("coordinator", coordinator);
        return response;
    }

}
