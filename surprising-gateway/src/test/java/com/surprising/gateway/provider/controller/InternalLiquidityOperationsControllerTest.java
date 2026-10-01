package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.AdminAuditRepository;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class InternalLiquidityOperationsControllerTest {
    private final String token = "a-test-operations-secret-with-32-characters";
    private final LocalBusinessApi local = mock(LocalBusinessApi.class);
    private final AccountCommandGateway accounts = mock(AccountCommandGateway.class);
    private final LeverageService leverage = mock(LeverageService.class);
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final AdminAuditRepository audit = mock(AdminAuditRepository.class);
    private InternalLiquidityOperationsController controller(String configured) {
        when(local.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        return new InternalLiquidityOperationsController(configured, local, accounts, leverage, instruments, audit, new ObjectMapper());
    }
    private MockHttpServletRequest request() {
        var r = new MockHttpServletRequest("POST", "/internal/v1/operations/liquidity/balance-adjustments");
        r.setRemoteAddr("127.0.0.1");
        r.addHeader("X-Operations-Token", token);
        r.addHeader("X-Operation-Id", "maker-capacity-test");
        return r;
    }
    private ProductBalanceAdjustmentRequest funds(AccountType type) {
        return new ProductBalanceAdjustmentRequest(2, type, "USDT", 100, "maker-capacity-test", "maker capacity");
    }

    @Test void disabledMissingOrWrongTokenCannotMutate() {
        for (String configured : new String[]{"", "short", token + "wrong"}) {
            assertThatThrownBy(() -> controller(configured).adjust(funds(AccountType.USDT_PERPETUAL), request()))
                    .isInstanceOf(ResponseStatusException.class);
        }
        var r = request(); r.removeHeader("X-Operations-Token");
        assertThatThrownBy(() -> controller(token).adjust(funds(AccountType.USDT_PERPETUAL), r))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(accounts, leverage, instruments, audit);
    }

    @Test void remoteAndForwardedRequestsCannotMutateEvenWithToken() {
        var r = request(); r.setRemoteAddr("192.0.2.1");
        assertThatThrownBy(() -> controller(token).adjust(funds(AccountType.USDT_PERPETUAL), r)).isInstanceOf(ResponseStatusException.class);
        for (String header : new String[]{"Forwarded", "X-Forwarded-For"}) {
            var forwarded = request(); forwarded.addHeader(header, "127.0.0.1");
            assertThatThrownBy(() -> controller(token).adjust(funds(AccountType.USDT_PERPETUAL), forwarded)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(accounts, audit);
    }

    @Test void productIsolationAndRequiredReasonPrecedeFundsCommand() {
        assertThatThrownBy(() -> controller(token).adjust(funds(AccountType.COIN_PERPETUAL), request())).isInstanceOf(ResponseStatusException.class);
        var noReason = new ProductBalanceAdjustmentRequest(2, AccountType.USDT_PERPETUAL, "USDT", 100, "ref", "");
        assertThatThrownBy(() -> controller(token).adjust(noReason, request())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(accounts, audit);
    }

    @Test void auditFailureStopsFundsCommand() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
        assertThatThrownBy(() -> controller(token).adjust(funds(AccountType.USDT_PERPETUAL), request())).hasMessage("audit unavailable");
        verifyNoInteractions(accounts);
    }

    @Test void fundsReferenceIsPreservedAndAuditedBeforeCommandAndAfterResult() {
        var body = funds(AccountType.USDT_PERPETUAL);
        controller(token).adjust(body, request());
        var order = inOrder(audit, accounts);
        order.verify(audit).recordRequired(argThat(r -> r.responseStatus() == 202 && r.traceId().equals(body.referenceId())));
        order.verify(accounts).adjustProductBalance(body, null, "SYSTEM:LIQUIDITY_OPERATIONS");
        order.verify(audit).recordRequired(argThat(r -> r.success() && r.requestBodySha256().length() == 64));
    }

    @Test void leverageRequiresExplicitMatchingProduct() {
        var wrong = new LeverageSettingRequest(2, ProductLine.INVERSE_PERPETUAL, "604", MarginMode.CROSS, 5_000_000, "maker capacity");
        assertThatThrownBy(() -> controller(token).leverage(wrong, request())).isInstanceOf(ResponseStatusException.class);
        var correct = new LeverageSettingRequest(2, ProductLine.LINEAR_PERPETUAL, "604", MarginMode.CROSS, 5_000_000, "maker capacity");
        controller(token).leverage(correct, request());
        verify(leverage).set(correct);
    }
}
