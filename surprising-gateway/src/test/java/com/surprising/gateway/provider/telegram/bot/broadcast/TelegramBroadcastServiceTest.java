package com.surprising.gateway.provider.telegram.bot.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(repository.require(4L)).thenReturn(row(4L, "SENDING", 2, 0, 0));
        TelegramBroadcastService service = service(repository, mock(TelegramBotClient.class));

        TelegramBroadcastService.BroadcastView view = service.create(7, "<p><strong>Hi</strong></p>");

        verify(repository).copyRecipients(4L);
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

    private static TelegramBroadcastService service(TelegramBroadcastRepository repository, TelegramBotClient bot) {
        return new TelegramBroadcastService(repository, bot);
    }

    private static BroadcastRow row(long id, String status, int recipients, int sent, int failed) {
        return new BroadcastRow(id, status, recipients, sent, failed, "<b>Hi</b>", Instant.EPOCH, null, null);
    }
}
