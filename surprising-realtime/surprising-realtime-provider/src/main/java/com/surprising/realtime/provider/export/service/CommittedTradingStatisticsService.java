package com.surprising.realtime.provider.export.service;

import com.surprising.realtime.provider.export.repository.*;
import com.surprising.realtime.provider.export.model.CommandStatisticsBucket;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedCommandResult;
import com.surprising.aeron.service.state.query.CommittedTradingActivity;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.Comparator;
import org.springframework.transaction.support.TransactionTemplate;

/** Realtime export-thread-owned statistics. Buckets retain attempts, snapshots publish one consistent prefix. */
@org.springframework.stereotype.Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedTradingStatisticsService {
    private final TransactionTemplate transaction;
    private final StatisticsInitializationRepository initialization;
    private final StatisticsCursorRepository cursors;
    private final CommandStatisticsRepository commandStatistics;
    private final StatisticsSnapshotRepository snapshots;
    private final CommittedExecutionRepository executions;

    public static final List<Integer> WINDOWS = List.of(60,360,1440,10080);

    public CommittedTradingStatisticsService(StatisticsInitializationRepository initialization,
            StatisticsCursorRepository cursors, CommandStatisticsRepository commandStatistics,
            StatisticsSnapshotRepository snapshots, CommittedExecutionRepository executions,
            @org.springframework.beans.factory.annotation.Qualifier("committedProjectionTransactions") TransactionTemplate transaction) {
        this.initialization = initialization; this.cursors = cursors; this.commandStatistics = commandStatistics;
        this.snapshots = snapshots; this.executions = executions; this.transaction = transaction;
    }
    public boolean initialized(ProductLine product) { return initialization.initialized(product); }
    public void initialize(ProductLine product, long position) { initialization.initialize(product, position); }

    /** Caller supplies the same transaction as order/trade projection persistence. */
    public void persist(ProductLine product,List<CommittedCommandResult> results) {
        var ordered=new TreeMap<Long,CommittedCommandResult>();
        for(var result:results) {
            if(result.product()!=product)throw new IllegalArgumentException("cross-product statistics command");
            var prior=ordered.putIfAbsent(result.clusterPosition(),result);
            if(prior!=null&&!prior.equals(result))throw new IllegalStateException("conflicting statistics command position");
        }
        if(ordered.isEmpty())return;
        long cursor = cursors.lock(product);
        var buckets=new LinkedHashMap<CommandStatisticsBucket,Long>();
        for(var result:ordered.tailMap(cursor,false).values()) {
            if(result.occurredAt()<=0)throw new IllegalArgumentException("invalid command statistics time");
            var key=new CommandStatisticsBucket(result.occurredAt()/60000*60000,result.userId(),result.commandType(),result.status(),result.code());
            buckets.merge(key,1L,Math::addExact);
        }
        commandStatistics.add(product, buckets);
        if(ordered.lastKey()>cursor)cursors.advance(product, ordered.lastKey());
    }
    public void repair(ProductLine product,List<CommittedCommandResult> results) { transaction.executeWithoutResult(tx->persist(product,results)); }

    public void publish(ProductLine product,CommittedTradingActivity activity,long position,long now) {
        transaction.executeWithoutResult(tx->{
            var storedCursors=cursors.current(product);
            // SQL may be ahead of the main file checkpoint after a crash. Wait for replay to
            // reach that prefix before combining minute counters with current open state.
            if(!storedCursors.isEmpty()&&storedCursors.getFirst()>position)return;
            for(int window:WINDOWS) {
                long from=(now/60000-window)*60000;
                var data=window(product,activity,position,now,from,window);
                snapshots.publish(product, window, position, now, data);
            }
            // Only the last seven days are queryable; retain one extra day of minute buckets.
            commandStatistics.prune(product,now/60000*60000-8*86400000L);
        });
    }

    private Map<String,Object> window(ProductLine product,CommittedTradingActivity activity,long position,long now,long from,int minutes) {
        var breakdown=commandStatistics.breakdown(product,from);
        BigInteger commands=BigInteger.ZERO,rejected=BigInteger.ZERO,submitted=BigInteger.ZERO,orderRejected=BigInteger.ZERO;
        for(var row:breakdown) {
            var count=new BigInteger(row.get("commands").toString());commands=commands.add(count);
            boolean reject="REJECTED".equals(row.get("status"));if(reject)rejected=rejected.add(count);
            if(Set.of("PLACE_ORDER","PLACE_ORDER_BATCH").contains(row.get("commandType"))) {submitted=submitted.add(count);if(reject)orderRejected=orderRejected.add(count);}
        }
        long commandUsers=commandStatistics.commandUsers(product,from);
        long orderUsers=commandStatistics.orderUsers(product,from);
        var symbolTrades=executions.symbolTrades(product,from,position);
        long participants=executions.participants(product,from,position);
        var lastPrices=executions.lastPrices(product,from,position);
        var symbols=new HashMap<String,Map<String,Object>>();
        var positionSymbols=new ArrayList<Map<String,Object>>();
        for(var item:activity.instruments()) {
            var row=new LinkedHashMap<String,Object>();row.put("instrumentId",item.instrumentId());row.put("openOrders",item.openOrders());
            row.put("openQuantitySteps",item.openQuantitySteps());row.put("openPositions",item.openPositions());row.put("users",item.users());
            row.put("longPositions",item.longPositions());row.put("shortPositions",item.shortPositions());
            row.put("longQuantitySteps",item.longQuantitySteps());row.put("shortQuantitySteps",item.shortQuantitySteps());row.put("trades",0L);
            symbols.put(item.instrumentId(),row);if(item.openPositions()>0)positionSymbols.add(row);
        }
        long trades=0,lastTime=0;
        for(var item:symbolTrades) {
            String id=(String)item.get("instrumentId");var row=symbols.computeIfAbsent(id,key->new LinkedHashMap<>(Map.of("instrumentId",key,"openOrders",0L,"openQuantitySteps",BigInteger.ZERO,"openPositions",0L,"users",0L,"longPositions",0L,"shortPositions",0L,"longQuantitySteps",BigInteger.ZERO,"shortQuantitySteps",BigInteger.ZERO)));
            long count=((Number)item.get("trades")).longValue();trades=Math.addExact(trades,count);
            long time=((Number)item.get("lastTradeEpochMs")).longValue();lastTime=Math.max(lastTime,time);
            row.put("trades",count);row.put("lastTradeAt",Instant.ofEpochMilli(time).toString());
        }
        for(var item:lastPrices)symbols.get((String)item.get("instrument_id")).put("lastTradePriceTicks",item.get("price_ticks"));
        var ranked=new ArrayList<>(symbols.values());ranked.sort(Comparator.<Map<String,Object>>comparingLong(v->((Number)v.get("trades")).longValue()).reversed().thenComparing(v->(String)v.get("instrumentId")));
        positionSymbols.sort(Comparator.<Map<String,Object>>comparingLong(v->((Number)v.get("openPositions")).longValue()).reversed().thenComparing(v->(String)v.get("instrumentId")));
        var data=new LinkedHashMap<String,Object>();data.put("schema","committed-trading-v1");data.put("productLine",product.name());
        data.put("generatedAt",Instant.ofEpochMilli(now).toString());data.put("asOfLogPosition",position);data.put("windowMinutes",minutes);data.put("windowStart",Instant.ofEpochMilli(from).toString());
        data.put("orders",Map.of("submittedCommands",submitted,"rejectedCommands",orderRejected,"rejectRatePpm",rate(orderRejected,submitted),"uniqueUsers",orderUsers,"openOrders",activity.openOrders()));
        data.put("commands",Map.of("total",commands,"rejected",rejected,"rejectRatePpm",rate(rejected,commands),"uniqueAccounts",commandUsers));
        var tradeData=new LinkedHashMap<String,Object>();tradeData.put("trades",trades);tradeData.put("uniqueParticipants",participants);tradeData.put("lastTradeAt",lastTime==0?null:Instant.ofEpochMilli(lastTime).toString());data.put("trades",tradeData);
        data.put("triggerOrders",Map.of("pending",activity.pendingTriggers(),"expiredPending",activity.expiredPendingTriggers()));
        data.put("positions",Map.of("usersWithPositions",activity.usersWithPositions(),"openPositions",activity.openPositions(),"symbolCount",positionSymbols.size(),"symbols",List.copyOf(positionSymbols.subList(0,Math.min(100,positionSymbols.size())))));
        data.put("symbols",List.copyOf(ranked.subList(0,Math.min(100,ranked.size()))));data.put("symbolCount",ranked.size());data.put("commandBreakdown",breakdown);
        return data;
    }
    private static BigInteger rate(BigInteger rejected,BigInteger all) { return all.signum()==0?BigInteger.ZERO:rejected.multiply(BigInteger.valueOf(1000000)).divide(all); }
}
