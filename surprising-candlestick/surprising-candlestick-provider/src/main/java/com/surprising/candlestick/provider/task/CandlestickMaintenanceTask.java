package com.surprising.candlestick.provider.task;

import com.surprising.candlestick.provider.service.SymbolRegistryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * K 线模块定时任务入口，只负责触发交易对注册表刷新。
 */
@Component
public class CandlestickMaintenanceTask {

    private final SymbolRegistryService symbolRegistryService;

    public CandlestickMaintenanceTask(SymbolRegistryService symbolRegistryService) {
        this.symbolRegistryService = symbolRegistryService;
    }

    @Scheduled(fixedDelayString = "${surprising.candlestick.symbols.refresh-delay-ms:30000}")
    public void refreshSymbols() {
        symbolRegistryService.refresh();
    }
}
