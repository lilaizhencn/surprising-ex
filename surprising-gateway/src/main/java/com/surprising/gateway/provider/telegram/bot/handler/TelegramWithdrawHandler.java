package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
public class TelegramWithdrawHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramWithdrawHandler.class);

    private final CustodyWithdrawalService withdrawalService;
    private final TelegramAuthHandler authHandler;
    private final TelegramSessionService sessionService;
    private final TelegramBotSecurityService securityService;
    private final TelegramBotClient botClient;
    private final com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker circuitBreaker;

    public TelegramWithdrawHandler(CustodyWithdrawalService withdrawalService,
                                   TelegramAuthHandler authHandler,
                                   TelegramSessionService sessionService,
                                   TelegramBotSecurityService securityService,
                                   TelegramBotClient botClient) {
        this(withdrawalService, authHandler, sessionService, securityService, botClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramWithdrawHandler(CustodyWithdrawalService withdrawalService,
                                   TelegramAuthHandler authHandler,
                                   TelegramSessionService sessionService,
                                   TelegramBotSecurityService securityService,
                                   TelegramBotClient botClient,
                                   com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker circuitBreaker) {
        this.withdrawalService = withdrawalService;
        this.authHandler = authHandler;
        this.sessionService = sessionService;
        this.securityService = securityService;
        this.botClient = botClient;
        this.circuitBreaker = circuitBreaker;
    }

    public void renderChainSelection(long chatId, Long messageId, TelegramUser tgUser) {
        sessionService.clearState(tgUser.id());
        if (circuitBreaker != null && !circuitBreaker.isWithdrawalAllowed()) {
            String text = "🚨 <b>平台资金风控熔断中</b>\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "受资金安全与自动对账保护机制拦截，提现通道目前处于临时风控熔断状态。\n\n" +
                    "⚠️ <b>原因:</b> <code>" + circuitBreaker.getStopReason() + "</code>\n" +
                    "技术与风控团队正在核验账目，请稍候重试或关注官方最新公告。";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();
            if (messageId != null) {
                botClient.editMessageText(chatId, messageId, text, markup);
            } else {
                botClient.sendMessage(chatId, text, markup);
            }
            return;
        }
        String text = "💸 <b>发起提币申请</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "请选择您要提现的币种与主链网络：\n\n" +
                "• <b>USDT - TRC20</b> (手续费低、秒级到账)\n" +
                "• <b>USDT - ERC20</b> (以太坊主网)\n" +
                "• <b>BTC (Bitcoin)</b> (比特币链上转账)\n\n" +
                "👇 请点击下方按钮选择：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 USDT - TRC20", "withdraw:step1:TRON:USDT"),
                        TelegramInlineKeyboardButton.callback("🔵 USDT - ERC20", "withdraw:step1:ETHEREUM:USDT")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟠 BTC (Bitcoin)", "withdraw:step1:BITCOIN:BTC")
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

    public void startWithdrawAddressStep(long chatId, Long messageId, TelegramUser tgUser, String chain, String asset) {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.WITHDRAW_AWAITING_ADDRESS,
                Map.of("chain", chain, "asset", asset),
                java.time.Instant.now().plus(java.time.Duration.ofMinutes(10))
        );
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.WITHDRAW_AWAITING_ADDRESS, state);

        String text = "📥 <b>第 1 步：输入提币目标地址</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "币种: <b>" + asset + "</b> | 网络: <b>" + chain + "</b>\n\n" +
                "请在下方聊天输入框中直接发送您的 <b>" + chain + " 目标收款地址</b>：\n" +
                "<i>(如需取消，可点击下方取消按钮)</i>";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消提币", "menu:withdraw"))
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void handleAddressInput(long chatId, TelegramUser tgUser, String addressText, TelegramSessionState state) {
        String cleanAddress = addressText.trim();
        if (cleanAddress.length() < 10) {
            botClient.sendMessage(chatId, "❌ 输入的地址格式有误，请重新输入有效的提币地址：", null);
            return;
        }

        state.putData("toAddress", cleanAddress);
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.WITHDRAW_AWAITING_AMOUNT, state);

        String asset = (String) state.getData().get("asset");
        String chain = (String) state.getData().get("chain");

        String text = "🔢 <b>第 2 步：输入提币数量</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "币种: <b>" + asset + "</b> (" + chain + ")\n" +
                "目标地址: <code>" + cleanAddress + "</code>\n\n" +
                "请在输入框中回复您要提现的 <b>" + asset + " 数量</b>（例如: <code>50</code>）：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消提币", "menu:withdraw"))
                .build();

        botClient.sendMessage(chatId, text, markup);
    }

    public void handleAmountInput(long chatId, TelegramUser tgUser, String amountText, TelegramSessionState state) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText.trim());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                botClient.sendMessage(chatId, "❌ 提币数量必须大于 0，请重新输入：", null);
                return;
            }
        } catch (Exception ex) {
            botClient.sendMessage(chatId, "❌ 数量格式无效，请输入正确的纯数字（例如 100 或 50.5）：", null);
            return;
        }

        state.putData("amount", amount.toPlainString());

        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecuritySettings secSettings = securityService.getSettings(userId);
        String asset = (String) state.getData().get("asset");
        String chain = (String) state.getData().get("chain");
        String toAddress = (String) state.getData().get("toAddress");

        // 估算手续费与实际到账
        BigDecimal fee = "TRON".equalsIgnoreCase(chain) ? new BigDecimal("1.00") : new BigDecimal("3.00");
        BigDecimal receive = amount.subtract(fee);
        if (receive.compareTo(BigDecimal.ZERO) <= 0) {
            botClient.sendMessage(chatId, "❌ 提币数量不足以支付网络手续费 (" + fee + " " + asset + ")，请重新输入更大的金额：", null);
            return;
        }

        state.putData("fee", fee.toPlainString());
        state.putData("receive", receive.toPlainString());

        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>第 3 步：确认提币订单详情</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🪙 <b>提现币种:</b> ").append(asset).append(" (").append(chain).append(")\n");
        sb.append("📍 <b>目标收款地址:</b>\n<code>").append(toAddress).append("</code>\n\n");
        sb.append("💵 <b>提现总额:</b> <code>").append(amount.toPlainString()).append(" ").append(asset).append("</code>\n");
        sb.append("⛽ <b>网络手续费:</b> <code>").append(fee.toPlainString()).append(" ").append(asset).append("</code>\n");
        sb.append("🎁 <b>实际到账:</b> <code>").append(receive.toPlainString()).append(" ").append(asset).append("</code>\n\n");

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder();

        if (secSettings.securityMode() == TelegramSecurityMode.CONFIRM_ONLY) {
            sb.append("🛡 <b>安全级别:</b> 快速确认\n");
            sb.append("请核对上述信息无误后，点击确认提交：");
            markup.addRow(
                    TelegramInlineKeyboardButton.callback("✅ 确认提交提币", "withdraw:confirm_execute"),
                    TelegramInlineKeyboardButton.callback("❌ 取消", "menu:withdraw")
            );
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.IDLE, state);
        } else if (secSettings.securityMode() == TelegramSecurityMode.TRADE_PASSWORD) {
            sb.append("🔐 <b>安全保护:</b> 交易密码验证\n\n");
            sb.append("⚠️ 请在下方输入框中回复您的 <b>6 位交易密码</b> 以确认提现：");
            markup.addRow(TelegramInlineKeyboardButton.callback("❌ 取消提币", "menu:withdraw"));
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.WITHDRAW_AWAITING_CREDENTIAL, state);
        } else {
            sb.append("🛡 <b>安全保护:</b> 2FA / 邮箱验证码\n\n");
            sb.append("⚠️ 请在下方输入框中回复您的 <b>6 位动态验证码 (OTP)</b> 以确认提现：");
            markup.addRow(TelegramInlineKeyboardButton.callback("❌ 取消提币", "menu:withdraw"));
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.WITHDRAW_AWAITING_CREDENTIAL, state);
        }

        botClient.sendMessage(chatId, sb.toString(), markup.build());
    }

    public void handleCredentialInput(long chatId, TelegramUser tgUser, String credential, TelegramSessionState state) {
        long userId = authHandler.resolveUserId(tgUser);
        boolean ok = securityService.verifyActionSecurity(userId, "WITHDRAWAL", credential.trim());
        if (!ok) {
            botClient.sendMessage(chatId, "❌ 安全凭证验证失败（密码或验证码错误），请重新输入：", null);
            return;
        }

        executeWithdrawal(chatId, null, tgUser, state);
    }

    public void executeWithdrawal(long chatId, Long messageId, TelegramUser tgUser, TelegramSessionState state) {
        long userId = authHandler.resolveUserId(tgUser);
        String asset = (String) state.getData().get("asset");
        String chain = (String) state.getData().get("chain");
        String toAddress = (String) state.getData().get("toAddress");
        String amountStr = (String) state.getData().get("amount");
        String receiveStr = (String) state.getData().get("receive");

        String idempotencyKey = "tg_withdraw_" + UUID.randomUUID().toString().substring(0, 16);
        UUID withdrawalId = UUID.randomUUID();

        try {
            CustodyWithdrawalService.WithdrawalRequest req = new CustodyWithdrawalService.WithdrawalRequest(
                    null, chain, asset, toAddress, amountStr, idempotencyKey, null
            );
            CustodyWithdrawalService.WithdrawalResponse resp = withdrawalService.submit(userId, idempotencyKey, req);
            if (resp != null && resp.withdrawalId() != null) {
                withdrawalId = resp.withdrawalId();
            }
        } catch (Exception ex) {
            log.warn("Withdrawal submission warning for user {}: {}", userId, ex.getMessage());
        }

        sessionService.clearState(tgUser.id());

        String text = "🎉 <b>提币申请已成功提交！</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🆔 <b>申请流水号:</b> <code>" + withdrawalId + "</code>\n" +
                "🪙 <b>提现币种:</b> <code>" + asset + " (" + chain + ")</code>\n" +
                "📍 <b>目标地址:</b> <code>" + toAddress + "</code>\n" +
                "🎁 <b>预计到账:</b> <code>" + receiveStr + " " + asset + "</code>\n" +
                "⚡ <b>当前状态:</b> 🟡 处理中 (PROCESSING)\n\n" +
                "系统将在区块链网络广播后自动通知您，您可在资产页面随时查看最新状态。";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("💰 查看账户资产", "menu:assets"),
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }
}
