package com.surprising.gateway.provider.telegram.bot.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastRepository.BroadcastRow;
import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastRepository.PendingSend;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramBroadcastServiceTest {

    @Test
    void rejectsEmptyNotice() {
        TelegramBroadcastService service = service(mock(TelegramBroadcastRepository.class), mock(TelegramBotClient.class));
        assertThrows(IllegalArgumentException.class, () -> service.create(1, "<p> </p>"));
    }

    @Test
    void snapshotsLinkedAccounts() {
        TelegramBroadcastRepository repository = mock(TelegramBroadcastRepository.class);
        when(repository.insert(7, "<b>Hi</b>")).thenReturn(4L);
        when(repository.copyRecipients(4L)).thenReturn(2);
        when(repository.require(4L)).thenReturn(row(4L, "QUEUED", 2, 0, 0));
        TelegramBroadcastService service = service(repository, mock(TelegramBotClient.class));

        TelegramBroadcastService.BroadcastView view = service.create(7, "<p><strong>Hi</strong></p>");

        verify(repository).copyRecipients(4L);
        verify(repository).seedCounts(4L, 2);
        assertEquals(2, view.recipientCount());
        assertEquals("Hi", view.preview());
    }

    @Test
    void recordsEachSend() {
        TelegramBroadcastRepository repository = mock(TelegramBroadcastRepository.class);
        TelegramBotClient bot = mock(TelegramBotClient.class);
        when(bot.isConfigured()).thenReturn(true);
        when(repository.claim(15)).thenReturn(List.of(new PendingSend(4L, "88", 88L, "<b>Hi</b>")));
        when(bot.sendHtml(88L, "<b>Hi</b>")).thenReturn(new TelegramBotClient.SendOutcome(true, false, null));
        service(repository, bot).drain();
        verify(repository).complete(4L, "88", "SENT", null);
        verify(repository).refresh(4L);
    }

    @Test void unconfiguredChecksBackOffButEnablingBotTakesEffectNextTick() {
        var repository = mock(TelegramBroadcastRepository.class);
        var bot = mock(TelegramBotClient.class);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var service = new TelegramBroadcastService(repository, bot, now::get);
        service.drain(); service.drain();
        verify(repository).failOutstanding(anyString());
        now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        service.drain();
        verify(repository, org.mockito.Mockito.times(2)).failOutstanding(anyString());
        when(bot.isConfigured()).thenReturn(true);
        when(repository.claim(15)).thenReturn(List.of());
        service.drain();
        verify(repository).claim(15);
    }

    @Test void retryDoesNotCompleteRecipientAndBroadcastIsRefreshedOnlyOnce() {
        var repository = mock(TelegramBroadcastRepository.class);
        var bot = mock(TelegramBotClient.class);
        when(bot.isConfigured()).thenReturn(true);
        when(repository.claim(15)).thenReturn(List.of(new PendingSend(4, "88", 88, "Hi"), new PendingSend(4, "99", 99, "Hi")));
        when(bot.sendHtml(88, "Hi")).thenReturn(new TelegramBotClient.SendOutcome(false, true, "retry"));
        when(bot.sendHtml(99, "Hi")).thenReturn(new TelegramBotClient.SendOutcome(false, false, "blocked"));
        service(repository, bot).drain();
        verify(repository, org.mockito.Mockito.never()).complete(org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq("88"), anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository).complete(4, "99", "FAILED", "blocked");
        verify(repository).refresh(4);
    }

    private static TelegramBroadcastService service(TelegramBroadcastRepository repository, TelegramBotClient bot) {
        return new TelegramBroadcastService(repository, bot);
    }

    private static BroadcastRow row(long id, String status, int recipients, int sent, int failed) {
        return new BroadcastRow(id, status, recipients, sent, failed, "<b>Hi</b>", Instant.EPOCH, null, null);
    }
}
