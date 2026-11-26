package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Telegram KOL 看板、层级树、团队成员列表、详情与海报的只读渲染器。 */
@Slf4j
final class TelegramAffiliateViewRenderer {

    private final AffiliateService affiliateService;
    private final TelegramAuthHandler authHandler;
    private final TelegramQrCodeService qrCodeService;
    private final TelegramBotClient botClient;

    TelegramAffiliateViewRenderer(AffiliateService affiliateService,
                                  TelegramAuthHandler authHandler,
                                  TelegramQrCodeService qrCodeService,
                                  TelegramBotClient botClient) {
        this.affiliateService = affiliateService;
        this.authHandler = authHandler;
        this.qrCodeService = qrCodeService;
        this.botClient = botClient;
    }

    void renderKolDashboard(long chatId, Long messageId, TelegramUser tgUser,
                            String inviteCode, String tgDeepLink) {
        long userId = authHandler.resolveUserId(tgUser);
        OverviewSummary summary = loadOverview(userId, true);
        double ratePct = summary.ratePpm() / 10000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("🤝 <b>KOL / 社区合伙人推广主控台</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("👤 <b>合伙人 UID:</b> <code>").append(userId).append("</code>\n");
        sb.append("💎 <b>返佣级别:</b> 超级合伙人 (<b>").append(String.format(Locale.ROOT, "%.1f%%", ratePct)).append("</b> 最高返佣)\n\n");

        sb.append("📋 <b>当前生效默认码:</b> <code>").append(inviteCode).append("</code>\n");
        sb.append("🔗 <b>Telegram 裂变链接 (一键直达):</b>\n");
        sb.append("<code>").append(tgDeepLink).append("</code>\n\n");

        sb.append("📊 <b>团队与业绩总览:</b>\n");
        sb.append("• <b>直推好友数:</b> <code>").append(summary.directCount()).append(" 人</code>\n");
        sb.append("• <b>裂变团队总数:</b> <code>").append(summary.teamCount()).append(" 人</code>\n");
        sb.append("• <b>累计获得佣金:</b> <code>").append(summary.totalCommission()).append(" USDT</code>\n");
        sb.append("• <b>待结算佣金:</b> <code>").append(summary.pendingCommission()).append(" USDT</code>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 <i>提示: 您可在此管理团队层级、查看单个成员业绩，以及调整下级分成比例。</i>\n");

        String shareText = "🚀 加入 Surprising 顶级交易平台！点击专属通道，享永久交易手续费优惠返现！";
        String shareUrl = shareUrl(tgDeepLink, shareText);

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.url("🚀 一键转发到 TG 群组 / 好友", shareUrl))
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

        sendOrEdit(chatId, messageId, sb.toString(), markup);
    }

    void renderHierarchy(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        OverviewSummary summary = loadOverview(userId, false);
        long indirectCount = Math.max(0, summary.teamCount() - summary.directCount());
        double ratePct = summary.ratePpm() / 10000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("🌳 <b>合伙人团队层级架构树</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("👑 <b>顶层节点:</b> UID <code>").append(userId).append("</code> (基准返佣 ")
                .append(String.format(Locale.ROOT, "%.1f%%", ratePct)).append(")\n");
        sb.append("│\n");
        sb.append("├── <b>Level 1 (一级直属下线):</b>\n");
        sb.append("│   • 成员数量: <code>").append(summary.directCount()).append(" 人</code>\n");
        sb.append("│   • 合作状态: <code>直接裂变 / 可自主设定分成比例</code>\n");
        sb.append("│\n");
        sb.append("└── <b>Level 2 (二级间接裂变):</b>\n");
        sb.append("    • 成员数量: <code>").append(indirectCount).append(" 人</code>\n");
        sb.append("    • 团队网络总计: <code>").append(summary.teamCount()).append(" 人</code>\n");
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

        sendOrEdit(chatId, messageId, sb.toString(), markup);
    }

    void renderTeamMembers(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);

        StringBuilder sb = new StringBuilder();
        sb.append("👥 <b>受邀团队成员管理与分成设置</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        List<Map<String, Object>> membersList = loadMembers(userId);
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

                int sharePpm = m.get("sharePpm") instanceof Number n ? n.intValue() : 0;
                double sharePct = sharePpm / 10000.0;
                String inviteCount = m.getOrDefault("directInviteCount", "0").toString();

                sb.append("<b>#").append(i++).append(" UID:</b> <code>").append(mId).append("</code>\n");
                sb.append("  • 绑定日期: <code>").append(joinDate).append("</code>\n");
                sb.append("  • 当前分成: <code>").append(String.format(Locale.ROOT, "%.1f%%", sharePct)).append("</code>");
                if (!"0".equals(inviteCount)) {
                    sb.append(" | 裂变二级: <code>").append(inviteCount).append("人</code>");
                }
                sb.append("\n\n");

                markup.addRow(TelegramInlineKeyboardButton.callback(
                        "⚙️ 管理 UID " + mId + " (" + String.format(Locale.ROOT, "%.1f%%", sharePct) + ")",
                        "kol:member:" + mId));
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

        sendOrEdit(chatId, messageId, sb.toString(), markup.build());
    }

    void renderMemberDetail(long chatId, Long messageId, TelegramUser tgUser, long memberUserId) {
        long userId = authHandler.resolveUserId(tgUser);

        int currentSharePpm = 100000;
        String joinDate = "近期";
        String inviteCount = "0";
        String contributionStr = "0.00 USDT";

        for (Map<String, Object> m : loadMembers(userId)) {
            if (memberUserId == Long.parseLong(m.get("userId").toString())) {
                if (m.get("sharePpm") instanceof Number n) currentSharePpm = n.intValue();
                if (m.get("acceptedAt") != null) joinDate = m.get("acceptedAt").toString();
                else if (m.get("createdAt") != null) joinDate = m.get("createdAt").toString();
                if (joinDate.length() > 10) joinDate = joinDate.substring(0, 10);
                if (m.get("directInviteCount") != null) inviteCount = m.get("directInviteCount").toString();

                if (m.get("contributions") instanceof List<?> list && !list.isEmpty()) {
                    long sumUnits = 0;
                    for (Object cItem : list) {
                        if (cItem instanceof Map<?, ?> cMap) {
                            sumUnits += parseUnits(cMap.get("retainedUnits"), "retainedUnits", userId);
                        }
                    }
                    if (sumUnits > 0) {
                        contributionStr = String.format(Locale.ROOT, "%.2f USDT", sumUnits / 1000000.0);
                    }
                }
                break;
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
                .addRow(TelegramInlineKeyboardButton.callback("✏️ 自定义输入比例", "kol:custom_share:" + memberUserId))
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 返回成员列表", "kol:members"),
                        TelegramInlineKeyboardButton.callback("📊 推广看板", "kol:dashboard")
                )
                .build();

        sendOrEdit(chatId, messageId, sb.toString(), markup);
    }

    void renderReferralPoster(long chatId, long userId, String inviteCode,
                              String registrationLink, String deepLink) {
        OverviewSummary summary = loadOverview(userId, true);
        double rebateRatePercent = summary.ratePpm() / 10000.0;
        String rateLabel = rebateRatePercent == Math.floor(rebateRatePercent)
                ? String.format(Locale.ROOT, "%.0f", rebateRatePercent)
                : String.format(Locale.ROOT, "%.1f", rebateRatePercent);

        String caption = "🌟 <b>AETERN 专属邀请函海报</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🔑 <b>专属短邀请码:</b> <code>" + inviteCode + "</code>\n" +
                "💎 <b>最高返佣比例:</b> <code>" + rateLabel + "%</code>\n" +
                "🌐 <b>专属注册链接:</b> <code>" + registrationLink + "</code>\n" +
                "📲 <b>TG 直达链接:</b> <code>" + deepLink + "</code>\n\n" +
                "💡 <i>保存海报并分享给好友，扫码即可直达专属邀请注册页并自动绑定您的短邀请码！</i>";

        String shareText = "🚀 受邀开启 AETERN 机构级数字资产交易体验！点击专属通道，最高享 " + rateLabel + "% 手续费返佣！";
        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.url("🚀 立即转发到 TG", shareUrl(deepLink, shareText)))
                .addRow(
                        TelegramInlineKeyboardButton.callback("👥 下级成员与分成管理", "kol:members"),
                        TelegramInlineKeyboardButton.callback("📊 推广看板", "kol:dashboard")
                )
                .build();

        try {
            byte[] posterBytes = qrCodeService.generateInvitationPosterPng(
                    inviteCode, registrationLink, rebateRatePercent, userId);
            botClient.sendPhoto(chatId, posterBytes, "aetern-invite-" + inviteCode + ".png", caption, markup);
        } catch (Exception ex) {
            log.error("Failed to generate KOL invitation poster: {}", ex.getMessage());
            botClient.sendMessage(chatId, caption, markup);
        }
    }

    void sendOrEdit(long chatId, Long messageId, String text, TelegramInlineKeyboardMarkup markup) {
        if (messageId != null && messageId > 0) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    static String shareUrl(String deepLink, String shareText) {
        return "https://t.me/share/url?url=" + URLEncoder.encode(deepLink, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(shareText, StandardCharsets.UTF_8);
    }

    private OverviewSummary loadOverview(long userId, boolean registerFirst) {
        long directCount = 0;
        long teamCount = 0;
        String totalCommission = "0.00";
        String pendingCommission = "0.00";
        int ratePpm = 300000;

        if (affiliateService != null) {
            try {
                if (registerFirst) {
                    affiliateService.register(userId, null);
                }
                Map<String, Object> overview = affiliateService.overview(userId, "");
                if (overview != null) {
                    if (overview.get("counts") instanceof Map<?, ?> countsMap) {
                        if (countsMap.get("direct") instanceof Number n) directCount = n.longValue();
                        if (countsMap.get("team") instanceof Number n) teamCount = n.longValue();
                    }
                    if (overview.get("rootRatePpm") instanceof Number rateNum) {
                        ratePpm = rateNum.intValue();
                    }
                    if (overview.get("balances") instanceof List<?> balancesList) {
                        long paidTotal = 0;
                        long pendingTotal = 0;
                        for (Object bItem : balancesList) {
                            if (bItem instanceof Map<?, ?> bMap) {
                                paidTotal += parseUnits(bMap.get("paidUnits"), "paidUnits", userId);
                                pendingTotal += parseUnits(bMap.get("pendingUnits"), "pendingUnits", userId);
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
        return new OverviewSummary(directCount, teamCount, ratePpm, totalCommission, pendingCommission);
    }

    private List<Map<String, Object>> loadMembers(long userId) {
        if (affiliateService == null) {
            return List.of();
        }
        try {
            var membersPage = affiliateService.members(userId, userId, 0L);
            if (membersPage != null && membersPage.items() != null) {
                return membersPage.items();
            }
        } catch (Exception ex) {
            log.debug("Team members query warning for user {}: {}", userId, ex.getMessage());
        }
        return new ArrayList<>();
    }

    private long parseUnits(Object raw, String field, long userId) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException ex) {
            log.warn("Invalid affiliate {} value '{}' for user {}: {}", field, raw, userId, ex.getMessage());
            return 0L;
        }
    }

    private record OverviewSummary(long directCount, long teamCount, int ratePpm,
                                   String totalCommission, String pendingCommission) {
    }
}
