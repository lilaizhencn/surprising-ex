package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;

/** Asynchronous realtime reads, created at an owner-validated committed boundary. */
final class RealtimeReadCoordinator {
    /** 唯一权威运行时状态；账户可变数据由所属 Lane 维护。 */
    private final TradingRuntimeState runtimeState;
    /** 撮合派发和完成通知通道；只在完成边界交接数据。 */
    private final MatcherPipelineGroup matcherPipeline;
    /** 真实撮合器适配器；命令经 matcher 队列推进。 */
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    private final RealtimeStateCapture realtimeCapture;

    RealtimeReadCoordinator(TradingRuntimeState state, MatcherPipelineGroup pipeline,
                            DeterministicExchangeCoreAdapter matcher, RealtimeStateCapture capture) {
        runtimeState = state;
        matcherPipeline = pipeline;
        matchingAdapter = matcher;
        realtimeCapture = capture;
    }

    /** 正在读取的用户快照 Future；完成前不接收第二个用户请求。 */
    private java.util.concurrent.CompletableFuture<com.surprising.aeron.service.state.RealtimeUserSnapshot> pendingRealtimeSnapshot;
    /** 当前用户快照请求的用户 ID。 当前用户快照请求 ID，用于端到端关联。 当前用户快照对应的已提交日志位置。 当前用户快照对应的集群时间。 当前用户快照对应的导出水位。 */
    private long realtimeSnapshotUser,realtimeSnapshotId,realtimeSnapshotPosition,realtimeSnapshotTimestamp,realtimeSnapshotExportSequence;
    boolean realtimeSnapshotPending() { return pendingRealtimeSnapshot != null; }
    void captureRealtimeSnapshot(long userId,long snapshotId,long position,long timestamp,long exportSequence) {
        if (realtimeCapture == null || pendingRealtimeSnapshot != null) return;
        realtimeSnapshotExportSequence=exportSequence;
        realtimeSnapshotUser=userId;realtimeSnapshotId=snapshotId;realtimeSnapshotPosition=position;realtimeSnapshotTimestamp=timestamp;
        try { pendingRealtimeSnapshot=runtimeState.realtimeSnapshot(userId); }
        catch (RuntimeException failure) { realtimeCapture.failed(); }
    }
    int pollRealtimeSnapshot() {
        if (pendingRealtimeSnapshot == null || !pendingRealtimeSnapshot.isDone()) return 0;
        var future=pendingRealtimeSnapshot;pendingRealtimeSnapshot=null;
        try {
            var snapshot=future.join();
            realtimeCapture.begin(realtimeSnapshotPosition,realtimeSnapshotTimestamp,realtimeSnapshotId);
            realtimeCapture.snapshot(realtimeSnapshotUser,snapshot,realtimeSnapshotExportSequence);realtimeCapture.commit();
        } catch (RuntimeException failure) {
            realtimeCapture.failed();
            realtimeCapture.begin(realtimeSnapshotPosition,realtimeSnapshotTimestamp,realtimeSnapshotId);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE,
                    realtimeSnapshotUser,"","",new byte[0]);
            realtimeCapture.commit();
        }
        return 1;
    }

    /** 正在读取的盘口 Future；只在完成后进行编码。 */
    private java.util.concurrent.CompletableFuture<java.util.List<com.surprising.aeron.protocol.CoreBookLevelView>> realtimeBook;
    /** 当前盘口请求的币对。 */
    private String realtimeBookSymbol;
    /** 当前盘口读取对应的已提交日志位置。 当前盘口读取对应的集群时间。 */
    private long realtimeBookPosition,realtimeBookTimestamp;
    boolean realtimeBookPending() {return realtimeBook != null;}
    void captureRealtimeBook(String symbol,long position,long timestamp) {
        if(realtimeCapture==null || realtimeBook!=null)return;
        realtimeBookSymbol=symbol;realtimeBookPosition=position;realtimeBookTimestamp=timestamp;
        try {realtimeBook=matcherPipeline.readAtSubmissionFence(matchingAdapter.matcherShardId(symbol),
                ()->matchingAdapter.orderBookLevelsAsync(symbol,20).join());}
        catch(RuntimeException failure){realtimeCapture.failed();}
    }
    int pollRealtimeBook() {
        if(realtimeBook==null || !realtimeBook.isDone())return 0;
        var future=realtimeBook;realtimeBook=null;
        try {
            var levels=future.join();
            realtimeCapture.begin(realtimeBookPosition,realtimeBookTimestamp,0);
            realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK,0,realtimeBookSymbol,realtimeBookSymbol,
                    CoreStateQueryCodec.encodeOrderBookView(new com.surprising.aeron.protocol.CoreOrderBookView(realtimeBookPosition,levels)));
            realtimeCapture.commit();
        }catch(RuntimeException failure){realtimeCapture.failed();}
        return 1;
    }

}
