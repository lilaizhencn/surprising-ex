package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.affiliate.model.AffiliateModels;
import com.surprising.affiliate.repository.AffiliateRepository;
import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;

/**
 * Telegram KOL / 推广合伙人与返佣全生命周期处理器
 * 支持：
 * 1. KOL 收益大盘与一键转发/裂变直达链接
 * 2. 团队层级架构树与全景网络看板
 * 3. 下级成员个人战报查询与动态设置/切分分成比例
 * 4. 创建差异化分比例渠道邀请码
 * 5. 动态生成推广二维码海报
 * 6. Telegram Deep Link 裂变免登无缝绑定
 */
@Slf4j
@Component
public class TelegramAffiliateHandler {

    private final AffiliateService affiliateService;
    private final AffiliateRepository affiliateRepository;
    private final TelegramAuthHandler authHandler;
    private final TelegramSessionService sessionService;
    private final TelegramQrCodeService qrCodeService;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;

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
        this.qrCodeService = qrCodeService;
        this.botClient = botClient;
        this.properties = properties;
    }

    /** 获取或为用户生成专属默认邀请码 */
    public String getOrCreateInviteCode(long userId) {
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

    /** 1. KOL 推广数据看板 */
    public void renderKolDashboard(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        String inviteCode = getOrCreateInviteCode(userId);
        String tgDeepLink = buildTelegramDeepLink(inviteCode);

        long directCount = 0;
        long teamCount = 0;
        String totalCommission = "0.00";
        String pendingCommission = "0.00";
        int ratePpm = 300000;

        if (affiliateService != null) {
            try {
                affiliateService.register(userId, null);
                Map<String, Object> overview = affiliateService.overview(userId, "");
                if (overview != null) {
                    Object countsObj = overview.get("counts");
                    if (countsObj instanceof Map<?, ?> countsMap) {
                        Object dVal = countsMap.get("direct");
                        if (dVal instanceof Number n) directCount = n.longValue();
                        Object tVal = countsMap.get("team");
                        if (tVal instanceof Number n) teamCount = n.longValue();
                    }
                    if (overview.get("rootRatePpm") instanceof Number rateNum) {
                        ratePpm = rateNum.intValue();
                    }
                    if (overview.get("balances") instanceof List<?> balancesList) {
                        long paidTotal = 0;
                        long pendingTotal = 0;
                        for (Object bItem : balancesList) {
                            if (bItem instanceof Map<?, ?> bMap) {
                                Object pUnits = bMap.get("paidUnits");
                                if (pUnits != null) {
                                    try { paidTotal += Long.parseLong(pUnits.toString()); } catch (Exception ignored) {}
                                }
                                Object pendUnits = bMap.get("pendingUnits");
                                if (pendUnits != null) {
                                    try { pendingTotal += Long.parseLong(pendUnits.toString()); } catch (Exception ignored) {}
                                }
                            }
                        }
                        if (paidTotal > 0) totalCommission = String.format(Locale.ROOT, "%.2f", paidTotal / 1000000.0);
                        if (pendingTotal > 0) pendingCommission = String.format(Locale.ROOT, "%.2f", pendingTotal / 1000000.0);
                    }
                }
            } catch (Exception ex) {
                log.debug("Affiliate overview query notice for user {}: {}", userId, ex.getMessage());
            }
        }

        double ratePct = ratePpm / 10000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("🤝 <b>KOL / 社区合伙人推广主控台</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("👤 <b>合伙人 UID:</b> <code>").append(userId).append("</code>\n");
        sb.append("💎 <b>返佣级别:</b> 超级合伙人 (<b>").append(String.format(Locale.ROOT, "%.1f%%", ratePct)).append("</b> 最高返佣)\n\n");

        sb.append("📋 <b>当前生效默认码:</b> <code>").append(inviteCode).append("</code>\n");
        sb.append("🔗 <b>Telegram 裂变链接 (一键直达):</b>\n");
        sb.append("<code>").append(tgDeepLink).append("</code>\n\n");

        sb.append("📊 <b>团队与业绩总览:</b>\n");
        sb.append("• <b>直推好友数:</b> <code>").append(directCount).append(" 人</code>\n");
        sb.append("• <b>裂变团队总数:</b> <code>").append(teamCount).append(" 人</code>\n");
        sb.append("• <b>累计获得佣金:</b> <code>").append(totalCommission).append(" USDT</code>\n");
        sb.append("• <b>待结算佣金:</b> <code>").append(pendingCommission).append(" USDT</code>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 <i>提示: 您可在此管理团队层级、查看单个成员业绩，以及调整下级分成比例。</i>\n");

        String shareText = "🚀 加入 Surprising 顶级交易平台！点击专属通道，享永久交易手续费优惠返现！";
        String shareUrl = "https://t.me/share/url?url=" + URLEncoder.encode(tgDeepLink, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(shareText, StandardCharsets.UTF_8);

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.url("🚀 一键转发到 TG 群组 / 好友", shareUrl)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 下级成员与分成管理", "kol:members"),
                        TelegramInlineKeyboardButton.callback("🌳 团队层级架构树", "kol:hierarchy")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🖼 生成专属二维码海报", "kol:poster"),
                        TelegramInlineKeyboardButton.callback("➕ 创建分比例邀请码", "kol:create_code")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🔄 刷新推广数据", "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup);
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup);
        }
    }

    /** 2. 团队层级架构树看板 */
    public void renderHierarchy(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);

        long directCount = 0;
        long teamCount = 0;
        int ratePpm = 300000;

        if (affiliateService != null) {
            try {
                Map<String, Object> overview = affiliateService.overview(userId, "");
                if (overview != null) {
                    Object countsObj = overview.get("counts");
                    if (countsObj instanceof Map<?, ?> countsMap) {
                        Object dVal = countsMap.get("direct");
                        if (dVal instanceof Number n) directCount = n.longValue();
                        Object tVal = countsMap.get("team");
                        if (tVal instanceof Number n) teamCount = n.longValue();
                    }
                    if (overview.get("rootRatePpm") instanceof Number rateNum) {
                        ratePpm = rateNum.intValue();
                    }
                }
            } catch (Exception ex) {
                log.debug("Affiliate overview error: {}", ex.getMessage());
            }
        }

        long indirectCount = Math.max(0, teamCount - directCount);
        double ratePct = ratePpm / 10000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("🌳 <b>合伙人团队层级架构树</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("👑 <b>顶层节点:</b> UID <code>").append(userId).append("</code> (基准返佣 ").append(String.format(Locale.ROOT, "%.1f%%", ratePct)).append(")\n");
        sb.append("│\n");
        sb.append("├── <b>Level 1 (一级直属下线):</b>\n");
        sb.append("│   • 成员数量: <code>").append(directCount).append(" 人</code>\n");
        sb.append("│   • 合作状态: <code>直接裂变 / 可自主设定分成比例</code>\n");
        sb.append("│\n");
        sb.append("└── <b>Level 2 (二级间接裂变):</b>\n");
        sb.append("    • 成员数量: <code>").append(indirectCount).append(" 人</code>\n");
        sb.append("    • 团队网络总计: <code>").append(teamCount).append(" 人</code>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 <i>提示: 点击【下级成员与分成管理】可对任意直属下级调整收益分成比例。</i>\n");

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 下级成员与分成管理", "kol:members"),
                        TelegramInlineKeyboardButton.callback("➕ 创建分比例邀请码", "kol:create_code")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 返回推广看板", "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup);
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup);
        }
    }

    /** 3. 团队直属成员列表与分成管理 */
    public void renderTeamMembers(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);

        StringBuilder sb = new StringBuilder();
        sb.append("👥 <b>受邀团队成员管理与分成设置</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        List<Map<String, Object>> membersList = new ArrayList<>();
        if (affiliateService != null) {
            try {
                var membersPage = affiliateService.members(userId, userId, 0L);
                if (membersPage != null && membersPage.items() != null) {
                    membersList = membersPage.items();
                }
            } catch (Exception ex) {
                log.debug("Team members query warning for user {}: {}", userId, ex.getMessage());
            }
        }

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder();

        if (membersList.isEmpty()) {
            sb.append("<i>您当前暂无下级受邀成员。</i>\n\n");
            sb.append("💡 点击下方<b>【生成专属二维码海报】</b>发送到 Telegram 群，立即开启推广！\n");
            markup.addRow(TelegramInlineKeyboardButton.callback("🖼 生成推广海报", "kol:poster"));
        } else {
            sb.append("📊 <b>直属受邀下级成员列表:</b>\n");
            sb.append("（点击对应按钮可<b>调整分成比例</b>或<b>查看详细战报</b>）：\n\n");
            int i = 1;
            for (Map<String, Object> m : membersList) {
                long mId = Long.parseLong(m.get("userId").toString());
                String joinDate = m.getOrDefault("acceptedAt", m.getOrDefault("createdAt", "近期")).toString();
                if (joinDate.length() > 10) joinDate = joinDate.substring(0, 10);

                int sharePpm = 0;
                if (m.get("sharePpm") instanceof Number n) sharePpm = n.intValue();
                double sharePct = sharePpm / 10000.0;
                String inviteCount = m.getOrDefault("directInviteCount", "0").toString();

                sb.append("<b>#").append(i++).append(" UID:</b> <code>").append(mId).append("</code>\n");
                sb.append("  • 绑定日期: <code>").append(joinDate).append("</code>\n");
                sb.append("  • 当前分成: <code>").append(String.format(Locale.ROOT, "%.1f%%", sharePct)).append("</code>");
                if (!"0".equals(inviteCount)) {
                    sb.append(" | 裂变二级: <code>").append(inviteCount).append("人</code>");
                }
                sb.append("\n\n");

                markup.addRow(
                        TelegramInlineKeyboardButton.callback("⚙️ 管理 UID " + mId + " (" + String.format(Locale.ROOT, "%.1f%%", sharePct) + ")", "kol:member:" + mId)
                );
            }
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("⏱ <i>更新时间: ").append(LocalTime.now().toString().substring(0, 8)).append("</i>");

        markup.addRow(
                TelegramInlineKeyboardButton.callback("🌳 查看层级架构", "kol:hierarchy"),
                TelegramInlineKeyboardButton.callback("➕ 创建分比例邀请码", "kol:create_code")
        );
        markup.addRow(
                TelegramInlineKeyboardButton.callback("📊 返回推广看板", "kol:dashboard"),
                TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
        );

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup.build());
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup.build());
        }
    }

    /** 4. 单个下级成员业绩详情与分成设置面板 */
    public void renderMemberDetail(long chatId, Long messageId, TelegramUser tgUser, long memberUserId) {
        long userId = authHandler.resolveUserId(tgUser);

        int currentSharePpm = 100000;
        String joinDate = "近期";
        String inviteCount = "0";
        String contributionStr = "0.00 USDT";

        if (affiliateService != null) {
            try {
                var membersPage = affiliateService.members(userId, userId, 0L);
                if (membersPage != null && membersPage.items() != null) {
                    for (Map<String, Object> m : membersPage.items()) {
                        if (memberUserId == Long.parseLong(m.get("userId").toString())) {
                            if (m.get("sharePpm") instanceof Number n) currentSharePpm = n.intValue();
                            if (m.get("acceptedAt") != null) joinDate = m.get("acceptedAt").toString();
                            else if (m.get("createdAt") != null) joinDate = m.get("createdAt").toString();
                            if (joinDate.length() > 10) joinDate = joinDate.substring(0, 10);
                            if (m.get("directInviteCount") != null) inviteCount = m.get("directInviteCount").toString();

                            Object cList = m.get("contributions");
                            if (cList instanceof List<?> list && !list.isEmpty()) {
                                long sumUnits = 0;
                                for (Object cItem : list) {
                                    if (cItem instanceof Map<?, ?> cMap && cMap.get("retainedUnits") != null) {
                                        try { sumUnits += Long.parseLong(cMap.get("retainedUnits").toString()); } catch (Exception ignored) {}
                                    }
                                }
                                if (sumUnits > 0) contributionStr = String.format(Locale.ROOT, "%.2f USDT", sumUnits / 1000000.0);
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("Member detail lookup error: {}", ex.getMessage());
            }
        }

        double currentPct = currentSharePpm / 10000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("👤 <b>下级成员专属管理面板</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🆔 <b>成员 UID:</b> <code>").append(memberUserId).append("</code>\n");
        sb.append("🔗 <b>关联关系:</b> 一级直接邀请下级\n");
        sb.append("📅 <b>绑定时间:</b> <code>").append(joinDate).append("</code>\n");
        sb.append("👥 <b>其裂变团队:</b> <code>").append(inviteCount).append(" 人</code>\n");
        sb.append("💰 <b>累计为我贡献返佣:</b> <code>").append(contributionStr).append("</code>\n");
        sb.append("⚖️ <b>当前分配给他的佣金分成:</b> <code>").append(String.format(Locale.ROOT, "%.1f%%", currentPct)).append("</code>\n\n");
        sb.append("<i>您可以在下方一键调整分配给该成员的佣金比例（从您的佣金中直接切分给他作为扶持激励）：</i>");

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("设为 5%", "kol:do_share:" + memberUserId + ":50000"),
                        TelegramInlineKeyboardButton.callback("设为 10%", "kol:do_share:" + memberUserId + ":100000"),
                        TelegramInlineKeyboardButton.callback("设为 20%", "kol:do_share:" + memberUserId + ":200000")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("设为 30%", "kol:do_share:" + memberUserId + ":300000"),
                        TelegramInlineKeyboardButton.callback("设为 50%", "kol:do_share:" + memberUserId + ":500000")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("✏️ 自定义输入比例", "kol:custom_share:" + memberUserId)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 返回成员列表", "kol:members"),
                        TelegramInlineKeyboardButton.callback("📊 推广看板", "kol:dashboard")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup);
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup);
        }
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

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
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
        int ppm = pct * 10000;
        executeSetShare(chatId, 0L, tgUser, memberUserId, ppm);
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

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
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
        String shareUrl = "https://t.me/share/url?url=" + URLEncoder.encode(deepLink, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(shareText, StandardCharsets.UTF_8);

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.url("🚀 一键分享到 TG 群组", shareUrl)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🖼 生成此码海报", "kol:poster"),
                        TelegramInlineKeyboardButton.callback("➕ 继续创建新码", "kol:create_code")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 返回推广看板", "kol:dashboard")
                )
                .build();

        if (messageId != null && messageId > 0) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    /** 8. 生成专属裂变海报与二维码图片 */
    public void renderReferralPoster(long chatId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        String inviteCode = getOrCreateInviteCode(userId);
        String deepLink = buildTelegramDeepLink(inviteCode);

        String caption = "🌟 <b>Surprising 专属 Telegram 推广裂变海报</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🔑 <b>您的专属邀请码:</b> <code>" + inviteCode + "</code>\n" +
                "📲 <b>直达链接:</b> <code>" + deepLink + "</code>\n\n" +
                "💡 <i>保存图片并分享给好友，好友使用 Telegram 扫码即可一键注册并自动绑定为您的高级合伙人成员！</i>";

        String shareText = "🚀 加入 Surprising 顶级交易平台！扫码立享专属交易手续费优惠！";
        String shareUrl = "https://t.me/share/url?url=" + URLEncoder.encode(deepLink, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(shareText, StandardCharsets.UTF_8);

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.url("🚀 立即转发到 TG", shareUrl)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 下级成员与分成管理", "kol:members"),
                        TelegramInlineKeyboardButton.callback("📊 推广看板", "kol:dashboard")
                )
                .build();

        try {
            byte[] qrBytes = qrCodeService.generateQrCodePng(deepLink, 320, 320);
            botClient.sendPhoto(chatId, qrBytes, "kol_invite_" + inviteCode + ".png", caption, markup);
        } catch (Exception ex) {
            log.error("Failed to generate KOL poster: {}", ex.getMessage());
            botClient.sendMessage(chatId, caption, markup);
        }
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
