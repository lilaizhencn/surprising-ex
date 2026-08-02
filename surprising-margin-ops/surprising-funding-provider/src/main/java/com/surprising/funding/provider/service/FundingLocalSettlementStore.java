package com.surprising.funding.provider.service;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.price.api.model.MarkPriceEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;
import tools.jackson.databind.ObjectMapper;

/**
 * 资金费结算的本地事实状态。
 *
 * <p>结算游标、支付命令和发布状态必须先同步写入本地 WAL，账户命令再从这里发布。
 * PostgreSQL 只能异步投影，不能用行锁决定分页进度，也不能决定是否重复扣款。</p>
 */
public final class FundingLocalSettlementStore implements AutoCloseable {

    private static final byte[] SETTLEMENT_PREFIX = "settlement/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYMENT_PREFIX = "payment/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMAND_PREFIX = "command/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SETTLEMENT_SEQUENCE_KEY = "meta/settlement-sequence".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYMENT_SEQUENCE_KEY = "meta/payment-sequence".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    public FundingLocalSettlementStore(Path directory, ObjectMapper objectMapper) {
        try {
            Objects.requireNonNull(directory, "directory");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            Files.createDirectories(directory);
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("资金费本地结算库打开失败: " + directory, ex);
        }
    }

    /** 创建或读取一个结算事实；重复调用必须返回同一 settlementId。 */
    public FundingSettlementWork begin(FundingRateResponse rate, MarkPriceEvent markPrice) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(markPrice, "markPrice");
        lock.lock();
        try {
            byte[] key = settlementKey(rate.symbol(), rate.fundingTime());
            SettlementRecord existing = read(key, SettlementRecord.class);
            if (existing != null) {
                return existing.work();
            }
            long settlementId = nextId(SETTLEMENT_SEQUENCE_KEY);
            SettlementRecord created = new SettlementRecord(settlementId, rate.symbol(), rate.fundingTime(),
                    rate.fundingRatePpm(), markPrice.instrumentVersion(), markPrice.markPriceTicks(),
                    new FundingPaymentCursor(0L, "", ""), false);
            write(key, created);
            return created.work();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将一页候选和新的游标作为一个本地事务提交。返回值中的命令即使尚未发布，重启后也会重放。
     */
    public List<PendingPayment> appendPage(FundingSettlementWork settlement,
                                           FundingPaymentPage page) {
        Objects.requireNonNull(settlement, "settlement");
        Objects.requireNonNull(page, "page");
        lock.lock();
        try {
            List<PendingPayment> payments = new ArrayList<>();
            for (FundingPaymentCandidate candidate : page.items()) {
                if (candidate.amountUnits() == 0L) {
                    continue;
                }
                long paymentId = nextId(PAYMENT_SEQUENCE_KEY);
                String commandId = "FUNDING:LOCAL:" + settlement.settlementId() + ":" + paymentId;
                PendingPayment payment = new PendingPayment(paymentId, settlement.settlementId(), commandId,
                        candidate, false);
                write(paymentKey(settlement.settlementId(), paymentId), payment);
                write(commandKey(commandId), new CommandIndex(payment.settlementId(), payment.paymentId()));
                payments.add(payment);
            }
            SettlementRecord updated = new SettlementRecord(settlement.settlementId(), settlement.symbol(),
                    settlement.fundingTime(), settlement.fundingRatePpm(), settlement.instrumentVersion(),
                    settlement.markPriceTicks(), page.nextCursor(), !page.hasMore());
            write(settlementKey(settlement.symbol(), settlement.fundingTime()), updated);
            return List.copyOf(payments);
        } finally {
            lock.unlock();
        }
    }

    /** 读取尚未追加到账户 WAL 的命令，用于崩溃恢复。 */
    public List<PendingPayment> pendingPayments(int limit) {
        lock.lock();
        try (RocksIterator iterator = database.newIterator()) {
            List<PendingPayment> result = new ArrayList<>();
            iterator.seek(PAYMENT_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), PAYMENT_PREFIX)
                    && result.size() < Math.max(1, limit)) {
                PendingPayment payment = readValue(iterator.value(), PendingPayment.class);
                if (payment != null && !payment.published()) {
                    result.add(payment);
                }
                iterator.next();
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    /** 账户 WAL 追加成功后标记命令；重复标记必须幂等。 */
    public void markPublished(String commandId) {
        lock.lock();
        try {
            CommandIndex index = read(commandKey(commandId), CommandIndex.class);
            if (index == null) {
                throw new IllegalStateException("资金费本地命令不存在: " + commandId);
            }
            byte[] key = paymentKey(index.settlementId(), index.paymentId());
            PendingPayment payment = read(key, PendingPayment.class);
            if (payment != null && !payment.published()) {
                write(key, new PendingPayment(payment.paymentId(), payment.settlementId(), payment.commandId(),
                        payment.payment(), true));
            }
        } finally {
            lock.unlock();
        }
    }

    public List<FundingSettlementWork> activeSettlements() {
        lock.lock();
        try (RocksIterator iterator = database.newIterator()) {
            List<FundingSettlementWork> result = new ArrayList<>();
            iterator.seek(SETTLEMENT_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), SETTLEMENT_PREFIX)) {
                SettlementRecord settlement = readValue(iterator.value(), SettlementRecord.class);
                if (settlement != null && !settlement.completed()) {
                    result.add(settlement.work());
                }
                iterator.next();
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        writeOptions.close();
        database.close();
        options.close();
    }

    private long nextId(byte[] key) {
        try {
            byte[] current = database.get(key);
            long next = current == null ? 1L : Math.addExact(ByteBuffer.wrap(current).getLong(), 1L);
            database.put(writeOptions, key, ByteBuffer.allocate(Long.BYTES).putLong(next).array());
            return next;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("资金费本地序号写入失败", ex);
        }
    }

    private <T> T read(byte[] key, Class<T> type) {
        try {
            byte[] value = database.get(key);
            return value == null ? null : objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("资金费本地状态读取失败", ex);
        }
    }

    private <T> T readValue(byte[] value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("资金费本地状态读取失败", ex);
        }
    }

    private void write(byte[] key, Object value) {
        try {
            database.put(writeOptions, key, objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("资金费本地状态写入失败", ex);
        }
    }

    private byte[] settlementKey(String symbol, Instant fundingTime) {
        return key(SETTLEMENT_PREFIX, symbol.trim().toUpperCase() + "/" + fundingTime.toEpochMilli());
    }

    private byte[] paymentKey(long settlementId, long paymentId) {
        return key(PAYMENT_PREFIX, settlementId + "/" + paymentId);
    }

    private byte[] commandKey(String commandId) {
        return key(COMMAND_PREFIX, commandId);
    }

    private byte[] key(byte[] prefix, String suffix) {
        byte[] bytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(bytes, 0, result, prefix.length, bytes.length);
        return result;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public record PendingPayment(long paymentId,
                                 long settlementId,
                                 String commandId,
                                 FundingPaymentCandidate payment,
                                 boolean published) {
    }

    private record CommandIndex(long settlementId, long paymentId) {
    }

    private record SettlementRecord(long settlementId,
                                    String symbol,
                                    Instant fundingTime,
                                    long fundingRatePpm,
                                    long instrumentVersion,
                                    long markPriceTicks,
                                    FundingPaymentCursor cursor,
                                    boolean completed) {
        private FundingSettlementWork work() {
            return new FundingSettlementWork(settlementId, symbol, fundingTime, fundingRatePpm,
                    instrumentVersion, markPriceTicks, cursor);
        }
    }
}
