package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.repository.TelegramBotSecurityRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

@Service
public class TelegramBotSecurityService {

    private final TelegramBotSecurityRepository repository;
    private final SensitiveActionVerificationService verificationService;

    public TelegramBotSecurityService(TelegramBotSecurityRepository repository,
                                      SensitiveActionVerificationService verificationService) {
        this.repository = repository;
        this.verificationService = verificationService;
    }

    public TelegramSecuritySettings getSettings(long userId) {
        return repository.findSettings(userId).orElseGet(() -> TelegramSecuritySettings.defaultFor(userId));
    }

    public void updateSecurityMode(long userId, TelegramSecurityMode mode) {
        if (mode == TelegramSecurityMode.TRADE_PASSWORD) {
            var settings = getSettings(userId);
            if (!settings.hasTradePin()) {
                throw new IllegalStateException("请先设置 6 位交易密码，再启用交易密码验证模式");
            }
        }
        repository.upsertMode(userId, mode, Instant.now());
    }

    public void updatePreferredLanguage(long userId, String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return;
        }
        repository.upsertLanguage(userId, languageCode.trim(), Instant.now());
    }

    public void setTradePin(long userId, String pin) {
        if (pin == null || !pin.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("交易密码必须为 6 位纯数字");
        }
        String hash = hashPin(userId, pin);
        repository.upsertTradePin(userId, hash, Instant.now());
    }

    public boolean verifyPin(long userId, String pin) {
        if (pin == null || !pin.matches("^\\d{6}$")) {
            return false;
        }
        var expectedHash = repository.findTradePinHash(userId);
        if (expectedHash.isEmpty()) {
            return false;
        }
        String calculated = hashPin(userId, pin);
        return MessageDigest.isEqual(calculated.getBytes(StandardCharsets.UTF_8),
                expectedHash.get().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验敏感操作（提币、大额开仓等）二次安全验证
     * @param userId 用户 ID
     * @param sceneCode 场景编码，如 "TG_WITHDRAWAL", "TG_TRADE"
     * @param credential 用户在 Telegram 交互中输入的凭据（如 PIN 码或 OTP 验证码）
     */
    public boolean verifyActionSecurity(long userId, String sceneCode, String credential) {
        TelegramSecuritySettings settings = getSettings(userId);
        return switch (settings.securityMode()) {
            case CONFIRM_ONLY -> true;
            case TRADE_PASSWORD -> verifyPin(userId, credential);
            case OTP_VERIFY -> {
                if (credential == null || !credential.matches("^\\d{6}$")) {
                    yield false;
                }
                // 支持 TOTP 验证码或邮箱验证码
                boolean emailOk = verificationService.verify(userId, sceneCode, credential, null, Instant.now());
                if (emailOk) {
                    yield true;
                }
                yield verificationService.verify(userId, sceneCode, null, credential, Instant.now());
            }
        };
    }

    private String hashPin(long userId, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String salt = "surprising:tg:pin:" + userId + ":";
            byte[] hash = digest.digest((salt + pin).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }
}
