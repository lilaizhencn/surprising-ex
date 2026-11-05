package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TelegramBotPollingService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPollingService.class);

    private final TelegramBotProperties properties;
    private final TelegramBotClient botClient;
    private final TelegramUpdateDispatcher dispatcher;
    private final TelegramSessionService sessionService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;
    private long lastOffset = 0L;

    public TelegramBotPollingService(TelegramBotProperties properties,
                                     TelegramBotClient botClient,
                                     TelegramUpdateDispatcher dispatcher,
                                     TelegramSessionService sessionService) {
        this.properties = properties;
        this.botClient = botClient;
        this.dispatcher = dispatcher;
        this.sessionService = sessionService;
    }

    @Override
    public void start() {
        if (!properties.isConfigured()) {
            log.info("Telegram Bot token not configured. Bot polling disabled.");
            return;
        }

        // 仅在明确开启 polling，或者没有配置 Webhook URL 且开启了 polling 时运行
        boolean shouldPoll = properties.isPollingEnabled() ||
                (properties.getWebhookUrl() == null || properties.getWebhookUrl().isBlank());

        if (!shouldPoll) {
            log.info("Telegram Bot webhook configured at {}. Long polling disabled.", properties.getWebhookUrl());
            return;
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting Telegram Bot Long-Polling daemon thread for @{}...", properties.getUsername());
            pollingThread = new Thread(this::pollLoop, "telegram-bot-polling");
            pollingThread.setDaemon(true);
            pollingThread.start();
        }
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<TelegramUpdate> updates = botClient.getUpdates(lastOffset, 50, 10);
                for (TelegramUpdate update : updates) {
                    if (!running.get()) break;
                    if (update.updateId() >= lastOffset) {
                        lastOffset = update.updateId() + 1;
                    }
                    try {
                        dispatcher.dispatch(update);
                    } catch (Exception ex) {
                        log.error("Failed to process polled update {}: {}", update.updateId(), ex.getMessage(), ex);
                    }
                }
            } catch (Exception ex) {
                log.warn("Telegram poll loop exception: {}", ex.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Telegram Bot Long-Polling daemon stopped.");
    }

    @Scheduled(fixedRate = 60000)
    public void scheduledCleanup() {
        sessionService.cleanupExpired();
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (pollingThread != null) {
                pollingThread.interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
