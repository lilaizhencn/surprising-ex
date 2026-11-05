package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.product.api.ProductLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class TelegramAssetHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramAssetHandler.class);

    private final GatewayProductServices products;
    private final TelegramAuthHandler authHandler;
    private final TelegramBotClient botClient;

    public TelegramAssetHandler(GatewayProductServices products,
                                TelegramAuthHandler authHandler,
                                TelegramBotClient botClient) {
        this.products = products;
        this.authHandler = authHandler;
        this.botClient = botClient;
    }

    public void renderAssets(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);

        Map<ProductLine, List<BalanceResponse>> productBalances = new LinkedHashMap<>();
        BigDecimal totalEstUsdt = BigDecimal.ZERO;

        // 收集各产品线的资产
        for (ProductLine line : List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    AccountService service = products.service(line, AccountService.class);
                    BalanceQueryResponse res = service.balances(userId);
                    if (res != null && res.balances() != null) {
                        productBalances.put(line, res.balances());
                        for (BalanceResponse b : res.balances()) {
                            // 简化估值：USDT/USDC 1:1，BTC 估值按 95000，其他暂按 1
                            BigDecimal avail = BigDecimal.valueOf(b.availableUnits()).movePointLeft(8);
                            BigDecimal locked = BigDecimal.valueOf(b.lockedUnits()).movePointLeft(8);
                            BigDecimal sum = avail.add(locked);
                            if ("USDT".equalsIgnoreCase(b.asset()) || "USD".equalsIgnoreCase(b.asset())) {
                                totalEstUsdt = totalEstUsdt.add(sum);
                            } else if ("BTC".equalsIgnoreCase(b.asset())) {
                                totalEstUsdt = totalEstUsdt.add(sum.multiply(new BigDecimal("95000")));
                            } else if ("ETH".equalsIgnoreCase(b.asset())) {
                                totalEstUsdt = totalEstUsdt.add(sum.multiply(new BigDecimal("3200")));
                            } else {
                                totalEstUsdt = totalEstUsdt.add(sum);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to load balances for userId={} line={}: {}", userId, line, ex.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("💰 <b>我的账户资产总览</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📊 <b>总资产估值:</b> <code>").append(totalEstUsdt.setScale(2, RoundingMode.HALF_UP)).append(" USDT</code>\n");
        sb.append("👤 <b>UID:</b> <code>").append(userId).append("</code>\n\n");

        // 现货账户
        appendAccountBlock(sb, "💳 现货账户 (Spot)", productBalances.get(ProductLine.SPOT));
        // U本位合约
        appendAccountBlock(sb, "📈 U本位永续 (USDT-M)", productBalances.get(ProductLine.LINEAR_PERPETUAL));
        // 币本位合约
        appendAccountBlock(sb, "🪙 币本位永续 (Coin-M)", productBalances.get(ProductLine.INVERSE_PERPETUAL));

        sb.append("⏱ <i>更新时间: ").append(java.time.LocalTime.now().toString().substring(0, 8)).append("</i>");

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("🔄 刷新资产", "menu:assets"),
                        TelegramInlineKeyboardButton.callback("🔄 资金划转", "menu:transfer")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("💳 充值", "menu:deposit"),
                        TelegramInlineKeyboardButton.callback("💸 提币", "menu:withdraw")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup);
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup);
        }
    }

    private void appendAccountBlock(StringBuilder sb, String title, List<BalanceResponse> list) {
        sb.append("<b>").append(title).append("</b>\n");
        if (list == null || list.isEmpty()) {
            sb.append("  <i>暂无资产</i>\n\n");
            return;
        }
        boolean hasNonZero = false;
        for (BalanceResponse b : list) {
            BigDecimal avail = BigDecimal.valueOf(b.availableUnits()).movePointLeft(8);
            BigDecimal locked = BigDecimal.valueOf(b.lockedUnits()).movePointLeft(8);
            if (avail.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                hasNonZero = true;
                sb.append("  • <b>").append(b.asset()).append("</b>: 可用 <code>")
                        .append(avail.stripTrailingZeros().toPlainString()).append("</code>");
                if (locked.compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" | 冻结 <code>").append(locked.stripTrailingZeros().toPlainString()).append("</code>");
                }
                sb.append("\n");
            }
        }
        if (!hasNonZero) {
            sb.append("  <i>暂无持仓资产 (余额为 0)</i>\n");
        }
        sb.append("\n");
    }
}
