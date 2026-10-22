package com.surprising.instrument.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.provider.service.*;
import com.surprising.instrument.provider.config.InstrumentProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InstrumentRequestHandlerTest {
    private final InstrumentStorageService storage = mock(InstrumentStorageService.class);
    private final MarketSummaryService market = mock(MarketSummaryService.class);
    private final InstrumentService instruments = new InstrumentService(storage, mock(InstrumentValidator.class),
            new InstrumentProperties(), mock(InstrumentOutboxService.class), market);
    private final InstrumentRequestHandler requests = new InstrumentRequestHandler(instruments,
            JsonMapper.builder().findAndAddModules().build());

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("malformedConfigurations")
    void malformedConfigurationIsRejectedBeforeBusinessEdit(String path, String jsonValue) {
        var business=mock(InstrumentService.class);
        var json=JsonMapper.builder().findAndAddModules().build();
        var fields=validFormat(json);
        var target=path.startsWith("source.")?(tools.jackson.databind.node.ObjectNode)fields.get("indexSources").get(0):fields;
        target.set(path.substring(path.lastIndexOf('.')+1),json.readTree(jsonValue));
        var request=json.treeToValue(fields,InstrumentUpsertRequest.class);
        var controller=new InstrumentRequestHandler(business,json);
        for (boolean edit:List.of(false,true)) {
            assertThatThrownBy(()->{if(edit)controller.edit(request,"1","edit config",0);else controller.upsert(request,"1","edit config");})
                    .isInstanceOfSatisfying(ResponseStatusException.class,failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(business);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformedConfigurations() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("symbol","\"BTC-USDT-SWAP\""),
                org.junit.jupiter.params.provider.Arguments.of("instrumentId","0"),
                org.junit.jupiter.params.provider.Arguments.of("baseAssetId","0"),
                org.junit.jupiter.params.provider.Arguments.of("status","null"),
                org.junit.jupiter.params.provider.Arguments.of("pricePrecision","19"),
                org.junit.jupiter.params.provider.Arguments.of("supportedOrderTypes","[\"LIMIT\",\"LIMIT\"]"),
                org.junit.jupiter.params.provider.Arguments.of("source.baseUrl","\"https://user:pass@example.com\""),
                org.junit.jupiter.params.provider.Arguments.of("source.path","\"//ticker\""),
                org.junit.jupiter.params.provider.Arguments.of("source.parser","\"UNKNOWN\""),
                org.junit.jupiter.params.provider.Arguments.of("source.websocketUrl","\"https://example.com\""),
                org.junit.jupiter.params.provider.Arguments.of("source.websocketSubscribeMessage","\"invalid-json\""),
                org.junit.jupiter.params.provider.Arguments.of("source.conversionMode","\"UNKNOWN\""));
    }

    @Test void validRepresentationKeepsTheSameConfigurationAtBusinessEntry() {
        var business=mock(InstrumentService.class);
        var json=JsonMapper.builder().findAndAddModules().build();
        var request=json.treeToValue(validFormat(json),InstrumentUpsertRequest.class);
        new InstrumentRequestHandler(business,json).edit(request,"1","edit config",0);
        verify(business).edit(request,"1","edit config",0);
    }

    private static tools.jackson.databind.node.ObjectNode validFormat(JsonMapper json) {
        return (tools.jackson.databind.node.ObjectNode)json.readTree("""
                {"symbol":"BTC-USDT","instrumentType":"SPOT","contractType":"SPOT","baseAssetId":3,"quoteAssetId":1,
                 "contractMultiplierPpm":1000000,"priceTickUnits":1,"quantityStepUnits":1,"minQuantitySteps":1,"maxQuantitySteps":100,
                 "minNotionalUnits":1,"maxNotionalUnits":100,"notionalMultiplierUnits":1,"postOnlyEnabled":false,"reduceOnlyEnabled":false,
                 "marketOrderEnabled":false,"maxLeveragePpm":1000000,"initialMarginRatePpm":1000000,"maintenanceMarginRatePpm":1000000,
                 "makerFeeRatePpm":0,"takerFeeRatePpm":0,"maxPositionNotionalUnits":100,"userOpenInterestLimitRatePpm":0,
                 "userOpenInterestLimitFloorUnits":1,"fundingIntervalHours":0,"interestRatePpm":0,"fundingRateCapPpm":0,"fundingRateFloorPpm":0,
                 "impactNotionalUnits":1,"minValidIndexSources":1,"settleAssetId":1,"contractValueAssetId":1,"status":"TRADING","pricePrecision":1,"quantityPrecision":3,
                 "supportedOrderTypes":["LIMIT"],"supportedTimeInForce":["GTC"],"indexSources":[
                   {"source":"A","enabled":true,"baseUrl":"https://example.com","path":"/ticker","sourceSymbol":"BTCUSDT",
                    "parser":"BINANCE_BOOK_TICKER","quoteCurrency":"USDT","targetQuoteCurrency":"USDT",
                    "conversionMode":"DISCOUNT","conversionOperation":"MULTIPLY","weightPpm":500000,
                    "websocketEnabled":true,"websocketUrl":"wss://example.com","websocketSubscribeMessage":"{}",
                    "websocketParser":"BINANCE_BOOK_TICKER","fallbackWeightMultiplierPpm":1000000}]}
                """);
    }

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void settlingDeliveryRemainsAvailableByIdAndInCatalog(ContractType type) {
        var settling = instrument(type, InstrumentStatus.SETTLING);
        var draft = instrument(type, InstrumentStatus.DRAFT);
        var closed = instrument(type, InstrumentStatus.CLOSED);
        when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(settling));
        when(storage.list(type.productLine(), null, null))
                .thenReturn(List.of(settling, draft, closed));

        assertThat(requests.latest(73, null, type.productLine().name())).isSameAs(settling);
        var catalog = (InstrumentQueryResponse) requests.list(null, null, null, type.productLine().name(), false, false);
        assertThat(catalog.instruments()).containsExactly(settling);
    }

    @Test
    void unlistedStatusesAndOtherProductVisibilityRemainRestricted() {
        for (var type : ContractType.values()) {
            for (var status : List.of(InstrumentStatus.DRAFT, InstrumentStatus.CLOSED, InstrumentStatus.SETTLING)) {
                if (type.productLine().isDeliveryProduct() && (status == InstrumentStatus.SETTLING || status == InstrumentStatus.CLOSED)) continue;
                var value = instrument(type, status);
                when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(value));
                assertThatThrownBy(() -> requests.latest(73, null, type.productLine().name()))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("404");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void closedDeliveryCanBeReadByIdentityButIsAbsentFromDefaultCatalog(ContractType type) {
        var closed = instrument(type, InstrumentStatus.CLOSED);
        when(storage.latest(73, type.productLine())).thenReturn(java.util.Optional.of(closed));
        when(storage.list(type.productLine(), null, null))
                .thenReturn(List.of(closed));
        assertThat(requests.latest(73, null, type.productLine().name())).isSameAs(closed);
        assertThat(((InstrumentQueryResponse) requests.list(null, null, null, type.productLine().name(), false, false)).instruments()).isEmpty();
    }

    @Test void catalogAggregatesOnlyVisibleContractsAndPreservesResponseFields() {
        var visible = instrument(ContractType.LINEAR_PERPETUAL, InstrumentStatus.TRADING);
        var draft = instrument(ContractType.LINEAR_PERPETUAL, InstrumentStatus.DRAFT);
        when(storage.list(ProductLine.LINEAR_PERPETUAL, null, null)).thenReturn(List.of(visible, draft));
        var one = java.math.BigDecimal.ONE;
        var summary = new com.surprising.instrument.provider.model.MarketSummaryRows.Summary(one, one, one, one, one, one, List.of(one));
        when(market.summaries(eq(List.of(73)), any())).thenReturn(java.util.Map.of(73, summary));
        var catalog = instruments.publicCatalog(ProductLine.LINEAR_PERPETUAL, null, null, true);
        assertThat(catalog.instruments().instruments()).containsExactly(visible);
        assertThat(catalog.summaries()).containsEntry(73, summary);
        verify(market).summaries(eq(List.of(73)), any());
    }

    private InstrumentResponse instrument(ContractType type, InstrumentStatus status) {
        var value = mock(InstrumentResponse.class);
        when(value.instrumentId()).thenReturn(73);
        when(value.status()).thenReturn(status);
        when(value.contractType()).thenReturn(type);
        return value;
    }
}
