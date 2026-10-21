package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderBusinessSettings;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderSettingsSnapshot;
import com.surprising.trading.order.repository.OrderBusinessSettingsRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 数据库保存订单业务规则；Core 合约同步将同一规则写入撮合核心。 */
@Service
public class OrderBusinessSettingsService {
    private final OrderBusinessSettingsRepository settings;
    private final TradingOrderProperties properties;
    private final ProductLine line;
    private volatile OrderSettingsSnapshot current;

    public OrderBusinessSettingsService(OrderBusinessSettingsRepository settings, TradingOrderProperties properties) {
        this.settings = settings;
        this.properties = properties;
        this.line = properties.getKafka().getProductLine();
    }

    @PostConstruct
    public void initialize() {
        settings.initialize(line, OrderBusinessSettings.initial());
        reload();
    }

    public OrderSettingsSnapshot current(ProductLine requested) {
        if (requested != line) throw new IllegalArgumentException("order settings product line mismatch");
        if (current == null) throw new IllegalStateException("order settings not initialized");
        return current;
    }

    // 保存独立提交后再发布本机规则；外层事务回滚不能留下未提交的内存版本。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized OrderSettingsSnapshot save(ProductLine requested, OrderBusinessSettings value,
                                                   long expectedVersion, String admin, String reason) {
        current(requested);
        var saved = settings.save(line, value, expectedVersion, admin, reason)
                .orElseThrow(() -> new OptimisticLockingFailureException("order settings changed; reload before saving"));
        // 此单表 CAS 在返回前已提交；只安装成功保存的数据库版本。
        install(saved);
        return current;
    }

    public synchronized void reload() {
        var value = settings.find(line);
        if (value == null) throw new IllegalStateException("order settings missing");
        if (current == null || value.configurationVersion() > current.configurationVersion()) install(value);
    }
    private void install(OrderSettingsSnapshot snapshot) {
        var priceProtection = snapshot.settings().risk();
        var risk = new TradingOrderProperties.Risk();
        risk.setMarketMaxSlippagePpm(priceProtection.marketMaxSlippagePpm());
        risk.setMarketMaxMarkAgeMs(priceProtection.marketMaxMarkAgeMs());
        risk.setLimitPriceProtectionEnabled(priceProtection.limitPriceProtectionEnabled());
        risk.setLimitPriceBandPpm(priceProtection.limitPriceBandPpm());
        risk.setLimitPriceMaxMarkAgeMs(priceProtection.limitPriceMaxMarkAgeMs());

        var algorithmLimits = snapshot.settings().algo();
        var algo = new TradingOrderProperties.Algo();
        algo.setEnabled(algorithmLimits.enabled());
        algo.setClaimBatchSize(algorithmLimits.claimBatchSize());
        algo.setScanDelayMs(algorithmLimits.scanDelayMs());
        algo.setMinIntervalSeconds(algorithmLimits.minIntervalSeconds());
        algo.setMaxIntervalSeconds(algorithmLimits.maxIntervalSeconds());
        algo.setMinDurationSeconds(algorithmLimits.minDurationSeconds());
        algo.setMaxDurationSeconds(algorithmLimits.maxDurationSeconds());
        algo.setClaimLease(Duration.ofMillis(algorithmLimits.claimLeaseMs()));

        properties.setRisk(risk);
        properties.setAlgo(algo);
        current = snapshot;
    }
}
