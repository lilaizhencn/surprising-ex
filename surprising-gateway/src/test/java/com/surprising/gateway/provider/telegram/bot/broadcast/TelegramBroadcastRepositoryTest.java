package com.surprising.gateway.provider.telegram.bot.broadcast;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TelegramBroadcastRepositoryTest {
    @Test void remembersOnlySuccessfulWritesAndRechecksAfterThirtySeconds() {
        var jdbc = mock(JdbcTemplate.class);
        var now = new AtomicLong();
        var repository = new TelegramBroadcastRepository(jdbc, now::get);
        repository.rememberChat("88", 88);
        repository.rememberChat("88", 88);
        verify(jdbc).update(contains("IS DISTINCT FROM"), eq("88"), eq(88L));
        repository.rememberChat("88", 99);
        verify(jdbc).update(anyString(), eq("88"), eq(99L));
        now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        repository.rememberChat("88", 99);
        verify(jdbc, times(2)).update(anyString(), eq("88"), eq(99L));
    }
    @Test void failedWriteAndTransactionalWriteCannotPoisonMemo() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new TelegramBroadcastRepository(jdbc, () -> 0L);
        when(jdbc.update(anyString(), eq("88"), eq(88L))).thenThrow(new IllegalStateException("offline")).thenReturn(1);
        assertThatThrownBy(() -> repository.rememberChat("88", 88)).isInstanceOf(IllegalStateException.class);
        repository.rememberChat("88", 88);
        repository.rememberChat("88", 88);
        verify(jdbc, times(2)).update(anyString(), eq("88"), eq(88L));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { repository.rememberChat("99", 99); } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        repository.rememberChat("99", 99);
        verify(jdbc, times(2)).update(anyString(), eq("99"), eq(99L));
    }
    @Test void memoIsBoundedAndIndependentAcrossNodes() {
        var jdbc = mock(JdbcTemplate.class);
        var first = new TelegramBroadcastRepository(jdbc, () -> 0L);
        var second = new TelegramBroadcastRepository(jdbc, () -> 0L);
        for (int i = 0; i <= 4096; i++) first.rememberChat("u" + i, i);
        first.rememberChat("u0", 0);
        second.rememberChat("u0", 0);
        verify(jdbc, times(3)).update(anyString(), eq("u0"), eq(0L));
    }
    @Test void refreshLocksParentBeforeSingleAggregateAndDoesNotRecountFixedTotal() {
        var jdbc = mock(JdbcTemplate.class);
        new TelegramBroadcastRepository(jdbc).refresh(7);
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(7L));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).update(sql.capture(), eq(7L), eq(7L));
        assertThat(sql.getValue()).contains("FILTER", "FROM gateway_telegram_broadcast_recipients").doesNotContain("recipient_count =", "EXISTS");
    }
}
