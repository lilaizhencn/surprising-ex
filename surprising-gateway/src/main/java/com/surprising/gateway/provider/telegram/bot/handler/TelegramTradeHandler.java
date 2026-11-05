package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TelegramTradeHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramTradeHandler.class);

    private final GatewayProductServices products;
    private final TelegramAuthHandler authHandler;
    private final TelegramSessionService sessionService;
    private final TelegramBotSecurityService securityService;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;

    public TelegramTradeHandler(GatewayProductServices products,
                                TelegramAuthHandler authHandler,
                                TelegramSessionService sessionService,
                                TelegramBotSecurityService securityService,
                                TelegramBotClient botClient,
                                TelegramBotProperties properties) {
        this.products = products;
        this.authHandler = authHandler;
        this.sessionService = sessionService;
        this.securityService = securityService;
        this.botClient = botClient;
        this.properties = properties;
    }

    public void renderTradeCenter(long chatId, Long messageId, TelegramUser tgUser) {
        sessionService.clearState(tgUser.id());
        String text = "⚡ <b>Surprising 交易中心（双模交易）</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "本交易平台支持两种交互模式：\n\n" +
                "1️⃣ <b>Telegram Mini App (TMA) 深度交易:</b>\n" +
                "点击下方专属按钮，在 Telegram 内部全屏打开专业 Web K线走势、深度盘口与高阶挂单交易终端！\n\n" +
                "2️⃣ <b>Bot 原生极速市价下单:</b>\n" +
                "无需打开任何网页，直接在 Bot 内选择币对与方向一键闪电下单！\n\n" +
                "👇 请选择您喜欢的交易方式：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.webApp("🚀 打开 Mini App 交易终端", properties.getWebappUrl())
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 快捷开多 BTC", "trade:fast:BTC-USDT:BUY"),
                        TelegramInlineKeyboardButton.callback("🔴 快捷做空 BTC", "trade:fast:BTC-USDT:SELL")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("🟢 快捷开多 ETH", "trade:fast:ETH-USDT:BUY"),
                        TelegramInlineKeyboardButton.callback("🔴 快捷做空 ETH", "trade:fast:ETH-USDT:SELL")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback("📈 查看我的持仓", "menu:positions"),
                        TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                )
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void startFastTradeQtyStep(long chatId, Long messageId, TelegramUser tgUser, String symbol, String side) {
        TelegramSessionState state = new TelegramSessionState(
                TelegramSessionState.Step.TRADE_AWAITING_QUANTITY,
                Map.of("symbol", symbol, "side", side),
                Instant.now().plus(Duration.ofMinutes(5))
        );
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.TRADE_AWAITING_QUANTITY, state);

        String sideTag = "BUY".equalsIgnoreCase(side) ? "🟢 买入 / 做多" : "🔴 卖出 / 做空";

        String text = "🔢 <b>输入下单委托数量</b>\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "标的合约: <b>" + symbol + "</b>\n" +
                "下单方向: <b>" + sideTag + "</b>\n" +
                "订单类型: <b>市价委托 (Market Order)</b>\n\n" +
                "请在下方聊天输入框直接回复您要开仓的<b>数量</b>（例如: <code>0.01</code> 或 <code>0.5</code>）：";

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback("❌ 取消下单", "menu:trade"))
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void handleQuantityInput(long chatId, TelegramUser tgUser, String qtyText, TelegramSessionState state) {
        BigDecimal qty;
        try {
            qty = new BigDecimal(qtyText.trim());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                botClient.sendMessage(chatId, "❌ 下单数量必须大于 0，请重新输入：", null);
                return;
            }
        } catch (Exception ex) {
            botClient.sendMessage(chatId, "❌ 数量格式有误，请输入有效数字（例如 0.05）：", null);
            return;
        }

        state.putData("quantity", qty.toPlainString());

        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecuritySettings sec = securityService.getSettings(userId);

        String symbol = (String) state.getData().get("symbol");
        String side = (String) state.getData().get("side");
        String sideTag = "BUY".equalsIgnoreCase(side) ? "🟢 买入 / 做多" : "🔴 卖出 / 做空";

        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>请确认快捷市价委托订单</b>\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🪙 <b>标的合约:</b> <code>").append(symbol).append("</code>\n");
        sb.append("🧭 <b>下单方向:</b> <b>").append(sideTag).append("</b>\n");
        sb.append("📦 <b>委托数量:</b> <code>").append(qty.toPlainString()).append("</code>\n");
        sb.append("⚡ <b>类型:</b> 市价即时成交 (IOC)\n\n");

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder();

        if (sec.securityMode() == TelegramSecurityMode.CONFIRM_ONLY) {
            sb.append("🛡 <b>安全确认:</b> 快速确认模式\n");
            sb.append("请核对上述信息无误后，点击确认下单：");
            markup.addRow(
                    TelegramInlineKeyboardButton.callback("✅ 确认市价下单", "trade:confirm_execute"),
                    TelegramInlineKeyboardButton.callback("❌ 取消", "menu:trade")
            );
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.IDLE, state);
        } else if (sec.securityMode() == TelegramSecurityMode.TRADE_PASSWORD) {
            sb.append("🔐 <b>安全保护:</b> 交易密码验证\n\n");
            sb.append("⚠️ 请在下方聊天框中回复您的 <b>6 位交易密码</b> 以确认下单：");
            markup.addRow(TelegramInlineKeyboardButton.callback("❌ 取消下单", "menu:trade"));
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.TRADE_AWAITING_CREDENTIAL, state);
        } else {
            sb.append("🛡 <b>安全保护:</b> 2FA / 邮箱验证码\n\n");
            sb.append("⚠️ 请在下方聊天框中回复您的 <b>6 位动态验证码 (OTP)</b> 以确认下单：");
            markup.addRow(TelegramInlineKeyboardButton.callback("❌ 取消下单", "menu:trade"));
            sessionService.setState(tgUser.id(), TelegramSessionState.Step.TRADE_AWAITING_CREDENTIAL, state);
        }

        botClient.sendMessage(chatId, sb.toString(), markup.build());
    }

    public void handleCredentialInput(long chatId, TelegramUser tgUser, String credential, TelegramSessionState state) {
        long userId = authHandler.resolveUserId(tgUser);
        boolean ok = securityService.verifyActionSecurity(userId, "TRADE", credential.trim());
        if (!ok) {
            botClient.sendMessage(chatId, "❌ 安全凭证验证失败（密码或验证码错误），请重新输入：", null);
            return;
        }

        executeTrade(chatId, null, tgUser, state);
    }

    public void executeTrade(long chatId, Long messageId, TelegramUser tgUser, TelegramSessionState state) {
        long userId = authHandler.resolveUserId(tgUser);
        String symbol = (String) state.getData().get("symbol");
        String sideStr = (String) state.getData().get("side");
        String qtyStr = (String) state.getData().get("quantity");

        OrderSide side = OrderSide.valueOf(sideStr);
        BigDecimal qty = new BigDecimal(qtyStr);
        long quantitySteps = qty.movePointRight(4).longValue();

        String clientOrderId = "tg_ord_" + UUID.randomUUID().toString().substring(0, 12);
        PlaceOrderRequest req = new PlaceOrderRequest(
                userId, clientOrderId, symbol, side, OrderType.MARKET, TimeInForce.IOC,
                0L, quantitySteps, MarginMode.CROSS, PositionSide.NET, false, false
        );

        boolean placed = false;
        String errorMsg = null;

        for (ProductLine line : List.of(ProductLine.LINEAR_PERPETUAL, ProductLine.SPOT)) {
            try {
                if (products.enabled().contains(line)) {
                    OrderService orderService = products.service(line, OrderService.class);
                    var future = orderService.placeCommandAsync(req);
                    if (future != null) {
                        placed = true;
                        break;
                    }
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
                log.warn("Place order failed on line {}: {}", line, ex.getMessage());
            }
        }

        sessionService.clearState(tgUser.id());

        if (placed) {
            String sideTag = side == OrderSide.BUY ? "🟢 买入/做多" : "🔴 卖出/做空";
            String text = "🎉 <b>市价下单成功已提交！</b>\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "🪙 <b>标的合约:</b> <code>" + symbol + "</code>\n" +
                    "🧭 <b>下单方向:</b> " + sideTag + "\n" +
                    "📦 <b>委托数量:</b> <code>" + qty.toPlainString() + "</code>\n" +
                    "🆔 <b>客户端单号:</b> <code>" + clientOrderId + "</code>\n" +
                    "⚡ <b>状态:</b> 🟢 撮合核心已受理";

            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(
                            TelegramInlineKeyboardButton.callback("📈 查看我的持仓", "menu:positions"),
                            TelegramInlineKeyboardButton.callback("⚡ 继续快捷交易", "menu:trade")
                    )
                    .addRow(
                            TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main")
                    )
                    .build();

            if (messageId != null) {
                botClient.editMessageText(chatId, messageId, text, markup);
            } else {
                botClient.sendMessage(chatId, text, markup);
            }
        } else {
            String text = "❌ <b>下单未成功:</b> " + (errorMsg != null ? errorMsg : "保证金不足或核心服务暂不可用") + "\n\n" +
                    "请检查账户保证金余额后重试。";

            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback("🔄 重新下单", "menu:trade"))
                    .addRow(TelegramInlineKeyboardButton.callback("⬅️ 返回主菜单", "menu:main"))
                    .build();

            if (messageId != null) {
                botClient.editMessageText(chatId, messageId, text, markup);
            } else {
                botClient.sendMessage(chatId, text, markup);
            }
        }
    }
}
