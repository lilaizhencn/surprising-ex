package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import tools.jackson.databind.ObjectMapper;

/**
 * 取消全部倒计时的本地事实状态。
 *
 * <p>倒计时设置、抢占和完成状态都在本地 RocksDB 同步提交；Redis 只作为快速到期索引，数据库不参与
 * 倒计时裁决。进程重启后可以从本地状态重新建立索引。</p>
 */
public final class CancelAllAfterLocalStateStore implements AutoCloseable {

    private static final byte[] PREFIX = "timer/".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final ObjectMapper objectMapper;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;

    public CancelAllAfterLocalStateStore(Path directory, ObjectMapper objectMapper) {
        try {
            this.objectMapper = objectMapper;
            Files.createDirectories(directory);
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("打开取消全部倒计时本地状态库失败: " + directory, ex);
        }
    }

    public synchronized CancelAllAfterTimer upsert(ProductLine productLine,
                                                    long userId,
                                                    String symbolScope,
                                                    long countdownMs,
                                                    Instant triggerAt,
                                                    String status,
                                                    Instant now) {
        CancelAllAfterTimer timer = new CancelAllAfterTimer(userId, symbolScope, countdownMs, status, triggerAt,
                now, 0, 0);
        put(productLine, timer);
        return timer;
    }

    public synchronized List<CancelAllAfterTimer> due(ProductLine productLine, Instant now, int limit) {
        return scan(productLine).stream()
                .filter(value -> "ACTIVE".equals(value.status()) && value.triggerAt() != null
                        && !value.triggerAt().isAfter(now))
                .sorted(Comparator.comparing(CancelAllAfterTimer::triggerAt)
                        .thenComparingLong(CancelAllAfterTimer::userId)
                        .thenComparing(CancelAllAfterTimer::symbolScope))
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized Optional<CancelAllAfterTimer> claim(ProductLine productLine,
                                                             long userId,
                                                             String symbolScope,
                                                             Instant now) {
        Optional<CancelAllAfterTimer> current = read(productLine, userId, symbolScope);
        if (current.isEmpty() || !"ACTIVE".equals(current.get().status())
                || current.get().triggerAt() == null || current.get().triggerAt().isAfter(now)) {
            return Optional.empty();
        }
        CancelAllAfterTimer claimed = withStatus(current.get(), "TRIGGERING", now,
                current.get().canceledOrders(), current.get().canceledTriggerOrders());
        put(productLine, claimed);
        return Optional.of(claimed);
    }

    public synchronized List<CancelAllAfterTimer> activeTimersForIndex(ProductLine productLine,
                                                                         long afterUserId,
                                                                         String afterSymbolScope,
                                                                         int limit) {
        String cursorScope = afterSymbolScope == null ? "" : afterSymbolScope;
        return scan(productLine).stream()
                .filter(value -> "ACTIVE".equals(value.status()) && value.triggerAt() != null)
                .filter(value -> value.userId() > afterUserId
                        || value.userId() == afterUserId && value.symbolScope().compareTo(cursorScope) > 0)
                .sorted(Comparator.comparingLong(CancelAllAfterTimer::userId)
                        .thenComparing(CancelAllAfterTimer::symbolScope))
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized void markTriggered(ProductLine productLine,
                                            long userId,
                                            String symbolScope,
                                            int canceledOrders,
                                            int canceledTriggerOrders,
                                            Instant now) {
        read(productLine, userId, symbolScope).ifPresent(timer -> {
            if ("TRIGGERING".equals(timer.status())) {
                put(productLine, withStatus(timer, "TRIGGERED", now, canceledOrders, canceledTriggerOrders));
            }
        });
    }

    public synchronized void releaseForRetry(ProductLine productLine,
                                              long userId,
                                              String symbolScope,
                                              String ignoredError,
                                              Instant now) {
        read(productLine, userId, symbolScope).ifPresent(timer -> {
            if ("TRIGGERING".equals(timer.status())) {
                put(productLine, withStatus(timer, "ACTIVE", now,
                        timer.canceledOrders(), timer.canceledTriggerOrders()));
            }
        });
    }

    private List<CancelAllAfterTimer> scan(ProductLine productLine) {
        List<CancelAllAfterTimer> result = new ArrayList<>();
        byte[] prefix = key(PREFIX, productLine.name() + "/");
        try (var iterator = database.newIterator()) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                result.add(decode(iterator.value()));
                iterator.next();
            }
        }
        return result;
    }

    private Optional<CancelAllAfterTimer> read(ProductLine productLine, long userId, String symbolScope) {
        try {
            byte[] value = database.get(key(PREFIX, timerKey(productLine, userId, symbolScope)));
            return value == null ? Optional.empty() : Optional.of(decode(value));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取取消全部倒计时失败", ex);
        }
    }

    private void put(ProductLine productLine, CancelAllAfterTimer timer) {
        try {
            database.put(writeOptions, key(PREFIX, timerKey(productLine, timer.userId(), timer.symbolScope())),
                    objectMapper.writeValueAsString(timer).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("写入取消全部倒计时失败", ex);
        }
    }

    private CancelAllAfterTimer decode(byte[] value) {
        try {
            return objectMapper.readValue(new String(value, StandardCharsets.UTF_8), CancelAllAfterTimer.class);
        } catch (Exception ex) {
            throw new IllegalStateException("取消全部倒计时本地状态损坏", ex);
        }
    }

    private CancelAllAfterTimer withStatus(CancelAllAfterTimer current,
                                           String status,
                                           Instant now,
                                           int canceledOrders,
                                           int canceledTriggerOrders) {
        return new CancelAllAfterTimer(current.userId(), current.symbolScope(), current.countdownMs(), status,
                current.triggerAt(), now, canceledOrders, canceledTriggerOrders);
    }

    private String timerKey(ProductLine productLine, long userId, String symbolScope) {
        return productLine.name() + "/" + String.format(java.util.Locale.ROOT, "%020d", userId)
                + "/" + symbolScope;
    }

    private byte[] key(byte[] prefix, String suffix) {
        byte[] bytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(bytes, 0, result, prefix.length, bytes.length);
        return result;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        writeOptions.close();
        database.close();
        options.close();
    }
}
