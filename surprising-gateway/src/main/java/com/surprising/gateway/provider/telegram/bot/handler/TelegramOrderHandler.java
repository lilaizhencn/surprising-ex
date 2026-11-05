package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class TelegramOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramOrderHandler.class);

    private final GatewayProductServices products;
    private final TelegramAuthHandler authHandler;
    private final TelegramBotClient botClient;

    public TelegramOrderHandler(GatewayProductServices products,
                                TelegramAuthHandler authHandler,
                                TelegramBotClient botClient) {
        this.products = products;
        this.authHandler = authHandler;
        this.botClient = botClient;
    }

    public void renderOpenOrders(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        List<OrderResponse> openOrders = new ArrayList<>();

        for (ProductLine line : List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    OrderService service = products.service(line, OrderService.class);
                    var resp = service.openOrders(userId, null, new ActiveOrderPageQuery(50, 0L), null);
                    if (resp != null && resp.orders() != null) {
                        openOrders.addAll(resp.orders());
                    }
                }
            } catch (Exception ex) {
                log.debug("No open orders found for user {} on line {}: {}", userId, line, ex.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>当前活跃委托挂单列表</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder();

        if (openOrders.isEmpty()) {
            sb.append("<i>您当前没有未成交的挂单委托。</i>\n\n");
            sb.append("💡 您可以进行快捷下单或在 Mini App 中挂单交易。");
            markup.addRow(
                    TelegramInlineKeyboardButton.callback("⚡ 快捷下单", "menu:trade"),
                    TelegramInlineKeyboardButton.callback("🔄 刷新挂单", "menu:orders")
            );
        } else {
            sb.append("📊 当前活跃委托共 <b>").append(openOrders.size()).append("</b> 笔：\n\n");
            int index = 1;
            for (OrderResponse o : openOrders) {
                String sideTag = "BUY".equalsIgnoreCase(o.side().name()) ? "🟢 买入" : "🔴 卖出";
                BigDecimal price = BigDecimal.valueOf(o.priceTicks()).movePointLeft(2);
                BigDecimal qty = BigDecimal.valueOf(o.quantitySteps()).movePointLeft(4);
                BigDecimal executed = BigDecimal.valueOf(o.executedQuantitySteps()).movePointLeft(4);

                sb.append("<b>#").append(index++).append(" ").append(o.instrumentId()).append("</b>\n");
                sb.append("  • 方向: ").append(sideTag).append(" | 单号: <code>").append(o.orderId()).append("</code>\n");
                sb.append("  • 委托价: <code>$").append(price.stripTrailingZeros().toPlainString()).append("</code>\n");
                sb.append("  • 数量: <code>").append(qty.stripTrailingZeros().toPlainString()).append("</code>");
                if (executed.compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" (已成交: <code>").append(executed.stripTrailingZeros().toPlainString()).append("</code>)");
                }
                sb.append("\n\n");

                markup.addRow(
                        TelegramInlineKeyboardButton.callback("❌ 撤单 #" + o.orderId(),
                                "orders:cancel:" + o.orderId() + ":" + o.instrumentId())
                );
            }

            markup.addRow(
                    TelegramInlineKeyboardButton.callback("⚠️ 一键全部撤单", "orders:cancel_all"),
                    TelegramInlineKeyboardButton.callback("🔄 刷新列表", "menu:orders")
            );
        }

        markup.addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"));

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, sb.toString(), markup.build());
        } else {
            botClient.sendMessage(chatId, sb.toString(), markup.build());
        }
    }

    public void executeCancelOrder(long chatId, long messageId, TelegramUser tgUser, long orderId, String instrumentId) {
        long userId = authHandler.resolveUserId(tgUser);
        CancelOrderRequest req = new CancelOrderRequest(userId, orderId);

        boolean cancelled = false;
        for (ProductLine line : List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    OrderService service = products.service(line, OrderService.class);
                    service.cancelCommandAsync(req);
                    cancelled = true;
                    break;
                }
            } catch (Exception ex) {
                log.warn("Cancel order warning: {}", ex.getMessage());
            }
        }

        if (cancelled) {
            botClient.answerCallbackQuery(null, "撤单指令已提交！", false);
        } else {
            botClient.answerCallbackQuery(null, "撤单失败或订单已完成", true);
        }

        renderOpenOrders(chatId, messageId, tgUser);
    }

    public void executeCancelAll(long chatId, long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);

        for (ProductLine line : List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            try {
                if (products.enabled().contains(line)) {
                    OrderService service = products.service(line, OrderService.class);
                    service.cancelOpenOrders(new CancelOpenOrdersRequest(userId, null, null));
                }
            } catch (Exception ex) {
                log.warn("Cancel all warning on line {}: {}", line, ex.getMessage());
            }
        }

        botClient.answerCallbackQuery(null, "一键全撤指令已发送！", false);
        renderOpenOrders(chatId, messageId, tgUser);
    }
}
