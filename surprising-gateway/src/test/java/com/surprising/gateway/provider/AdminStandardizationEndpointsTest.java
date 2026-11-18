package com.surprising.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.asset.model.AssetConfiguration.Asset;
import com.surprising.asset.model.AssetConfiguration.Network;
import com.surprising.asset.repository.AssetConfigurationChangeRepository;
import com.surprising.asset.repository.AssetNetworkRepository;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.auth.controller.AdminComplianceController;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.client.SpotAccountClient;
import com.surprising.gateway.provider.client.WithdrawalValuationClient;
import com.surprising.gateway.provider.compliance.por.service.ProofOfReservesService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.CreateRequest;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.CreateResult;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.FeeSnapshot;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.WithdrawalRecord;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import com.surprising.gateway.provider.service.CustodyWithdrawalReconciliationService;
import com.surprising.gateway.provider.service.CustodyWithdrawalRefundService;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.gateway.provider.service.CustodyWithdrawalStateService;
import com.surprising.gateway.provider.subaccount.controller.AdminSubAccountController;
import com.surprising.gateway.provider.subaccount.model.SubAccountModels.SubAccountRecord;
import com.surprising.gateway.provider.subaccount.service.SubAccountService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.controller.AdminTradingController;
import com.surprising.trading.order.controller.AdminTradingFeeController;
import com.surprising.trading.order.model.GridStrategyModels.CreateGridStrategyRequest;
import com.surprising.trading.order.service.GridStrategyService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.TradingFeeService;
import com.surprising.trading.order.service.VipTierService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class AdminStandardizationEndpointsTest {

    @Test
    void withdrawalQuoteEnforcesMaintenanceMaxAndPrecision() {
        AssetRepository assetRepo = mock(AssetRepository.class);
        AssetNetworkRepository networkRepo = mock(AssetNetworkRepository.class);
        AssetConfigurationService service = new AssetConfigurationService(
                assetRepo, networkRepo, mock(AssetConfigurationChangeRepository.class), new ObjectMapper());

        when(assetRepo.byAccountingCode("USDT")).thenReturn(Optional.of(new Asset(
                1, "USDT", "Tether USD", "泰达币", "STABLECOIN", "", 100_000_000L, 2, 1,
                1_000_000L, "", "", true, true, true, true, true, true, true, true, true, 1L, Instant.now())));

        Network maintenance = new Network(
                10, 1, "TRON", "TRON (TRC20)", "TRC20", "TR7...", false, 6,
                true, true, true, true, false, true, true, true,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("500"),
                BigDecimal.ONE, new BigDecimal("50"), 2, 20, 20, 2,
                "^T[1-9A-HJ-NP-Za-km-z]{33}$", "", "", "", 3L, Instant.now());
        when(networkRepo.list(1)).thenReturn(List.of(maintenance));
        assertThatThrownBy(() -> service.withdrawalQuote("USDT", "TRON", "20"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenance");

        Network active = new Network(
                10, 1, "TRON", "TRON (TRC20)", "TRC20", "TR7...", false, 6,
                true, true, true, true, false, true, true, false,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("500"),
                BigDecimal.ONE, new BigDecimal("50"), 2, 20, 20, 2,
                "^T[1-9A-HJ-NP-Za-km-z]{33}$", "", "", "", 3L, Instant.now());
        when(networkRepo.list(1)).thenReturn(List.of(active));

        assertThatThrownBy(() -> service.withdrawalQuote("USDT", "TRON", "101"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum single withdrawal");
        assertThatThrownBy(() -> service.withdrawalQuote("USDT", "TRON", "20.123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");

        var quote = service.withdrawalQuote("USDT", "TRON", "20.25");
        assertThat(quote.receivedAmount()).isEqualTo("19.25");
    }

    @Test
    void custodyWithdrawalEnforcesAddressRegexAndAutoAuditMaxAmount() {
        GatewayProperties properties = new GatewayProperties();
        properties.getWithdrawal().setSingleApprovalThresholdUsdt(new BigDecimal("10000"));
        CustodyWithdrawalRepository repo = mock(CustodyWithdrawalRepository.class);
        CustodyWithdrawalStateService states = mock(CustodyWithdrawalStateService.class);
        WithdrawalValuationClient valuation = mock(WithdrawalValuationClient.class);
        AssetConfigurationService assets = mock(AssetConfigurationService.class);

        Network route = new Network(
                10, 1, "TRON", "TRON (TRC20)", "TRC20", "TR7...", false, 6,
                true, true, true, true, false, true, true, false,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("5000"),
                BigDecimal.ONE, new BigDecimal("50"), 2, 20, 20, 2,
                "^T[1-9A-HJ-NP-Za-km-z]{33}$", "", "", "", 5L, Instant.now());
        when(assets.withdrawalQuote("USDT", "TRON", "80"))
                .thenReturn(new AssetConfigurationService.WithdrawalQuote(
                        "USDT", "TRON", "80", 8_000_000_000L, "1", 100_000_000L,
                        "79", 7_900_000_000L, 10, 5L));
        when(assets.findRoute("USDT", "TRON")).thenReturn(Optional.of(route));
        when(valuation.toUsdt("USDT", new BigDecimal("80"))).thenReturn(new BigDecimal("80"));

        CustodyWithdrawalService service = new CustodyWithdrawalService(
                properties, repo, states, mock(CustodyWalletClient.class), mock(SpotAccountClient.class),
                valuation, mock(CustodyWithdrawalRefundService.class),
                mock(CustodyWithdrawalReconciliationService.class), new ObjectMapper(), assets);

        assertThatThrownBy(() -> service.submit(42L, "idem-bad-addr-01",
                new CustodyWithdrawalService.WithdrawalRequest(null, "TRON", "USDT", "0x1234", "80", null, 5L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address format");

        UUID id = UUID.randomUUID();
        WithdrawalRecord pendingRecord = new WithdrawalRecord(
                id, 42L, "idem-good-addr-01", "hash", "TRON", "USDT", null,
                "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb", "80", 8_000_000_000L, new BigDecimal("80"),
                "ext", "spot", "{}", "PENDING_APPROVAL", null, null, null, null,
                Instant.now(), Instant.now(), null, null, null, null, null,
                new FeeSnapshot("1", 100_000_000L, "79", 7_900_000_000L, 10, 5L), "TENANT_POOL");
        when(states.createOrGet(any())).thenReturn(new CreateResult(pendingRecord, true));

        var res = service.submit(42L, "idem-good-addr-01",
                new CustodyWithdrawalService.WithdrawalRequest(
                        null, "TRON", "USDT", "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb", "80", null, 5L));
        assertThat(res.status()).isEqualTo("PENDING_APPROVAL");

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(states).createOrGet(captor.capture());
        assertThat(captor.getValue().requiresApproval()).isTrue();
    }

    @Test
    void vipAdminEndpointsReturnLevelsAndOverrideStatus() {
        TradingFeeService feeService = mock(TradingFeeService.class);
        VipTierService vipService = mock(VipTierService.class);
        AdminTradingFeeController controller = new AdminTradingFeeController(feeService, vipService);
        when(vipService.getVipTiers()).thenReturn(com.surprising.trading.order.model.VipTierModels.VIP_TIERS);
        var updated = new VipTierService.AdminUserVipStatus(
                1001L, 5, "VIP5", 1234L, 80L, 250L, 120L, 250L, true, Instant.now());
        when(vipService.getAdminUserVipStatus(1001L, ProductLine.LINEAR_PERPETUAL)).thenReturn(updated);
        when(vipService.overrideUserVip(1001L, 5, null, null, 20L, 10L, "Market maker deal", ProductLine.LINEAR_PERPETUAL))
                .thenReturn(updated);

        assertThat(controller.vipLevels("9")).hasSize(10);
        assertThat(controller.vipStatus("9", 1001L, "LINEAR_PERPETUAL", null).currentLevel()).isEqualTo(5);
        var overrideRes = controller.overrideVip("9",
                new AdminTradingFeeController.AdminVipOverrideRequest(
                        1001L, 5, null, ProductLine.LINEAR_PERPETUAL, null, null, 20L, 10L, "Market maker deal"),
                "LINEAR_PERPETUAL", null);
        assertThat(overrideRes.get("ok")).isEqualTo(true);
    }

    @Test
    void gridAdminEndpointsListAndStopActiveStrategies() {
        OrderService orderService = mock(OrderService.class);
        when(orderService.place(any())).thenReturn(new OrderResponse(
                88L, 1001L, "grid-c1", "BTC-USDT", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                50000L, 10L, 0L, 10L, false, true, OrderStatus.ACCEPTED, null, Instant.now(), Instant.now()));
        GridStrategyService gridService = new GridStrategyService(orderService);
        gridService.createAndStartGrid(1001L, new CreateGridStrategyRequest(
                "BTC-USDT", ProductLine.LINEAR_PERPETUAL, 48000L, 52000L, 4, 1_000_000L, true), 50000L);

        AdminTradingController controller = new AdminTradingController(orderService, null, null, gridService);
        var running = controller.listGridStrategies("9", "BTC", "RUNNING", null);
        assertThat(running).hasSize(1);
        String strategyId = String.valueOf(running.getFirst().get("strategyId"));

        var stopped = controller.stopGridStrategy("9", Long.parseLong(strategyId));
        assertThat(stopped.get("ok")).isEqualTo(true);
        assertThat(controller.listGridStrategies("9", null, "STOPPED", null)).hasSize(1);
    }

    @Test
    void subAccountAdminControllerListsAndUpdatesStatus() {
        AuthService auth = mock(AuthService.class);
        AdminApprovalService approvals = mock(AdminApprovalService.class);
        SubAccountService subAccounts = mock(SubAccountService.class);
        when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        var rec = new SubAccountRecord(1L, 100L, 200L, "algo-1", "TRADE_SPOT", "ACTIVE", Instant.now());
        when(subAccounts.adminListSubAccounts(100L, null, 100)).thenReturn(List.of(rec));
        when(subAccounts.adminUpdateStatus(200L, "FROZEN")).thenReturn(
                new SubAccountRecord(1L, 100L, 200L, "algo-1", "TRADE_SPOT", "FROZEN", Instant.now()));

        AdminSubAccountController controller = new AdminSubAccountController(auth, approvals, subAccounts, new ObjectMapper());
        assertThat(controller.list("Bearer admin", 100L, null, 100)).hasSize(1);

        var req = new MockHttpServletRequest("POST", "/api/v1/admin/sub-accounts/200/status");
        req.addHeader("X-Admin-Approval-Id", "55");
        var res = controller.updateStatus("Bearer admin", 200L, "{\"status\":\"FROZEN\"}".getBytes(), req);
        assertThat(res.get("ok")).isEqualTo(true);
        verify(approvals).requireWrite(eq("Bearer admin"), eq("admin.users.write"), eq("gateway-admin"), any(), any());
    }

    @Test
    void complianceControllerExposesLatestHistoryAndTriggerPorSnapshots() {
        ComplianceService compliance = mock(ComplianceService.class);
        AuthService auth = mock(AuthService.class);
        AdminApprovalService approvals = mock(AdminApprovalService.class);
        when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        when(approvals.requireWrite(any(), eq("admin.compliance.write"), eq("gateway-admin"), any(), any()))
                .thenReturn(new JwtPrincipal(9L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60)));
        ProofOfReservesService por = new ProofOfReservesService();
        AdminComplianceController controller = new AdminComplianceController(
                compliance, new ObjectMapper(), por, auth, approvals, null);

        var initialLatest = controller.latestPor("Bearer admin");
        assertThat(initialLatest.get("merkleRoot")).isNotNull();
        assertThat(controller.porHistory("Bearer admin")).hasSize(1);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/compliance/por/snapshot/trigger");
        req.addHeader("X-Admin-Approval-Id", "88");
        var triggered = controller.triggerPorSnapshot("Bearer admin", "{}".getBytes(), req);
        assertThat(triggered.get("ok")).isEqualTo(true);
        assertThat(controller.porHistory("Bearer admin")).hasSize(2);
    }
}
