package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.affiliate.model.AffiliateModels;
import com.surprising.affiliate.repository.AffiliateRepository;
import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Telegram KOL / 推广合伙人与返佣入口处理器。
 * 负责邀请码生成、分成比例变更、渠道码创建与 Deep Link 绑定；只读视图渲染委派给 {@link TelegramAffiliateViewRenderer}。
 */
@Slf4j
@Component
public class TelegramAffiliateHandler {

    private final AffiliateService affiliateService;
    private final AffiliateRepository affiliateRepository;
    private final TelegramAuthHandler authHandler;
    private final TelegramSessionService sessionService;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;
    private final TelegramAffiliateViewRenderer viewRenderer;

    @Autowired
    public TelegramAffiliateHandler(@Autowired(required = false) AffiliateService affiliateService,
                                    @Autowired(required = false) AffiliateRepository affiliateRepository,
                                    TelegramAuthHandler authHandler,
                                    TelegramSessionService sessionService,
                                    TelegramQrCodeService qrCodeService,
                                    TelegramBotClient botClient,
                                    TelegramBotProperties properties) {
        this.affiliateService = affiliateService;
        this.affiliateRepository = affiliateRepository;
        this.authHandler = authHandler;
        this.sessionService = sessionService;
        this.botClient = botClient;
        this.properties = properties;
        this.viewRenderer = new TelegramAffiliateViewRenderer(affiliateService, authHandler, qrCodeService, botClient);
    }

    /** 获取或为用户生成专属短邀请码（与 Web 返佣页 AffiliateProfile.inviteCode 保持一致） */
    public String getOrCreateInviteCode(long userId) {
        if (affiliateService != null) {
            try {
                AffiliateModels.Profile profile = affiliateService.register(userId, null);
                if (profile != null && profile.inviteCode() != null && !profile.inviteCode().isBlank()) {
                    return profile.inviteCode();
                }
            } catch (Exception ex) {
                log.warn("Failed to resolve affiliate profile invite code for user {}: {}", userId, ex.getMessage());
            }
        }
        if (affiliateRepository != null) {
            try {
                List<AffiliateModels.Invitation> list = affiliateRepository.invitations(userId);
                if (list != null && !list.isEmpty()) {
                    return list.get(0).code();
                }
                String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
                affiliateRepository.createInvitation(token, userId, null, 100000);
                return token;
            } catch (Exception ex) {
                log.warn("Failed to get/create invite code from repository for user {}: {}", userId, ex.getMessage());
            }
        }
        return "SURP" + String.format(Locale.ROOT, "%06d", userId % 1000000);
    }

    /** 构造专属 Telegram Deep Link 直达链接 */
    public String buildTelegramDeepLink(String inviteCode) {
        String botName = (properties != null && properties.getUsername() != null && !properties.getUsername().isBlank())
                ? properties.getUsername()
                : "surprising_ex_bot";
        return "https://t.me/" + botName + "?start=aff_" + inviteCode;
    }

    /** 构造与 Web 返佣页一致的专属注册邀请链接 */
    public String buildRegistrationInviteLink(String inviteCode) {
        String baseUrl = (properties != null && properties.getWebappUrl() != null && !properties.getWebappUrl().isBlank())
                ? properties.getWebappUrl().trim()
                : "https://app.surprising.exchange";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/auth/register?invite=" + URLEncoder.encode(inviteCode, StandardCharsets.UTF_8);
    }

    /** 1. KOL 推广数据看板 */
    public void renderKolDashboard(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        String inviteCode = getOrCreateInviteCode(userId);
        viewRenderer.renderKolDashboard(chatId, messageId, tgUser, inviteCode, buildTelegramDeepLink(inviteCode));
    }

    /** 2. 团队层级架构树看板 */
    public void renderHierarchy(long chatId, Long messageId, TelegramUser tgUser) {
        viewRenderer.renderHierarchy(chatId, messageId, tgUser);
    }

    /** 3. 团队直属成员列表与分成管理 */
    public void renderTeamMembers(long chatId, Long messageId, TelegramUser tgUser) {
        viewRenderer.renderTeamMembers(chatId, messageId, tgUser);
    }

    /** 4. 单个下级成员业绩详情与分成设置面板 */
    public void renderMemberDetail(long chatId, Long messageId, TelegramUser tgUser, long memberUserId) {
        viewRenderer.renderMemberDetail(chatId, messageId, tgUser, memberUserId);
    }

    /** 5. 执行设置分成比例 */
    public void executeSetShare(long chatId, long messageId, TelegramUser tgUser, long memberUserId, int ppm) {
        long userId = authHandler.resolveUserId(tgUser);
        boolean ok = false;
        String errorMsg = null;

        if (affiliateService != null) {
            try {
                affiliateService.share(userId, memberUserId, ppm);
                ok = true;
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
                log.warn("Failed to set share for user {} to child {}: {}", userId, memberUserId, ex.getMessage());
            }
        }

        double pct = ppm / 10000.0;
        if (ok) {
            botClient.answerCallbackQuery(null, "已成功将 UID " + memberUserId + " 的分成比例设为 " + String.format(Locale.ROOT, "%.1f%%", pct) + "！", true);
            renderMemberDetail(chatId, messageId, tgUser, memberUserId);
        } else {
            String text = "❌ <b>设置分成比例未成功:</b> " + (errorMsg != null ? errorMsg : "该用户尚未接受邀请或不是直属下级") + "\n\n" +
                    "请核对后重试。";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("👥 返回成员列表", "kol:members"))
                    .build();
            botClient.editMessageText(chatId, messageId, text, markup);
        }
    }

    /** 6. 开始自定义输入分成比例 */
    public void startCustomShareInput(long chatId, Long messageId, TelegramUser tgUser, long memberUserId) {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.KOL_AWAITING_CUSTOM_SHARE,
                Map.of("targetMemberId", memberUserId),
                Instant.now().plus(Duration.ofMinutes(5))
        );
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.KOL_AWAITING_CUSTOM_SHARE, state);

        String text = "✏️ <b>输入自定义分成比例</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "目标成员: <code>UID " + memberUserId + "</code>\n\n" +
                "请直接在聊天框中回复您希望分配给该下级的百分比（整数 0 到 80）：\n" +
                "<i>例如：回复 <code>15</code> 表示分配 15% 佣金给该成员。</i>";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消返回", "kol:member:" + memberUserId))
                .build();

        viewRenderer.sendOrEdit(chatId, messageId, text, markup);
    }

    /** 处理自定义输入的分成比例 */
    public void handleCustomShareInput(long chatId, TelegramUser tgUser, String text, TelegramSessionState state) {
        long memberUserId = ((Number) state.getData().get("targetMemberId")).longValue();
        int pct;
        try {
            pct = Integer.parseInt(text.trim().replace("%", ""));
            if (pct < 0 || pct > 80) {
                botClient.sendMessage(chatId, "❌ 分成百分比必须在 0 到 80 之间，请重新输入（例如 25）：", null);
                return;
            }
        } catch (Exception ex) {
            botClient.sendMessage(chatId, "❌ 格式无效，请输入 0-80 之间的纯整数：", null);
            return;
        }

        sessionService.clearState(tgUser.id());
        executeSetShare(chatId, 0L, tgUser, memberUserId, pct * 10000);
    }

    /** 7. 渲染创建新比例邀请码面板 */
    public void renderCreateCustomInvitation(long chatId, Long messageId, TelegramUser tgUser) {
        String text = "➕ <b>创建指定返现比例的专属邀请码</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "为了方便您在不同社群和渠道做精细化营销，您可以为不同渠道创建不同的邀请码：\n\n" +
                "• <b>0% 返现码</b>: 佣金 100% 自留，适合普通散客推广\n" +
                "• <b>10% 返现码</b>: 给好友返现 10%，自留 90%，高转化黄金比例\n" +
                "• <b>20% 返现码</b>: 给好友返现 20%，适合重点渠道合伙人\n" +
                "• <b>30% 返现码</b>: 超高让利，专用于大客户挖角与渠道置换\n\n" +
                "👇 请选择您要创建的邀请码让利比例：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("0% 返现 (自留 100%)", "kol:do_create_code:0"),
                        TelegramInlineKeyboardButton.callback("10% 返现 (推荐黄金档)", "kol:do_create_code:100000")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("20% 返现 (渠道合伙人)", "kol:do_create_code:200000"),
                        TelegramInlineKeyboardButton.callback("30% 返现 (超高让利档)", "kol:do_create_code:300000")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 返回推广看板", "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        viewRenderer.sendOrEdit(chatId, messageId, text, markup);
    }

    /** 执行创建指定比例的邀请码 */
    public void executeCreateCustomInvitation(long chatId, Long messageId, TelegramUser tgUser, int sharePpm) {
        long userId = authHandler.resolveUserId(tgUser);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);

        if (affiliateRepository != null) {
            try {
                affiliateRepository.createInvitation(token, userId, null, sharePpm);
            } catch (Exception ex) {
                log.warn("Create invitation error: {}", ex.getMessage());
            }
        }

        String deepLink = buildTelegramDeepLink(token);
        double sharePct = sharePpm / 10000.0;

        String text = "🎉 <b>专属渠道邀请码创建成功！</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🔑 <b>邀请码:</b> <code>" + token + "</code>\n" +
                "🎁 <b>好友返现比例:</b> <code>" + String.format(Locale.ROOT, "%.1f%%", sharePct) + "</code>\n\n" +
                "📲 <b>专属 Telegram 直达链接:</b>\n" +
                "<code>" + deepLink + "</code>\n\n" +
                "💡 您可以将此链接配置给对应渠道的合伙人或社群！";

        String shareText = "🔥 Surprising 顶级交易体验！点击专属通道，立享 " + String.format(Locale.ROOT, "%.1f%%", sharePct) + " 手续费返现！";
        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.url("🚀 一键分享到 TG 群组",
                        TelegramAffiliateViewRenderer.shareUrl(deepLink, shareText)))
                .addRow(
                        TelegramInlineKeyboardButton.callback("🖼 生成此码海报", "kol:poster"),
                        TelegramInlineKeyboardButton.callback("➕ 继续创建新码", "kol:create_code")
                )
                .addRow(TelegramInlineKeyboardButton.callback("📊 返回推广看板", "kol:dashboard"))
                .build();

        viewRenderer.sendOrEdit(chatId, messageId, text, markup);
    }

    /** 8. 生成专属邀请函海报（与 Web 返佣页同款逻辑与视觉） */
    public void renderReferralPoster(long chatId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        String inviteCode = getOrCreateInviteCode(userId);
        viewRenderer.renderReferralPoster(
                chatId,
                userId,
                inviteCode,
                buildRegistrationInviteLink(inviteCode),
                buildTelegramDeepLink(inviteCode)
        );
    }

    /** 处理新用户通过 Deep Link 点击进入：/start aff_CODE */
    public void handleDeepLinkInvitation(long chatId, TelegramUser tgUser, String inviteCode) {
        long userId = authHandler.resolveUserId(tgUser);
        boolean acceptOk = false;

        if (affiliateService != null && inviteCode != null && !inviteCode.isBlank()) {
            try {
                affiliateService.register(userId, null);
                affiliateService.accept(userId, inviteCode.trim());
                acceptOk = true;
                log.info("Successfully bound user {} to inviter with code {}", userId, inviteCode);
            } catch (Exception ex) {
                log.info("Invitation acceptance notice for user {}: {}", userId, ex.getMessage());
            }
        }

        StringBuilder welcome = new StringBuilder();
        if (acceptOk) {
            welcome.append("🎉 <b>合伙人特权已激活！</b>\n");
            welcome.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            welcome.append("👤 <b>您的账户 UID:</b> <code>").append(userId).append("</code>\n");
            welcome.append("🔑 <b>专属邀请码:</b> <code>").append(inviteCode).append("</code>\n\n");
            welcome.append("恭喜您！您已通过官方 VIP 合伙人专属通道加入，已为您<b>永久激活交易手续费自动返现特权</b>！\n\n");
            welcome.append("您可随时通过下方菜单开始<b>充值、查看实时行情、快捷开平仓或打开 Mini App 专业终端</b>。");
        } else {
            welcome.append("👋 <b>欢迎回到 Surprising 交易平台！</b>\n");
            welcome.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            welcome.append("👤 <b>您的账户 UID:</b> <code>").append(userId).append("</code>\n\n");
            welcome.append("💡 <i>提示: 您已是平台认证用户（UID: ").append(userId).append("），无需重复绑定。</i>\n\n");
            welcome.append("您可随时通过下方菜单开始<b>充值、查看实时行情、快捷开平仓或管理您的推广团队</b>。");
        }

        authHandler.sendWelcomeAndMainMenu(chatId, tgUser);
        botClient.sendMessage(chatId, welcome.toString(), null);
    }
}
