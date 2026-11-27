package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastRepository;
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
    private final TelegramBroadcastRepository chats;

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
                                    TelegramAffiliateHandler affiliateHandler,
                                    TelegramBroadcastRepository chats) {
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
        this.chats = chats;
    }

    private void rememberChat(TelegramUser user, long chatId) {
        if (user == null || chats == null) return;
        try {
            chats.rememberChat(String.valueOf(user.id()), chatId);
        } catch (RuntimeException ex) {
            log.warn("Failed to remember Telegram chat for subject {}: {}", user.id(), ex.getMessage());
        }
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
        authHandler.resolveUserId(tgUser);
        rememberChat(tgUser, chatId);

        log.info("Telegram callback data: {} from userId={}", data, tgUser.id());
        if (dispatchExactCallback(data, chatId, messageId, tgUser)) {
            return;
        }
        dispatchPrefixCallback(data, chatId, messageId, tgUser);
    }

    private boolean dispatchExactCallback(String data, long chatId, long messageId, TelegramUser tgUser) {
        switch (data) {
            case "menu:main" -> authHandler.renderMainMenu(chatId, messageId, tgUser);
            case "menu:assets" -> assetHandler.renderAssets(chatId, messageId, tgUser);
            case "menu:market" -> marketHandler.renderMarketOverview(chatId, messageId, "BTC-USDT");
            case "menu:positions" -> positionHandler.renderPositions(chatId, messageId, tgUser);
            case "menu:orders" -> orderHandler.renderOpenOrders(chatId, messageId, tgUser);
            case "menu:trade" -> tradeHandler.renderTradeCenter(chatId, messageId, tgUser);
            case "menu:deposit" -> depositHandler.renderChainSelection(chatId, messageId, tgUser);
            case "menu:withdraw" -> withdrawHandler.renderChainSelection(chatId, messageId, tgUser);
            case "menu:transfer" -> transferHandler.renderDirectionSelection(chatId, messageId, tgUser);
            case "menu:settings" -> settingsHandler.renderSettings(chatId, messageId, tgUser);
            case "kol:dashboard" -> affiliateHandler.renderKolDashboard(chatId, messageId, tgUser);
            case "kol:poster" -> affiliateHandler.renderReferralPoster(chatId, tgUser);
            case "kol:members" -> affiliateHandler.renderTeamMembers(chatId, messageId, tgUser);
            case "kol:hierarchy" -> affiliateHandler.renderHierarchy(chatId, messageId, tgUser);
            case "kol:create_code" -> affiliateHandler.renderCreateCustomInvitation(chatId, messageId, tgUser);
            case "orders:cancel_all" -> orderHandler.executeCancelAll(chatId, messageId, tgUser);
            case "trade:confirm_execute" ->
                    sessionService.getState(tgUser.id()).ifPresent(state -> tradeHandler.executeTrade(chatId, messageId, tgUser, state));
            case "withdraw:confirm_execute" ->
                    sessionService.getState(tgUser.id()).ifPresent(state -> withdrawHandler.executeWithdrawal(chatId, messageId, tgUser, state));
            case "settings:begin_pin" -> settingsHandler.startPinSetupStep(chatId, messageId, tgUser);
            case "settings:lang:menu" -> settingsHandler.renderLanguageSettings(chatId, messageId, tgUser);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void dispatchPrefixCallback(String data, long chatId, long messageId, TelegramUser tgUser) {
        if (data.startsWith("kol:")) {
            dispatchKolCallback(data, chatId, messageId, tgUser);
        } else if (data.startsWith("market:depth:")) {
            marketHandler.renderOrderBookDepth(chatId, messageId, data.substring("market:depth:".length()));
        } else if (data.startsWith("market:view:")) {
            marketHandler.renderMarketOverview(chatId, messageId, data.substring("market:view:".length()));
        } else if (data.startsWith("orders:cancel:")) {
            String[] parts = data.split(":");
            orderHandler.executeCancelOrder(chatId, messageId, tgUser, Long.parseLong(parts[2]), parts[3]);
        } else if (data.startsWith("trade:symbol:")) {
            tradeHandler.startFastTradeQtyStep(chatId, messageId, tgUser, data.substring("trade:symbol:".length()), "BUY");
        } else if (data.startsWith("trade:fast:")) {
            String[] parts = data.split(":");
            tradeHandler.startFastTradeQtyStep(chatId, messageId, tgUser, parts[2], parts[3]);
        } else if (data.startsWith("pos:confirm_close:")) {
            String[] parts = data.split(":");
            positionHandler.renderConfirmClose(chatId, messageId, tgUser, parts[2], parts[3], parts.length > 4 ? parts[4] : "100");
        } else if (data.startsWith("pos:do_close:")) {
            String[] parts = data.split(":");
            positionHandler.executeClosePosition(chatId, messageId, tgUser, parts[2], parts[3], parts.length > 4 ? parts[4] : "100");
        } else if (data.startsWith("deposit:get:")) {
            depositHandler.handleGetAddress(chatId, messageId, tgUser, data.substring("deposit:get:".length()));
        } else if (data.startsWith("withdraw:step1:")) {
            String[] parts = data.split(":");
            withdrawHandler.startWithdrawAddressStep(chatId, messageId, tgUser, parts[2], parts[3]);
        } else if (data.startsWith("transfer:dir:")) {
            String[] parts = data.split(":");
            transferHandler.startTransferAmountStep(chatId, messageId, tgUser, parts[2], parts[3], parts[4]);
        } else if (data.startsWith("settings:mode:")) {
            settingsHandler.handleModeChange(chatId, messageId, tgUser, data.substring("settings:mode:".length()));
        } else if (data.startsWith("settings:lang:set:")) {
            settingsHandler.handleLanguageChange(chatId, messageId, tgUser, data.substring("settings:lang:set:".length()));
        }
    }

    private void dispatchKolCallback(String data, long chatId, long messageId, TelegramUser tgUser) {
        String[] parts = data.split(":");
        if (data.startsWith("kol:do_create_code:")) {
            affiliateHandler.executeCreateCustomInvitation(chatId, messageId, tgUser, Integer.parseInt(parts[2]));
        } else if (data.startsWith("kol:member:")) {
            affiliateHandler.renderMemberDetail(chatId, messageId, tgUser, Long.parseLong(parts[2]));
        } else if (data.startsWith("kol:do_share:")) {
            affiliateHandler.executeSetShare(chatId, messageId, tgUser, Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        } else if (data.startsWith("kol:custom_share:")) {
            affiliateHandler.startCustomShareInput(chatId, messageId, tgUser, Long.parseLong(parts[2]));
        }
    }

    private void handleMessage(TelegramMessage msg) {
        String text = msg.text();
        if (text == null || msg.from() == null || msg.chat() == null) return;
        text = text.trim();

        long chatId = msg.chat().id();
        TelegramUser tgUser = msg.from();

        if (text.startsWith("/start")) authHandler.registerUser(tgUser);
        else authHandler.resolveUserId(tgUser);
        rememberChat(tgUser, chatId);

        if (handleSessionInput(chatId, tgUser, text)) {
            return;
        }
        dispatchTextCommand(chatId, tgUser, text);
    }

    private boolean handleSessionInput(long chatId, TelegramUser tgUser, String text) {
        Optional<TelegramSessionState> optState = sessionService.getState(tgUser.id());
        if (optState.isEmpty()) {
            return false;
        }
        TelegramSessionState state = optState.get();
        switch (state.getStep()) {
            case WITHDRAW_AWAITING_ADDRESS -> withdrawHandler.handleAddressInput(chatId, tgUser, text, state);
            case WITHDRAW_AWAITING_AMOUNT -> withdrawHandler.handleAmountInput(chatId, tgUser, text, state);
            case WITHDRAW_AWAITING_CREDENTIAL -> withdrawHandler.handleCredentialInput(chatId, tgUser, text, state);
            case TRADE_AWAITING_QUANTITY -> tradeHandler.handleQuantityInput(chatId, tgUser, text, state);
            case TRADE_AWAITING_CREDENTIAL -> tradeHandler.handleCredentialInput(chatId, tgUser, text, state);
            case TRANSFER_AWAITING_AMOUNT -> transferHandler.handleAmountInput(chatId, tgUser, text, state);
            case SETTINGS_AWAITING_NEW_PIN -> settingsHandler.handlePinInput(chatId, tgUser, text);
            case KOL_AWAITING_CUSTOM_SHARE -> affiliateHandler.handleCustomShareInput(chatId, tgUser, text, state);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void dispatchTextCommand(long chatId, TelegramUser tgUser, String text) {
        if (text.startsWith("/start aff_")) {
            affiliateHandler.handleDeepLinkInvitation(chatId, tgUser, text.substring("/start aff_".length()).trim());
        } else if (text.startsWith("/start") || text.startsWith("/menu") || matchesAny(text, "主菜单", "Menu", "Main Menu", "Главное меню")) {
            authHandler.sendWelcomeAndMainMenu(chatId, tgUser);
        } else if (text.startsWith("/assets") || matchesAny(text, "💰 账户资产", "💰 Assets", "💰 Активы", "资产", "Assets")) {
            assetHandler.renderAssets(chatId, null, tgUser);
        } else if (text.startsWith("/depth")) {
            marketHandler.renderOrderBookDepth(chatId, null, extractSecondToken(text, "BTC-USDT"));
        } else if (text.startsWith("/price") || text.startsWith("/market")
                || matchesAny(text, "📊 实时行情", "📊 Market", "📊 Рынок", "行情", "Market")) {
            marketHandler.renderMarketOverview(chatId, null, extractSecondToken(text, "BTC-USDT"));
        } else if (matchesAny(text, "btc", "eth", "sol", "doge", "xrp")) {
            marketHandler.renderMarketOverview(chatId, null, text.toUpperCase() + "-USDT");
        } else if (text.startsWith("/positions") || matchesAny(text, "📈 合约持仓", "📈 Positions", "📈 Позиции", "持仓", "Positions")) {
            positionHandler.renderPositions(chatId, null, tgUser);
        } else if (text.startsWith("/orders") || matchesAny(text, "📋 当前挂单", "📋 Open Orders", "📋 Ордера", "挂单", "Orders")) {
            orderHandler.renderOpenOrders(chatId, null, tgUser);
        } else if (text.startsWith("/trade") || matchesAny(text, "⚡ 快捷交易", "⚡ Fast Trade", "⚡ Быстрая торговля", "交易", "Trade")) {
            tradeHandler.renderTradeCenter(chatId, null, tgUser);
        } else if (text.startsWith("/deposit") || matchesAny(text, "💳 充值", "💳 Deposit", "💳 Депозит", "充币", "Deposit")) {
            depositHandler.renderChainSelection(chatId, null, tgUser);
        } else if (text.startsWith("/withdraw") || matchesAny(text, "💸 提币", "💸 Withdraw", "💸 Вывод", "提现", "Withdraw")) {
            withdrawHandler.renderChainSelection(chatId, null, tgUser);
        } else if (text.startsWith("/transfer") || matchesAny(text, "🔄 划转", "🔄 Transfer", "🔄 Перевод", "资金划转", "Transfer")) {
            transferHandler.renderDirectionSelection(chatId, null, tgUser);
        } else if (text.startsWith("/kol") || text.startsWith("/affiliate")
                || matchesAny(text, "🤝 邀请返佣", "🤝 Referral (KOL)", "🤝 Рефералы (KOL)", "邀请", "Referral")) {
            affiliateHandler.renderKolDashboard(chatId, null, tgUser);
        } else if (text.startsWith("/settings") || matchesAny(text, "⚙️ 设置", "⚙️ Settings", "⚙️ Настройки", "安全设置", "Settings")) {
            settingsHandler.renderSettings(chatId, null, tgUser);
        } else {
            authHandler.sendWelcomeAndMainMenu(chatId, tgUser);
        }
    }

    private static boolean matchesAny(String text, String... aliases) {
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }

    private static String extractSecondToken(String text, String fallback) {
        String[] parts = text.split("\\s+");
        return parts.length > 1 ? parts[1] : fallback;
    }
}
