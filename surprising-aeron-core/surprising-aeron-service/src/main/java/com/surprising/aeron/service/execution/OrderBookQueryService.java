package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.matching.BookBootstrapSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 盘口查询服务：异步读取撮合器、缓存分页会话并限制响应大小。 */
final class OrderBookQueryService {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    OrderBookQueryService(TradingCoreRuntime owner) { this.owner = owner; }

    /** 撮合线程完成的盘口查询；owner 消费后移除。 */
    final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();

    /** 异步盘口查询失败标记；owner 生成对应终态响应。 */
    final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();

    /** 外部查询 ID 到内部异步查询 ID 的关联。 */
    final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();

    /** 有界盘口分页会话，避免跨页混用不同快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 下一个盘口内部查询 ID，与交易命令序号分离。 */
    long nextAsyncQueryId = Long.MIN_VALUE;

    CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadUnsafe().length == 0) {
            throw new IllegalArgumentException("single-symbol book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        long queryId = nextAsyncQueryId++;
        try {
            owner.matcherPipeline.readAtSubmissionFence(owner.matchingAdapter.matcherShardId(query.symbol()),
                    () -> owner.matchingAdapter.orderBookLevelsAsync(query.symbol(), query.depth()).join())
                    .whenComplete((levels, failure) -> {
                        if (failure != null) failedQueries.put(queryId, true);
                        else completedBookQueries.put(queryId, CompletedBookQuery.single(levels));
                    });
        } catch (RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount, owner.cachedBusinessStateHash);
    }

    CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
            BookBootstrapSession session = bookBootstrapSessions.get(query.snapshotId());
            if (session == null || session.depth() != query.depth()) {
                return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
            }
            return bootstrapPageResponse(session, query);
        }
        long queryId = nextAsyncQueryId++;
        try {
            var snapshot = owner.matchingAdapter.topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline.readAtSubmissionFence(0,
                    () -> owner.matchingAdapter.orderBookBootstrapAsync(query.depth()).join())
                    : owner.matcherPipeline.readEachAsync(
                    shardId -> owner.matchingAdapter.orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter::mergeBookBootstrapShards);
            snapshot.whenComplete((value, failure) -> {
                if (failure != null) failedQueries.put(queryId, true);
                else completedBookQueries.put(queryId, CompletedBookQuery.bootstrap(
                        message.header().commandId().toString(), query, value));
            });
        } catch (RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount, owner.cachedBusinessStateHash);
    }

    public long querySequence(UUID queryId) {
        return queryIds.getOrDefault(queryId, 0L);
    }

    public CoreResponse takeQueryResult(long queryId) {
        if (failedQueries.remove(queryId) != null) {
            queryIds.values().removeIf(value -> value == queryId);
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        CompletedBookQuery completed = completedBookQueries.remove(queryId);
        if (completed == null) return null;
        queryIds.values().removeIf(value -> value == queryId);
        long exportSequence = owner.runtimeProjectionJournal.publishedSequence();
        if (completed.bootstrapSnapshot() == null) {
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= TradingCoreRuntime.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    CoreResponse bootstrapPageResponse(BookBootstrapSession session, CoreOrderBookBootstrapQuery query) {
        if (!query.symbolCursor().isEmpty() && !session.symbols().containsKey(query.symbolCursor())) {
            return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
        }
        List<String> symbols = session.symbols().tailMap(query.symbolCursor(), false).keySet().stream()
                .limit(query.limit()).toList();
        int expectedLevels = 0;
        for (String symbol : symbols) {
            expectedLevels = Math.addExact(expectedLevels, session.symbols().get(symbol).size());
        }
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
        for (String symbol : symbols) levels.addAll(session.symbols().get(symbol));
        boolean complete = symbols.isEmpty()
                || session.symbols().higherKey(symbols.getLast()) == null;
        String nextCursor = complete ? "" : symbols.getLast();
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(session.snapshotId(),
                session.exportSequence(), nextCursor, complete, levels);
        byte[] encoded = CoreStateQueryCodec.encodeOrderBookBootstrapPage(page);
        return boundedBookResponse(levels.size(), encoded);
    }

    CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > TradingCoreRuntime.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCoreRuntime.MAX_BOOK_RESPONSE_BYTES) {
            return owner.rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount, owner.cachedBusinessStateHash, encoded);
    }

    record CompletedBookQuery(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            BookBootstrapSnapshot bootstrapSnapshot) {

        static CompletedBookQuery single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new CompletedBookQuery(List.copyOf(levels), "", null, null);
        }

        static CompletedBookQuery bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                BookBootstrapSnapshot snapshot) {
            return new CompletedBookQuery(List.of(), snapshotId, query, snapshot);
        }
    }

    record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                BookBootstrapSnapshot snapshot) {
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> grouped = new TreeMap<>();
            for (String symbol : snapshot.symbols()) grouped.put(symbol, new ArrayList<>());
            for (com.surprising.aeron.protocol.CoreBookLevelView level : snapshot.levels()) {
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels = grouped.get(level.symbol());
                if (levels == null) throw new IllegalStateException("bootstrap level references unknown symbol");
                levels.add(level);
            }
            grouped.replaceAll((symbol, levels) -> List.copyOf(levels));
            return new BookBootstrapSession(snapshotId, exportSequence, depth,
                    Collections.unmodifiableNavigableMap(grouped));
        }
    }
}
