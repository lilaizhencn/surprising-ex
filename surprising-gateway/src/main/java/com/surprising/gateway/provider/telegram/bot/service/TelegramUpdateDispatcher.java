package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.handler.*;
import com.surprising.gateway.provider.telegram.bot.model.TelegramCallbackQuery;
import com.surprising.gateway.provider.telegram.bot.model.TelegramMessage;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUpdate;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TelegramUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateDispatcher.class);

    private final TelegramBotClient botClient;
    private final TelegramSessionService sessionService;
    private final TelegramAuthHandler authHandler;
    private final TelegramAssetHandler assetHandler;
    private final TelegramMarketHandler marketHandler;
    private final TelegramPositionHandler positionHandler;
    private final TelegramOrderHandler orderHandler;
    private final TelegramTradeHandler tradeHandler;
    private final TelegramDepositHandler depositHandler;
    private final TelegramWithdrawHandler withdrawHandler;
    private final TelegramTransferHandler transferHandler;
    private final TelegramSettingsHandler settingsHandler;
    private final TelegramAffiliateHandler affiliateHandler;

    public TelegramUpdateDispatcher(TelegramBotClient botClient,
                                    TelegramSessionService sessionService,
                                    TelegramAuthHandler authHandler,
                                    TelegramAssetHandler assetHandler,
                                    TelegramMarketHandler marketHandler,
                                    TelegramPositionHandler positionHandler,
                                    TelegramOrderHandler orderHandler,
                                    TelegramTradeHandler tradeHandler,
                                    TelegramDepositHandler depositHandler,
                                    TelegramWithdrawHandler withdrawHandler,
                                    TelegramTransferHandler transferHandler,
                                    TelegramSettingsHandler settingsHandler,
                                    TelegramAffiliateHandler affiliateHandler) {
        this.botClient = botClient;
        this.sessionService = sessionService;
        this.authHandler = authHandler;
        this.assetHandler = assetHandler;
        this.marketHandler = marketHandler;
        this.positionHandler = positionHandler;
        this.orderHandler = orderHandler;
        this.tradeHandler = tradeHandler;
        this.depositHandler = depositHandler;
        this.withdrawHandler = withdrawHandler;
        this.transferHandler = transferHandler;
        this.settingsHandler = settingsHandler;
        this.affiliateHandler = affiliateHandler;
    }

    public void dispatch(TelegramUpdate update) {
        if (update == null) return;

        try {
            if (update.callbackQuery() != null) {
                handleCallbackQuery(update.callbackQuery());
            } else if (update.message() != null) {
                handleMessage(update.message());
            }
        } catch (Exception ex) {
            log.error("Error dispatching Telegram update id={}: {}", update.updateId(), ex.getMessage(), ex);
            Long chatId = update.getChatId();
            if (chatId != null) {
                botClient.sendMessage(chatId, "⚠️ 处理请求时发生异常，请稍后重试或联系客服。", null);
            }
        }
    }

    private void handleCallbackQuery(TelegramCallbackQuery cq) {
        String data = cq.data();
        if (data == null) return;
        botClient.answerCallbackQuery(cq.id(), null, false);

        long chatId = cq.message().chat().id();
        long messageId = cq.message().messageId();
        TelegramUser tgUser = cq.from();

        log.info("Telegram callback data: {} from userId={}", data, tgUser.id());

        if ("menu:main".equals(data)) {
            authHandler.renderMainMenu(chatId, messageId, tgUser);
        } else if ("menu:assets".equals(data)) {
            assetHandler.renderAssets(chatId, messageId, tgUser);
        } else if ("menu:market".equals(data)) {
            marketHandler.renderMarketOverview(chatId, messageId, "BTC-USDT");
        } else if ("menu:positions".equals(data)) {
            positionHandler.renderPositions(chatId, messageId, tgUser);
        } else if ("menu:orders".equals(data)) {
            orderHandler.renderOpenOrders(chatId, messageId, tgUser);
        } else if ("menu:trade".equals(data)) {
            tradeHandler.renderTradeCenter(chatId, messageId, tgUser);
        } else if ("menu:deposit".equals(data)) {
            depositHandler.renderChainSelection(chatId, messageId, tgUser);
        } else if ("menu:withdraw".equals(data)) {
            withdrawHandler.renderChainSelection(chatId, messageId, tgUser);
        } else if ("menu:transfer".equals(data)) {
            transferHandler.renderDirectionSelection(chatId, messageId, tgUser);
        } else if ("kol:dashboard".equals(data)) {
            affiliateHandler.renderKolDashboard(chatId, messageId, tgUser);
        } else if ("kol:poster".equals(data)) {
            affiliateHandler.renderReferralPoster(chatId, tgUser);
        } else if ("kol:members".equals(data)) {
            affiliateHandler.renderTeamMembers(chatId, messageId, tgUser);
        } else if ("kol:hierarchy".equals(data)) {
            affiliateHandler.renderHierarchy(chatId, messageId, tgUser);
        } else if ("kol:create_code".equals(data)) {
            affiliateHandler.renderCreateCustomInvitation(chatId, messageId, tgUser);
        } else if (data.startsWith("kol:do_create_code:")) {
            String[] parts = data.split(":");
            affiliateHandler.executeCreateCustomInvitation(chatId, messageId, tgUser, Integer.parseInt(parts[2]));
        } else if (data.startsWith("kol:member:")) {
            String[] parts = data.split(":");
            affiliateHandler.renderMemberDetail(chatId, messageId, tgUser, Long.parseLong(parts[2]));
        } else if (data.startsWith("kol:do_share:")) {
            String[] parts = data.split(":");
            affiliateHandler.executeSetShare(chatId, messageId, tgUser, Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        } else if (data.startsWith("kol:custom_share:")) {
            String[] parts = data.split(":");
            affiliateHandler.startCustomShareInput(chatId, messageId, tgUser, Long.parseLong(parts[2]));
        } else if ("menu:settings".equals(data)) {
            settingsHandler.renderSettings(chatId, messageId, tgUser);
        } else if (data.startsWith("orders:cancel:")) {
            String[] parts = data.split(":");
            orderHandler.executeCancelOrder(chatId, messageId, tgUser, Long.parseLong(parts[2]), parts[3]);
        } else if ("orders:cancel_all".equals(data)) {
            orderHandler.executeCancelAll(chatId, messageId, tgUser);
        } else if (data.startsWith("market:depth:")) {
            String symbol = data.substring("market:depth:".length());
            marketHandler.renderOrderBookDepth(chatId, messageId, symbol);
        } else if (data.startsWith("market:view:")) {
            String symbol = data.substring("market:view:".length());
            marketHandler.renderMarketOverview(chatId, messageId, symbol);
        } else if (data.startsWith("trade:symbol:")) {
            String symbol = data.substring("trade:symbol:".length());
            tradeHandler.startFastTradeQtyStep(chatId, messageId, tgUser, symbol, "BUY");
        } else if (data.startsWith("trade:fast:")) {
            String[] parts = data.split(":");
            tradeHandler.startFastTradeQtyStep(chatId, messageId, tgUser, parts[2], parts[3]);
        } else if ("trade:confirm_execute".equals(data)) {
            sessionService.getState(tgUser.id()).ifPresent(state -> tradeHandler.executeTrade(chatId, messageId, tgUser, state));
        } else if (data.startsWith("pos:confirm_close:")) {
            String[] parts = data.split(":");
            String pct = parts.length > 4 ? parts[4] : "100";
            positionHandler.renderConfirmClose(chatId, messageId, tgUser, parts[2], parts[3], pct);
        } else if (data.startsWith("pos:do_close:")) {
            String[] parts = data.split(":");
            String pct = parts.length > 4 ? parts[4] : "100";
            positionHandler.executeClosePosition(chatId, messageId, tgUser, parts[2], parts[3], pct);
        } else if (data.startsWith("deposit:get:")) {
            String chain = data.substring("deposit:get:".length());
            depositHandler.handleGetAddress(chatId, messageId, tgUser, chain);
        } else if (data.startsWith("withdraw:step1:")) {
            String[] parts = data.split(":");
            withdrawHandler.startWithdrawAddressStep(chatId, messageId, tgUser, parts[2], parts[3]);
        } else if ("withdraw:confirm_execute".equals(data)) {
            sessionService.getState(tgUser.id()).ifPresent(state -> withdrawHandler.executeWithdrawal(chatId, messageId, tgUser, state));
        } else if (data.startsWith("transfer:dir:")) {
            String[] parts = data.split(":");
            transferHandler.startTransferAmountStep(chatId, messageId, tgUser, parts[2], parts[3], parts[4]);
        } else if (data.startsWith("settings:mode:")) {
            String mode = data.substring("settings:mode:".length());
            settingsHandler.handleModeChange(chatId, messageId, tgUser, mode);
        } else if ("settings:begin_pin".equals(data)) {
            settingsHandler.startPinSetupStep(chatId, messageId, tgUser);
        }
    }

    private void handleMessage(TelegramMessage msg) {
        String text = msg.text();
        if (text == null) return;
        text = text.trim();

        long chatId = msg.chat().id();
        TelegramUser tgUser = msg.from();

        // 1. 先检查是否处于多步会话状态机中
        Optional<TelegramSessionState> optState = sessionService.getState(tgUser.id());
        if (optState.isPresent()) {
            TelegramSessionState state = optState.get();
            switch (state.getStep()) {
                case WITHDRAW_AWAITING_ADDRESS -> {
                    withdrawHandler.handleAddressInput(chatId, tgUser, text, state);
                    return;
                }
                case WITHDRAW_AWAITING_AMOUNT -> {
                    withdrawHandler.handleAmountInput(chatId, tgUser, text, state);
                    return;
                }
                case WITHDRAW_AWAITING_CREDENTIAL -> {
                    withdrawHandler.handleCredentialInput(chatId, tgUser, text, state);
                    return;
                }
                case TRADE_AWAITING_QUANTITY -> {
                    tradeHandler.handleQuantityInput(chatId, tgUser, text, state);
                    return;
                }
                case TRADE_AWAITING_CREDENTIAL -> {
                    tradeHandler.handleCredentialInput(chatId, tgUser, text, state);
                    return;
                }
                case TRANSFER_AWAITING_AMOUNT -> {
                    transferHandler.handleAmountInput(chatId, tgUser, text, state);
                    return;
                }
                case SETTINGS_AWAITING_NEW_PIN -> {
                    settingsHandler.handlePinInput(chatId, tgUser, text);
                    return;
                }
                case KOL_AWAITING_CUSTOM_SHARE -> {
                    affiliateHandler.handleCustomShareInput(chatId, tgUser, text, state);
                    return;
                }
                default -> {}
            }
        }

        // 2. 匹配固定指令与键盘快捷语
        if (text.startsWith("/start aff_")) {
            String inviteCode = text.substring("/start aff_".length()).trim();
            affiliateHandler.handleDeepLinkInvitation(chatId, tgUser, inviteCode);
        } else if (text.startsWith("/start") || text.startsWith("/menu") || "主菜单".equals(text)) {
            authHandler.sendWelcomeAndMainMenu(chatId, tgUser);
        } else if (text.startsWith("/assets") || "💰 账户资产".equals(text) || "资产".equals(text)) {
            assetHandler.renderAssets(chatId, null, tgUser);
        } else if (text.startsWith("/depth")) {
            String[] parts = text.split("\\s+");
            String symbol = parts.length > 1 ? parts[1] : "BTC-USDT";
            marketHandler.renderOrderBookDepth(chatId, null, symbol);
        } else if (text.startsWith("/price") || text.startsWith("/market") || "📊 实时行情".equals(text) || "行情".equals(text)) {
            String[] parts = text.split("\\s+");
            String symbol = parts.length > 1 ? parts[1] : "BTC-USDT";
            marketHandler.renderMarketOverview(chatId, null, symbol);
        } else if (text.equalsIgnoreCase("btc") || text.equalsIgnoreCase("eth") || text.equalsIgnoreCase("sol")
                || text.equalsIgnoreCase("doge") || text.equalsIgnoreCase("xrp")) {
            marketHandler.renderMarketOverview(chatId, null, text.toUpperCase() + "-USDT");
        } else if (text.startsWith("/positions") || "📈 合约持仓".equals(text) || "持仓".equals(text)) {
            positionHandler.renderPositions(chatId, null, tgUser);
        } else if (text.startsWith("/orders") || "📋 当前挂单".equals(text) || "挂单".equals(text)) {
            orderHandler.renderOpenOrders(chatId, null, tgUser);
        } else if (text.startsWith("/trade") || "⚡ 快捷交易".equals(text) || "交易".equals(text)) {
            tradeHandler.renderTradeCenter(chatId, null, tgUser);
        } else if (text.startsWith("/deposit") || "💳 充值".equals(text) || "充币".equals(text)) {
            depositHandler.renderChainSelection(chatId, null, tgUser);
        } else if (text.startsWith("/withdraw") || "💸 提币".equals(text) || "提现".equals(text)) {
            withdrawHandler.renderChainSelection(chatId, null, tgUser);
        } else if (text.startsWith("/transfer") || "🔄 划转".equals(text) || "资金划转".equals(text)) {
            transferHandler.renderDirectionSelection(chatId, null, tgUser);
        } else if (text.startsWith("/kol") || text.startsWith("/affiliate") || "🤝 邀请返佣".equals(text) || "邀请".equals(text)) {
            affiliateHandler.renderKolDashboard(chatId, null, tgUser);
        } else if (text.startsWith("/settings") || "⚙️ 设置".equals(text) || "安全设置".equals(text)) {
            settingsHandler.renderSettings(chatId, null, tgUser);
        } else {
            // 未知输入时引导用户回主菜单
            authHandler.sendWelcomeAndMainMenu(chatId, tgUser);
        }
    }
}
