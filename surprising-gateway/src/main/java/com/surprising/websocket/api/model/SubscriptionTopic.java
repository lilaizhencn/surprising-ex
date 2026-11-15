package com.surprising.websocket.api.model;

import com.surprising.product.api.ProductLine;
import java.util.Locale;
import java.util.regex.Pattern;

public record SubscriptionTopic(
        WsChannel channel,
        String instrumentId,
        String period,
        Long userId,
        ProductLine productLine,
        Integer depth) {

    public static final String WILDCARD = "*";
    public static final int MAX_DEPTH = 50;
    private static final Pattern PERIOD_PATTERN = Pattern.compile("[0-9]{1,3}[mhdw]");

    public SubscriptionTopic(WsChannel channel, String instrumentId, String period, Long userId) {
        this(channel, instrumentId, period, userId, null, null);
    }

    public SubscriptionTopic(WsChannel channel, String instrumentId, String period, Long userId, ProductLine productLine) {
        this(channel, instrumentId, period, userId, productLine, null);
    }

    public SubscriptionTopic {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        instrumentId = normalizeInstrumentId(instrumentId, channel);
        period = normalizePeriod(period, channel);
        depth = normalizeDepth(depth, channel);
        if (!channel.isPublicChannel() && userId == null) {
            throw new IllegalArgumentException("private channel requires userId");
        }
    }

    public static SubscriptionTopic fromCommand(WsClientCommand command, Long authenticatedUserId) {
        WsChannel channel = WsChannel.fromCode(command.channel());
        Long userId = channel.isPublicChannel() ? null : privateUserId(command.userId(), authenticatedUserId);
        return new SubscriptionTopic(channel, command.instrumentId(), command.period(), userId,
                parseProductLine(command.productLine()), command.depth());
    }

    public SubscriptionTopic withInstrumentId(String instrumentId) {
        return new SubscriptionTopic(channel, instrumentId, period, userId, productLine, depth);
    }

    public int effectiveDepth() {
        return depth == null ? MAX_DEPTH : depth;
    }

    private static Integer normalizeDepth(Integer depth, WsChannel channel) {
        if (channel != WsChannel.DEPTH || depth == null) {
            return null;
        }
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be between 1 and " + MAX_DEPTH);
        }
        return depth;
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

    private static String normalizeInstrumentId(String instrumentId, WsChannel channel) {
        if (!channel.isPublicChannel() && (instrumentId == null || instrumentId.isBlank())) {
            return WILDCARD;
        }
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required for channel " + channel.code());
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!channel.isPublicChannel() && WILDCARD.equals(normalized)) {
            return WILDCARD;
        }
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
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
        if (!PERIOD_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid candle period: " + period);
        }
        return normalized;
    }
}
