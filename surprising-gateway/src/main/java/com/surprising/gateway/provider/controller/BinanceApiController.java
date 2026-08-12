package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
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
    private final SensitiveActionVerificationService verificationService;
    private final AuthService authService;
    private final ComplianceService complianceService;
    private final CustodyWalletClient custodyWalletClient;
    private final CustodyWithdrawalService withdrawalService;
    private final ObjectMapper objectMapper;

    public BinanceApiController(GatewayProperties properties,
                                GatewayProxyService gatewayProxyService,
                                GatewayApiKeyService apiKeyService,
                                SensitiveActionVerificationService verificationService,
                                AuthService authService,
                                ComplianceService complianceService,
                                CustodyWalletClient custodyWalletClient,
                                CustodyWithdrawalService withdrawalService,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.gatewayProxyService = gatewayProxyService;
        this.apiKeyService = apiKeyService;
        this.verificationService = verificationService;
        this.authService = authService;
        this.complianceService = complianceService;
        this.custodyWalletClient = custodyWalletClient;
        this.withdrawalService = withdrawalService;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(path = {"/api/v3/**", "/sapi/v1/**", "/fapi/v1/**", "/dapi/v1/**", "/eapi/v1/**"}, method = {
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
            if (path.endsWith("/capital/config/getall")) {
                return capitalConfig(request);
            }
            if (path.endsWith("/capital/withdraw/history")) {
                return withdrawHistory(request);
            }
            if (path.endsWith("/capital/withdraw/apply")) {
                return withdraw(request, body);
            }
            if (path.endsWith("/account")) {
                long userId = authenticate(request, "READ", body);
                return account(request, body, userId);
            }
            if (path.endsWith("/account/status")) {
                authenticate(request, "READ");
                return json(HttpStatus.OK, Map.of("data", "Normal"));
            }
            if (path.endsWith("/openOrders")) {
                long userId = authenticate(request, "READ");
                return orders(request, userId, true);
            }
            if (path.endsWith("/allOrders")) {
                long userId = authenticate(request, "READ");
                return historyOrders(request, userId);
            }
            if (path.endsWith("/order")) {
                return order(request, body);
            }
            if (path.endsWith("/ticker/price")) {
                return tickerPrice(request);
            }
            if (path.endsWith("/ticker/bookTicker")) {
                return bookTicker(request);
            }
            if (path.endsWith("/ticker/24hr")) {
                return ticker24hr(request);
            }
            if (path.endsWith("/klines")) {
                return klines(request);
            }
            return error(HttpStatus.NOT_FOUND, -1003, "unsupported Binance-compatible endpoint");
        } catch (ResponseStatusException ex) {
            return error(HttpStatus.valueOf(ex.getStatusCode().value()), -1000,
                    ex.getReason() == null ? "request failed" : ex.getReason());
        } catch (CustodyWithdrawalService.WithdrawalRejectedException ex) {
            return error(HttpStatus.BAD_REQUEST, -2010, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, -1100, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, -1001, ex.getMessage());
        }
    }

    private ResponseEntity<byte[]> transfer(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = parameters(request, body);
        long userId = authenticate(request, "TRADE", body);
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
        payload.put("referenceId", idempotencyKey.trim());
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

    private ResponseEntity<byte[]> capitalConfig(HttpServletRequest request) {
        authenticate(request, "READ");
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        if (!wallet.isEnabled()) {
            return json(HttpStatus.OK, List.of());
        }
        Map<String, List<Map<String, Object>>> networksByAsset = new LinkedHashMap<>();
        for (Map<String, Object> chain : custodyWalletClient.chains()) {
            if (!Boolean.TRUE.equals(chain.get("enabled"))) {
                continue;
            }
            String network = stringValue(chain.get("chain"));
            if (network.isBlank()) {
                continue;
            }
            boolean withdrawEnabled = Boolean.TRUE.equals(chain.get("withdrawalEnabled"));
            Object assets = chain.get("assetSymbols");
            if (!(assets instanceof List<?> assetList)) {
                continue;
            }
            for (Object asset : assetList) {
                String normalizedAsset = String.valueOf(asset).trim().toUpperCase(Locale.ROOT);
                if (normalizedAsset.isBlank()) {
                    continue;
                }
                List<Map<String, Object>> networks = networksByAsset.computeIfAbsent(
                        normalizedAsset, ignored -> new ArrayList<>());
                networks.add(Map.of(
                        "coin", normalizedAsset,
                        "network", network.toUpperCase(Locale.ROOT),
                        "depositEnable", true,
                        "withdrawEnable", withdrawEnabled,
                        "isDefault", networks.isEmpty()));
            }
        }
        List<Map<String, Object>> result = networksByAsset.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "coin", entry.getKey(),
                        "name", entry.getKey(),
                        "networkList", entry.getValue()))
                .toList();
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> withdraw(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = parameters(request, body);
        boolean apiKeyRequest = request.getHeader("X-MBX-APIKEY") != null
                && !request.getHeader("X-MBX-APIKEY").isBlank();
        long userId = authenticate(request, "WITHDRAW", body);
        requireWithdrawalSecurity(request, userId, apiKeyRequest ? "API_WITHDRAWAL" : "WITHDRAWAL");
        requireKyc(userId);
        complianceService.requireWithdrawalEligibility(userId);
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
        CustodyWithdrawalService.WithdrawalResponse result = withdrawalService.submit(userId, idempotencyKey,
                new CustodyWithdrawalService.WithdrawalRequest(
                        java.util.UUID.fromString(sourceId), chain, asset, required(params, "address"),
                        required(params, "amount"), null));
        return json(HttpStatus.OK, Map.of("id", result.walletWithdrawalId() == null
                        ? result.withdrawalId().toString() : result.walletWithdrawalId(),
                "withdrawalId", result.withdrawalId().toString(), "status", result.status(),
                "msg", "success", "success", true));
    }

    private ResponseEntity<byte[]> withdrawHistory(HttpServletRequest request) {
        long userId = authenticate(request, "READ");
        return json(HttpStatus.OK, withdrawalService.history(userId, request.getParameter("network"),
                request.getParameter("coin"), capped(request.getParameter("limit"))));
    }

    private void requireKyc(long userId) {
        KycProfile profile = complianceService.kyc(userId);
        if (!isKycVerified(profile)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "verified KYC is required for withdrawals");
        }
    }

    private boolean isKycVerified(KycProfile profile) {
        return profile != null && "VERIFIED".equalsIgnoreCase(profile.status())
                && (profile.expiresAt() == null || !profile.expiresAt().isBefore(Instant.now()));
    }

    private void requireWithdrawalSecurity(HttpServletRequest request, long userId, String scene) {
        if (!verificationService.verify(userId, scene,
                request.getHeader("X-Security-Email-Code"),
                request.getHeader("X-Security-TOTP-Code"), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "security verification is required or invalid");
        }
    }

    private String transferAccount(String type, boolean source) {
        return switch (type) {
            case "MAIN_UMFUTURE" -> source ? "FUNDING" : "USDT_PERPETUAL";
            case "UMFUTURE_MAIN" -> source ? "USDT_PERPETUAL" : "FUNDING";
            case "MAIN_CM" -> source ? "FUNDING" : "COIN_PERPETUAL";
            case "CM_MAIN" -> source ? "COIN_PERPETUAL" : "FUNDING";
            case "MAIN_CMFUTURE" -> source ? "FUNDING" : "COIN_PERPETUAL";
            case "CMFUTURE_MAIN" -> source ? "COIN_PERPETUAL" : "FUNDING";
            case "MAIN_SPOT" -> source ? "FUNDING" : "SPOT";
            case "SPOT_MAIN" -> source ? "SPOT" : "FUNDING";
            default -> throw new IllegalArgumentException("unsupported transfer type");
        };
    }

    private ResponseEntity<byte[]> order(HttpServletRequest request, byte[] body) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        Map<String, Object> params = parameters(request, body);
        long userId = authenticate(request, "GET".equals(method) || "DELETE".equals(method) ? "READ" : "TRADE", body);
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

    private ResponseEntity<byte[]> historyOrders(HttpServletRequest request, long userId) {
        String symbol = request.getParameter("symbol");
        String query = "userId=" + userId + "&limit=" + capped(request.getParameter("limit"));
        if (symbol != null && !symbol.isBlank()) {
            query += "&symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol));
        }
        for (String parameter : List.of("orderId", "startTime", "endTime")) {
            String value = request.getParameter(parameter);
            if (value != null && !value.isBlank()) query += "&" + parameter + "=" + encode(value);
        }
        ResponseEntity<byte[]> response = proxy("trading", "/history", request, null, userId, query, true);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        List<Map<String, Object>> result = new ArrayList<>();
        Object rows = payload.get("orders");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                Map<String, Object> value = mapValue(row);
                String rowSymbol = symbol == null || symbol.isBlank()
                        ? stringValue(value.get("symbol")) : symbol;
                result.add(orderView(value, rowSymbol, properties.getBinanceApi().scale(rowSymbol)));
            }
        }
        result.sort((left, right) -> Long.compare(number(left.get("orderId")),
                number(right.get("orderId"))));
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> account(HttpServletRequest request, byte[] body, long userId) {
        ResponseEntity<byte[]> response = proxy("account", "/balances", request, null, userId,
                "userId=" + userId, true);
        if (response.getStatusCode().isError()) return response;
        boolean canWithdraw = properties.getCustodyWallet().isEnabled()
                && isKycVerified(complianceService.kyc(userId));
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
                "canWithdraw", canWithdraw, "canDeposit", properties.getCustodyWallet().isEnabled(),
                "updateTime", System.currentTimeMillis(),
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

    private ResponseEntity<byte[]> tickerPrice(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        String query = "symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol));
        ResponseEntity<byte[]> response = proxy("trading-market", "/latest-trade", request, null, null, query, false);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        return json(HttpStatus.OK, Map.of("symbol", symbol,
                "price", decimalString(number(payload.get("priceTicks")), scale.getPriceScale())));
    }

    private ResponseEntity<byte[]> bookTicker(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        String query = "symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol))
                + "&depth=5";
        ResponseEntity<byte[]> response = proxy("trading-market", "/orderbook", request, null, null, query, false);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        Map<String, Object> bid = firstLevel(payload.get("bids"));
        Map<String, Object> ask = firstLevel(payload.get("asks"));
        return json(HttpStatus.OK, Map.of(
                "symbol", symbol,
                "bidPrice", decimalString(number(bid.get("priceTicks")), scale.getPriceScale()),
                "bidQty", decimalString(number(bid.get("quantitySteps")), scale.getQuantityScale()),
                "askPrice", decimalString(number(ask.get("priceTicks")), scale.getPriceScale()),
                "askQty", decimalString(number(ask.get("quantitySteps")), scale.getQuantityScale())));
    }

    private ResponseEntity<byte[]> klines(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        String period = requiredParameter(request, "interval").trim().toLowerCase(Locale.ROOT);
        if (!List.of("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "1w")
                .contains(period)) {
            throw new IllegalArgumentException("unsupported interval");
        }
        int limit = capped(request.getParameter("limit"));
        Instant end = epochParameter(request.getParameter("endTime"), Instant.now());
        Instant start = epochParameter(request.getParameter("startTime"), end.minus(Duration.ofDays(1)));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("startTime must be before endTime");
        }
        String query = "symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol))
                + "&period=" + encode(period)
                + "&startTime=" + encode(start.toString())
                + "&endTime=" + encode(end.toString())
                + "&limit=" + limit;
        ResponseEntity<byte[]> response = proxy("candlestick", "/candles", request, null, null, query, false);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        List<List<Object>> result = new ArrayList<>();
        Object rows = payload.get("candles");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                Map<String, Object> candle = mapValue(row);
                result.add(List.of(
                        epochMillisOrZero(candle.get("openTime")),
                        decimalResponse(candle.get("openPrice")),
                        decimalResponse(candle.get("highPrice")),
                        decimalResponse(candle.get("lowPrice")),
                        decimalResponse(candle.get("closePrice")),
                        decimalResponse(candle.get("baseVolume")),
                        epochMillisOrZero(candle.get("closeTime")),
                        decimalResponse(candle.get("quoteVolume")),
                        number(candle.get("tradeCount")),
                        "0",
                        "0",
                        "0"));
            }
        }
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> ticker24hr(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        String query = "symbol=" + encode(properties.getBinanceApi().backendSymbol(symbol));
        ResponseEntity<byte[]> response = proxy("trading-market", "/ticker-24hr", request, null, null, query, false);
        if (response.getStatusCode().isError()) return response;
        Map<String, Object> payload = readMap(response.getBody());
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        int priceScale = scale.getPriceScale();
        int quantityScale = scale.getQuantityScale();
        long openTicks = number(payload.get("openPriceTicks"));
        long lastTicks = number(payload.get("lastPriceTicks"));
        BigDecimal volumeSteps = decimalNumber(payload.get("volumeSteps"));
        BigDecimal quoteVolumeTicksSteps = decimalNumber(payload.get("quoteVolumeTicksSteps"));
        BigDecimal weightedTicks = volumeSteps.signum() == 0
                ? BigDecimal.ZERO
                : quoteVolumeTicksSteps.divide(volumeSteps, priceScale + 8, java.math.RoundingMode.HALF_UP);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        BigDecimal priceChangeTicks = BigDecimal.valueOf(lastTicks).subtract(BigDecimal.valueOf(openTicks));
        result.put("priceChange", decimalString(priceChangeTicks, priceScale));
        result.put("priceChangePercent", percentage(lastTicks, openTicks));
        result.put("weightedAvgPrice", decimalString(weightedTicks, priceScale));
        result.put("prevClosePrice", decimalString(openTicks, priceScale));
        result.put("lastPrice", decimalString(lastTicks, priceScale));
        result.put("lastQty", decimalString(decimalNumber(payload.get("lastQuantitySteps")), quantityScale));
        result.put("bidPrice", "0");
        result.put("bidQty", "0");
        result.put("askPrice", "0");
        result.put("askQty", "0");
        result.put("openPrice", decimalString(openTicks, priceScale));
        result.put("highPrice", decimalString(number(payload.get("highPriceTicks")), priceScale));
        result.put("lowPrice", decimalString(number(payload.get("lowPriceTicks")), priceScale));
        result.put("volume", decimalString(volumeSteps, quantityScale));
        result.put("quoteVolume", decimalString(quoteVolumeTicksSteps, priceScale + quantityScale));
        result.put("openTime", epochMillisOrZero(payload.get("openTime")));
        result.put("closeTime", epochMillisOrZero(payload.get("closeTime")));
        result.put("firstId", number(payload.get("firstTradeId")));
        result.put("lastId", number(payload.get("lastTradeId")));
        result.put("count", number(payload.get("tradeCount")));
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> proxy(String service, String suffix, HttpServletRequest request,
                                         byte[] body, Long userId, String query, boolean privateRoute) {
        return gatewayProxyService.proxyCompat(service, suffix, query, HttpMethod.valueOf(request.getMethod()),
                request, body, userId);
    }

    private long authenticate(HttpServletRequest request, String permission) {
        return authenticate(request, permission, null);
    }

    private long authenticate(HttpServletRequest request, String permission, byte[] body) {
        String apiKey = request.getHeader("X-MBX-APIKEY");
        if (apiKey != null && !apiKey.isBlank()) return apiKeyService.authenticate(request, permission, body);
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

    private String decimalString(BigDecimal units, int scale) {
        return units.movePointLeft(scale).stripTrailingZeros().toPlainString();
    }

    private String decimalResponse(Object value) {
        return decimalNumber(value).stripTrailingZeros().toPlainString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstLevel(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return Map.of();
        return mapValue(list.getFirst());
    }

    private BigDecimal decimalNumber(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("decimal response field is invalid", ex);
        }
    }

    private String percentage(long lastTicks, long openTicks) {
        if (openTicks == 0L) return "0";
        return BigDecimal.valueOf(lastTicks).subtract(BigDecimal.valueOf(openTicks))
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(openTicks), 8, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
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

    private long epochMillisOrZero(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        try {
            return Instant.parse(String.valueOf(value)).toEpochMilli();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("timestamp response field is invalid", ex);
        }
    }

    private Instant epochParameter(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("timestamp parameter is invalid", ex);
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
