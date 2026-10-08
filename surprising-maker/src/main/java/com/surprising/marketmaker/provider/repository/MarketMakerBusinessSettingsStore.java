package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Owns versioned settings for this process only. Copies isolate mutable HTTP/configuration objects. */
@Repository
public class MarketMakerBusinessSettingsStore {
    private final ObjectMapper json;
    private final ProductLine product;
    private Settings current;

    public MarketMakerBusinessSettingsStore(MarketMakerProperties properties, ObjectMapper json) {
        this.json = json;
        product = properties.getProductLine();
        current = copy(new Settings(new MarketMakerBusinessSettings(properties.getEngine(), properties.getQuoting(),
                properties.getRisk(), properties.getTrade(), properties.getReferenceMarket()),
                1, "SYSTEM:YAML", "Startup YAML configuration", Instant.now()));
    }

    public synchronized Settings load(ProductLine line) {
        requireProduct(line);
        return copy(current);
    }

    public synchronized Settings save(ProductLine line, MarketMakerBusinessSettings settings, long expectedVersion,
                                      String admin, String reason) {
        requireProduct(line);
        if (expectedVersion != current.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "做市公共设置已更新，请重新加载");
        current = copy(new Settings(settings, Math.addExact(current.version(), 1), admin, reason, Instant.now()));
        return copy(current);
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
