package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.auth.repository.GatewaySessionAuditRepository;
import com.surprising.gateway.provider.auth.repository.LoginSessionRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.mock;

/** 每个夹具自带一次性 Valkey 子进程，不连接、不清理开发或生产实例。 */
public final class ValkeySessionFixture implements AutoCloseable {
    private final Process process;
    private final LettuceConnectionFactory connection;
    public final StringRedisTemplate redis;
    public final ValkeyAuthStateBackend backend;
    public final GatewayProperties properties=new GatewayProperties();
    public final AuthState state;
    public final GatewaySessionAuditRepository logs=mock(GatewaySessionAuditRepository.class);
    public final LoginSessionRepository sessions;
    public ValkeySessionFixture() {
        try {
            int port;
            try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
            process=new ProcessBuilder("valkey-server","--bind","127.0.0.1","--port",Integer.toString(port),
                    "--save","","--appendonly","no","--loglevel","warning")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean ready=false;
            for(int attempt=0;attempt<200;attempt++) {
                try(var socket=new Socket("127.0.0.1",port)) { ready=true;break; }
                catch(java.io.IOException notReady) { Thread.sleep(10); }
            }
            if(!ready) { process.destroyForcibly();throw new IllegalStateException("test Valkey did not start"); }
            connection=new LettuceConnectionFactory("127.0.0.1",port);
            connection.afterPropertiesSet();connection.start();
            redis=new StringRedisTemplate(connection);
            backend=new ValkeyAuthStateBackend(redis);
            state=new AuthState(backend,properties);
            sessions=new LoginSessionRepository(redis,state,logs);
        } catch(Exception failure) { throw new IllegalStateException("cannot start isolated test Valkey",failure); }
    }
    public void user(long uid,boolean telegram) {
        state.putUser(new UserState(uid,"user"+uid,null,null,Instant.EPOCH,"NORMAL",List.of("USER"),List.of(),Set.of(),Set.of(),false,telegram));
    }
    public void startSession(long uid,long id) { seed(id,uid,null,"127.0.0.1",Instant.now()); }
    public void seed(long id,long uid,String device,String ip,Instant created) {
        user(uid,false);
        state.startSession(new SessionState(id,uid,device,created.toEpochMilli(),Duration.ofDays(7).toSeconds(),
                created.toEpochMilli(),"test",ip,created.toEpochMilli()),Duration.ofDays(7));
    }
    @Override public void close() {
        try { backend.stop(); } catch(Exception ignored) { }
        connection.destroy();process.destroy();
        try { if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();process.destroyForcibly(); }
    }
}
