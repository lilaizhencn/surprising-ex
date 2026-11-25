package com.surprising.gateway.provider.controller;

import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.gateway.provider.service.OpenApiSupport;
import com.surprising.gateway.provider.service.OpenCapitalApiService;
import com.surprising.gateway.provider.service.OpenMarketApiService;
import com.surprising.gateway.provider.service.OpenTradingApiService;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.service.OrderService;
import tools.jackson.databind.ObjectMapper;

/** 测试用 OpenApiController 装配辅助。 */
public final class OpenApiTestSupport {

    private OpenApiTestSupport() {
    }

    public static OpenApiController controller(GatewayProperties properties,
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
        OpenApiSupport support = new OpenApiSupport(properties, apiKeyService, authService, objectMapper);
        return new OpenApiController(
                properties,
                support,
                new OpenMarketApiService(properties, instrumentService, marketDataService, support),
                new OpenTradingApiService(properties, orderService, accountService, complianceService, support),
                new OpenCapitalApiService(properties, accountService, verificationService, complianceService,
                        custodyWalletClient, withdrawalService, null, null, null, support));
    }
}
