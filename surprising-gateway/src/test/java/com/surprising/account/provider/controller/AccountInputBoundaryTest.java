package com.surprising.account.provider.controller;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.*;
import com.surprising.account.api.model.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountInputBoundaryTest {
    final ValkeyUserQueries views=mock(ValkeyUserQueries.class);
    final AccountAeronGateway aeron=mock(AccountAeronGateway.class);
    final ObjectMapper json=new ObjectMapper();
    MockMvc mvc;
    AccountService service;
    void setup(ProductLine line) {
        var properties=new AccountProperties();properties.getKafka().setProductLine(line);
        var commands=new AccountCommandGateway(properties,aeron);service=new AccountService(properties,commands,views);
        mvc=MockMvcBuilders.standaloneSetup(new AccountInternalController(new AccountRequestController(service,mock(AccountQueryService.class),commands,properties))).build();
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void balanceRoutesBindCanonicalAssetAndKeepExactInt64Units(ProductLine line) throws Exception {
        setup(line);when(views.balance(line,42,"USDT",null)).thenReturn(Optional.of(new CoreBalanceView("USDT",Long.MAX_VALUE-10,10)));
        for(String route:List.of("balance","product-balance")) {
            var request=get("/api/v1/accounts/"+route).param("userId","42").param("asset"," usdt ");if(route.equals("product-balance"))request.param("accountType",line.accountTypeCode());
            var result=mvc.perform(request).andExpect(status().isOk()).andReturn();var body=json.readTree(result.getResponse().getContentAsString());assertThat(body.path("asset").asText()).isEqualTo("USDT");assertThat(body.path("equityUnits").asLong()).isEqualTo(Long.MAX_VALUE);
        }
        verify(views,times(2)).balance(line,42,"USDT",null);verifyNoInteractions(aeron);
    }
    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void positionRoutesBindNativeEnumsBeforeReadingDerivativeState(ProductLine line) throws Exception {
        setup(line);var shortPosition=new CorePositionView("1","USDT",CoreMarginMode.ISOLATED,CorePositionSide.SHORT,-11,100,1000,0,220);
        when(views.position(line,42,"1",CoreMarginMode.ISOLATED,CorePositionSide.SHORT,null)).thenReturn(Optional.of(shortPosition));
        var result=mvc.perform(get("/api/v1/accounts/position").param("userId","42").param("instrumentId"," 1 ").param("marginMode"," isolated ").param("positionSide"," short ")).andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());assertThat(body.path("signedQuantitySteps").asLong()).isEqualTo(-11);assertThat(body.path("marginMode").asText()).isEqualTo("ISOLATED");assertThat(body.path("positionSide").asText()).isEqualTo("SHORT");
        when(views.require(line,42,null)).thenReturn(new UserReadView("READY","v",1,new CoreUserStateView(line,42,1,CorePositionMode.HEDGE,List.of(),List.of(),List.of(shortPosition,new CorePositionView("1","USDT",CoreMarginMode.CROSS,CorePositionSide.LONG,5,100,500,0,100))),List.of(),List.of(),1,List.of()));
        var filtered=mvc.perform(get("/api/v1/accounts/positions").param("userId","42").param("positionSide","short")).andExpect(status().isOk()).andReturn();assertThat(json.readTree(filtered.getResponse().getContentAsString()).path("count").asInt()).isEqualTo(1);verifyNoInteractions(aeron);
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void adjustmentRetainsOriginalStableIdAndCanonicalPayload(ProductLine line) throws Exception {
        setup(line);when(aeron.userState(42)).thenReturn(new CoreUserStateView(line,42,1,CorePositionMode.ONE_WAY,List.of(new CoreBalanceView("USDT",900,100)),List.of(),List.of()));
        var input=new BalanceAdjustmentRequest(42," usdt ",1000," deposit-1 ","reason");
        mvc.perform(post("/api/v1/accounts/admin/balance-adjustments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isOk());
        var payload=org.mockito.ArgumentCaptor.forClass(byte[].class);UUID expected=UUID.nameUUIDFromBytes((line+":42:balance-adjust:deposit-1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(aeron).command(eq(CoreMessageType.ADJUST_BALANCE),eq(expected),eq(42L),payload.capture());assertThat(TradingCommandCodec.decodeBalanceAdjustment(payload.getValue())).isEqualTo(new BalanceAdjustmentCommand("USDT",1000));verifyNoInteractions(views);
    }
    static Stream<Arguments> invalid() {return Stream.of(Arguments.of("balance","asset","bad-code"),Arguments.of("balance","asset","x"),Arguments.of("position","instrumentId","BTCUSDT"),Arguments.of("position","marginMode","bad"),Arguments.of("position","positionSide","bad"),Arguments.of("positions","positionSide","bad"));}
    @ParameterizedTest @MethodSource("invalid")
    void malformedReadNeverAccessesState(String route,String field,String value) throws Exception {
        setup(ProductLine.LINEAR_PERPETUAL);var request=get("/api/v1/accounts/"+route).param("userId","42");if(!field.equals("asset"))request.param("asset","USDT");if(!field.equals("instrumentId"))request.param("instrumentId","1");request.param(field,value);
        mvc.perform(request).andExpect(status().isBadRequest());verifyNoInteractions(views,aeron);
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "bad-code"})
    void malformedWriteNeverOffersACommand(String asset) throws Exception {
        setup(ProductLine.LINEAR_PERPETUAL);mvc.perform(post("/api/v1/accounts/admin/balance-adjustments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new BalanceAdjustmentRequest(42,asset,1,"ref","reason")))).andExpect(status().isBadRequest());verifyNoInteractions(aeron,views);
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void mismatchedAccountProductStillRejectsBeforeReading(ProductLine line) throws Exception {
        setup(line);ProductLine other=line==ProductLine.SPOT?ProductLine.LINEAR_PERPETUAL:ProductLine.SPOT;
        mvc.perform(get("/api/v1/accounts/product-balance").param("userId","42").param("asset","usdt").param("accountType",other.accountTypeCode())).andExpect(status().isBadRequest());verifyNoInteractions(aeron,views);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void productAdjustmentKeepsNativeTypePayloadAndStableId(ProductLine line) throws Exception {
        setup(line);when(aeron.userState(42)).thenReturn(new CoreUserStateView(line,42,1,CorePositionMode.ONE_WAY,List.of(new CoreBalanceView("USDT",900,100)),List.of(),List.of()));
        mvc.perform(post("/api/v1/accounts/admin/product-balance-adjustments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ProductBalanceAdjustmentRequest(42,AccountType.valueOf(line.accountTypeCode())," usdt ",-100," product-key ","reason")))).andExpect(status().isOk());
        var payload=org.mockito.ArgumentCaptor.forClass(byte[].class);UUID expected=UUID.nameUUIDFromBytes((line+":42:product-balance-adjust:product-key").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(aeron).command(eq(CoreMessageType.ADJUST_BALANCE),eq(expected),eq(42L),payload.capture());assertThat(TradingCommandCodec.decodeBalanceAdjustment(payload.getValue())).isEqualTo(new BalanceAdjustmentCommand("USDT",-100));verifyNoInteractions(views);
    }
    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void positionCommandsKeepNativeEnumsAndStableIds(ProductLine line) throws Exception {
        setup(line);when(aeron.userState(42)).thenReturn(new CoreUserStateView(line,42,1,CorePositionMode.HEDGE,List.of(new CoreBalanceView("USDT",900,100)),List.of(),List.of(new CorePositionView("1","USDT",CoreMarginMode.ISOLATED,CorePositionSide.SHORT,-11,100,1000,0,220))));
        mvc.perform(post("/api/v1/accounts/position-mode").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new PositionModeUpdateRequest(42,line,com.surprising.trading.api.model.PositionMode.HEDGE," mode-key ")))).andExpect(status().isOk());
        var mode=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(aeron).command(eq(CoreMessageType.UPDATE_POSITION_MODE),eq(UUID.nameUUIDFromBytes((line+":42:position-mode:mode-key").getBytes(java.nio.charset.StandardCharsets.UTF_8))),eq(42L),mode.capture());assertThat(TradingCommandCodec.decodeUpdatePositionMode(mode.getValue()).positionMode()).isEqualTo(CorePositionMode.HEDGE);
        mvc.perform(post("/api/v1/accounts/position-margin-adjustments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new PositionMarginAdjustmentRequest(42," 1 ",com.surprising.trading.api.model.MarginMode.ISOLATED,com.surprising.trading.api.model.PositionSide.SHORT,50," margin-key ","reason")))).andExpect(status().isOk());
        var margin=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(aeron).command(eq(CoreMessageType.ADJUST_POSITION_MARGIN),eq(UUID.nameUUIDFromBytes((line+":42:position-margin:margin-key").getBytes(java.nio.charset.StandardCharsets.UTF_8))),eq(42L),margin.capture());var decoded=TradingCommandCodec.decodeAdjustPositionMargin(margin.getValue());assertThat(decoded.instrumentId()).isEqualTo("1");assertThat(decoded.marginMode()).isEqualTo(CoreMarginMode.ISOLATED);assertThat(decoded.positionSide()).isEqualTo(CorePositionSide.SHORT);assertThat(decoded.amountUnits()).isEqualTo(50);verifyNoInteractions(views);
    }
}
