package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.account.api.model.AccountType;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class TelegramTransferHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramTransferHandler.class);

    private final ProductTransferCoordinator transferCoordinator;
    private final TelegramAuthHandler authHandler;
    private final TelegramSessionService sessionService;
    private final TelegramBotClient botClient;

    public TelegramTransferHandler(ProductTransferCoordinator transferCoordinator,
                                   TelegramAuthHandler authHandler,
                                   TelegramSessionService sessionService,
                                   TelegramBotClient botClient) {
        this.transferCoordinator = transferCoordinator;
        this.authHandler = authHandler;
        this.sessionService = sessionService;
        this.botClient = botClient;
    }

    public void renderDirectionSelection(long chatId, Long messageId, TelegramUser tgUser) {
        sessionService.clearState(tgUser.id());
        String text = "🔄 <b>内部资金免手续费闪电划转</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "请选择您想要进行划转的账户与方向：\n\n" +
                "• <b>现货 ➔ U本位合约</b>\n" +
                "• <b>U本位合约 ➔ 现货</b>\n" +
                "• <b>现货 ➔ 币本位合约</b>\n" +
                "• <b>币本位合约 ➔ 现货</b>\n\n" +
                "👇 请点击下方按钮选择：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("💳 现货 ➔ 📈 U本位合约", "transfer:dir:SPOT:LINEAR_PERPETUAL:USDT"),
                        TelegramInlineKeyboardButton.callback("📈 U本位合约 ➔ 💳 现货", "transfer:dir:LINEAR_PERPETUAL:SPOT:USDT")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("💳 现货 ➔ 🪙 币本位合约", "transfer:dir:SPOT:INVERSE_PERPETUAL:BTC"),
                        TelegramInlineKeyboardButton.callback("🪙 币本位合约 ➔ 💳 现货", "transfer:dir:INVERSE_PERPETUAL:SPOT:BTC")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void startTransferAmountStep(long chatId, Long messageId, TelegramUser tgUser,
                                       String fromAcc, String toAcc, String asset) {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.TRANSFER_AWAITING_AMOUNT,
                Map.of("from", fromAcc, "to", toAcc, "asset", asset),
                Instant.now().plus(Duration.ofMinutes(5))
        );
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.TRANSFER_AWAITING_AMOUNT, state);

        String fromName = accountName(fromAcc);
        String toName = accountName(toAcc);

        String text = "🔢 <b>输入划转金额</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "方向: <b>" + fromName + " ➔ " + toName + "</b>\n" +
                "划转币种: <b>" + asset + "</b>\n\n" +
                "请在下方聊天输入框直接回复您要划转的 <b>" + asset + " 数量</b>（例如: <code>100</code>）：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消划转", "menu:transfer"))
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void handleAmountInput(long chatId, TelegramUser tgUser, String amountText, TelegramSessionState state) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText.trim());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                botClient.sendMessage(chatId, "❌ 划转金额必须大于 0，请重新输入：", null);
                return;
            }
        } catch (Exception ex) {
            botClient.sendMessage(chatId, "❌ 金额格式无效，请输入有效数字（例如: 100）：", null);
            return;
        }

        long userId = authHandler.resolveUserId(tgUser);
        String from = (String) state.getData().get("from");
        String to = (String) state.getData().get("to");
        String asset = (String) state.getData().get("asset");

        AccountType fromType = AccountType.valueOf(from);
        AccountType toType = AccountType.valueOf(to);

        long amountUnits = amount.movePointRight(8).longValue();
        String idempotencyKey = "tg_tf_" + UUID.randomUUID().toString().substring(0, 16);

        ProductTransferCommand cmd = new ProductTransferCommand(
                userId, idempotencyKey, fromType, toType, asset, amountUnits,
                "TG_TRANSFER", "Telegram Bot user quick transfer"
        );

        boolean success = false;
        String errorMsg = null;
        try {
            ProductTransferResult res = transferCoordinator.transfer(cmd);
            success = res != null;
        } catch (Exception ex) {
            log.warn("Transfer failed for userId={}: {}", userId, ex.getMessage());
            errorMsg = ex.getMessage();
        }

        sessionService.clearState(tgUser.id());

        if (success) {
            String text = "🎉 <b>资金划转成功！</b>\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "转出账户: <b>" + accountName(from) + "</b>\n" +
                    "转入账户: <b>" + accountName(to) + "</b>\n" +
                    "划转金额: <code>" + amount.toPlainString() + " " + asset + "</code>\n" +
                    "手续费: <code>0 " + asset + " (免费)</code>\n" +
                    "状态: 🟢 立即到账";

            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(
                            TelegramInlineKeyboardButton.callback("💰 查看账户资产", "menu:assets"),
                            TelegramInlineKeyboardButton.callback("🔄 继续划转", "menu:transfer")
                    )
                    .addRow(
                            TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                    )
                    .build();

            botClient.sendMessage(chatId, text, markup);
        } else {
            String text = "❌ <b>资金划转未完成:</b> " + (errorMsg != null ? errorMsg : "账户余额不足或产品线不可用") + "\n\n" +
                    "请检查转出账户可用余额后重试。";

            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("🔄 重新划转", "menu:transfer"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();

            botClient.sendMessage(chatId, text, markup);
        }
    }

    private String accountName(String typeStr) {
        if ("SPOT".equalsIgnoreCase(typeStr)) return "现货账户";
        if ("LINEAR_PERPETUAL".equalsIgnoreCase(typeStr)) return "U本位合约账户";
        if ("INVERSE_PERPETUAL".equalsIgnoreCase(typeStr)) return "币本位合约账户";
        return typeStr;
    }
}
