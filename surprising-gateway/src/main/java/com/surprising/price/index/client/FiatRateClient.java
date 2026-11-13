package com.surprising.price.index.client;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.FiatRateSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 法币提供方的 HTTP 请求与响应协议边界，不持有汇率业务缓存。 */
@Component
public class FiatRateClient {
    private final IndexPriceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public FiatRateClient(IndexPriceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .proxy(properties.getHttp().proxySelector())
                .build();
    }

    public FiatRateSnapshot fetch(String baseCurrency, IndexPriceProperties.Fiat fiat) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(fiat.getBaseUrl() + fiat.getPath()
                    .replace("{base}", baseCurrency)))
                .timeout(properties.getHttp().getRequestTimeout())
                .header("User-Agent", properties.getHttp().getUserAgent())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("fiat rate http status " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!"success".equalsIgnoreCase(root.path("result").asString())) {
            throw new IllegalStateException("fiat provider result=" + root.path("result").asString());
        }
        var rates = new java.util.LinkedHashMap<String, String>();
        for (String currency : root.path("rates").propertyNames()) {
            JsonNode value = root.path("rates").path(currency);
            if (!value.isNull() && !value.asString().isBlank()) rates.put(currency, value.asString());
        }
        return new FiatRateSnapshot(root.path("provider").asString(fiat.getProvider()),
                root.hasNonNull("time_last_update_unix")
                        ? Instant.ofEpochSecond(root.path("time_last_update_unix").asLong()) : Instant.now(),
                java.util.Collections.unmodifiableMap(rates));
    }
}
