package com.surprising.gateway.provider.telegram.bot;

import com.surprising.affiliate.model.AffiliateModels;
import com.surprising.affiliate.repository.AffiliateRepository;
import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.handler.TelegramAffiliateHandler;
import com.surprising.gateway.provider.telegram.bot.handler.TelegramAuthHandler;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramAffiliateHandlerTest {

    private AffiliateService affiliateService;
    private AffiliateRepository affiliateRepository;
    private TelegramAuthHandler authHandler;
    private TelegramSessionService sessionService;
    private TelegramQrCodeService qrCodeService;
    private TelegramBotClient botClient;
    private TelegramBotProperties properties;
    private TelegramAffiliateHandler affiliateHandler;

    private final TelegramUser testUser = new TelegramUser(66666L, false, "KOL_Bob", null, "kol_bob", "zh-hans");

    @BeforeEach
    void setUp() {
        affiliateService = mock(AffiliateService.class);
        affiliateRepository = mock(AffiliateRepository.class);
        authHandler = mock(TelegramAuthHandler.class);
        sessionService = mock(TelegramSessionService.class);
        qrCodeService = mock(TelegramQrCodeService.class);
        botClient = mock(TelegramBotClient.class);
        properties = new TelegramBotProperties();
        properties.setUsername("surprising_test_bot");

        when(authHandler.resolveUserId(testUser)).thenReturn(10086L);

        affiliateHandler = new TelegramAffiliateHandler(
                affiliateService, affiliateRepository, authHandler,
                sessionService, qrCodeService, botClient, properties
        );
    }

    @Test
    void buildsCorrectTelegramDeepLink() {
        String deepLink = affiliateHandler.buildTelegramDeepLink("KOL888");
        assertThat(deepLink).isEqualTo("https://t.me/surprising_test_bot?start=aff_KOL888");
    }

    @Test
    void rendersKolDashboardSuccessfully() {
        when(affiliateService.overview(eq(10086L), anyString())).thenReturn(Map.of(
                "counts", Map.of("direct", 15, "team", 48),
                "rootRatePpm", 400000
        ));

        affiliateHandler.renderKolDashboard(123456L, null, testUser);

        verify(botClient).sendMessage(eq(123456L), contains("KOL / 社区合伙人推广主控台"), any());
    }

    @Test
    void generatesReferralPosterWithQrCode() {
        when(qrCodeService.generateQrCodePng(anyString(), anyInt(), anyInt())).thenReturn(new byte[]{1, 2, 3});

        affiliateHandler.renderReferralPoster(123456L, testUser);

        verify(qrCodeService).generateQrCodePng(contains("https://t.me/surprising_test_bot?start=aff_"), eq(320), eq(320));
        verify(botClient).sendPhoto(eq(123456L), any(), contains("kol_invite_"), contains("专属 Telegram 推广裂变海报"), any());
    }

    @Test
    void handlesDeepLinkInvitationCorrectly() {
        affiliateHandler.handleDeepLinkInvitation(123456L, testUser, "VIP999");

        verify(affiliateService).register(eq(10086L), isNull());
        verify(affiliateService).accept(eq(10086L), eq("VIP999"));
        verify(authHandler).sendWelcomeAndMainMenu(eq(123456L), eq(testUser));
        verify(botClient).sendMessage(eq(123456L), contains("合伙人特权已激活"), isNull());
    }

    @Test
    void rendersHierarchySuccessfully() {
        when(affiliateService.overview(eq(10086L), anyString())).thenReturn(Map.of(
                "counts", Map.of("direct", 15, "team", 48),
                "rootRatePpm", 400000
        ));

        affiliateHandler.renderHierarchy(123456L, 99L, testUser);

        verify(botClient).editMessageText(eq(123456L), eq(99L), contains("合伙人团队层级架构树"), any());
    }

    @Test
    void rendersTeamMembersSuccessfully() {
        List<Map<String, Object>> items = List.of(
                Map.of("userId", 20001L, "sharePpm", 100000, "createdAt", "2026-11-01"),
                Map.of("userId", 20002L, "sharePpm", 200000, "createdAt", "2026-11-02")
        );
        when(affiliateService.members(10086L, 10086L, 0L)).thenReturn(new AffiliateModels.Page<>(items, null));

        affiliateHandler.renderTeamMembers(123456L, 99L, testUser);

        verify(botClient).editMessageText(eq(123456L), eq(99L), contains("受邀团队成员管理与分成设置"), any());
    }

    @Test
    void rendersMemberDetailSuccessfully() {
        affiliateHandler.renderMemberDetail(123456L, 99L, testUser, 20001L);

        verify(botClient).editMessageText(eq(123456L), eq(99L), contains("下级成员专属管理面板"), any());
    }

    @Test
    void executesSetShareSuccessfully() {
        affiliateHandler.executeSetShare(123456L, 99L, testUser, 20001L, 200000);

        verify(affiliateService).share(10086L, 20001L, 200000);
        verify(botClient).answerCallbackQuery(isNull(), contains("已成功将 UID 20001 的分成比例设为 20.0%"), eq(true));
    }

    @Test
    void rendersCreateCustomInvitationSuccessfully() {
        affiliateHandler.renderCreateCustomInvitation(123456L, 99L, testUser);

        verify(botClient).editMessageText(eq(123456L), eq(99L), contains("创建指定返现比例的专属邀请码"), any());
    }

    @Test
    void executesCreateCustomInvitationSuccessfully() {
        affiliateHandler.executeCreateCustomInvitation(123456L, 99L, testUser, 100000);

        verify(affiliateRepository).createInvitation(anyString(), eq(10086L), isNull(), eq(100000));
        verify(botClient).editMessageText(eq(123456L), eq(99L), contains("专属渠道邀请码创建成功"), any());
    }

    @Test
    void handlesCustomShareInputCorrectly() {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.KOL_AWAITING_CUSTOM_SHARE,
                Map.of("targetMemberId", 20001L),
                Instant.now().plusSeconds(300)
        );

        affiliateHandler.handleCustomShareInput(123456L, testUser, "15", state);

        verify(sessionService).clearState(testUser.id());
        verify(affiliateService).share(10086L, 20001L, 150000);
    }
}
