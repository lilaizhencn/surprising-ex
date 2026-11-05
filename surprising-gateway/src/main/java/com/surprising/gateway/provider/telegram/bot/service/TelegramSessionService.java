package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramSessionService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private final ConcurrentHashMap<Long, TelegramSessionState> sessions = new ConcurrentHashMap<>();

    public Optional<TelegramSessionState> getState(long telegramId) {
        TelegramSessionState state = sessions.get(telegramId);
        if (state == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (state.isExpired(now)) {
            sessions.remove(telegramId);
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public void setState(long telegramId, TelegramSessionState.Step step) {
        setState(telegramId, step, null);
    }

    public void setState(long telegramId, TelegramSessionState.Step step, TelegramSessionState previousState) {
        Instant expireAt = Instant.now().plus(DEFAULT_TTL);
        TelegramSessionState newState = new TelegramSessionState(
                step,
                previousState != null ? previousState.getData() : null,
                expireAt
        );
        sessions.put(telegramId, newState);
    }

    public void clearState(long telegramId) {
        sessions.remove(telegramId);
    }

    public void cleanupExpired() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
}
