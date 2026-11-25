package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 公开 Open API 交易与账户端点处理（order、openOrders、allOrders、account、account/status）。
 */
@Service
public class OpenTradingApiService {

    private final GatewayProperties properties;
    private final OrderService orderService;
    private final AccountService accountService;
    private final ComplianceService complianceService;
    private final OpenApiSupport support;

    public OpenTradingApiService(GatewayProperties properties,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) OrderService orderService,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) AccountService accountService,
                                 ComplianceService complianceService,
                                 OpenApiSupport support) {
        this.properties = properties;
        this.orderService = orderService;
        this.accountService = accountService;
        this.complianceService = complianceService;
        this.support = support;
    }

    public ResponseEntity<byte[]> account(HttpServletRequest request, byte[] body) {
        long userId = support.authenticate(request, "READ", body);
        BalanceQueryResponse balanceResp = accountService.balances(userId);
        boolean canWithdraw = properties.getCustodyWallet().isEnabled()
                && isKycVerified(complianceService.kyc(userId));
        List<Map<String, Object>> balances = new ArrayList<>();
        if (balanceResp.balances() != null) {
            for (BalanceResponse b : balanceResp.balances()) {
                String asset = b.asset();
                GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
                balances.add(Map.of("asset", asset,
                        "free", support.decimalString(b.availableUnits(), scale.getQuantityScale()),
                        "locked", support.decimalString(b.lockedUnits(), scale.getQuantityScale())));
            }
        }
        return support.json(HttpStatus.OK, Map.of("makerCommission", 0, "takerCommission", 0,
                "buyerCommission", 0, "sellerCommission", 0, "canTrade", true,
                "canWithdraw", canWithdraw, "canDeposit", properties.getCustodyWallet().isEnabled(),
                "updateTime", System.currentTimeMillis(),
                "accountType", "SPOT", "balances", balances));
    }

    public ResponseEntity<byte[]> accountStatus(HttpServletRequest request) {
        support.authenticate(request, "READ");
        return support.json(HttpStatus.OK, Map.of("data", "Normal"));
    }

    public ResponseEntity<byte[]> openOrders(HttpServletRequest request, byte[] body) {
        long userId = support.authenticate(request, "READ", body);
        String symbol = request.getParameter("symbol");
        String instrumentId = (symbol != null && !symbol.isBlank())
                ? properties.getBinanceApi().backendInstrumentId(symbol, support.productLine(request))
                : null;
        int limit = support.capped(request.getParameter("limit"));
        OrderQueryResponse openOrdersResp = orderService.openOrders(userId, instrumentId, new ActiveOrderPageQuery(limit, 0L), null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (openOrdersResp.orders() != null) {
            for (OrderResponse ord : openOrdersResp.orders()) {
                String rowSymbol = (symbol == null || symbol.isBlank())
                        ? properties.getBinanceApi().externalSymbol(ord.instrumentId(), support.productLine(request))
                        : symbol;
                result.add(orderView(ord, rowSymbol, properties.getBinanceApi().scale(rowSymbol)));
            }
        }
        return support.json(HttpStatus.OK, result);
    }

    public ResponseEntity<byte[]> historyOrders(HttpServletRequest request, byte[] body) {
        long userId = support.authenticate(request, "READ", body);
        String symbol = request.getParameter("symbol");
        String instrumentId = (symbol != null && !symbol.isBlank())
                ? properties.getBinanceApi().backendInstrumentId(symbol, support.productLine(request))
                : null;
        int limit = support.capped(request.getParameter("limit"));
        Long orderId = support.longParameter(request, "orderId");
        Long startTime = support.longParameter(request, "startTime");
        Long endTime = support.longParameter(request, "endTime");
        OrderQueryResponse historyResp = orderService.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime, null, null, null);
        List<OrderResponse> list = new ArrayList<>(historyResp.orders() != null ? historyResp.orders() : List.of());
        list.sort(Comparator.comparingLong(OrderResponse::orderId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderResponse ord : list) {
            String rowSymbol = (symbol == null || symbol.isBlank())
                    ? properties.getBinanceApi().externalSymbol(ord.instrumentId(), support.productLine(request))
                    : symbol;
            result.add(orderView(ord, rowSymbol, properties.getBinanceApi().scale(rowSymbol)));
        }
        return support.json(HttpStatus.OK, result);
    }

    public ResponseEntity<byte[]> order(HttpServletRequest request, byte[] body) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        Map<String, Object> params = support.parameters(request, body);
        long userId = support.authenticate(request, "GET".equals(method) ? "READ" : "TRADE", body);
        String symbol = support.required(params, "symbol");
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, support.productLine(request));
        if ("POST".equals(method)) {
            return placeOrder(params, userId, symbol, instrumentId);
        }
        if ("DELETE".equals(method)) {
            return cancelOrder(params, userId, symbol);
        }
        return queryOrder(params, userId, symbol);
    }

    private ResponseEntity<byte[]> placeOrder(Map<String, Object> params, long userId, String symbol, String instrumentId) {
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        String clientOrderId = support.first(params, "newClientOrderId", "clientOrderId");
        String side = support.required(params, "side").toUpperCase(Locale.ROOT);
        String type = support.required(params, "type").toUpperCase(Locale.ROOT);
        String tif = support.first(params, "timeInForce") == null
                ? (type.equals("MARKET") ? "IOC" : "GTC")
                : String.valueOf(support.first(params, "timeInForce")).toUpperCase(Locale.ROOT);
        long priceTicks = type.equals("MARKET") ? 0L
                : support.decimalUnits(support.required(params, "price"), scale.getPriceScale());
        long quantitySteps = support.decimalUnits(support.required(params, "quantity"), scale.getQuantityScale());
        boolean reduceOnly = support.booleanValue(params.get("reduceOnly"));
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
        OrderCommandReceipt receipt;
        try {
            receipt = orderService.placeCommandAsync(placeRequest).toCompletableFuture().join();
        } catch (Exception ex) {
            throw unwrapCommandException(ex);
        }
        if (receipt != null && receipt.result() instanceof OrderResponse resp) {
            return support.json(HttpStatus.OK, orderView(resp, symbol, scale));
        }
        long prospectiveOrderId = (receipt != null && !receipt.prospectiveOrderIds().isEmpty())
                ? receipt.prospectiveOrderIds().getFirst() : 0L;
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("symbol", symbol);
        fallback.put("orderId", prospectiveOrderId);
        fallback.put("orderListId", -1);
        fallback.put("clientOrderId", clientOrderId);
        fallback.put("transactTime", System.currentTimeMillis());
        fallback.put("price", support.decimalString(priceTicks, scale.getPriceScale()));
        fallback.put("origQty", support.decimalString(quantitySteps, scale.getQuantityScale()));
        fallback.put("executedQty", "0");
        fallback.put("cummulativeQuoteQty", "0");
        fallback.put("status", "NEW");
        fallback.put("timeInForce", tif);
        fallback.put("type", type);
        fallback.put("side", side);
        fallback.put("workingTime", System.currentTimeMillis());
        fallback.put("selfTradePreventionMode", "NONE");
        return support.json(HttpStatus.OK, fallback);
    }

    private ResponseEntity<byte[]> cancelOrder(Map<String, Object> params, long userId, String symbol) {
        Long orderId = support.longParameter(params, "orderId");
        OrderCommandReceipt receipt;
        try {
            receipt = orderService.cancelCommandAsync(new CancelOrderRequest(userId, orderId)).toCompletableFuture().join();
        } catch (Exception ex) {
            throw unwrapCommandException(ex);
        }
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        if (receipt != null && receipt.result() instanceof OrderResponse resp) {
            return support.json(HttpStatus.OK, orderView(resp, symbol, scale));
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("symbol", symbol);
        fallback.put("orderId", orderId);
        fallback.put("orderListId", -1);
        fallback.put("clientOrderId", support.first(params, "origClientOrderId", "clientOrderId"));
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
        return support.json(HttpStatus.OK, fallback);
    }

    private ResponseEntity<byte[]> queryOrder(Map<String, Object> params, long userId, String symbol) {
        String orderId = support.first(params, "orderId");
        String clientOrderId = support.first(params, "origClientOrderId", "clientOrderId");
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        if (orderId != null) {
            OrderResponse orderResp = orderService.get(userId, Long.parseLong(orderId), null);
            return support.json(HttpStatus.OK, orderView(orderResp, symbol, scale));
        }
        if (clientOrderId != null) {
            OrderResponse orderResp = orderService.getByClientOrderId(userId, clientOrderId, null);
            return support.json(HttpStatus.OK, orderView(orderResp, symbol, scale));
        }
        throw new IllegalArgumentException("orderId or origClientOrderId is required");
    }

    private RuntimeException unwrapCommandException(Exception ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        if (cause instanceof ResponseStatusException rse) {
            return rse;
        }
        if (cause instanceof com.surprising.trading.order.exception.OrderCommandRejectedException rejected) {
            return rejected;
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, cause != null ? cause.getMessage() : ex.getMessage(), cause);
    }

    private boolean isKycVerified(KycProfile profile) {
        return profile != null && "VERIFIED".equalsIgnoreCase(profile.status())
                && (profile.expiresAt() == null || !profile.expiresAt().isBefore(Instant.now()));
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
        result.put("price", support.decimalString(order.priceTicks(), scale.getPriceScale()));
        result.put("origQty", support.decimalString(order.quantitySteps(), scale.getQuantityScale()));
        result.put("executedQty", support.decimalString(order.executedQuantitySteps(), scale.getQuantityScale()));
        result.put("cummulativeQuoteQty", "0");
        result.put("status", status(order.status() != null ? order.status().name() : "NEW"));
        result.put("timeInForce", order.timeInForce() != null ? order.timeInForce().name() : "GTC");
        result.put("type", order.orderType() != null ? order.orderType().name() : "LIMIT");
        result.put("side", order.side() != null ? order.side().name() : "BUY");
        result.put("workingTime", order.createdAt() != null ? order.createdAt().toEpochMilli() : System.currentTimeMillis());
        result.put("selfTradePreventionMode", "NONE");
        return result;
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
}
