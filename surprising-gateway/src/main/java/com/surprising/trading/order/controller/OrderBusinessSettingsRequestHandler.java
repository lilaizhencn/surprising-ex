package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderBusinessSettings;
import com.surprising.trading.order.model.OrderSettingsSnapshot;
import com.surprising.trading.order.service.OrderBusinessSettingsService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 网关本地协议入口：校验完整请求后调用业务；不注册额外 HTTP 路由。 */
@Component
public class OrderBusinessSettingsRequestHandler {
    public record Update(OrderBusinessSettings settings, Long expectedVersion, String reason) { }
    private final OrderBusinessSettingsService settings;
    private final ObjectMapper json;

    public OrderBusinessSettingsRequestHandler(OrderBusinessSettingsService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public OrderSettingsSnapshot current(ProductLine productLine) { return settings.current(productLine); }

    public OrderSettingsSnapshot save(ProductLine productLine, JsonNode body,
                                      String admin) {
        try {
            var request = parse(body);
            if (request.expectedVersion() < 1 || admin == null || !admin.matches("[1-9][0-9]*")
                    || request.reason().isBlank() || request.reason().length() > 1000)
                throw new IllegalArgumentException("complete settings, version, administrator and reason (1-1000 characters) required");
            return settings.save(productLine, request.settings(), request.expectedVersion(), admin, request.reason().trim());
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid order settings: " + ex.getMessage(), ex);
        }
    }

    private Update parse(JsonNode body) {
        requireKeys(body, Set.of("settings", "expectedVersion", "reason"));
        var value = body.get("settings");
        requireKeys(value, Set.of("risk", "algo"));
        var risk = value.get("risk");
        var algo = value.get("algo");
        requireKeys(risk, Set.of("marketMaxSlippagePpm", "marketMaxMarkAgeMs", "limitPriceProtectionEnabled",
                "limitPriceBandPpm", "limitPriceMaxMarkAgeMs"));
        requireKeys(algo, Set.of("enabled", "claimBatchSize", "scanDelayMs", "minIntervalSeconds", "maxIntervalSeconds",
                "minDurationSeconds", "maxDurationSeconds", "claimLeaseMs"));
        for (var section : List.of(risk, algo)) for (var entry : section.properties()) {
            var name = entry.getKey();
            var field = entry.getValue();
            boolean flag = name.equals("enabled") || name.equals("limitPriceProtectionEnabled");
            if (flag ? !field.isBoolean() : !field.isIntegralNumber() || !field.canConvertToLong()
                    || (name.equals("claimBatchSize") && !field.canConvertToInt()))
                throw new IllegalArgumentException(name + " has invalid type");
        }
        if (!body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                || !body.get("reason").isString())
            throw new IllegalArgumentException("version and reason have invalid types");
        return json.treeToValue(body, Update.class);
    }

    private void requireKeys(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(expected))
            throw new IllegalArgumentException("complete settings required; unknown keys rejected");
    }
}
