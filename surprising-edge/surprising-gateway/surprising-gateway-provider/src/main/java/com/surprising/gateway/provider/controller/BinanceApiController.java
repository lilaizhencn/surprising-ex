package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import com.surprising.gateway.provider.service.SpotAccountClient;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class BinanceApiController {

    private final GatewayProperties properties;
    private final GatewayProxyService gatewayProxyService;
    private final GatewayApiKeyService apiKeyService;
    private final AuthService authService;
    private final ComplianceService complianceService;
    private final CustodyWalletClient custodyWalletClient;
    private final SpotAccountClient spotAccountClient;
    private final ObjectMapper objectMapper;

    public BinanceApiController(GatewayProperties properties,
                                GatewayProxyService gatewayProxyService,
                                GatewayApiKeyService apiKeyService,
                                AuthService authService,
                                ComplianceService complianceService,
                                CustodyWalletClient custodyWalletClient,
                                SpotAccountClient spotAccountClient,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.gatewayProxyService = gatewayProxyService;
        this.apiKeyService = apiKeyService;
        this.authService = authService;
        this.complianceService = complianceService;
        this.custodyWalletClient = custodyWalletClient;
        this.spotAccountClient = spotAccountClient;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(path = {"/api/v3/**", "/sapi/v1/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.PUT})
    public ResponseEntity<byte[]> handle(HttpServletRequest request,
                                         @RequestBody(required = false) byte[] body) {
        if (!properties.getBinanceApi().isEnabled()) {
            return error(HttpStatus.NOT_FOUND, -1003, "Binance-compatible API is disabled");
        }
        String path = request.getRequestURI();
        try {
            if (path.endsWith("/ping")) {
                return json(HttpStatus.OK, Map.of());
            }
            if (path.endsWith("/time")) {
                return json(HttpStatus.OK, Map.of("serverTime", System.currentTimeMillis()));
            }
            if (path.endsWith("/exchangeInfo")) {
                return proxy("instrument", "", request, body, null, null, false);
            }
            if (path.endsWith("/depth")) {
                return depth(request, body);
            }
            if (path.endsWith("/asset/transfer")) {
                return transfer(request, body);
            }
            if (path.endsWith("/capital/deposit/address")) {
                return depositAddress(request);
            }
            if (path.endsWith("/capital/deposit/hisrec")) {
                return depositHistory(request);
            }
            if (path.endsWith("/capital/withdraw/history")) {
                return withdrawHistory(request);
            }
            if (path.endsWith("/capital/withdraw/apply")) {
                return withdraw(request);
            }
            if (path.endsWith("/account")) {
                long userId = authenticate(request, "READ");
                return account(request, body, userId);
            }
            if (path.endsWith("/openOrders")) {
                long userId = authenticate(request, "READ");
                return orders(request, userId, true);
            }
            if (path.endsWith("/allOrders")) {
                return error(HttpStatus.NOT_IMPLEMENTED, -1000, "historical order query is not available yet");
            }
            if (path.endsWith("/order")) {
                return order(request, body);
            }
            if (path.endsWith("/ticker/price") || path.endsWith("/ticker/24hr")) {
                return error(HttpStatus.NOT_IMPLEMENTED, -1000, "ticker compatibility is not available yet");
            }
            return error(HttpStatus.NOT_FOUND, -1003, "unsupported Binance-compatible endpoint");
        } catch (ResponseStatusException ex) {
            return error(HttpStatus.valueOf(ex.getStatusCode().value()), -1000,
                    ex.getReason() == null ? "request failed" : ex.getReason());
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, -1100, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, -1001, ex.getMessage());
        }
    }

    private ResponseEntity<byte[]> transfer(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = parameters(request, body);
        long userId = authenticate(request, "TRADE");
        String type = required(params, "type").toUpperCase(Locale.ROOT);
        String asset = required(params, "asset");
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("sourceAccountType", transferAccount(type, true));
        payload.put("targetAccountType", transferAccount(type, false));
        payload.put("asset", asset.toUpperCase(Locale.ROOT));
        payload.put("amountUnits", decimalUnits(required(params, "amount"), scale.getQuantityScale()));
        String idempotencyKey = first(params, "clientTranId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("clientTranId or Idempotency-Key is required");
        }
        payload.put("referenceId", "binance-transfer:" + idempotencyKey.trim());
        payload.put("reason", "binance asset transfer");
        ResponseEntity<byte[]> response = proxy("account", "/transfers", request, jsonBytes(payload), userId, null, true);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> result = readMap(response.getBody());
        return json(HttpStatus.OK, Map.of("tranId", number(result.get("transferId"))));
    }

    private ResponseEntity<byte[]> depositAddress(HttpServletRequest request) {
        long userId = authenticate(request, "READ");
        String chain = requiredParameter(request, "network");
        Map<String, Object> result = custodyWalletClient.createAddress(userId, chain, null);
        return json(HttpStatus.OK, Map.of("coin", requiredParameter(request, "coin"),
                "address", stringValue(result.get("address")), "tag", stringValue(result.get("memo"))));
    }

    private ResponseEntity<byte[]> depositHistory(HttpServletRequest request) {
        long userId = authenticate(request, "READ");
        String chain = request.getParameter("network");
        String asset = request.getParameter("coin");
        return json(HttpStatus.OK, custodyWalletClient.deposits(userId, chain, asset,
                capped(request.getParameter("limit"))));
    }

    private ResponseEntity<byte[]> withdraw(HttpServletRequest request) {
        Map<String, Object> params = parameters(request, null);
        long userId = authenticate(request, "WITHDRAW");
        requireKyc(userId);
        String asset = required(params, "coin");
        String chain = required(params, "network");
        String idempotencyKey = first(params, "withdrawOrderId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("withdrawOrderId or Idempotency-Key is required");
        }
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        String sourceId = wallet.getWithdrawalAddressIds().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(chain))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("withdrawal source address is not configured for network"));
        long amountUnits = custodyWalletClient.amountUnits(asset, required(params, "amount"));
        String reference = "custody-wallet-withdrawal:" + idempotencyKey;
        spotAccountClient.adjustBalance(userId, asset, -amountUnits, reference, "binance wallet withdrawal");
        Map<String, Object> payload = Map.of(
                "custodyAddressId", java.util.UUID.fromString(sourceId),
                "chain", chain,
                "assetSymbol", asset,
                "toAddress", required(params, "address"),
                "amount", required(params, "amount"),
                "externalReference", reference,
                "confirmed", true);
        Map<String, Object> result = custodyWalletClient.createWithdrawal(userId, payload, idempotencyKey);
        return json(HttpStatus.OK, Map.of("id", result.getOrDefault("id", idempotencyKey),
                "msg", "success", "success", true));
    }

    private ResponseEntity<byte[]> withdrawHistory(HttpServletRequest request) {
        long userId = authenticate(request, "READ");
        return json(HttpStatus.OK, custodyWalletClient.withdrawals(userId, request.getParameter("network"),
                request.getParameter("coin"), capped(request.getParameter("limit"))));
    }

    private void requireKyc(long userId) {
        KycProfile profile = complianceService.kyc(userId);
        if (profile == null || !"VERIFIED".equalsIgnoreCase(profile.status())
                || (profile.expiresAt() != null && profile.expiresAt().isBefore(Instant.now()))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "verified KYC is required for withdrawals");
        }
    }

    private String transferAccount(String type, boolean source) {
        return switch (type) {
            case "MAIN_UMFUTURE" -> source ? "FUNDING" : "USDT_PERPETUAL";
            case "UMFUTURE_MAIN" -> source ? "USDT_PERPETUAL" : "FUNDING";
            case "MAIN_CM" -> source ? "FUNDING" : "COIN_PERPETUAL";
            case "CM_MAIN" -> source ? "COIN_PERPETUAL" : "FUNDING";
            case "MAIN_SPOT" -> source ? "FUNDING" : "SPOT";
            case "SPOT_MAIN" -> source ? "SPOT" : "FUNDING";
            default -> throw new IllegalArgumentException("unsupported transfer type");
        };
    }

    private ResponseEntity<byte[]> order(HttpServletRequest request, byte[] body) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        Map<String, Object> params = parameters(request, body);
        long userId = authenticate(request, "GET".equals(method) || "DELETE".equals(method) ? "READ" : "TRADE");
        String symbol = required(params, "symbol");
        String backendSymbol = properties.getBinanceApi().backendSymbol(symbol);
        if ("POST".equals(method)) {
            GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("clientOrderId", first(params, "newClientOrderId", "clientOrderId"));
            payload.put("symbol", backendSymbol);
            payload.put("side", required(params, "side").toUpperCase(Locale.ROOT));
            String type = required(params, "type").toUpperCase(Locale.ROOT);
            payload.put("orderType", type);
            payload.put("timeInForce", first(params, "timeInForce") == null
                    ? (type.equals("MARKET") ? "IOC" : "GTC")
                    : String.valueOf(first(params, "timeInForce")).toUpperCase(Locale.ROOT));
            payload.put("priceTicks", type.equals("MARKET") ? 0L
                    : decimalUnits(required(params, "price"), scale.getPriceScale()));
            payload.put("quantitySteps", decimalUnits(required(params, "quantity"), scale.getQuantityScale()));
            payload.put("marginMode", "CROSS");
            payload.put("positionSide", "NET");
            payload.put("reduceOnly", booleanValue(params.get("reduceOnly")));
            payload.put("postOnly", "GTX".equals(payload.get("timeInForce")));
            return transformOrder(proxy("trading", "", request, jsonBytes(payload), userId, null, true), symbol, scale);
        }
        if ("DELETE".equals(method)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("orderId", longParameter(params, "orderId"));
            return transformOrder(proxy("trading", "/cancel", request, jsonBytes(payload), userId, null, true), symbol,
                    properties.getBinanceApi().scale(symbol));
        }
        String orderId = first(params, "orderId");
        String clientOrderId = first(params, "origClientOrderId", "clientOrderId");
        if (orderId != null) {
            return transformOrder(proxy("trading", "/" + orderId, request, null, userId, null, true), symbol,
                    properties.getBinanceApi().scale(symbol));
        }
        if (clientOrderId != null) {
            String query = "userId=" + userId + "&clientOrderId=" + encode(clientOrderId);
            return transformOrder(proxy("trading", "/by-client-order-id", request, null, userId, query, true), symbol,
                    properties.getBinanceApi().scale(symbol));
        }
        throw new IllegalArgumentException("orderId or origClientOrderId is required");
    }

    private ResponseEntity<byte[]> orders(HttpServletRequest request, long userId, boolean openOnly) {
        String symbol = request.getParameter("symbol");
        String query = "userId=" + userId + "&limit=" + capped(request.getParameter("limit"));
        if (symbol != null && !symbol.isBlank()) query += "&symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol));
        ResponseEntity<byte[]> response = proxy("trading", "/open", request, null, userId, query, true);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        List<Map<String, Object>> result = new ArrayList<>();
        Object rows = payload.get("orders");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                result.add(orderView(mapValue(row), symbol == null ? stringValue(mapValue(row).get("symbol")) : symbol,
                        properties.getBinanceApi().scale(symbol == null ? stringValue(mapValue(row).get("symbol")) : symbol)));
            }
        }
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> account(HttpServletRequest request, byte[] body, long userId) {
        ResponseEntity<byte[]> response = proxy("account", "/balances", request, null, userId,
                "userId=" + userId, true);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        List<Map<String, Object>> balances = new ArrayList<>();
        Object rows = payload.get("balances");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                Map<String, Object> value = mapValue(row);
                String asset = stringValue(value.get("asset"));
                GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
                balances.add(Map.of("asset", asset,
                        "free", decimalString(number(value.get("availableUnits")), scale.getQuantityScale()),
                        "locked", decimalString(number(value.get("lockedUnits")), scale.getQuantityScale())));
            }
        }
        return json(HttpStatus.OK, Map.of("makerCommission", 0, "takerCommission", 0,
                "buyerCommission", 0, "sellerCommission", 0, "canTrade", true,
                "canWithdraw", true, "canDeposit", true, "updateTime", System.currentTimeMillis(),
                "accountType", "SPOT", "balances", balances));
    }

    private ResponseEntity<byte[]> depth(HttpServletRequest request, byte[] body) {
        String symbol = requiredParameter(request, "symbol");
        int limit = capped(request.getParameter("limit"));
        String query = "symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol)) + "&depth=" + limit;
        ResponseEntity<byte[]> response = proxy("trading-market", "/orderbook", request, null, null, query, false);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        return json(HttpStatus.OK, Map.of(
                "lastUpdateId", number(payload.get("sequence")),
                "bids", levels(payload.get("bids"), scale),
                "asks", levels(payload.get("asks"), scale)));
    }

    private ResponseEntity<byte[]> proxy(String service, String suffix, HttpServletRequest request,
                                         byte[] body, Long userId, String query, boolean privateRoute) {
        return gatewayProxyService.proxyCompat(service, suffix, query, HttpMethod.valueOf(request.getMethod()),
                request, body, userId);
    }

    private long authenticate(HttpServletRequest request, String permission) {
        String apiKey = request.getHeader("X-MBX-APIKEY");
        if (apiKey != null && !apiKey.isBlank()) return apiKeyService.authenticate(request, permission);
        try {
            JwtPrincipal principal = authService.authenticateBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
            return principal.userId();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    private Map<String, Object> parameters(HttpServletRequest request, byte[] body) {
        Map<String, Object> result = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) result.put(key, values[0]);
        });
        if (result.isEmpty() && body != null && body.length > 0) result.putAll(readMap(body));
        return result;
    }

    private ResponseEntity<byte[]> transformOrder(ResponseEntity<byte[]> response,
                                                  String symbol,
                                                  GatewayProperties.SymbolScale scale) {
        if (response.getStatusCode().isError()) return response;
        return json(HttpStatus.OK, orderView(readMap(response.getBody()), symbol, scale));
    }

    private Map<String, Object> orderView(Map<String, Object> order, String requestedSymbol,
                                          GatewayProperties.SymbolScale scale) {
        String symbol = requestedSymbol == null ? stringValue(order.get("symbol")) : requestedSymbol;
        String type = stringValue(order.get("orderType"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("orderId", number(order.get("orderId")));
        result.put("orderListId", -1);
        result.put("clientOrderId", order.get("clientOrderId"));
        result.put("transactTime", epochMillis(order.get("createdAt")));
        result.put("price", decimalString(number(order.get("priceTicks")), scale.getPriceScale()));
        result.put("origQty", decimalString(number(order.get("quantitySteps")), scale.getQuantityScale()));
        result.put("executedQty", decimalString(number(order.get("executedQuantitySteps")), scale.getQuantityScale()));
        result.put("cummulativeQuoteQty", "0");
        result.put("status", status(stringValue(order.get("status"))));
        result.put("timeInForce", stringValue(order.get("timeInForce")));
        result.put("type", type);
        result.put("side", stringValue(order.get("side")));
        result.put("workingTime", epochMillis(order.get("createdAt")));
        result.put("selfTradePreventionMode", "NONE");
        return result;
    }

    private List<List<String>> levels(Object value, GatewayProperties.SymbolScale scale) {
        if (!(value instanceof List<?> list)) return List.of();
        List<List<String>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> level = mapValue(item);
            result.add(List.of(decimalString(number(level.get("priceTicks")), scale.getPriceScale()),
                    decimalString(number(level.get("quantitySteps")), scale.getQuantityScale())));
        }
        return result;
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("request cannot be encoded", ex);
        }
    }

    private ResponseEntity<byte[]> json(HttpStatus status, Object value) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(jsonBytes(value));
    }

    private ResponseEntity<byte[]> error(HttpStatus status, int code, String message) {
        return json(status, Map.of("code", code, "msg", message == null ? "request failed" : message));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(byte[] body) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, Map.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("response is not a JSON object", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("response item is invalid");
        return (Map<String, Object>) map;
    }

    private String required(Map<String, Object> params, String key) {
        String value = first(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String requiredParameter(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String first(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private long longParameter(Map<String, Object> params, String key) {
        try {
            return Long.parseLong(required(params, key));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid", ex);
        }
    }

    private long decimalUnits(String value, int scale) {
        try {
            return new BigDecimal(value).movePointRight(scale).setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("decimal value does not match symbol precision", ex);
        }
    }

    private String decimalString(long units, int scale) {
        return BigDecimal.valueOf(units, scale).stripTrailingZeros().toPlainString();
    }

    private long number(Object value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("numeric response field is invalid", ex);
        }
    }

    private long epochMillis(Object value) {
        if (value == null) return System.currentTimeMillis();
        try {
            return Instant.parse(String.valueOf(value)).toEpochMilli();
        } catch (RuntimeException ex) {
            return System.currentTimeMillis();
        }
    }

    private String status(String value) {
        return switch (value) {
            case "FILLED" -> "FILLED";
            case "PARTIALLY_FILLED" -> "PARTIALLY_FILLED";
            case "CANCELED", "CANCEL_REQUESTED" -> "CANCELED";
            case "REJECTED" -> "REJECTED";
            default -> "NEW";
        };
    }

    private boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private int capped(String value) {
        if (value == null || value.isBlank()) return 100;
        try {
            return Math.min(1000, Math.max(1, Integer.parseInt(value)));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("limit is invalid", ex);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
