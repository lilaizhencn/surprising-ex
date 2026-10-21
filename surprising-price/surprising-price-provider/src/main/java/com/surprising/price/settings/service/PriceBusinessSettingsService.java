package com.surprising.price.settings.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.model.PriceSettingsSnapshot;
import com.surprising.price.settings.repository.PriceBusinessSettingsRepository;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 按产品线保存规则；完整安装后发布事件，运行时和调度只消费已提交的配置。 */
@Service
public class PriceBusinessSettingsService {
    private final PriceBusinessSettingsRepository settings;
    private final ObjectMapper json;
    private final IndexPriceProperties index;
    private final MarkPriceProperties mark;
    private final ProductLine line;
    private final ApplicationEventPublisher events;
    private volatile PriceSettingsSnapshot current;

    public PriceBusinessSettingsService(PriceBusinessSettingsRepository settings, ObjectMapper json,
            IndexPriceProperties index, MarkPriceProperties mark, ApplicationEventPublisher events) {
        this.settings = settings; this.json = json; this.index = index; this.mark = mark; this.events = events;
        this.line = index.getKafka().getProductLine();
        if (line == null || line != mark.getKafka().getProductLine()) throw new IllegalArgumentException("index and mark settings product line mismatch");
    }

    @PostConstruct
    public void initialize() {
        settings.initialize(line, PriceBusinessSettings.initial());
        reload();
    }
    public PriceSettingsSnapshot current() {
        if (current == null) throw new IllegalStateException("price settings not initialized");
        return current;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized PriceSettingsSnapshot save(PriceBusinessSettings value, long expectedVersion, String admin, String reason) {
        var saved = settings.save(line, value, expectedVersion, admin, reason)
                .orElseThrow(() -> new OptimisticLockingFailureException("price settings changed; reload before saving"));
        install(saved);
        return current;
    }
    public synchronized void reload() {
        var value = settings.find(line);
        if (value == null) throw new IllegalStateException("price settings missing");
        if (current == null || value.version() > current.version()) install(value);
    }
    private void install(PriceSettingsSnapshot snapshot) {
        var values = snapshot.settings();
        var indexCalculation = new IndexPriceProperties.Calculation();
        var markCalculation = new MarkPriceProperties.Calculation();
        var webSocket = json.convertValue(index.getWebSocket(), IndexPriceProperties.WebSocket.class);
        var fiat = new IndexPriceProperties.Fiat();
        var stable = new IndexPriceProperties.StableCoin();
        var indexCoordination = new IndexPriceProperties.Coordination();
        var markCoordination = new MarkPriceProperties.Coordination();
        indexCoordination.setNodeId(index.getCoordination().getNodeId());
        markCoordination.setNodeId(mark.getCoordination().getNodeId());
        indexCalculation.setPollDelayMs(values.indexPollDelayMs());
        indexCalculation.setMaxSourceAge(Duration.ofMillis(values.indexMaxSourceAgeMs()));
        indexCalculation.setOutlierThreshold(values.indexOutlierThreshold());
        indexCalculation.setMinValidSources(values.indexMinValidSources());
        indexCalculation.setScale(values.indexScale());
        indexCalculation.setConversionCacheTtl(Duration.ofMillis(values.conversionCacheTtlMs()));
        markCalculation.setPublishIntervalMs(values.markPublishIntervalMs());
        markCalculation.setBasisWindow(Duration.ofMillis(values.markBasisWindowMs()));
        markCalculation.setMaxInputAge(Duration.ofMillis(values.markMaxInputAgeMs()));
        markCalculation.setClampRatio(values.markClampRatio());
        markCalculation.setDefaultFundingIntervalHours(values.defaultFundingIntervalHours());
        markCalculation.setScale(values.markScale());
        webSocket.setEnabled(values.websocketEnabled());
        webSocket.setRestFallbackEnabled(values.restFallbackEnabled());
        webSocket.setRefreshDelayMs(values.connectionRefreshDelayMs());
        indexCoordination.setEnabled(values.indexCoordinationEnabled());
        indexCoordination.setLeaseDuration(Duration.ofMillis(values.indexLeaseDurationMs()));
        markCoordination.setEnabled(values.markCoordinationEnabled());
        markCoordination.setLeaseDuration(Duration.ofMillis(values.markLeaseDurationMs()));
        fiat.setEnabled(values.fiatEnabled());
        fiat.setRefreshDelayMs(values.fiatRefreshDelayMs());
        fiat.setStaleAfter(Duration.ofMillis(values.fiatStaleAfterMs()));
        fiat.setBaseUrl(values.fiatBaseUrl());
        fiat.setPath(values.fiatPath());
        fiat.setBaseCurrency(values.fiatBaseCurrency());
        fiat.setQuoteCurrencies(values.fiatQuoteCurrencies());
        stable.setEnabled(values.stableEnabled());
        stable.setRefreshDelayMs(values.stableRefreshDelayMs());
        stable.setStaleAfter(Duration.ofMillis(values.stableStaleAfterMs()));
        stable.setCurrency(values.stableCurrency());
        stable.setFiatCurrency(values.stableFiatCurrency());
        stable.setBaseUrl(values.stableBaseUrl());
        stable.setPath(values.stablePath());
        stable.setParser(values.stableParser());
        stable.setFallbackRate(values.stableFallbackRate());
        fiat.setStableCoin(stable);
        index.setCalculation(indexCalculation);
        index.setWebSocket(webSocket);
        index.setFiat(fiat);
        index.setCoordination(indexCoordination);
        mark.setCalculation(markCalculation);
        mark.setCoordination(markCoordination);
        current = snapshot;
        events.publishEvent(snapshot);
    }
}
