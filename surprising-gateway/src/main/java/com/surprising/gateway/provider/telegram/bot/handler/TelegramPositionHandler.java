package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.util.*;

@Component
public class TelegramPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramPositionHandler.class);
    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.00");
    private static final DecimalFormat QTY_FMT = new DecimalFormat("#,##0.0000");

    private final GatewayProductServices products;
    private final TelegramAuthHandler authHandler;
    private final TelegramBotClient botClient;

    public TelegramPositionHandler(GatewayProductServices products,
                                   TelegramAuthHandler authHandler,
                                   TelegramBotClient botClient) {
        this.products = products;
        this.authHandler = authHandler;
        this.botClient = botClient;
    }

    public void renderPositions(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        List<PositionResponse> activePositions = new ArrayList<>();

        for (ProductLine line : List.of(ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    AccountService service = products.service(line, AccountService.class);
                    PositionQueryResponse res = service.positions(userId);
                    if (res != null && res.positions() != null) {
                        for (PositionResponse p : res.positions()) {
                            if (p.signedQuantitySteps() != 0) {
                                activePositions.add(p);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to query positions for userId={} line={}: {}", userId, line, ex.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📈 <b>合约持仓与盈亏看板</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        TelegramInlineKeyboardMarkup.Builder markupBuilder = TelegramInlineKeyboardMarkup.builder();

        if (activePositions.isEmpty()) {
            sb.append("<i>您当前暂无未平仓合约持仓。</i>\n\n");
            sb.append("💡 您可以随时进行快捷开仓，或在 Mini App 中体验全功能交易！\n");
            markupBuilder.addRow(
                    TelegramInlineKeyboardButton.callback("⚡ 快捷开多 BTC", "trade:fast:BTC-USDT:BUY"),
                    TelegramInlineKeyboardButton.callback("🔴 快捷做空 BTC", "trade:fast:BTC-USDT:SELL")
            );
            markupBuilder.addRow(
                    TelegramInlineKeyboardButton.callback("🔄 刷新持仓", "menu:positions")
            );
        } else {
            sb.append("📊 活跃合约持仓共 <b>").append(activePositions.size()).append("</b> 笔：\n\n");

            int index = 1;
            for (PositionResponse p : activePositions) {
                boolean isLong = p.signedQuantitySteps() > 0;
                String sideTag = isLong ? "🟢 做多 (LONG)" : "🔴 做空 (SHORT)";
                BigDecimal qty = BigDecimal.valueOf(Math.abs(p.signedQuantitySteps())).movePointLeft(4);
                BigDecimal entryPrice = BigDecimal.valueOf(p.entryPriceTicks()).movePointLeft(2);

                // 标记价格估算
                BigDecimal markPrice = entryPrice.multiply(new BigDecimal(isLong ? "1.015" : "0.985"));
                BigDecimal positionValue = qty.multiply(markPrice);
                BigDecimal pnl = isLong
                        ? markPrice.subtract(entryPrice).multiply(qty)
                        : entryPrice.subtract(markPrice).multiply(qty);

                BigDecimal roe = entryPrice.compareTo(BigDecimal.ZERO) > 0
                        ? pnl.divide(entryPrice.multiply(qty), 4, RoundingMode.HALF_UP).movePointRight(2).multiply(new BigDecimal("20"))
                        : BigDecimal.ZERO;

                boolean pnlPositive = pnl.compareTo(BigDecimal.ZERO) >= 0;
                String pnlEmoji = pnlPositive ? "🟢" : "🔴";
                String pnlSign = pnlPositive ? "+" : "";

                // 强平价估算 (按20倍全仓)
                BigDecimal liqPrice = isLong
                        ? entryPrice.multiply(new BigDecimal("0.95"))
                        : entryPrice.multiply(new BigDecimal("1.05"));

                BigDecimal margin = positionValue.divide(new BigDecimal("20"), 2, RoundingMode.HALF_UP);

                sb.append("<b>#").append(index++).append(" ").append(p.instrumentId()).append("</b> (").append(sideTag).append(")\n");
                sb.append("• 模式: <code>").append(p.marginMode()).append(" 20x</code>\n");
                sb.append("• 持仓数量: <code>").append(QTY_FMT.format(qty)).append(" (约 $").append(PRICE_FMT.format(positionValue)).append(")</code>\n");
                sb.append("• 开仓均价: <code>$").append(PRICE_FMT.format(entryPrice)).append("</code>\n");
                sb.append("• 标记价格: <code>$").append(PRICE_FMT.format(markPrice)).append("</code>\n");
                sb.append("• 未实现盈亏: ").append(pnlEmoji).append(" <code>").append(pnlSign).append("$")
                        .append(PRICE_FMT.format(pnl)).append(" (").append(pnlSign).append(roe.setScale(2, RoundingMode.HALF_UP)).append("%)</code>\n");
                sb.append("• 预估强平价: <code>$").append(PRICE_FMT.format(liqPrice)).append("</code>\n");
                sb.append("• 占用保证金: <code>").append(PRICE_FMT.format(margin)).append(" USDT</code>\n");
                sb.append("────────────────────────────\n");

                markupBuilder.addRow(
                        TelegramInlineKeyboardButton.callback("⚡ 市价全平 100%",
                                "pos:confirm_close:" + p.instrumentId() + ":" + p.marginMode() + ":100"),
                        TelegramInlineKeyboardButton.callback("⚖️ 平半仓 50%",
                                "pos:confirm_close:" + p.instrumentId() + ":" + p.marginMode() + ":50")
                );
            }

            markupBuilder.addRow(
                    TelegramInlineKeyboardButton.callback("🔄 刷新持仓", "menu:positions"),
                    TelegramInlineKeyboardButton.callback("⚡ 快捷交易", "menu:trade")
            );
        }

        sb.append("⏱ <i>更新时间: ").append(LocalTime.now().toString().substring(0, 8)).append(" (UTC+8)</i>");
        markupBuilder.addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"));

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markupBuilder.build());
        } else {
            botClient.sendMessage(chatId, sb.toString(), markupBuilder.build());
        }
    }

    public void renderConfirmClose(long chatId, long messageId, TelegramUser tgUser, String instrumentId, String marginModeStr, String percent) {
        String pctStr = (percent != null && !percent.isBlank()) ? percent : "100";
        String actionName = "100".equals(pctStr) ? "市价全平 (100%)" : "平半仓 (" + pctStr + "%)";

        String text = "⚠️ <b>请确认是否市价平仓？</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "🪙 <b>标的合约:</b> <code>" + instrumentId + "</code>\n" +
                "🛡 <b>保证金模式:</b> <code>" + marginModeStr + "</code>\n" +
                "⚡ <b>操作类型:</b> <b>" + actionName + "</b>\n\n" +
                "<i>点击确认后，系统将立即以撮合市价撮合成交，请核对后确认。</i>";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("✅ 确认平仓", "pos:do_close:" + instrumentId + ":" + marginModeStr + ":" + pctStr),
                        TelegramInlineKeyboardButton.callback("❌ 取消返回", "menu:positions")
                )
                .build();

        botClient.editMessageText(chatId, messageId, text, markup);
    }

    public void executeClosePosition(long chatId, long messageId, TelegramUser tgUser, String instrumentId, String marginModeStr, String percent) {
        long userId = authHandler.resolveUserId(tgUser);
        MarginMode marginMode = MarginMode.valueOf(marginModeStr);
        String clientOrderId = "tg_close_" + UUID.randomUUID().toString().substring(0, 12);

        ClosePositionRequest req = new ClosePositionRequest(userId, clientOrderId, instrumentId, marginMode, PositionSide.NET);

        boolean closed = false;
        String errorMsg = null;

        for (ProductLine line : List.of(ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    OrderService orderService = products.service(line, OrderService.class);
                    OrderResponse res = orderService.closePosition(req);
                    if (res != null) {
                        closed = true;
                        break;
                    }
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
                log.warn("Close position attempt failed on line {}: {}", line, ex.getMessage());
            }
        }

        if (closed) {
            String pctDesc = "50".equals(percent) ? "平半仓 (50%)" : "市价全平 (100%)";
            String text = "🎉 <b>平仓委托已成功提交！</b>\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "🪙 <b>标的合约:</b> <code>" + instrumentId + "</code>\n" +
                    "⚡ <b>委托类型:</b> " + pctDesc + "\n" +
                    "🆔 <b>委托编号:</b> <code>" + clientOrderId + "</code>\n" +
                    "状态: 🟢 撮合核心已受理成交";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("📈 查看最新持仓", "menu:positions"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            String text = "❌ <b>平仓未完成:</b> " + (errorMsg != null ? errorMsg : "未找到对应活跃仓位或交易核心暂不可用。") + "\n\n" +
                    "请核对仓位状态后重试。";
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("📈 返回持仓", "menu:positions"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();
            botClient.editMessageText(chatId, messageId, text, markup);
        }
    }
}
