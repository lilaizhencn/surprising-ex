package com.surprising.gateway.provider.telegram.bot.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class TelegramSessionState {

    public enum Step {
        IDLE,
        // 提币流程
        WITHDRAW_AWAITING_ADDRESS,
        WITHDRAW_AWAITING_AMOUNT,
        WITHDRAW_AWAITING_CREDENTIAL,
        // 快捷交易流程
        TRADE_AWAITING_QUANTITY,
        TRADE_AWAITING_PRICE,
        TRADE_AWAITING_CREDENTIAL,
        // 资金划转流程
        TRANSFER_AWAITING_AMOUNT,
        // 安全密码设置流程
        SETTINGS_AWAITING_NEW_PIN,
        // KOL 下级分成比例设置
        KOL_AWAITING_CUSTOM_SHARE
    }

    private final Step step;
    private final Map<String, Object> data;
    private final Instant expireAt;

    public TelegramSessionState(Step step, Instant expireAt) {
        this(step, new HashMap<>(), expireAt);
    }

    public TelegramSessionState(Step step, Map<String, Object> data, Instant expireAt) {
        this.step = step;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.expireAt = expireAt;
    }

    public Step getStep() {
        return step;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expireAt);
    }

    public TelegramSessionState withStep(Step newStep, Instant newExpireAt) {
        return new TelegramSessionState(newStep, this.data, newExpireAt);
    }

    public TelegramSessionState putData(String key, Object value) {
        this.data.put(key, value);
        return this;
    }
}
