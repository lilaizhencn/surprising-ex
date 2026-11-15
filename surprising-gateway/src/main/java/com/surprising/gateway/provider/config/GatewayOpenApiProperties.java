package com.surprising.gateway.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * External OpenAPI symbol-alias and scale configuration models.
 */
public final class GatewayOpenApiProperties {

    private GatewayOpenApiProperties() {
    }

    @Getter
    public static class BinanceApi {
        @Setter
        private boolean enabled = true;
        private Map<String, String> symbolAliases = Map.of();
        private Map<String, GatewayProperties.SymbolScale> symbolScales = Map.of();

        public void setSymbolAliases(Map<String, String> symbolAliases) {
            this.symbolAliases = symbolAliases == null ? Map.of() : Map.copyOf(symbolAliases);
        }

        public void setSymbolScales(Map<String, GatewayProperties.SymbolScale> symbolScales) {
            this.symbolScales = symbolScales == null ? Map.of() : Map.copyOf(symbolScales);
        }

        public String backendInstrumentId(String symbol, ProductLine line) {
            String key = line.name() + ":" + symbol.trim().toUpperCase(Locale.ROOT);
            String id = symbolAliases.get(key);
            if (!InstrumentIds.valid(id)) {
                throw new IllegalArgumentException("permanent instrument ID alias is not configured: " + key);
            }
            return id;
        }

        public String externalSymbol(String instrumentId, ProductLine line) {
            String prefix = line.name() + ":";
            return symbolAliases.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix) && entry.getValue().equals(instrumentId))
                    .map(entry -> entry.getKey().substring(prefix.length()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("external symbol alias is not configured"));
        }

        public GatewayProperties.SymbolScale scale(String symbol) {
            String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
            return symbolScales.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(normalized))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("symbol scale is not configured: " + symbol));
        }
    }

    @Getter
    @Setter
    public static class SymbolScale {
        private int priceScale;
        private int quantityScale;
    }
}
