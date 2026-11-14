package com.surprising.gateway.provider.controller;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.controller.AccountRequestHandler;
import com.surprising.gateway.provider.auth.controller.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.instrument.provider.controller.InstrumentRequestHandler;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.controller.OrderRequestHandler;
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
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import com.surprising.gateway.provider.repository.UserDepositAddressRepository;
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
    private static final java.util.Set<String> MARKET_PATHS = java.util.Set.of("/ping", "/time", "/exchangeInfo", "/depth",
            "/account", "/account/status", "/openOrders", "/allOrders", "/order", "/ticker/bookTicker");
    private static final java.util.Set<String> ACCOUNT_PATHS = java.util.Set.of("/sapi/v1/account/status", "/sapi/v1/asset/transfer",
            "/sapi/v1/capital/deposit/address", "/sapi/v1/capital/deposit/hisrec", "/sapi/v1/capital/config/getall",
            "/sapi/v1/capital/withdraw/history", "/sapi/v1/capital/withdraw/apply", "/sapi/v1/capital/withdraw/quote");

    private final ApiKeyRequestBinding apiKeyRequests;
    private final GatewayProperties properties;
    private final InstrumentRequestHandler instrumentRequests;
    private final OrderRequestHandler orderRequests;
    private final AccountRequestHandler accountRequests;
    private final MatchingMarketDataService marketDataService;
    private final GatewayApiKeyService apiKeyService;
    private final SensitiveActionVerificationService verificationService;
    private final AuthService authService;
    private final ComplianceService complianceService;
    private final CustodyWalletClient custodyWalletClient;
    private final CustodyWithdrawalService withdrawalService;
    private final UserDepositAddressRepository depositAddressRepository;
    private final ObjectMapper objectMapper;

    public BinanceApiController(GatewayProperties properties,
                                InstrumentRequestHandler instrumentRequests,
                                OrderRequestHandler orderRequests,
                                AccountRequestHandler accountRequests,
                                MatchingMarketDataService marketDataService,
                                GatewayApiKeyService apiKeyService,
                                SensitiveActionVerificationService verificationService,
                                AuthService authService,
                                ComplianceService complianceService,
                                CustodyWalletClient custodyWalletClient,
                                CustodyWithdrawalService withdrawalService,
                                ObjectMapper objectMapper) {
        this(properties, instrumentRequests, orderRequests, accountRequests, marketDataService,
                apiKeyService, verificationService, authService, complianceService, custodyWalletClient,
                withdrawalService, null, objectMapper);
    }

    @Autowired
    public BinanceApiController(GatewayProperties properties,
                                InstrumentRequestHandler instrumentRequests,
                                OrderRequestHandler orderRequests,
                                AccountRequestHandler accountRequests,
                                MatchingMarketDataService marketDataService,
                                GatewayApiKeyService apiKeyService,
                                SensitiveActionVerificationService verificationService,
                                AuthService authService,
                                ComplianceService complianceService,
                                CustodyWalletClient custodyWalletClient,
                                CustodyWithdrawalService withdrawalService,
                                @Autowired(required = false) UserDepositAddressRepository depositAddressRepository,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.apiKeyRequests = new ApiKeyRequestBinding(properties);
        this.instrumentRequests = instrumentRequests;
        this.orderRequests = orderRequests;
        this.accountRequests = accountRequests;
        this.marketDataService = marketDataService;
        this.apiKeyService = apiKeyService;
        this.verificationService = verificationService;
        this.authService = authService;
        this.complianceService = complianceService;
        this.custodyWalletClient = custodyWalletClient;
        this.withdrawalService = withdrawalService;
        this.depositAddressRepository = depositAddressRepository;
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
        boolean supported = ACCOUNT_PATHS.contains(path);
        for (String prefix : COMPATIBLE_PREFIXES)
            if (path.startsWith(prefix + "/") && MARKET_PATHS.contains(path.substring(prefix.length()))) supported = true;
        if (!supported) return error(HttpStatus.NOT_FOUND, -1003, "unsupported compatible endpoint");
        String method = request.getMethod();
        boolean orderEndpoint = path.endsWith("/order");
        boolean writeEndpoint = path.endsWith("/asset/transfer") || path.endsWith("/capital/withdraw/apply");
        boolean methodAllowed;
        if (orderEndpoint) methodAllowed = ORDER_METHODS.contains(method);
        else if (writeEndpoint) methodAllowed = "POST".equals(method);
        else methodAllowed = "GET".equals(method);
        if (!methodAllowed) return error(HttpStatus.METHOD_NOT_ALLOWED, -1100, "HTTP method is not supported for this endpoint");
        try {
            if (path.endsWith("/ping")) {
                return json(HttpStatus.OK, Map.of());
            }
            if (path.endsWith("/time")) {
                return json(HttpStatus.OK, Map.of("serverTime", System.currentTimeMillis()));
            }
            if (path.endsWith("/exchangeInfo")) {
                ProductLine product = productLine(request);
                return json(HttpStatus.OK, instrumentRequests.list(null, null, product.name(), product.name(), false, false));
            }
            if (path.endsWith("/depth")) {
                return depth(request);
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
            if (path.endsWith("/capital/withdraw/quote")) {
                authenticate(request, "WITHDRAW", body);
                var params = parameters(request, body);
                return json(HttpStatus.OK, withdrawalService.quote(required(params, "coin"), required(params, "network"), required(params, "amount")));
            }
            if (path.endsWith("/capital/withdraw/apply")) {
                return withdraw(request, body);
            }
            if (path.endsWith("/account")) {
                long userId = authenticate(request, "READ", body);
                return account(request, userId);
            }
            if (path.endsWith("/account/status")) {
                authenticate(request, "READ");
                return json(HttpStatus.OK, Map.of("data", "Normal"));
            }
            if (path.endsWith("/openOrders")) {
                long userId = authenticate(request, "READ", body);
                return orders(request, userId);
            }
            if (path.endsWith("/allOrders")) {
                long userId = authenticate(request, "READ", body);
                return historyOrders(request, userId);
            }
            if (path.endsWith("/order")) {
                return order(request, body);
            }
            if (path.endsWith("/ticker/bookTicker")) {
                return bookTicker(request);
            }
            return error(HttpStatus.NOT_FOUND, -1003, "endpoint not implemented");
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, -1102, ex.getMessage());
        } catch (ResponseStatusException ex) {
            return error(HttpStatus.valueOf(ex.getStatusCode().value()), -1000, ex.getReason());
        } catch (Exception ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, -1001, ex.getMessage());
        }
    }

    private ResponseEntity<byte[]> transfer(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = parameters(request, body);
        long userId = authenticate(request, "TRADE", body);
        String type = required(params, "type").toUpperCase(Locale.ROOT);
        String asset = required(params, "asset");
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
        String idempotencyKey = first(params, "clientTranId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("clientTranId or Idempotency-Key is required");
        }
        ProductTransferRequest transferRequest = new ProductTransferRequest(
                userId,
                AccountType.valueOf(transferAccount(type, true)),
                AccountType.valueOf(transferAccount(type, false)),
                asset.toUpperCase(Locale.ROOT),
                decimalUnits(required(params, "amount"), scale.getQuantityScale()),
                idempotencyKey.trim(),
                "binance asset transfer"
        );
        ProductTransferResponse result = accountRequests.transfer(transferRequest);
        return json(HttpStatus.OK, Map.of("tranId", result.transferId()));
    }

    private ResponseEntity<byte[]> depositAddress(HttpServletRequest request) {
        long userId = authenticate(request, "READ");
        String chain = requiredParameter(request, "network");
        if (depositAddressRepository != null) {
            var cached = depositAddressRepository.find(userId, chain, 1L);
            if (cached.isPresent()) {
                var r = cached.get();
                return json(HttpStatus.OK, Map.of(
                        "coin", requiredParameter(request, "coin"),
                        "address", r.address(),
                        "tag", r.memo(),
                        "url", ""
                ));
            }
        }
        Map<String, Object> result = custodyWalletClient.createAddress(userId, chain, null);
        if (result != null && depositAddressRepository != null && result.get("address") instanceof String address && !address.isBlank()) {
            String memo = Objects.toString(result.get("memo"), "");
            String family = "generic";
            try {
                for (var c : custodyWalletClient.chains()) {
                    if (chain.equals(c.get("chain")) && c.get("family") instanceof String f) {
                        family = f.toLowerCase(Locale.ROOT);
                        break;
                    }
                }
            } catch (Exception ignored) {}
            depositAddressRepository.save(userId, chain, family, address, memo, 1L);
        }
        return json(HttpStatus.OK, Map.of("coin", requiredParameter(request, "coin"),
                "address", stringValue(result.get("address")), "tag", stringValue(result.get("memo")),
                "url", ""));
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
        String asset = required(params, "coin");
        String chain = required(params, "network");
        String idempotencyKey = first(params, "withdrawOrderId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("withdrawOrderId or Idempotency-Key is required");
        }
        String amount = required(params, "amount");
        String revision = required(params, "networkRevision");
        var intent = new CustodyWithdrawalService.WithdrawalRequest(null, chain, asset, required(params, "address"), amount, null, Long.parseLong(revision));
        var result = withdrawalService.replay(userId, idempotencyKey, intent);
        if (result == null) {
            requireWithdrawalSecurity(request, userId, apiKeyRequest ? "API_WITHDRAWAL" : "WITHDRAWAL");
            requireKyc(userId);
            complianceService.requireWithdrawalEligibility(userId);
            result = withdrawalService.submit(userId, idempotencyKey, intent);
        }
        return json(HttpStatus.OK, Map.of("id", result.walletWithdrawalId() == null
                        ? result.withdrawalId().toString() : result.walletWithdrawalId(),
                "withdrawalId", result.withdrawalId().toString(), "status", result.status(),
                "msg", "success", "success", true, "amount", result.amount(), "platformFee", result.fee(), "receivedAmount", result.receivedAmount()));
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
        long userId = authenticate(request, "GET".equals(method) ? "READ" : "TRADE", body);
        String symbol = required(params, "symbol");
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, productLine(request));
        if ("POST".equals(method)) {
            GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
            String clientOrderId = first(params, "newClientOrderId", "clientOrderId");
            String side = required(params, "side").toUpperCase(Locale.ROOT);
            String type = required(params, "type").toUpperCase(Locale.ROOT);
            String tif = first(params, "timeInForce") == null
                    ? (type.equals("MARKET") ? "IOC" : "GTC")
                    : String.valueOf(first(params, "timeInForce")).toUpperCase(Locale.ROOT);
            long priceTicks = type.equals("MARKET") ? 0L
                    : decimalUnits(required(params, "price"), scale.getPriceScale());
            long quantitySteps = decimalUnits(required(params, "quantity"), scale.getQuantityScale());
            boolean reduceOnly = booleanValue(params.get("reduceOnly"));
            boolean postOnly = "GTX".equals(tif);

            PlaceOrderRequest placeRequest = new PlaceOrderRequest(
                    userId,
                    clientOrderId,
                    instrumentId,
                    OrderSide.valueOf(side),
                    OrderType.valueOf(type),
                    TimeInForce.valueOf(tif.equals("GTX") ? "GTC" : tif),
                    priceTicks,
                    quantitySteps,
                    MarginMode.CROSS,
                    PositionSide.NET,
                    reduceOnly,
                    postOnly
            );
            ResponseEntity<OrderCommandReceipt> receiptEntity;
            try {
                receiptEntity = orderRequests.place(placeRequest).toCompletableFuture().join();
            } catch (Exception ex) {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                if (cause instanceof ResponseStatusException rse) {
                    throw rse;
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, cause != null ? cause.getMessage() : ex.getMessage(), cause);
            }
            if (receiptEntity.getStatusCode().isError()) {
                return ResponseEntity.status(receiptEntity.getStatusCode()).body(jsonBytes(receiptEntity.getBody()));
            }
            OrderCommandReceipt receipt = receiptEntity.getBody();
            if (receipt != null && receipt.result() instanceof OrderResponse resp) {
                return json(HttpStatus.OK, orderView(resp, symbol, scale));
            }
            long prospectiveOrderId = (receipt != null && !receipt.prospectiveOrderIds().isEmpty())
                    ? receipt.prospectiveOrderIds().getFirst() : 0L;
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("symbol", symbol);
            fallback.put("orderId", prospectiveOrderId);
            fallback.put("orderListId", -1);
            fallback.put("clientOrderId", clientOrderId);
            fallback.put("transactTime", System.currentTimeMillis());
            fallback.put("price", decimalString(priceTicks, scale.getPriceScale()));
            fallback.put("origQty", decimalString(quantitySteps, scale.getQuantityScale()));
            fallback.put("executedQty", "0");
            fallback.put("cummulativeQuoteQty", "0");
            fallback.put("status", "NEW");
            fallback.put("timeInForce", tif);
            fallback.put("type", type);
            fallback.put("side", side);
            fallback.put("workingTime", System.currentTimeMillis());
            fallback.put("selfTradePreventionMode", "NONE");
            return json(HttpStatus.OK, fallback);
        }
        if ("DELETE".equals(method)) {
            Long orderId = longParameter(params, "orderId");
            ResponseEntity<OrderCommandReceipt> receiptEntity;
            try {
                receiptEntity = orderRequests.cancel(new CancelOrderRequest(userId, orderId)).toCompletableFuture().join();
            } catch (Exception ex) {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                if (cause instanceof ResponseStatusException rse) {
                    throw rse;
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, cause != null ? cause.getMessage() : ex.getMessage(), cause);
            }
            if (receiptEntity.getStatusCode().isError()) {
                return ResponseEntity.status(receiptEntity.getStatusCode()).body(jsonBytes(receiptEntity.getBody()));
            }
            GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
            OrderCommandReceipt receipt = receiptEntity.getBody();
            if (receipt != null && receipt.result() instanceof OrderResponse resp) {
                return json(HttpStatus.OK, orderView(resp, symbol, scale));
            }
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("symbol", symbol);
            fallback.put("orderId", orderId);
            fallback.put("orderListId", -1);
            fallback.put("clientOrderId", first(params, "origClientOrderId", "clientOrderId"));
            fallback.put("transactTime", System.currentTimeMillis());
            fallback.put("price", "0");
            fallback.put("origQty", "0");
            fallback.put("executedQty", "0");
            fallback.put("cummulativeQuoteQty", "0");
            fallback.put("status", "CANCELED");
            fallback.put("timeInForce", "GTC");
            fallback.put("type", "LIMIT");
            fallback.put("side", "BUY");
            fallback.put("workingTime", System.currentTimeMillis());
            fallback.put("selfTradePreventionMode", "NONE");
            return json(HttpStatus.OK, fallback);
        }
        String orderId = first(params, "orderId");
        String clientOrderId = first(params, "origClientOrderId", "clientOrderId");
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        if (orderId != null) {
            OrderResponse orderResp = orderRequests.get(userId, Long.parseLong(orderId), null);
            return json(HttpStatus.OK, orderView(orderResp, symbol, scale));
        }
        if (clientOrderId != null) {
            OrderResponse orderResp = orderRequests.getByClientOrderId(userId, clientOrderId, null);
            return json(HttpStatus.OK, orderView(orderResp, symbol, scale));
        }
        throw new IllegalArgumentException("orderId or origClientOrderId is required");
    }

    private static final List<String> COMPATIBLE_PREFIXES = List.of("/api/v3", "/fapi/v1", "/dapi/v1", "/eapi/v1");
    private static final java.util.Set<String> ORDER_METHODS = java.util.Set.of("GET", "POST", "DELETE");

    private ResponseEntity<byte[]> orders(HttpServletRequest request, long userId) {
        String symbol = request.getParameter("symbol");
        String instrumentId = (symbol != null && !symbol.isBlank())
                ? properties.getBinanceApi().backendInstrumentId(symbol, productLine(request))
                : null;
        int limit = capped(request.getParameter("limit"));
        OrderQueryResponse openOrdersResp = orderRequests.openOrders(userId, instrumentId, limit, null, null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (openOrdersResp.orders() != null) {
            for (OrderResponse ord : openOrdersResp.orders()) {
                String rowSymbol = (symbol == null || symbol.isBlank())
                        ? properties.getBinanceApi().externalSymbol(ord.instrumentId(), productLine(request))
                        : symbol;
                result.add(orderView(ord, rowSymbol, properties.getBinanceApi().scale(rowSymbol)));
            }
        }
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> historyOrders(HttpServletRequest request, long userId) {
        String symbol = request.getParameter("symbol");
        String instrumentId = (symbol != null && !symbol.isBlank())
                ? properties.getBinanceApi().backendInstrumentId(symbol, productLine(request))
                : null;
        int limit = capped(request.getParameter("limit"));
        Long orderId = longParameter(request, "orderId");
        Long startTime = longParameter(request, "startTime");
        Long endTime = longParameter(request, "endTime");
        OrderQueryResponse historyResp = orderRequests.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime, null, null, null);
        List<OrderResponse> list = new ArrayList<>(historyResp.orders() != null ? historyResp.orders() : List.of());
        list.sort(java.util.Comparator.comparingLong(OrderResponse::orderId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderResponse ord : list) {
            String rowSymbol = (symbol == null || symbol.isBlank())
                    ? properties.getBinanceApi().externalSymbol(ord.instrumentId(), productLine(request))
                    : symbol;
            result.add(orderView(ord, rowSymbol, properties.getBinanceApi().scale(rowSymbol)));
        }
        return json(HttpStatus.OK, result);
    }

    private ResponseEntity<byte[]> account(HttpServletRequest request, long userId) {
        BalanceQueryResponse balanceResp = accountRequests.balances(userId);
        boolean canWithdraw = properties.getCustodyWallet().isEnabled()
                && isKycVerified(complianceService.kyc(userId));
        List<Map<String, Object>> balances = new ArrayList<>();
        if (balanceResp.balances() != null) {
            for (BalanceResponse b : balanceResp.balances()) {
                String asset = b.asset();
                GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
                balances.add(Map.of("asset", asset,
                        "free", decimalString(b.availableUnits(), scale.getQuantityScale()),
                        "locked", decimalString(b.lockedUnits(), scale.getQuantityScale())));
            }
        }
        return json(HttpStatus.OK, Map.of("makerCommission", 0, "takerCommission", 0,
                "buyerCommission", 0, "sellerCommission", 0, "canTrade", true,
                "canWithdraw", canWithdraw, "canDeposit", properties.getCustodyWallet().isEnabled(),
                "updateTime", System.currentTimeMillis(),
                "accountType", "SPOT", "balances", balances));
    }

    private ResponseEntity<byte[]> depth(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        int limit = capped(request.getParameter("limit"));
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, productLine(request));
        OrderBookSnapshotResponse snapshot = marketDataService.orderBookSnapshot(instrumentId, limit);
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        return json(HttpStatus.OK, Map.of(
                "lastUpdateId", snapshot.sequence(),
                "bids", levels(snapshot.bids(), scale),
                "asks", levels(snapshot.asks(), scale)));
    }

    private ResponseEntity<byte[]> bookTicker(HttpServletRequest request) {
        String symbol = requiredParameter(request, "symbol");
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, productLine(request));
        OrderBookSnapshotResponse snapshot = marketDataService.orderBookSnapshot(instrumentId, 5);
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        OrderBookLevel bid = snapshot.bids().isEmpty() ? null : snapshot.bids().getFirst();
        OrderBookLevel ask = snapshot.asks().isEmpty() ? null : snapshot.asks().getFirst();
        return json(HttpStatus.OK, Map.of(
                "symbol", symbol,
                "bidPrice", decimalString(bid != null ? bid.priceTicks() : 0L, scale.getPriceScale()),
                "bidQty", decimalString(bid != null ? bid.quantitySteps() : 0L, scale.getQuantityScale()),
                "askPrice", decimalString(ask != null ? ask.priceTicks() : 0L, scale.getPriceScale()),
                "askQty", decimalString(ask != null ? ask.quantitySteps() : 0L, scale.getQuantityScale())));
    }

    private ProductLine productLine(HttpServletRequest request) {
        String explicit = request.getHeader("X-Product-Line");
        if (explicit == null || explicit.isBlank()) explicit = request.getParameter("productLine");
        if (explicit != null && !explicit.isBlank()) return ProductLine.valueOf(explicit);
        String path = request.getRequestURI();
        if (path.startsWith("/fapi/")) return ProductLine.LINEAR_PERPETUAL;
        if (path.startsWith("/dapi/")) return ProductLine.INVERSE_PERPETUAL;
        if (path.startsWith("/eapi/")) return ProductLine.OPTION;
        return ProductLine.SPOT;
    }

    private long authenticate(HttpServletRequest request, String permission) {
        return authenticate(request, permission, null);
    }

    private long authenticate(HttpServletRequest request, String permission, byte[] body) {
        String apiKey = request.getHeader("X-MBX-APIKEY");
        if (apiKey != null && !apiKey.isBlank()) {
            String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (bearer != null && !bearer.isBlank()) throw new IllegalArgumentException("choose bearer or API key authentication");
            try { return apiKeyService.authenticate(apiKeyRequests.bind(request, body), permission); }
            catch (ApiKeyAccessBlockedException ex) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
            }
        }
        try {
            JwtPrincipal principal = authService.authenticateBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
            return principal.userId();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private Map<String, Object> parameters(HttpServletRequest request, byte[] body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (body != null && body.length > 0 && new String(body, StandardCharsets.UTF_8).stripLeading().startsWith("{")) {
            request.getParameterMap().forEach((key, values) -> {
                if (values != null && values.length > 0) result.put(key, values[0]);
            });
            readMap(body).forEach(result::putIfAbsent);
        } else {
            apiKeyRequests.formParameters(request, body).forEach((key, values) -> {
                if (!values.isEmpty()) result.put(key, values.getFirst());
            });
        }
        return result;
    }

    private Map<String, Object> orderView(OrderResponse order, String requestedSymbol,
                                          GatewayProperties.SymbolScale scale) {
        String symbol = (requestedSymbol == null || requestedSymbol.isBlank())
                ? properties.getBinanceApi().externalSymbol(order.instrumentId(), ProductLine.SPOT) : requestedSymbol;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("orderId", order.orderId());
        result.put("orderListId", -1);
        result.put("clientOrderId", order.clientOrderId());
        result.put("transactTime", order.createdAt() != null ? order.createdAt().toEpochMilli() : System.currentTimeMillis());
        result.put("price", decimalString(order.priceTicks(), scale.getPriceScale()));
        result.put("origQty", decimalString(order.quantitySteps(), scale.getQuantityScale()));
        result.put("executedQty", decimalString(order.executedQuantitySteps(), scale.getQuantityScale()));
        result.put("cummulativeQuoteQty", "0");
        result.put("status", status(order.status() != null ? order.status().name() : "NEW"));
        result.put("timeInForce", order.timeInForce() != null ? order.timeInForce().name() : "GTC");
        result.put("type", order.orderType() != null ? order.orderType().name() : "LIMIT");
        result.put("side", order.side() != null ? order.side().name() : "BUY");
        result.put("workingTime", order.createdAt() != null ? order.createdAt().toEpochMilli() : System.currentTimeMillis());
        result.put("selfTradePreventionMode", "NONE");
        return result;
    }

    private List<List<String>> levels(List<OrderBookLevel> levels, GatewayProperties.SymbolScale scale) {
        if (levels == null) return List.of();
        List<List<String>> result = new ArrayList<>();
        for (OrderBookLevel level : levels) {
            result.add(List.of(decimalString(level.priceTicks(), scale.getPriceScale()),
                    decimalString(level.quantitySteps(), scale.getQuantityScale())));
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

    private Long longParameter(HttpServletRequest request, String key) {
        String val = request.getParameter(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid", ex);
        }
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
}
