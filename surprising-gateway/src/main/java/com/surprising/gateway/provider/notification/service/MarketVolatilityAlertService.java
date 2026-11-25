package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.notification.repository.NotificationPreferenceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 行情大幅波动检测与精准推送服务
 */
@Service
public class MarketVolatilityAlertService {

    private static final Logger log = LoggerFactory.getLogger(MarketVolatilityAlertService.class);
    private static final DateTimeFormatter HOUR_KEY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HH")
            .withZone(ZoneId.systemDefault());
    private static final int ALERT_BATCH_LIMIT = 500;

    private final NotificationPreferenceRepository preferenceRepository;
    private final PushDispatcher pushDispatcher;
    private final ObjectMapper objectMapper;

    public MarketVolatilityAlertService(NotificationPreferenceRepository preferenceRepository, PushDispatcher pushDispatcher) {
        this(preferenceRepository, pushDispatcher, com.surprising.gateway.provider.support.JsonMappers.DEFAULT);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MarketVolatilityAlertService(
            NotificationPreferenceRepository preferenceRepository,
            PushDispatcher pushDispatcher,
            ObjectMapper objectMapper) {
        this.preferenceRepository = preferenceRepository;
        this.pushDispatcher = pushDispatcher;
        this.objectMapper = com.surprising.gateway.provider.support.JsonMappers.orDefault(objectMapper);
    }

    /**
     * 手动或业务事件触发特定币对的波动率检查
     */
    public void evaluateMarketMovement(String symbol, BigDecimal currentPrice, BigDecimal change24hPct) {
        if (symbol == null || currentPrice == null || change24hPct == null) return;

        BigDecimal absChange = change24hPct.abs();
        String hourKey = HOUR_KEY_FMT.format(Instant.now());

        // 查询开启了行情推送、且波动阈值 <= 当前波动率的用户列表
        List<Long> targetUserIds = preferenceRepository.findVolatilityAlertUserIds(absChange, ALERT_BATCH_LIMIT);

        if (targetUserIds.isEmpty()) return;

        String direction = change24hPct.compareTo(BigDecimal.ZERO) >= 0 ? "急涨" : "急跌";
        String sign = change24hPct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        String pctStr = sign + change24hPct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        String priceStr = currentPrice.setScale(2, RoundingMode.HALF_UP).toPlainString();

        String title = String.format("行情剧烈异动：%s 24H %s %s", symbol, direction, pctStr);
        String body = String.format("【行情预警】%s 当前价格达到 $%s，24小时涨跌幅已达 %s。点击立即查看深度盘口与仓位风险。",
                symbol, priceStr, pctStr);
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);
        String route = "/trade/usd-perpetual";
        String routeParams = objectMapper.writeValueAsString(Map.of("symbol", upperSymbol));

        for (Long userId : targetUserIds) {
            String idempotencyKey = "VOLATILITY:" + userId + ":" + upperSymbol + ":" + hourKey;
            pushDispatcher.publish(userId, "MARKET", title, body, route, routeParams, idempotencyKey);
        }

        log.info("Dispatched market volatility alert for symbol={}, change={}, targetUsersCount={}",
                symbol, pctStr, targetUserIds.size());
    }
}
