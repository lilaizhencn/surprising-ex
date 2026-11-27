package com.surprising.gateway.provider.telegram.bot.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.service.ProductTransferService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.order.service.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramFinancialHandlersTest {

    private static final TelegramUser USER = new TelegramUser(1001L, false, "Alice", null, "alice", "en");

    @Test
    void depositHandlerFailsClosedWithoutFakeAddressOrQrWhenWalletFails() {
        var wallet = mock(CustodyWalletClient.class);
        var auth = mock(TelegramAuthHandler.class);
        var qr = mock(TelegramQrCodeService.class);
        var bot = mock(TelegramBotClient.class);
        when(auth.resolveUserId(USER)).thenReturn(42L);
        when(wallet.createAddress(42L, "TRON", 1L)).thenThrow(new IllegalStateException("custody down"));

        new TelegramDepositHandler(wallet, auth, qr, bot).handleGetAddress(99L, 7L, USER, "TRON");

        verify(qr, never()).generateQrCodePng(anyString(), eq(300), eq(300));
        verify(bot, never()).sendPhoto(anyLong(), any(), anyString(), anyString(), any());
        verify(bot).editMessageText(eq(99L), eq(7L), contains("充值地址暂不可用"), any());
    }

    @Test
    void withdrawHandlerReportsFailureInsteadOfFakeSuccessAndEnforcesCircuitBreaker() {
        var withdrawal = mock(CustodyWithdrawalService.class);
        var auth = mock(TelegramAuthHandler.class);
        var sessions = mock(TelegramSessionService.class);
        var security = mock(TelegramBotSecurityService.class);
        var bot = mock(TelegramBotClient.class);
        var breaker = mock(EmergencyWithdrawalCircuitBreaker.class);
        when(auth.resolveUserId(USER)).thenReturn(42L);
        when(breaker.isWithdrawalAllowed()).thenReturn(true);
        when(withdrawal.submit(eq(42L), anyString(), any())).thenThrow(new IllegalStateException("insufficient balance"));

        var state = new TelegramSessionState(
                TelegramSessionState.Step.IDLE,
                Map.of("asset", "USDT", "chain", "TRON", "toAddress", "T1234567890", "amount", "50"),
                Instant.now().plusSeconds(60));
        var handler = new TelegramWithdrawHandler(withdrawal, auth, sessions, security, bot, breaker);
        handler.executeWithdrawal(99L, 7L, USER, state);

        verify(bot).editMessageText(eq(99L), eq(7L), contains("提币申请提交失败"), any());

        when(breaker.isWithdrawalAllowed()).thenReturn(false);
        when(breaker.getStopReason()).thenReturn("RECON_MISMATCH");
        handler.executeWithdrawal(99L, 8L, USER, state);
        verify(bot).editMessageText(eq(99L), eq(8L), contains("风控熔断拦截"), any());
    }

    @Test
    void tradeHandlerReportsRejectedOrderInsteadOfFakeSuccess() {
        var products = mock(GatewayProductServices.class);
        var auth = mock(TelegramAuthHandler.class);
        var sessions = mock(TelegramSessionService.class);
        var security = mock(TelegramBotSecurityService.class);
        var bot = mock(TelegramBotClient.class);
        var orderService = mock(OrderService.class);
        when(auth.resolveUserId(USER)).thenReturn(42L);
        when(products.enabled()).thenReturn(List.of(ProductLine.LINEAR_PERPETUAL));
        when(products.service(ProductLine.LINEAR_PERPETUAL, OrderService.class)).thenReturn(orderService);
        var rejected = mock(OrderResponse.class);
        when(rejected.status()).thenReturn(OrderStatus.REJECTED);
        when(rejected.rejectReason()).thenReturn("INSUFFICIENT_AVAILABLE_BALANCE");
        when(orderService.place(any())).thenReturn(rejected);

        var state = new TelegramSessionState(
                TelegramSessionState.Step.IDLE,
                Map.of("symbol", "BTC-USDT", "side", "BUY", "quantity", "0.5"),
                Instant.now().plusSeconds(60));
        new TelegramTradeHandler(products, auth, sessions, security, bot, new TelegramBotProperties())
                .executeTrade(99L, 7L, USER, state);

        verify(bot).editMessageText(eq(99L), eq(7L), contains("INSUFFICIENT_AVAILABLE_BALANCE"), any());
    }

    @Test
    void transferHandlerMapsProductLineNamesToAccountTypes() {
        var transfer = mock(ProductTransferService.class);
        var auth = mock(TelegramAuthHandler.class);
        var sessions = mock(TelegramSessionService.class);
        var bot = mock(TelegramBotClient.class);
        when(auth.resolveUserId(USER)).thenReturn(42L);
        when(transfer.transfer(any())).thenReturn(mock(ProductTransferResult.class));

        var state = new TelegramSessionState(
                TelegramSessionState.Step.TRANSFER_AWAITING_AMOUNT,
                Map.of("from", "SPOT", "to", "LINEAR_PERPETUAL", "asset", "USDT"),
                Instant.now().plusSeconds(60));
        new TelegramTransferHandler(transfer, auth, sessions, bot).handleAmountInput(99L, USER, "25", state);

        var captor = ArgumentCaptor.forClass(ProductTransferCommand.class);
        verify(transfer).transfer(captor.capture());
        assertThat(captor.getValue().sourceAccountType()).isEqualTo(AccountType.SPOT);
        assertThat(captor.getValue().targetAccountType()).isEqualTo(AccountType.USDT_PERPETUAL);
        assertThat(TelegramTransferHandler.parseAccountType("INVERSE_PERPETUAL")).isEqualTo(AccountType.COIN_PERPETUAL);
    }
}
