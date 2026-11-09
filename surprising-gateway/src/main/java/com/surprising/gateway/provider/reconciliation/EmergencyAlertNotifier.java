package com.surprising.gateway.provider.reconciliation;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class EmergencyAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmergencyAlertNotifier.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"));

    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;

    public EmergencyAlertNotifier(TelegramBotClient botClient, TelegramBotProperties properties) {
        this.botClient = botClient;
        this.properties = properties;
    }

    public void broadcastReconciliationAlert(ReconciliationResult result) {
        String timeStr = TIME_FMT.format(result.executionTime());
        StringBuilder sb = new StringBuilder();
        sb.append("🚨 <b>【CRITICAL 资金对账异常 - 紧急停机提现】</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("⚠️ <b>触发状态:</b> 资金轧差不平，已<b>自动触发提现熔断停机</b>！\n");
        sb.append("⏱ <b>核对时间:</b> <code>").append(timeStr).append(" UTC</code>\n");
        sb.append("👥 <b>检查账户数:</b> <code>").append(result.totalAccountsChecked()).append("</code>\n");
        sb.append("❌ <b>负余额账户数:</b> <code>").append(result.negativeBalanceCount()).append("</code>\n");
        sb.append("💸 <b>在途提现挂起数:</b> <code>").append(result.pendingWithdrawalsCount()).append("</code>\n");
        sb.append("💰 <b>轧差偏差金额:</b> <code>").append(result.totalDiscrepancyUsdt()).append(" USDT</code>\n\n");

        if (result.discrepancies() != null && !result.discrepancies().isEmpty()) {
            sb.append("🔍 <b>异常快照详情:</b>\n");
            for (int i = 0; i < Math.min(result.discrepancies().size(), 5); i++) {
                sb.append("• ").append(result.discrepancies().get(i)).append("\n");
            }
        }
        sb.append("\n<b>处置动作:</b> 全平台 API、Web 与 Telegram 提现通道已强制关闭，请值班技术与风控人员立即登录核查！");

        sendTelegramAlert(sb.toString());
    }

    public void broadcastWithdrawalStoppedAlert(String reason, Instant stoppedAt) {
        String timeStr = TIME_FMT.format(stoppedAt != null ? stoppedAt : Instant.now());
        String text = "🚨 <b>【提现通道紧急熔断停机通知】</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "⚠️ <b>原因:</b> " + reason + "\n" +
                "⏱ <b>停机时间:</b> <code>" + timeStr + " UTC</code>\n" +
                "🛡 <b>防护动作:</b> 所有新提现请求已被全局阻断，防止资金损失风险扩大。";
        sendTelegramAlert(text);
    }

    public void broadcastWithdrawalResumed(String authorizedBy) {
        String timeStr = TIME_FMT.format(Instant.now());
        String text = "✅ <b>【提现通道恢复通知】</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "👤 <b>确认操作人:</b> " + authorizedBy + "\n" +
                "⏱ <b>恢复时间:</b> <code>" + timeStr + " UTC</code>\n" +
                "🟢 <b>系统状态:</b> 账目核查无误，提现熔断器已复位，充提币通道恢复正常。";
        sendTelegramAlert(text);
    }

    private void sendTelegramAlert(String htmlMessage) {
        log.error("[EMERGENCY BROADCAST]\n{}", htmlMessage);
        Long alertChatId = properties.getAlertChatId();
        if (alertChatId != null && alertChatId != 0 && botClient.isConfigured()) {
            try {
                boolean sent = botClient.sendMessage(alertChatId, htmlMessage, null);
                if (sent) {
                    log.info("Emergency alert successfully sent to Telegram alertChatId={}", alertChatId);
                } else {
                    log.warn("Failed to deliver emergency alert to Telegram alertChatId={}", alertChatId);
                }
            } catch (Exception ex) {
                log.error("Exception sending emergency Telegram alert: {}", ex.getMessage(), ex);
            }
        }
    }
}
