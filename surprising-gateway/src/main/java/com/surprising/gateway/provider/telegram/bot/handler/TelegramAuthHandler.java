package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramReplyKeyboardMarkup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TelegramAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthHandler.class);

    private final TelegramIdentityRepository identities;
    private final AuthPersistenceService authPersistence;
    private final GatewayUserRepository users;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;

    public TelegramAuthHandler(TelegramIdentityRepository identities,
                               AuthPersistenceService authPersistence,
                               GatewayUserRepository users,
                               TelegramBotClient botClient,
                               TelegramBotProperties properties) {
        this.identities = identities;
        this.authPersistence = authPersistence;
        this.users = users;
        this.botClient = botClient;
        this.properties = properties;
    }

    public long resolveUserId(TelegramUser tgUser) {
        String subject = String.valueOf(tgUser.id());
        Instant now = Instant.now();
        return identities.lockIdentity(subject).orElseGet(() -> {
            log.info("New Telegram user auto-registration: id={}, name={}", tgUser.id(), tgUser.getDisplayName());
            var user = authPersistence.createUser(null, null, null, now);
            authPersistence.ensureDefaultRole(user.userId(), now);
            identities.bind(subject, user.userId(), now);
            users.updateNickname(user.userId(), tgUser.getDisplayName(), now);
            return user.userId();
        });
    }

    public void sendWelcomeAndMainMenu(long chatId, TelegramUser tgUser) {
        long userId = resolveUserId(tgUser);

        String text = "🚀 <b>欢迎使用 Surprising 交易平台 Telegram 官方 Bot</b>\n\n" +
                "👤 <b>当前用户:</b> " + escapeHtml(tgUser.getDisplayName()) + "\n" +
                "🆔 <b>账户 UID:</b> <code>" + userId + "</code>\n" +
                "🔗 <b>Telegram ID:</b> <code>" + tgUser.id() + "</code>\n\n" +
                "您可以通过下方菜单快速进行<b>充提币、资金划转、开平仓、查看持仓、资产汇总与行情数据</b>。\n" +
                "也可以直接点击<b>【打开 Mini App】</b>体验全功能 Web 交易终端！";

        // 发送带常驻 Reply 键盘的消息
        TelegramReplyKeyboardMarkup replyMarkup = TelegramReplyKeyboardMarkup.builder()
                .addRow(TelegramKeyboardButton.text("💰 账户资产"), TelegramKeyboardButton.text("📊 实时行情"))
                .addRow(TelegramKeyboardButton.text("📈 合约持仓"), TelegramKeyboardButton.text("📋 当前挂单"), TelegramKeyboardButton.text("⚡ 快捷交易"))
                .addRow(TelegramKeyboardButton.text("💳 充值"), TelegramKeyboardButton.text("💸 提币"), TelegramKeyboardButton.text("🔄 划转"))
                .addRow(TelegramKeyboardButton.text("🤝 邀请返佣"), TelegramKeyboardButton.webApp("🚀 打开 Mini App 交易终端", properties.getWebappUrl()))
                .build();

        // 附带 Inline 快捷键盘
        TelegramInlineKeyboardMarkup inlineMarkup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("💰 资产看板", "menu:assets"),
                        TelegramInlineKeyboardButton.callback("📈 持仓管理", "menu:positions")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 市场行情", "menu:market"),
                        TelegramInlineKeyboardButton.callback("📋 活跃挂单", "menu:orders"),
                        TelegramInlineKeyboardButton.callback("⚡ 快捷交易", "menu:trade")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("💳 充值", "menu:deposit"),
                        TelegramInlineKeyboardButton.callback("💸 提币", "menu:withdraw"),
                        TelegramInlineKeyboardButton.callback("🔄 划转", "menu:transfer")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🤝 邀请返佣 (KOL)", "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback("⚙️ 安全设置", "menu:settings")
                )
                .addRow(
                        TelegramInlineKeyboardButton.webApp("📱 打开 Mini App 终端", properties.getWebappUrl())
                )
                .build();

        botClient.sendMessage(chatId, text, inlineMarkup);
        botClient.sendMessage(chatId, "👇 您也可以使用底部快捷菜单键盘快速导航：", replyMarkup);
    }

    public void renderMainMenu(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = resolveUserId(tgUser);
        String text = "🏛 <b>Surprising 交易主控制台</b>\n\n" +
                "👤 <b>账户:</b> " + escapeHtml(tgUser.getDisplayName()) + " (UID: <code>" + userId + "</code>)\n" +
                "⚡ 系统运行状态: 🟢 正常\n\n" +
                "请选择您要执行的操作：";

        TelegramInlineKeyboardMarkup inlineMarkup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("💰 资产看板", "menu:assets"),
                        TelegramInlineKeyboardButton.callback("📈 合约持仓", "menu:positions")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 市场行情", "menu:market"),
                        TelegramInlineKeyboardButton.callback("📋 活跃挂单", "menu:orders"),
                        TelegramInlineKeyboardButton.callback("⚡ 快捷交易", "menu:trade")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("💳 充值", "menu:deposit"),
                        TelegramInlineKeyboardButton.callback("💸 提币", "menu:withdraw"),
                        TelegramInlineKeyboardButton.callback("🔄 划转", "menu:transfer")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🤝 邀请返佣 (KOL)", "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback("⚙️ 安全设置", "menu:settings")
                )
                .addRow(
                        TelegramInlineKeyboardButton.webApp("📱 打开 Mini App 终端", properties.getWebappUrl())
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, inlineMarkup);
        } else {
            botClient.sendMessage(chatId, text, inlineMarkup);
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
