package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountServiceLocalSnapshotTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void everyProductReadsItsOwnAssetBalanceWithoutTradingCommands(ProductLine line) {
        var queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var commands = mock(AccountCommandGateway.class);
        var projection = mock(AccountQueryService.class);
        var properties = properties();
        properties.getKafka().setProductLine(line);
        var service = new AccountService(properties, commands, queries, projection);
        when(queries.balance(line, 1001, "USDT", null))
                .thenReturn(java.util.Optional.of(new CoreBalanceView("USDT", 800, 200)));
        assertThat(service.balance(1001, "usdt").availableUnits()).isEqualTo(800);
        assertThat(service.productBalance(1001, AccountType.valueOf(line.accountTypeCode()), "usdt").lockedUnits())
                .isEqualTo(200);
        verifyNoInteractions(commands, projection);
    }

    @Test
    void enabledReadViewNeverCallsCoreEvenWhenValkeyIsUnavailable() {
        var projection=mock(AccountQueryService.class);
        var queries=mock(com.surprising.realtime.api.ValkeyUserQueries.class);var service=service(queries,projection);
        when(queries.require(ProductLine.LINEAR_PERPETUAL,1001L,null)).thenReturn(
            new com.surprising.realtime.api.UserReadView("READY","v",1,snapshot(),List.of(),List.of(),1,List.of()));
        when(queries.balance(ProductLine.LINEAR_PERPETUAL,1001L,"USDT",null))
                .thenReturn(java.util.Optional.of(snapshot().balances().getFirst()));
        assertThat(service.balance(1001L,"USDT").availableUnits()).isEqualTo(800);
        assertThat(service.positions(1001L).count()).isEqualTo(1);
        when(queries.require(ProductLine.LINEAR_PERPETUAL,1001L,null)).thenThrow(
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        when(queries.balance(ProductLine.LINEAR_PERPETUAL,1001L,"USDT",null)).thenThrow(
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->service.balance(1001L,"USDT")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(projection);
    }

    @Test
    void individualPositionMarginAndProductBalanceUseOnlyTargetReadViewQueries() {
        var projection = mock(AccountQueryService.class);
        var queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var service = service(queries, projection);
        when(queries.position(ProductLine.LINEAR_PERPETUAL, 1001, "1", CoreMarginMode.CROSS, CorePositionSide.NET, null))
                .thenReturn(java.util.Optional.of(snapshot().positions().getFirst()));
        when(queries.balance(ProductLine.LINEAR_PERPETUAL, 1001, "USDT", null))
                .thenReturn(java.util.Optional.of(snapshot().balances().getFirst()));
        assertThat(service.position(1001, "1").signedQuantitySteps()).isEqualTo(10);
        assertThat(service.positionMargin(1001, "1", "CROSS").marginUnits()).isEqualTo(200);
        assertThat(service.productBalance(1001, AccountType.USDT_PERPETUAL, "usdt").availableUnits()).isEqualTo(800);
        when(queries.position(ProductLine.LINEAR_PERPETUAL, 1001, "1", CoreMarginMode.CROSS, CorePositionSide.NET, null))
                .thenReturn(java.util.Optional.empty());
        assertThat(service.position(1001, "1").signedQuantitySteps()).isZero();
        org.mockito.Mockito.verify(queries, org.mockito.Mockito.never()).require(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(projection);
    }

    @Test
    void balanceAndPositionsReadOnlyRealtimeState() {
        AccountQueryService projection = mock(AccountQueryService.class);
        com.surprising.realtime.api.ValkeyUserQueries queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        when(queries.require(ProductLine.LINEAR_PERPETUAL,1001L,null)).thenReturn(
                new com.surprising.realtime.api.UserReadView("READY","v",1,snapshot(),List.of(),List.of(),1,List.of()));
        when(queries.balance(ProductLine.LINEAR_PERPETUAL,1001L,"USDT",null))
                .thenReturn(java.util.Optional.of(snapshot().balances().getFirst()));
        when(queries.position(ProductLine.LINEAR_PERPETUAL,1001L,"1",CoreMarginMode.CROSS,CorePositionSide.NET,null))
                .thenReturn(java.util.Optional.of(snapshot().positions().getFirst()));
        AccountService service = service(queries, projection);

        assertThat(service.balance(1001L, "usdt").availableUnits()).isEqualTo(800L);
        assertThat(service.position(1001L, "1").signedQuantitySteps()).isEqualTo(10L);
        assertThat(service.positions(1001L).count()).isEqualTo(1);
        assertThat(service.positionMode(1001L).positionMode()).isEqualTo(PositionMode.ONE_WAY);
        verifyNoInteractions(projection);
    }

    @Test
    void unavailableReadViewFailsClosedInsteadOfQueryingProjectionDatabase() {
        AccountQueryService projection = mock(AccountQueryService.class);
        com.surprising.realtime.api.ValkeyUserQueries queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        AccountService service = service(queries, projection);

        when(queries.balance(ProductLine.LINEAR_PERPETUAL,1001L,"USDT",null)).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"READ_VIEW_UNAVAILABLE"));
        assertThatThrownBy(() -> service.balance(1001L, "USDT"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("READ_VIEW_UNAVAILABLE");
        verifyNoInteractions(projection);
    }

    @Test
    void unsupportedProductLineAndTransferDoNotFallBackToDatabase() {
        AccountQueryService projection = mock(AccountQueryService.class);
        AccountCommandGateway gateway = mock(AccountCommandGateway.class);
        com.surprising.realtime.api.ValkeyUserQueries queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        AccountService service = new AccountService(properties(), gateway, queries, projection);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_DELIVERY, 1001L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(new com.surprising.account.api.model.ProductTransferRequest(
                1001L, AccountType.USDT_PERPETUAL, AccountType.FUNDING, "USDT", 1L, "ref", "test")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(projection);
    }

    private static AccountService service(com.surprising.realtime.api.ValkeyUserQueries queries, AccountQueryService projection) {
        return new AccountService(properties(), mock(AccountCommandGateway.class), queries, projection);
    }

    private static AccountProperties properties() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    private static CoreUserStateView snapshot() {
        return new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 1001L, 1L, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 800L, 200L)), List.of(),
                List.of(new CorePositionView("1", "USDT", CoreMarginMode.CROSS,
                        CorePositionSide.NET, 10L, 100L, 1000L, 0L, 200L)));
    }
}
