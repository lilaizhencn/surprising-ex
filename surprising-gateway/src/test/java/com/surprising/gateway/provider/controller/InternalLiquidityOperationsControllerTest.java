package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.product.GatewayProductServices;
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
    private final GatewayProductServices local = mock(GatewayProductServices.class);
    private final AccountCommandGateway accounts = mock(AccountCommandGateway.class);
    private final LeverageService leverage = mock(LeverageService.class);
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final AdminAuditRepository audit = mock(AdminAuditRepository.class);
    private InternalLiquidityOperationsController controller(String configured) {
        when(local.service(ProductLine.LINEAR_PERPETUAL, AccountCommandGateway.class)).thenReturn(accounts);
        when(local.service(ProductLine.LINEAR_PERPETUAL, LeverageService.class)).thenReturn(leverage);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(local).requireEnabled(ProductLine.INVERSE_PERPETUAL);
        return new InternalLiquidityOperationsController(configured, new com.surprising.gateway.provider.service.LiquidityOperationsService(local,instruments,audit), new ObjectMapper());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"symbol","sourceUrl"})
    void instrumentRepresentationIsRejectedBeforeIntentOrBusiness(String invalid) {
        var body=mock(com.surprising.instrument.api.model.InstrumentUpsertRequest.class);
        when(body.instrumentId()).thenReturn(null);
        when(body.symbol()).thenReturn(invalid.equals("symbol")?"BTC-USDT-SWAP":"BTC-USDT");
        when(body.baseAssetId()).thenReturn(3);when(body.quoteAssetId()).thenReturn(1);
        when(body.settleAssetId()).thenReturn(1);when(body.contractValueAssetId()).thenReturn(1);
        when(body.status()).thenReturn(com.surprising.instrument.api.model.InstrumentStatus.TRADING);
        when(body.instrumentType()).thenReturn(com.surprising.instrument.api.model.InstrumentType.SPOT);
        when(body.contractType()).thenReturn(com.surprising.instrument.api.model.ContractType.SPOT);
        when(body.supportedOrderTypes()).thenReturn(java.util.List.of("LIMIT"));
        when(body.supportedTimeInForce()).thenReturn(java.util.List.of("GTC"));
        if (invalid.equals("sourceUrl")) {
            var source=mock(com.surprising.instrument.api.model.IndexSourceConfig.class);
            when(source.baseUrl()).thenReturn("not-an-absolute-url");
            when(body.indexSources()).thenReturn(java.util.List.of(source));
        }
        assertThatThrownBy(()->controller(token).instrument(body,"update config",request()))
                .isInstanceOfSatisfying(ResponseStatusException.class,failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(instruments,audit);
    }

    @Test void operationFailureRecordsItsOutcomeAfterTheDurableIntent() {
        var body=funds(AccountType.USDT_PERPETUAL);
        doThrow(new IllegalStateException("Core outcome unknown")).when(accounts).adjustProductBalance(any(),any(),any());
        assertThatThrownBy(()->controller(token).adjust(body,request())).hasMessage("Core outcome unknown");
        var order=inOrder(audit,accounts);
        order.verify(audit).recordRequired(argThat(record->record.responseStatus()==202));
        order.verify(accounts).adjustProductBalance(body,null,"SYSTEM:LIQUIDITY_OPERATIONS");
        order.verify(audit).record(argThat(record->record.responseStatus()==500 && !record.success() && record.errorMessage().equals("IllegalStateException")));
        verify(audit,times(1)).recordRequired(any());
    }

    @Test void failedSuccessAuditNeverRetriesAnAlreadyIssuedFundsCommand() {
        doNothing().doThrow(new IllegalStateException("result audit unavailable")).when(audit).recordRequired(any());
        var body=funds(AccountType.USDT_PERPETUAL);
        assertThatThrownBy(()->controller(token).adjust(body,request())).hasMessage("result audit unavailable");
        verify(accounts,times(1)).adjustProductBalance(body,null,"SYSTEM:LIQUIDITY_OPERATIONS");
        verify(audit).record(argThat(record->record.responseStatus()==500 && !record.success()));
    }

    @Test void malformedLeverageReadNeverReachesBusiness() {
        var c=controller(token);
        assertThatThrownBy(()->c.leverage(0,"604",ProductLine.LINEAR_PERPETUAL,MarginMode.CROSS,request())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->c.leverage(2,"BTC-USDT",ProductLine.LINEAR_PERPETUAL,MarginMode.CROSS,request())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->c.leverage(2,"604",ProductLine.LINEAR_PERPETUAL,null,request())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(leverage,audit);
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
        order.verify(audit).recordRequired(argThat(r -> r.responseStatus() == 202
                && r.queryString().contains("referenceId=" + body.referenceId())
                && r.requestBodySha256().length() == 64));
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

    @Test void leverageReadRequiresHostAuthenticationAndMatchingProduct() {
        var c = controller(token);
        var missing = request(); missing.removeHeader("X-Operations-Token");
        assertThatThrownBy(() -> c.leverage(2, "604", ProductLine.LINEAR_PERPETUAL, MarginMode.CROSS, missing))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.leverage(2, "604", ProductLine.INVERSE_PERPETUAL, MarginMode.CROSS, request()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(leverage);
        c.leverage(2, "604", ProductLine.LINEAR_PERPETUAL, MarginMode.CROSS, request());
        verify(leverage).get(2, "604", MarginMode.CROSS, ProductLine.LINEAR_PERPETUAL);
    }
}
