package com.surprising.gateway.provider.controller;

import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.UserDepositAddressRepository;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 公开 Open API 统一网关入口，按行情、交易/账户、资金/充提三类路由至专职服务。
 */
@RestController
public class OpenApiController {

    private static final Set<String> MARKET_PATHS = Set.of("/ping", "/time", "/exchangeInfo", "/depth",
            "/account", "/account/status", "/openOrders", "/allOrders", "/order", "/ticker/bookTicker");
    private static final Set<String> ACCOUNT_PATHS = Set.of("/sapi/v1/account/status", "/sapi/v1/asset/transfer",
            "/sapi/v1/capital/deposit/address", "/sapi/v1/capital/deposit/hisrec", "/sapi/v1/capital/config/getall",
            "/sapi/v1/capital/withdraw/history", "/sapi/v1/capital/withdraw/apply", "/sapi/v1/capital/withdraw/quote");
    private static final List<String> COMPATIBLE_PREFIXES = List.of("/api/v3", "/fapi/v1", "/dapi/v1", "/eapi/v1");
    private static final Set<String> ORDER_METHODS = Set.of("GET", "POST", "DELETE");

    private final GatewayProperties properties;
    private final OpenApiSupport support;
    private final OpenMarketApiService marketApi;
    private final OpenTradingApiService tradingApi;
    private final OpenCapitalApiService capitalApi;

    public OpenApiController(GatewayProperties properties,
                             InstrumentService instrumentService,
                             OrderService orderService,
                             AccountService accountService,
                             MatchingMarketDataService marketDataService,
                             GatewayApiKeyService apiKeyService,
                             SensitiveActionVerificationService verificationService,
                             AuthService authService,
                             ComplianceService complianceService,
                             CustodyWalletClient custodyWalletClient,
                             CustodyWithdrawalService withdrawalService,
                             ObjectMapper objectMapper) {
        this(properties, instrumentService, orderService, accountService, marketDataService,
                apiKeyService, verificationService, authService, complianceService, custodyWalletClient,
                withdrawalService, null, objectMapper);
    }

    public OpenApiController(GatewayProperties properties,
                             InstrumentService instrumentService,
                             OrderService orderService,
                             AccountService accountService,
                             MatchingMarketDataService marketDataService,
                             GatewayApiKeyService apiKeyService,
                             SensitiveActionVerificationService verificationService,
                             AuthService authService,
                             ComplianceService complianceService,
                             CustodyWalletClient custodyWalletClient,
                             CustodyWithdrawalService withdrawalService,
                             UserDepositAddressRepository depositAddressRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.support = new OpenApiSupport(properties, apiKeyService, authService, objectMapper);
        this.marketApi = new OpenMarketApiService(properties, instrumentService, marketDataService, support);
        this.tradingApi = new OpenTradingApiService(properties, orderService, accountService, complianceService, support);
        this.capitalApi = new OpenCapitalApiService(properties, accountService, verificationService, complianceService,
                custodyWalletClient, withdrawalService, depositAddressRepository, support);
    }

    @Autowired
    public OpenApiController(GatewayProperties properties,
                             OpenApiSupport support,
                             OpenMarketApiService marketApi,
                             OpenTradingApiService tradingApi,
                             OpenCapitalApiService capitalApi) {
        this.properties = properties;
        this.support = support;
        this.marketApi = marketApi;
        this.tradingApi = tradingApi;
        this.capitalApi = capitalApi;
    }

    @RequestMapping(path = {"/api/v3/**", "/sapi/v1/**", "/fapi/v1/**", "/dapi/v1/**", "/eapi/v1/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.PUT})
    public ResponseEntity<byte[]> handle(HttpServletRequest request,
                                         @RequestBody(required = false) byte[] body) {
        if (!properties.getBinanceApi().isEnabled()) {
            return support.error(HttpStatus.NOT_FOUND, -1003, "Open API is disabled");
        }
        String path = request.getRequestURI();
        boolean supported = ACCOUNT_PATHS.contains(path);
        for (String prefix : COMPATIBLE_PREFIXES) {
            if (path.startsWith(prefix + "/") && MARKET_PATHS.contains(path.substring(prefix.length()))) {
                supported = true;
            }
        }
        if (!supported) {
            return support.error(HttpStatus.NOT_FOUND, -1003, "unsupported endpoint");
        }
        String method = request.getMethod();
        boolean orderEndpoint = path.endsWith("/order");
        boolean writeEndpoint = path.endsWith("/asset/transfer") || path.endsWith("/capital/withdraw/apply");
        boolean methodAllowed;
        if (orderEndpoint) methodAllowed = ORDER_METHODS.contains(method);
        else if (writeEndpoint) methodAllowed = "POST".equals(method);
        else methodAllowed = "GET".equals(method);
        if (!methodAllowed) {
            return support.error(HttpStatus.METHOD_NOT_ALLOWED, -1100, "HTTP method is not supported for this endpoint");
        }
        try {
            if (path.endsWith("/ping")) return marketApi.ping();
            if (path.endsWith("/time")) return marketApi.time();
            if (path.endsWith("/exchangeInfo")) return marketApi.exchangeInfo(request);
            if (path.endsWith("/depth")) return marketApi.depth(request);
            if (path.endsWith("/ticker/bookTicker")) return marketApi.bookTicker(request);
            if (path.endsWith("/asset/transfer")) return capitalApi.transfer(request, body);
            if (path.endsWith("/capital/deposit/address")) return capitalApi.depositAddress(request);
            if (path.endsWith("/capital/deposit/hisrec")) return capitalApi.depositHistory(request);
            if (path.endsWith("/capital/config/getall")) return capitalApi.capitalConfig(request);
            if (path.endsWith("/capital/withdraw/history")) return capitalApi.withdrawHistory(request);
            if (path.endsWith("/capital/withdraw/quote")) return capitalApi.withdrawQuote(request, body);
            if (path.endsWith("/capital/withdraw/apply")) return capitalApi.withdraw(request, body);
            if (path.endsWith("/account")) return tradingApi.account(request, body);
            if (path.endsWith("/account/status")) return tradingApi.accountStatus(request);
            if (path.endsWith("/openOrders")) return tradingApi.openOrders(request, body);
            if (path.endsWith("/allOrders")) return tradingApi.historyOrders(request, body);
            if (path.endsWith("/order")) return tradingApi.order(request, body);
            return support.error(HttpStatus.NOT_FOUND, -1003, "endpoint not implemented");
        } catch (IllegalArgumentException ex) {
            return support.error(HttpStatus.BAD_REQUEST, -1102, ex.getMessage());
        } catch (ResponseStatusException ex) {
            return support.error(HttpStatus.valueOf(ex.getStatusCode().value()), -1000, ex.getReason());
        } catch (Exception ex) {
            return support.error(HttpStatus.SERVICE_UNAVAILABLE, -1001, ex.getMessage());
        }
    }
}
