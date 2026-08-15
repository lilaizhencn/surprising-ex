package com.surprising.websocket.api.model;

import com.surprising.product.api.ProductLine;
import java.util.Locale;

public record SubscriptionTopic(
        WsChannel channel,
        String symbol,
        String period,
        Long userId,
        ProductLine productLine) {

    public static final String WILDCARD = "*";

    public SubscriptionTopic(WsChannel channel, String symbol, String period, Long userId) {
        this(channel, symbol, period, userId, null);
    }

    public SubscriptionTopic {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        symbol = normalizeSymbol(symbol, channel);
        period = normalizePeriod(period, channel);
        if (!channel.isPublicChannel() && userId == null) {
            throw new IllegalArgumentException("private channel requires userId");
        }
    }

    public static SubscriptionTopic fromCommand(WsClientCommand command, Long authenticatedUserId) {
        WsChannel channel = WsChannel.fromCode(command.channel());
        Long userId = channel.isPublicChannel() ? null : privateUserId(command.userId(), authenticatedUserId);
        return new SubscriptionTopic(channel, command.symbol(), command.period(), userId,
                parseProductLine(command.productLine()));
    }

    public SubscriptionTopic withUserId(long userId) {
        return new SubscriptionTopic(channel, symbol, period, userId, productLine);
    }

    public SubscriptionTopic withSymbol(String symbol) {
        return new SubscriptionTopic(channel, symbol, period, userId, productLine);
    }

    public SubscriptionTopic withProductLine(ProductLine productLine) {
        return new SubscriptionTopic(channel, symbol, period, userId, productLine);
    }

    private static Long privateUserId(Long requestedUserId, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("private websocket channel requires authenticated user");
        }
        if (requestedUserId != null && requestedUserId.longValue() != authenticatedUserId.longValue()) {
            throw new IllegalArgumentException("requested userId does not match authenticated user");
        }
        return authenticatedUserId;
    }

    private static String normalizeSymbol(String symbol, WsChannel channel) {
        if (!channel.isPublicChannel() && (symbol == null || symbol.isBlank())) {
            return WILDCARD;
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for channel " + channel.code());
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!channel.isPublicChannel() && WILDCARD.equals(normalized)) {
            return WILDCARD;
        }
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private static ProductLine parseProductLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(normalized).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(normalized).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = normalized.replace('-', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName) || productLine.topicSegment().equalsIgnoreCase(value.trim())) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }

    private static String normalizePeriod(String period, WsChannel channel) {
        if (channel != WsChannel.CANDLES) {
            return null;
        }
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required for candles");
        }
        String normalized = period.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9]{1,3}[mhdw]")) {
            throw new IllegalArgumentException("invalid candle period: " + period);
        }
        return normalized;
    }
}
