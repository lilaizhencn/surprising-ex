package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class TelegramMarketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramMarketHandler.class);
    private static final String PRICE_PATTERN = "#,##0.00";
    private static final String QTY_PATTERN = "#,##0.000";

    private final MatchingMarketDataService marketDataService;
    private final TelegramBotClient botClient;

    @Autowired
    public TelegramMarketHandler(@Autowired(required = false) MatchingMarketDataService marketDataService,
                                 TelegramBotClient botClient) {
        this.marketDataService = marketDataService;
        this.botClient = botClient;
    }

    /** 渲染主流综合行情看板 */
    public void renderMarketOverview(long chatId, Long messageId, String symbol) {
        String targetSymbol = normalizeSymbol(symbol);
        MarketSnapshot snap = fetchMarketSnapshot(targetSymbol);
        DecimalFormat priceFmt = new DecimalFormat(PRICE_PATTERN);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>").append(targetSymbol).append(" 市场行情看板</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💵 <b>参考中间价:</b> <code>").append(formatUsd(priceFmt, snap.lastPrice)).append("</code>\n");
        sb.append("• <b>盘口买一/卖一:</b> <code>")
                .append(formatUsd(priceFmt, snap.bid1)).append(" / ")
                .append(formatUsd(priceFmt, snap.ask1)).append("</code>\n");
        if (snap.bids.isEmpty() && snap.asks.isEmpty()) {
            sb.append("• <b>盘口状态:</b> <i>暂无实时盘口数据</i>\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("⏱ <i>更新时间: ").append(LocalTime.now().toString().substring(0, 8)).append(" (UTC+8)</i>\n");

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("BTC", "market:view:BTC-USDT"),
                        TelegramInlineKeyboardButton.callback("ETH", "market:view:ETH-USDT"),
                        TelegramInlineKeyboardButton.callback("SOL", "market:view:SOL-USDT"),
                        TelegramInlineKeyboardButton.callback("DOGE", "market:view:DOGE-USDT"),
                        TelegramInlineKeyboardButton.callback("XRP", "market:view:XRP-USDT")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📖 查看买卖5档盘口", "market:depth:" + targetSymbol),
                        TelegramInlineKeyboardButton.callback("🔄 刷新行情", "market:view:" + targetSymbol)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 快捷买入/做多", "trade:fast:" + targetSymbol + ":BUY"),
                        TelegramInlineKeyboardButton.callback("🔴 快捷卖出/做空", "trade:fast:" + targetSymbol + ":SELL")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        sendOrEdit(chatId, messageId, sb.toString(), markup);
    }

    /** 渲染专业买卖 5 档深度盘口 */
    public void renderOrderBookDepth(long chatId, Long messageId, String symbol) {
        String targetSymbol = normalizeSymbol(symbol);
        MarketSnapshot snap = fetchMarketSnapshot(targetSymbol);
        DecimalFormat priceFmt = new DecimalFormat(PRICE_PATTERN);
        DecimalFormat qtyFmt = new DecimalFormat(QTY_PATTERN);

        StringBuilder sb = new StringBuilder();
        sb.append("📖 <b>").append(targetSymbol).append(" L2 盘口买卖 5 档深度</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        if (snap.bids.isEmpty() && snap.asks.isEmpty()) {
            sb.append("<i>当前暂无实时盘口深度数据，请稍后刷新。</i>\n");
        } else {
            sb.append("<pre>\n");
            sb.append("档位   价格 (USD)     数量 (").append(baseAsset(targetSymbol)).append(")  深度\n");
            sb.append("───────────────────────────────────────\n");
            for (int i = snap.asks.size() - 1; i >= 0; i--) {
                DepthLevel lvl = snap.asks.get(i);
                sb.append(String.format("卖%d  %10s  %10s  %s\n",
                        (i + 1), priceFmt.format(lvl.price), qtyFmt.format(lvl.quantity), renderBar(lvl.quantity, snap.maxDepthQty)));
            }
            BigDecimal spread = (snap.ask1 != null && snap.bid1 != null) ? snap.ask1.subtract(snap.bid1) : null;
            sb.append("───────────────────────────────────────\n");
            sb.append(String.format(" 参考价: %s  价差: %s\n",
                    formatUsd(priceFmt, snap.lastPrice), spread == null ? "—" : priceFmt.format(spread)));
            sb.append("───────────────────────────────────────\n");
            for (int i = 0; i < snap.bids.size(); i++) {
                DepthLevel lvl = snap.bids.get(i);
                sb.append(String.format("买%d  %10s  %10s  %s\n",
                        (i + 1), priceFmt.format(lvl.price), qtyFmt.format(lvl.quantity), renderBar(lvl.quantity, snap.maxDepthQty)));
            }
            sb.append("</pre>\n");
            sb.append("💡 <i>提示: 绿色代表买方挂单厚度，红色代表卖方挂单厚度</i>\n");
        }

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback("📊 切换行情概览", "market:view:" + targetSymbol),
                        TelegramInlineKeyboardButton.callback("🔄 刷新盘口", "market:depth:" + targetSymbol)
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 闪电买多", "trade:fast:" + targetSymbol + ":BUY"),
                        TelegramInlineKeyboardButton.callback("🔴 闪电做空", "trade:fast:" + targetSymbol + ":SELL")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        sendOrEdit(chatId, messageId, sb.toString(), markup);
    }

    private void sendOrEdit(long chatId, Long messageId, String text, TelegramInlineKeyboardMarkup markup) {
        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    private static String formatUsd(DecimalFormat priceFmt, BigDecimal value) {
        return value == null ? "—" : "$" + priceFmt.format(value);
    }

    private String renderBar(BigDecimal qty, BigDecimal max) {
        if (max == null || max.compareTo(BigDecimal.ZERO) <= 0) return "█";
        double ratio = qty.divide(max, 2, RoundingMode.HALF_UP).doubleValue();
        int blocks = Math.max(1, (int) Math.round(ratio * 6));
        return "█".repeat(Math.min(6, blocks));
    }

    private String normalizeSymbol(String raw) {
        if (raw == null || raw.isBlank()) return "BTC-USDT";
        String s = raw.trim().toUpperCase().replace("/", "-");
        return s.contains("-") ? s : s + "-USDT";
    }

    private String baseAsset(String symbol) {
        int idx = symbol.indexOf('-');
        return idx > 0 ? symbol.substring(0, idx) : symbol;
    }

    private record DepthLevel(BigDecimal price, BigDecimal quantity) {}

    private static class MarketSnapshot {
        BigDecimal lastPrice;
        BigDecimal bid1;
        BigDecimal ask1;
        List<DepthLevel> bids = new ArrayList<>();
        List<DepthLevel> asks = new ArrayList<>();
        BigDecimal maxDepthQty = BigDecimal.ZERO;
    }

    private MarketSnapshot fetchMarketSnapshot(String symbol) {
        MarketSnapshot snap = new MarketSnapshot();
        if (marketDataService == null) {
            return snap;
        }
        try {
            OrderBookSnapshotResponse book = marketDataService.orderBookSnapshot(symbol, 5);
            if (book == null) {
                return snap;
            }
            appendLevels(book.bids(), snap.bids, snap);
            appendLevels(book.asks(), snap.asks, snap);
            snap.bid1 = snap.bids.isEmpty() ? null : snap.bids.getFirst().price;
            snap.ask1 = snap.asks.isEmpty() ? null : snap.asks.getFirst().price;
            if (snap.bid1 != null && snap.ask1 != null) {
                snap.lastPrice = snap.bid1.add(snap.ask1).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
            } else {
                snap.lastPrice = snap.bid1 != null ? snap.bid1 : snap.ask1;
            }
        } catch (Exception ex) {
            log.debug("Live book not available for {}: {}", symbol, ex.getMessage());
        }
        return snap;
    }

    private static void appendLevels(List<OrderBookLevel> source, List<DepthLevel> target, MarketSnapshot snap) {
        if (source == null) {
            return;
        }
        for (OrderBookLevel level : source) {
            BigDecimal price = BigDecimal.valueOf(level.priceTicks()).movePointLeft(2);
            BigDecimal qty = BigDecimal.valueOf(level.quantitySteps()).movePointLeft(4);
            target.add(new DepthLevel(price, qty));
            if (qty.compareTo(snap.maxDepthQty) > 0) {
                snap.maxDepthQty = qty;
            }
        }
    }
}
