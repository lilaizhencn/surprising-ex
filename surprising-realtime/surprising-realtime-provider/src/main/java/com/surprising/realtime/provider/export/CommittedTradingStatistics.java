package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedCommandResult;
import com.surprising.aeron.service.state.query.CommittedTradingActivity;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Realtime export-thread-owned statistics. Buckets retain attempts, snapshots publish one consistent prefix. */
final class CommittedTradingStatistics {
    static final List<Integer> WINDOWS = List.of(60,360,1440,10080);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();

    CommittedTradingStatistics(JdbcTemplate jdbc, TransactionTemplate transaction) { this.jdbc=jdbc; this.transaction=transaction; }
    boolean initialized(ProductLine product) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_initialization WHERE product_line=?",Integer.class,product.name()));
    }
    void initialize(ProductLine product,long position) {
        jdbc.update("INSERT INTO core_trading_statistics_initialization VALUES (?,?) ON CONFLICT DO NOTHING",product.name(),position);
    }
    private record Bucket(long minute,long user,CoreMessageType type,ResponseStatus status,CoreResultCode code) {}

    /** Caller supplies the same transaction as order/trade projection persistence. */
    void persist(ProductLine product,List<CommittedCommandResult> results) {
        var ordered=new TreeMap<Long,CommittedCommandResult>();
        for(var result:results) {
            if(result.product()!=product)throw new IllegalArgumentException("cross-product statistics command");
            var prior=ordered.putIfAbsent(result.clusterPosition(),result);
            if(prior!=null&&!prior.equals(result))throw new IllegalStateException("conflicting statistics command position");
        }
        if(ordered.isEmpty())return;
        jdbc.update("INSERT INTO core_trading_statistics_cursor VALUES (?,0) ON CONFLICT DO NOTHING",product.name());
        long cursor=jdbc.queryForObject("SELECT last_command_position FROM core_trading_statistics_cursor WHERE product_line=? FOR UPDATE",Long.class,product.name());
        var buckets=new LinkedHashMap<Bucket,Long>();
        for(var result:ordered.tailMap(cursor,false).values()) {
            if(result.occurredAt()<=0)throw new IllegalArgumentException("invalid command statistics time");
            var key=new Bucket(result.occurredAt()/60000*60000,result.userId(),result.commandType(),result.status(),result.code());
            buckets.merge(key,1L,Math::addExact);
        }
        jdbc.batchUpdate("""
                INSERT INTO core_trading_command_statistics VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (product_line,minute_epoch_ms,user_id,command_type,command_status,result_code)
                DO UPDATE SET commands=core_trading_command_statistics.commands+EXCLUDED.commands
                """,buckets.entrySet(),256,(s,e)->{
            var k=e.getKey();s.setString(1,product.name());s.setLong(2,k.minute());s.setLong(3,k.user());s.setString(4,k.type().name());
            s.setString(5,k.status().name());s.setString(6,k.code().name());s.setLong(7,e.getValue());
        });
        if(ordered.lastKey()>cursor)jdbc.update("UPDATE core_trading_statistics_cursor SET last_command_position=? WHERE product_line=?",ordered.lastKey(),product.name());
    }
    void repair(ProductLine product,List<CommittedCommandResult> results) { transaction.executeWithoutResult(tx->persist(product,results)); }

    void publish(ProductLine product,CommittedTradingActivity activity,long position,long now) {
        transaction.executeWithoutResult(tx->{
            var cursors=jdbc.queryForList("SELECT last_command_position FROM core_trading_statistics_cursor WHERE product_line=?",Long.class,product.name());
            // SQL may be ahead of the main file checkpoint after a crash. Wait for replay to
            // reach that prefix before combining minute counters with current open state.
            if(!cursors.isEmpty()&&cursors.getFirst()>position)return;
            for(int window:WINDOWS) {
                long from=(now/60000-window)*60000;
                var data=window(product,activity,position,now,from,window);
                jdbc.update("""
                        INSERT INTO core_trading_statistics_snapshot VALUES (?,?,?,?,?::jsonb)
                        ON CONFLICT (product_line,window_minutes) DO UPDATE SET cluster_position=EXCLUDED.cluster_position,
                          generated_at_epoch_ms=EXCLUDED.generated_at_epoch_ms,metrics=EXCLUDED.metrics
                        WHERE core_trading_statistics_snapshot.cluster_position<=EXCLUDED.cluster_position
                        """,product.name(),window,position,now,json.writeValueAsString(data));
            }
            // Only the last seven days are queryable; retain one extra day of minute buckets.
            jdbc.update("DELETE FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms<?",product.name(),now/60000*60000-8*86400000L);
        });
    }

    private Map<String,Object> window(ProductLine product,CommittedTradingActivity activity,long position,long now,long from,int minutes) {
        var breakdown=jdbc.queryForList("""
                SELECT command_type AS "commandType",command_status AS "status",result_code AS "resultCode",sum(commands) AS "commands"
                FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=?
                GROUP BY command_type,command_status,result_code ORDER BY command_type,command_status,result_code
                """,product.name(),from);
        BigInteger commands=BigInteger.ZERO,rejected=BigInteger.ZERO,submitted=BigInteger.ZERO,orderRejected=BigInteger.ZERO;
        for(var row:breakdown) {
            var count=new BigInteger(row.get("commands").toString());commands=commands.add(count);
            boolean reject="REJECTED".equals(row.get("status"));if(reject)rejected=rejected.add(count);
            if(Set.of("PLACE_ORDER","PLACE_ORDER_BATCH").contains(row.get("commandType"))) {submitted=submitted.add(count);if(reject)orderRejected=orderRejected.add(count);}
        }
        long commandUsers=jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=?",Long.class,product.name(),from);
        long orderUsers=jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=? AND command_type IN ('PLACE_ORDER','PLACE_ORDER_BATCH')",Long.class,product.name(),from);
        var symbolTrades=jdbc.queryForList("""
                SELECT instrument_id AS "instrumentId",count(*) AS trades,max(occurred_at_epoch_ms) AS "lastTradeEpochMs"
                FROM core_execution_projection WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=? GROUP BY instrument_id
                """,product.name(),from,position);
        long participants=jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT taker_user_id FROM core_execution_projection WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?
                UNION SELECT maker_user_id FROM core_execution_projection WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?) participants
                """,Long.class,product.name(),from,position,product.name(),from,position);
        var lastPrices=jdbc.queryForList("""
                SELECT DISTINCT ON (instrument_id) instrument_id,price_ticks FROM core_execution_projection
                WHERE product_line=? AND occurred_at_epoch_ms>=? AND export_sequence<=?
                ORDER BY instrument_id,occurred_at_epoch_ms DESC,export_sequence DESC,execution_index DESC
                """,product.name(),from,position);
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
