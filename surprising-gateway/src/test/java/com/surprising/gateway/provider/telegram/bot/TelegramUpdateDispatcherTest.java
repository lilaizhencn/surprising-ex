package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.handler.*;
import com.surprising.gateway.provider.telegram.bot.model.*;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramUpdateDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramUpdateDispatcherTest {

    private TelegramBotClient botClient;
    private TelegramSessionService sessionService;
    private TelegramAuthHandler authHandler;
    private TelegramAssetHandler assetHandler;
    private TelegramMarketHandler marketHandler;
    private TelegramPositionHandler positionHandler;
    private TelegramOrderHandler orderHandler;
    private TelegramTradeHandler tradeHandler;
    private TelegramDepositHandler depositHandler;
    private TelegramWithdrawHandler withdrawHandler;
    private TelegramTransferHandler transferHandler;
    private TelegramSettingsHandler settingsHandler;
    private TelegramAffiliateHandler affiliateHandler;
    private TelegramUpdateDispatcher dispatcher;

    private final TelegramUser testUser = new TelegramUser(88888L, false, "Alice", null, "alice", "en");
    private final TelegramChat testChat = new TelegramChat(99999L, "private", null, "alice");

    @BeforeEach
    void setUp() {
        botClient = mock(TelegramBotClient.class);
        sessionService = new TelegramSessionService();
        authHandler = mock(TelegramAuthHandler.class);
        assetHandler = mock(TelegramAssetHandler.class);
        marketHandler = mock(TelegramMarketHandler.class);
        positionHandler = mock(TelegramPositionHandler.class);
        orderHandler = mock(TelegramOrderHandler.class);
        tradeHandler = mock(TelegramTradeHandler.class);
        depositHandler = mock(TelegramDepositHandler.class);
        withdrawHandler = mock(TelegramWithdrawHandler.class);
        transferHandler = mock(TelegramTransferHandler.class);
        settingsHandler = mock(TelegramSettingsHandler.class);
        affiliateHandler = mock(TelegramAffiliateHandler.class);

        dispatcher = new TelegramUpdateDispatcher(
                botClient, sessionService, authHandler, assetHandler, marketHandler,
                positionHandler, orderHandler, tradeHandler, depositHandler, withdrawHandler,
                transferHandler, settingsHandler, affiliateHandler
        );
    }

    @Test
    void dispatchesStartCommandToAuthHandler() {
        TelegramMessage msg = new TelegramMessage(1L, testUser, testChat, 123456L, "/start");
        dispatcher.dispatch(new TelegramUpdate(1L, msg, null));

        verify(authHandler).sendWelcomeAndMainMenu(eq(testChat.id()), eq(testUser));
    }

    @Test
    void dispatchesDeepLinkStartToAffiliateHandler() {
        TelegramMessage msg = new TelegramMessage(100L, testUser, testChat, 123456L, "/start aff_SUPER_KOL");
        dispatcher.dispatch(new TelegramUpdate(100L, msg, null));

        verify(affiliateHandler).handleDeepLinkInvitation(eq(testChat.id()), eq(testUser), eq("SUPER_KOL"));
    }

    @Test
    void dispatchesKolCommandToAffiliateHandler() {
        TelegramMessage msg = new TelegramMessage(101L, testUser, testChat, 123456L, "🤝 邀请返佣");
        dispatcher.dispatch(new TelegramUpdate(101L, msg, null));

        verify(affiliateHandler).renderKolDashboard(eq(testChat.id()), isNull(), eq(testUser));
    }

    @Test
    void dispatchesKolHierarchyCallback() {
        TelegramMessage origMsg = new TelegramMessage(12L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_hier", testUser, origMsg, "kol:hierarchy");
        dispatcher.dispatch(new TelegramUpdate(102L, null, cq));

        verify(affiliateHandler).renderHierarchy(eq(testChat.id()), eq(12L), eq(testUser));
    }

    @Test
    void dispatchesKolMemberDetailCallback() {
        TelegramMessage origMsg = new TelegramMessage(13L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_mem", testUser, origMsg, "kol:member:20001");
        dispatcher.dispatch(new TelegramUpdate(103L, null, cq));

        verify(affiliateHandler).renderMemberDetail(eq(testChat.id()), eq(13L), eq(testUser), eq(20001L));
    }

    @Test
    void dispatchesKolSetShareCallback() {
        TelegramMessage origMsg = new TelegramMessage(14L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_share", testUser, origMsg, "kol:do_share:20001:150000");
        dispatcher.dispatch(new TelegramUpdate(104L, null, cq));

        verify(affiliateHandler).executeSetShare(eq(testChat.id()), eq(14L), eq(testUser), eq(20001L), eq(150000));
    }

    @Test
    void dispatchesKolCreateCodeCallback() {
        TelegramMessage origMsg = new TelegramMessage(15L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_code", testUser, origMsg, "kol:create_code");
        dispatcher.dispatch(new TelegramUpdate(105L, null, cq));

        verify(affiliateHandler).renderCreateCustomInvitation(eq(testChat.id()), eq(15L), eq(testUser));
    }

    @Test
    void dispatchesAssetsCommandToAssetHandler() {
        TelegramMessage msg = new TelegramMessage(2L, testUser, testChat, 123456L, "💰 账户资产");
        dispatcher.dispatch(new TelegramUpdate(2L, msg, null));

        verify(assetHandler).renderAssets(eq(testChat.id()), isNull(), eq(testUser));
    }

    @Test
    void dispatchesPositionsCommandToPositionHandler() {
        TelegramMessage msg = new TelegramMessage(3L, testUser, testChat, 123456L, "/positions");
        dispatcher.dispatch(new TelegramUpdate(3L, msg, null));

        verify(positionHandler).renderPositions(eq(testChat.id()), isNull(), eq(testUser));
    }

    @Test
    void dispatchesOrdersCommandToOrderHandler() {
        TelegramMessage msg = new TelegramMessage(4L, testUser, testChat, 123456L, "/orders");
        dispatcher.dispatch(new TelegramUpdate(4L, msg, null));

        verify(orderHandler).renderOpenOrders(eq(testChat.id()), isNull(), eq(testUser));
    }

    @Test
    void dispatchesCancelAllOrdersCallback() {
        TelegramMessage origMsg = new TelegramMessage(11L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_cancel", testUser, origMsg, "orders:cancel_all");
        dispatcher.dispatch(new TelegramUpdate(5L, null, cq));

        verify(orderHandler).executeCancelAll(eq(testChat.id()), eq(11L), eq(testUser));
    }

    @Test
    void dispatchesMarketCallbackQuery() {
        TelegramMessage origMsg = new TelegramMessage(10L, null, testChat, 123456L, "original");
        TelegramCallbackQuery cq = new TelegramCallbackQuery("cq_123", testUser, origMsg, "market:view:ETH-USDT");
        dispatcher.dispatch(new TelegramUpdate(4L, null, cq));

        verify(botClient).answerCallbackQuery(eq("cq_123"), isNull(), eq(false));
        verify(marketHandler).renderMarketOverview(eq(testChat.id()), eq(10L), eq("ETH-USDT"));
    }

    @Test
    void routesSessionInputWhenAwaitingWithdrawAddress() {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.WITHDRAW_AWAITING_ADDRESS,
                Map.of("chain", "TRON", "asset", "USDT"),
                Instant.now().plusSeconds(300)
        );
        sessionService.setState(testUser.id(), TelegramSessionState.Step.WITHDRAW_AWAITING_ADDRESS, state);

        TelegramMessage msg = new TelegramMessage(5L, testUser, testChat, 123456L, "TYDzsxdczEfD17Q6yoRPAxmGgkPxT888888");
        dispatcher.dispatch(new TelegramUpdate(5L, msg, null));

        verify(withdrawHandler).handleAddressInput(eq(testChat.id()), eq(testUser), eq("TYDzsxdczEfD17Q6yoRPAxmGgkPxT888888"), any());
    }

    @Test
    void dispatchesMultilingualAssetsCommands() {
        // 英文键盘
        TelegramMessage enMsg = new TelegramMessage(6L, testUser, testChat, 123456L, "💰 Assets");
        dispatcher.dispatch(new TelegramUpdate(6L, enMsg, null));
        verify(assetHandler, times(1)).renderAssets(eq(testChat.id()), isNull(), eq(testUser));

        // 俄文键盘
        TelegramMessage ruMsg = new TelegramMessage(7L, testUser, testChat, 123456L, "💰 Активы");
        dispatcher.dispatch(new TelegramUpdate(7L, ruMsg, null));
        verify(assetHandler, times(2)).renderAssets(eq(testChat.id()), isNull(), eq(testUser));
    }

    @Test
    void dispatchesLanguageSettingsCallbacks() {
        TelegramMessage origMsg = new TelegramMessage(20L, null, testChat, 123456L, "settings");

        // 打开语言设置菜单
        TelegramCallbackQuery cqMenu = new TelegramCallbackQuery("cq_lang_menu", testUser, origMsg, "settings:lang:menu");
        dispatcher.dispatch(new TelegramUpdate(20L, null, cqMenu));
        verify(settingsHandler).renderLanguageSettings(eq(testChat.id()), eq(20L), eq(testUser));

        // 切换语言为俄文
        TelegramCallbackQuery cqSet = new TelegramCallbackQuery("cq_lang_set", testUser, origMsg, "settings:lang:set:ru");
        dispatcher.dispatch(new TelegramUpdate(21L, null, cqSet));
        verify(settingsHandler).handleLanguageChange(eq(testChat.id()), eq(20L), eq(testUser), eq("ru"));
    }
}
