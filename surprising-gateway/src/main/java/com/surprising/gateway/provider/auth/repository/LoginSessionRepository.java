package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.AdminSessionResponse;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.state.AuthState;
import com.surprising.gateway.provider.auth.state.AuthStateUnavailableException;
import com.surprising.gateway.provider.auth.state.SessionState;
import com.surprising.trading.api.utils.AdminCursorPage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 登录会话只存 Valkey。索引有序分页，单用户最多 100 个活跃会话；Postgres 只留操作记录。 */
@Repository
public class LoginSessionRepository {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LoginSessionRepository.class);
    private static final String ALL = "auth:sessions";
    private static final String EXPIRY = "auth:sessions:expiry";
    private static final String USER = "auth:sessions:user:";
    private static final DefaultRedisScript<Long> NEXT_ID = new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local base=tonumber(t[1])*1000000+tonumber(t[2])
            local previous=tonumber(redis.call('GET',KEYS[1]) or '0')
            local id=math.max(base,previous+1)
            redis.call('SET',KEYS[1],string.format('%.0f',id))
            return id
            """,Long.class);
    private static final DefaultRedisScript<Long> PRUNE = new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
            local expired=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',now,'LIMIT',0,1000)
            for _,member in ipairs(expired) do
              local split=string.find(member,':')
              local uid=string.sub(member,1,split-1)
              redis.call('ZREM',KEYS[1],member)
              redis.call('ZREM',KEYS[2],member)
              redis.call('ZREM','auth:sessions:user:'..uid,member)
            end
            return #expired
            """,Long.class);
    private static final DefaultRedisScript<List> REVOKE = new DefaultRedisScript<>("""
            local members={}
            if ARGV[1]=='session' then
              local uid=redis.call('HGET','auth:session:'..ARGV[2],'uid')
              if uid and (ARGV[3]=='' or uid==ARGV[3]) then members={uid..':'..ARGV[2]} end
            else
              members=redis.call('ZRANGE',KEYS[1],0,99)
            end
            local revoked={}
            for _,member in ipairs(members) do
              local split=string.find(member,':')
              local uid=string.sub(member,1,split-1)
              local sid=string.sub(member,split+1)
              local key='auth:session:'..sid
              local selected=ARGV[1]=='session' or ARGV[1]=='user'
                or (ARGV[1]=='except' and sid~=ARGV[2])
                or (ARGV[1]=='deviceId' and redis.call('HGET',key,'deviceId')==ARGV[2])
                or (ARGV[1]=='ipAddress' and redis.call('HGET',key,'ipAddress')==ARGV[2])
              if selected then
                if redis.call('DEL',key)>0 then table.insert(revoked,sid) end
                redis.call('ZREM','auth:sessions',member)
                redis.call('ZREM','auth:sessions:expiry',member)
                redis.call('ZREM','auth:sessions:user:'..uid,member)
                redis.call('PUBLISH','auth:invalidate','session:'..sid)
              end
            end
            return revoked
            """,List.class);

    private final StringRedisTemplate redis;
    private final AuthState state;
    private final GatewaySessionAuditRepository logs;
    public LoginSessionRepository(StringRedisTemplate redis, AuthState state, GatewaySessionAuditRepository logs) {
        this.redis=redis; this.state=state; this.logs=logs;
    }

    /** 事务提交后才创建会话；登录业务回滚时不会留下可用会话。 */
    public long create(long userId, Duration ttl, String userAgent, String ipAddress, String deviceId,
                       Instant authenticatedAt) {
        pruneExpired();
        Long id=call(() -> redis.execute(NEXT_ID,List.of("auth:session:sequence")));
        if (id==null) throw new IllegalStateException("session ID unavailable");
        long createdAt=id/1000;
        SessionState session=new SessionState(id,userId,deviceId,authenticatedAt.toEpochMilli(),ttl.toSeconds(),
                Instant.now().toEpochMilli(),userAgent,ipAddress,createdAt);
        Runnable create=() -> state.startSession(session,ttl);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { create.run(); }
            });
        } else create.run();
        return id;
    }

    private volatile long cleanupRetryAt;
    @Scheduled(fixedDelay=60000)
    public void cleanupExpired() {
        long now=System.nanoTime();
        if(now<cleanupRetryAt) return;
        try { pruneExpired(); }
        catch(AuthStateUnavailableException unavailable) {
            cleanupRetryAt=now+5_000_000_000L;
            log.warn("session index cleanup paused while Valkey is unavailable");
        }
    }

    public void pruneExpired() { call(() -> redis.execute(PRUNE,List.of(EXPIRY,ALL))); }

    public AdminCursorPage.CursorPage<AdminSessionResponse> findPage(Long userId, Boolean active, AuthPageQuery query) {
        if (!"createdAt".equals(query.order().field())) throw new IllegalArgumentException("unsupported session sort");
        int limit=Math.max(1,Math.min(query.limit(),200));
        String sort=query.order().token();
        if (Boolean.FALSE.equals(active)) return new AdminCursorPage.CursorPage<>(List.of(),null,false,sort,limit);
        pruneExpired();
        double min=query.after()==null ? Double.NEGATIVE_INFINITY : query.after().id()+1;
        double max=query.after()==null ? Double.POSITIVE_INFINITY : query.after().id()-1;
        Set<String> range=call(() -> query.order().descending()
                ? redis.opsForZSet().reverseRangeByScore(index(userId),Double.NEGATIVE_INFINITY,max,0,limit+1)
                : redis.opsForZSet().rangeByScore(index(userId),min,Double.POSITIVE_INFINITY,0,limit+1));
        List<String> members=range==null?List.of():List.copyOf(range);
        var rows=new ArrayList<AdminSessionResponse>();
        int examined=Math.min(limit,members.size());
        for(int i=0;i<examined;i++) {
            long id=id(members.get(i));
            Map<Object,Object> fields=fields(id);
            if(fields.isEmpty()) continue;
            Long remaining=call(() -> redis.getExpire(key(id),TimeUnit.MILLISECONDS));
            if(remaining==null||remaining<=0) continue;
            rows.add(new AdminSessionResponse(id,number(fields,"uid"),true,Instant.now().plusMillis(remaining),null,
                    text(fields,"userAgent"),text(fields,"ipAddress"),text(fields,"deviceId"),
                    Instant.ofEpochMilli(number(fields,"createdAt")),Instant.ofEpochMilli(number(fields,"renewedAt"))));
        }
        boolean more=members.size()>limit;
        String cursor=null;
        if(more) {
            long last=id(members.get(examined-1));
            cursor=AdminCursorPage.encodeCursor(Instant.ofEpochMilli(last/1000),last);
        }
        return new AdminCursorPage.CursorPage<>(rows,cursor,more,sort,limit);
    }

    public Optional<String> deviceIdForSession(long userId,long sessionId) {
        var fields=fields(sessionId);
        return fields.isEmpty()||number(fields,"uid")!=userId ? Optional.empty():Optional.ofNullable(text(fields,"deviceId"));
    }
    public int revoke(long sessionId,Instant now) { return revokeActive(sessionId,now); }
    public int revokeActive(long sessionId,Instant now) {
        var fields=fields(sessionId);
        return revoke("session",sessionId,"",fields.isEmpty()?0:number(fields,"uid"),now);
    }
    public int revokeActiveForUser(long userId,Instant now) { return revoke("user",0,"",userId,now); }
    public int revokeActiveForUserExcept(long userId,long excluded,Instant now) { return revoke("except",excluded,"",userId,now); }
    public int revokeActiveForUserSession(long userId,long sessionId,Instant now) { return revoke("session",sessionId,Long.toString(userId),userId,now); }
    public int revokeActiveForDevice(long userId,String deviceId,Instant now) { return revoke("deviceId",0,deviceId,userId,now); }
    public int revokeActiveForIp(long userId,String ip,Instant now) { return revoke("ipAddress",0,ip,userId,now); }

    private int revoke(String kind,long sessionId,String value,long userId,Instant now) {
        String match=(kind.equals("session")||kind.equals("except"))?Long.toString(sessionId):value;
        String owner=kind.equals("session")?value:"";
        List<?> removed=call(() -> redis.execute(REVOKE,List.of(USER+userId),kind,match,owner));
        List<Long> ids=removed==null?List.of():removed.stream().map(v->Long.parseLong(v.toString())).toList();
        state.endSessions(ids); // 清掉本节点的短缓存；不会重建已删除会话
        if(!ids.isEmpty()) logs.append(userId,"REVOKE_"+kind,ids,now);
        return ids.size();
    }

    public List<DeviceView> devices(long userId,int limit,int offset) {
        if(offset<0||offset>100) throw new IllegalArgumentException("device offset out of range");
        var page=findPage(userId,true,new AuthPageQuery(100,null,AuthPageQuery.Order.CREATED_DESC));
        Map<String,DeviceView> distinct=new LinkedHashMap<>();
        for(var row:page.items()) {
            String device=row.deviceId()==null?"session:"+row.sessionId():row.deviceId();
            distinct.putIfAbsent(device,new DeviceView(row.sessionId(),row.deviceId(),row.userAgent(),row.ipAddress(),row.updatedAt(),true));
        }
        return distinct.values().stream().skip(offset).limit(Math.max(1,Math.min(limit,100))).toList();
    }
    private Map<Object,Object> fields(long id) { return call(() -> redis.opsForHash().entries(key(id))); }
    private static String index(Long user) { return user==null?ALL:USER+user; }
    private static String key(long id) { return "auth:session:"+id; }
    private static long id(String member) { return Long.parseLong(member.substring(member.indexOf(':')+1)); }
    private static String text(Map<Object,Object> fields,String name) { Object v=fields.get(name);return v==null?null:v.toString(); }
    private static long number(Map<Object,Object> fields,String name) { String v=text(fields,name);return v==null?0:Long.parseLong(v); }
    private <T>T call(java.util.function.Supplier<T> action) {
        try { return action.get(); } catch (org.springframework.dao.DataAccessException ex) {
            throw new AuthStateUnavailableException("auth state store is unavailable",ex);
        }
    }
    public record DeviceView(long sessionId,String deviceId,String userAgent,String ipAddress,Instant lastSeen,boolean active) {}
}
