package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class TelegramSettingsHandler {

    private final TelegramAuthHandler authHandler;
    private final TelegramBotSecurityService securityService;
    private final TelegramSessionService sessionService;
    private final TelegramBotClient botClient;

    public TelegramSettingsHandler(TelegramAuthHandler authHandler,
                                   TelegramBotSecurityService securityService,
                                   TelegramSessionService sessionService,
                                   TelegramBotClient botClient) {
        this.authHandler = authHandler;
        this.securityService = securityService;
        this.sessionService = sessionService;
        this.botClient = botClient;
    }

    public void renderSettings(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecuritySettings settings = securityService.getSettings(userId);

        String modeDesc = switch (settings.securityMode()) {
            case CONFIRM_ONLY -> "🟢 快速确认 (仅内联按钮二次确认)";
            case TRADE_PASSWORD -> "🔐 交易密码 (需要输入 6 位交易 PIN)";
            case OTP_VERIFY -> "🛡 2FA / 动态验证码 (需要输入 6 位 OTP/邮箱码)";
        };

        String pinDesc = settings.hasTradePin() ? "🟢 已设置" : "🔴 未设置 (使用前需先设置)";

        String text = "⚙️ <b>Telegram Bot 安全与确认设置</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "👤 <b>账户 UID:</b> <code>" + userId + "</code>\n" +
                "🛡 <b>当前操作确认级别:</b>\n" + modeDesc + "\n" +
                "🔑 <b>交易密码状态:</b> " + pinDesc + "\n\n" +
                "<i>您可以在下方自由切换提币与敏感交易时的二次安全防线：</i>";

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 设为: 快速确认", "settings:mode:CONFIRM_ONLY"),
                        TelegramInlineKeyboardButton.callback("🔐 设为: 交易密码", "settings:mode:TRADE_PASSWORD")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🛡 设为: 2FA验证码", "settings:mode:OTP_VERIFY")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🔑 设置/修改 6 位交易密码", "settings:begin_pin")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                );

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup.build());
        } else {
            botClient.sendMessage(chatId, text, markup.build());
        }
    }

    public void handleModeChange(long chatId, long messageId, TelegramUser tgUser, String modeStr) {
        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecurityMode mode = TelegramSecurityMode.fromString(modeStr);
        try {
            securityService.updateSecurityMode(userId, mode);
            renderSettings(chatId, messageId, tgUser);
        } catch (IllegalStateException ex) {
            botClient.answerCallbackQuery(null, ex.getMessage(), true);
            String text = "⚠️ <b>无法切换为交易密码模式</b>\n\n" +
                    ex.getMessage() + "\n请点击下方按钮先完成交易密码初始化。";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("🔑 立即设置 6 位交易密码", "settings:begin_pin"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回设置", "menu:settings"))
                    .build();
            botClient.editMessageText(chatId, messageId, text, markup);
        }
    }

    public void startPinSetupStep(long chatId, Long messageId, TelegramUser tgUser) {
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.SETTINGS_AWAITING_NEW_PIN, null);

        String text = "🔑 <b>设置 6 位交易密码</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "请在下方聊天输入框直接回复您的 <b>6 位纯数字交易密码</b>（例如: <code>886622</code>）：\n\n" +
                "<i>此密码将用于您在 Telegram Bot 执行提币与快捷开仓时的二次安全验证。</i>";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消设置", "menu:settings"))
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void handlePinInput(long chatId, TelegramUser tgUser, String pinText) {
        long userId = authHandler.resolveUserId(tgUser);
        String pin = pinText.trim();
        try {
            securityService.setTradePin(userId, pin);
            sessionService.clearState(tgUser.id());

            String text = "🎉 <b>6 位交易密码设置成功！</b>\n\n" +
                    "您的安全密码已就绪，当前已自动开启交易密码安全保护。";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("⚙️ 查看安全设置", "menu:settings"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();
            botClient.sendMessage(chatId, text, markup);
        } catch (IllegalArgumentException ex) {
            botClient.sendMessage(chatId, "❌ " + ex.getMessage() + "，请重新输入 6 位纯数字：", null);
        }
    }
}
