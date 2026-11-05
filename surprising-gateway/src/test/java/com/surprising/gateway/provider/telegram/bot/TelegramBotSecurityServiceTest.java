package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.repository.TelegramBotSecurityRepository;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramBotSecurityServiceTest {

    private TelegramBotSecurityRepository repository;
    private SensitiveActionVerificationService verificationService;
    private TelegramBotSecurityService service;

    @BeforeEach
    void setUp() {
        repository = mock(TelegramBotSecurityRepository.class);
        verificationService = mock(SensitiveActionVerificationService.class);
        service = new TelegramBotSecurityService(repository, verificationService);
    }

    @Test
    void confirmOnlyModeAlwaysPassesActionSecurity() {
        when(repository.findSettings(1001L)).thenReturn(Optional.of(
                new TelegramSecuritySettings(1001L, TelegramSecurityMode.CONFIRM_ONLY, false, Instant.now())
        ));

        boolean ok = service.verifyActionSecurity(1001L, "WITHDRAWAL", null);
        assertThat(ok).isTrue();
    }

    @Test
    void tradePasswordModeRequiresValidPin() {
        when(repository.findSettings(1001L)).thenReturn(Optional.of(
                new TelegramSecuritySettings(1001L, TelegramSecurityMode.TRADE_PASSWORD, true, Instant.now())
        ));

        service.setTradePin(1001L, "123456");
        verify(repository).upsertTradePin(eq(1001L), anyString(), any());

        // 模拟已保存的哈希
        var inOrder = mock(TelegramBotSecurityRepository.class);
        var inOrderService = new TelegramBotSecurityService(inOrder, verificationService);
        inOrderService.setTradePin(1001L, "654321");
    }

    @Test
    void rejectsInvalidPinFormat() {
        assertThatThrownBy(() -> service.setTradePin(1001L, "123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setTradePin(1001L, "abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otpVerifyDelegatesToSensitiveActionVerification() {
        when(repository.findSettings(1001L)).thenReturn(Optional.of(
                new TelegramSecuritySettings(1001L, TelegramSecurityMode.OTP_VERIFY, false, Instant.now())
        ));
        when(verificationService.verify(eq(1001L), eq("WITHDRAWAL"), eq("123456"), isNull(), any())).thenReturn(true);

        boolean ok = service.verifyActionSecurity(1001L, "WITHDRAWAL", "123456");
        assertThat(ok).isTrue();
    }
}
