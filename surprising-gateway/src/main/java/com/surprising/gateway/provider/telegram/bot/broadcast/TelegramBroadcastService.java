package com.surprising.gateway.provider.telegram.bot.broadcast;

import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastRepository.BroadcastRow;
import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastRepository.PendingSend;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramBroadcastService {

    private final TelegramBroadcastRepository broadcasts;
    private final TelegramBotClient bot;
    private final java.util.function.LongSupplier nanoTime;
    private long nextUnconfiguredCheck;
    private boolean unconfiguredChecked;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramBroadcastService(TelegramBroadcastRepository broadcasts, TelegramBotClient bot) {
        this(broadcasts, bot, System::nanoTime);
    }

    TelegramBroadcastService(TelegramBroadcastRepository broadcasts, TelegramBotClient bot, java.util.function.LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.broadcasts = broadcasts;
        this.bot = bot;
    }

    @Transactional
    public BroadcastView create(long adminUserId, String html) {
        String body = TelegramHtml.toTelegram(html);
        if (body.isBlank()) throw new IllegalArgumentException("通知正文不能为空");
        if (body.length() > TelegramHtml.MAX_LENGTH) {
            throw new IllegalArgumentException("通知超过 Telegram 单条 4096 字符上限");
        }
        long id = broadcasts.insert(adminUserId, body);
        int recipientCount = broadcasts.copyRecipients(id);
        broadcasts.seedCounts(id, recipientCount);
        return view(broadcasts.require(id));
    }

    public List<BroadcastView> list() {
        return broadcasts.list().stream().map(TelegramBroadcastService::view).toList();
    }

    public synchronized void drain() {
        if (!bot.isConfigured()) {
            long now = nanoTime.getAsLong();
            if (!unconfiguredChecked || now - nextUnconfiguredCheck >= 0) {
                broadcasts.failOutstanding("Telegram Bot 未配置，通知没有发出");
                nextUnconfiguredCheck = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                unconfiguredChecked = true;
            }
            return;
        }
        unconfiguredChecked = false;
        broadcasts.releaseStaleClaims();
        List<PendingSend> batch = broadcasts.claim(15);
        Set<Long> touched = new LinkedHashSet<>();
        for (PendingSend row : batch) {
            TelegramBotClient.SendOutcome outcome = bot.sendHtml(row.chatId(), row.html());
            if (outcome.retry()) {
                touched.add(row.broadcastId());
                continue;
            }
            if (outcome.accepted()) {
                broadcasts.complete(row.broadcastId(), row.subject(), "SENT", null);
            } else {
                broadcasts.complete(row.broadcastId(), row.subject(), "FAILED", outcome.error());
            }
            touched.add(row.broadcastId());
        }
        for (long id : touched) broadcasts.refresh(id);
    }

    private static BroadcastView view(BroadcastRow row) {
        return new BroadcastView(row.broadcastId(), row.status(), row.recipientCount(), row.sentCount(),
                row.failedCount(), TelegramHtml.preview(row.bodyHtml()), row.createdAt(), row.finishedAt(), row.error());
    }

    public record BroadcastView(long broadcastId, String status, int recipientCount, int sentCount, int failedCount,
                                String preview, Instant createdAt, Instant finishedAt, String error) {}
}
