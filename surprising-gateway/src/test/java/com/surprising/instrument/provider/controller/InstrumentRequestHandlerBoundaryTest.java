package com.surprising.instrument.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.instrument.provider.exception.InstrumentOperationException;
import com.surprising.instrument.provider.model.InstrumentPageQuery;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InstrumentRequestHandlerBoundaryTest {
    private final InstrumentService service = mock(InstrumentService.class);
    private final InstrumentRequestHandler controller = new InstrumentRequestHandler(service, JsonMapper.builder().findAndAddModules().build());

    @Test void nativeVersionConflictRemains409AndInternalStateFailuresAreNotMisreportedAs404() {
        var request = mock(InstrumentUpsertRequest.class);
        when(request.instrumentId()).thenReturn(null);
        when(request.symbol()).thenReturn("BTC-USDT");
        when(request.baseAssetId()).thenReturn(3);
        when(request.quoteAssetId()).thenReturn(1);
        when(request.settleAssetId()).thenReturn(1);
        when(request.contractValueAssetId()).thenReturn(1);
        when(request.status()).thenReturn(com.surprising.instrument.api.model.InstrumentStatus.TRADING);
        when(request.instrumentType()).thenReturn(com.surprising.instrument.api.model.InstrumentType.SPOT);
        when(request.contractType()).thenReturn(com.surprising.instrument.api.model.ContractType.SPOT);
        when(request.supportedOrderTypes()).thenReturn(java.util.List.of("LIMIT"));
        when(request.supportedTimeInForce()).thenReturn(java.util.List.of("GTC"));
        when(service.edit(request, "1", "edit", 2)).thenThrow(new InstrumentOperationException(
                InstrumentOperationException.Reason.CONFLICT, "合约配置已更新，请重新加载后检查修改；本次修改未保存"));
        assertThatThrownBy(() -> controller.edit(request, "1", "edit", 2)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        when(service.publicLatest(1, ProductLine.SPOT)).thenThrow(new IllegalStateException("instrument product current mismatch"));
        assertThatThrownBy(() -> controller.latest(1, null, "SPOT"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("instrument product current mismatch");
    }

    @ParameterizedTest @ValueSource(strings = {"status.desc", "updatedAt.desc;drop table instruments", "symbol.DESC"})
    void unsupportedSortNeverReachesBusinessService(String sort) {
        assertBadRequest(() -> controller.adminList(null, null, 10, null, sort, null, "SPOT"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"broken", "a|", "not-a-time|BTC-USDT"})
    void malformedCursorNeverReachesBusinessService(String raw) {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertBadRequest(() -> controller.adminList(null, null, 10, cursor, "updatedAt.desc", null, "SPOT"));
        verifyNoInteractions(service);
    }

    @Test void cursorTimeAndSymbolAreBoundBeforeNativeQueryAndLimitClampingIsPreserved() {
        var time = Instant.parse("2026-01-01T00:00:00.123456Z");
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|BTC-USDT").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        controller.adminList(null, null, 1001, cursor, "updatedAt.asc", null, "SPOT");
        verify(service).list(ProductLine.SPOT, null, null, new InstrumentPageQuery(1000,
                InstrumentPageQuery.Sort.UPDATED_AT_ASC, new InstrumentPageQuery.Cursor("BTC-USDT", time)));
        controller.adminList(null, null, 0, null, null, null, "SPOT");
        verify(service).list(ProductLine.SPOT, null, null, new InstrumentPageQuery(1, InstrumentPageQuery.Sort.SYMBOL_ASC, null));
    }

    @Test void invalidAdministratorOrReasonCannotStartBusinessEdit() {
        assertBadRequest(() -> controller.edit(null, "", "edit", 1));
        assertBadRequest(() -> controller.editStatus(1, null, null, "SPOT", "1", "x".repeat(1001), 1));
        verifyNoInteractions(service);
    }

    @Test void actualLocalDispatchMapsBadCursorAndNativeMissingInstrumentWithoutCreatingHttpRoutes() {
        try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var api = LocalBusinessApi.shared(controller, JsonMapper.builder().findAndAddModules().build(), validation.getValidator(),
                    mock(com.surprising.websocket.provider.service.SubscriptionRegistry.class));
            var headers = new HttpHeaders(); headers.set("X-Admin-User-Id", "1");
            var response = api.invoke("instrument-admin", URI.create("http://local/api/v1/instruments/admin/list?productLine=SPOT&sort=updatedAt.desc&cursor=broken"),
                    HttpMethod.GET, headers, null, Duration.ofSeconds(1));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(service);
            when(service.publicLatest(1, ProductLine.SPOT)).thenThrow(new InstrumentOperationException(InstrumentOperationException.Reason.NOT_FOUND, "instrument is not listed"));
            response = api.invoke("instrument", URI.create("http://local/api/v1/instruments/latest?productLine=SPOT&instrumentId=1"),
                    HttpMethod.GET, new HttpHeaders(), null, Duration.ofSeconds(1));
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
        assertThat(InstrumentRequestHandler.class.getAnnotation(org.springframework.web.bind.annotation.RestController.class)).isNull();
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
