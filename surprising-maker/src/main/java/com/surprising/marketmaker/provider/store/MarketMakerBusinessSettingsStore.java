package com.surprising.marketmaker.provider.store;

import com.surprising.marketmaker.provider.exception.MarketMakerSettingsConflictException;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Owns versioned settings for this process only. Copies isolate mutable HTTP/configuration objects. */
@Component
public class MarketMakerBusinessSettingsStore {
    private final ObjectMapper json;
    private final ProductLine product;
    private final java.util.concurrent.atomic.AtomicReference<Settings> current;

    public MarketMakerBusinessSettingsStore(MarketMakerProperties properties, ObjectMapper json) {
        this.json = json;
        product = properties.getProductLine();
        current = new java.util.concurrent.atomic.AtomicReference<>(copy(new Settings(
                new MarketMakerBusinessSettings(properties.getEngine(), properties.getQuoting(),
                        properties.getRisk(), properties.getTrade(), properties.getReferenceMarket()),
                1, "SYSTEM:YAML", "Startup YAML configuration", Instant.now())));
    }

    public Settings load(ProductLine line) {
        requireProduct(line);
        return copy(current.get());
    }

    public Settings save(ProductLine line, MarketMakerBusinessSettings settings, long expectedVersion,
                         String admin, String reason) {
        requireProduct(line);
        while (true) {
            Settings previous = current.get();
            if (expectedVersion != previous.version()) {
                throw new MarketMakerSettingsConflictException("做市公共设置已更新，请重新加载");
            }
            Settings next = copy(new Settings(settings, Math.addExact(previous.version(), 1), admin, reason, Instant.now()));
            if (current.compareAndSet(previous, next)) {
                return copy(next);
            }
        }
    }

    private void requireProduct(ProductLine line) {
        com.surprising.product.api.ProductLineConfiguration.requireSame(product, line, "maker settings");
    }
    private Settings copy(Settings value) {
        return json.readValue(json.writeValueAsBytes(value), Settings.class);
    }
    public record Settings(MarketMakerBusinessSettings settings, long version, String updatedBy, String reason,
                           Instant updatedAt) {}
}
