package com.surprising.gateway.provider.telegram.bot.broadcast;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TelegramBroadcastWorker {

    private final TelegramBroadcastService broadcasts;

    public TelegramBroadcastWorker(TelegramBroadcastService broadcasts) {
        this.broadcasts = broadcasts;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void drain() {
        broadcasts.drain();
    }
}
