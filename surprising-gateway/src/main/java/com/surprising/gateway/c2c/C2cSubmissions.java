package com.surprising.gateway.c2c;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 新建广告/订单的持久接收凭据；GET 不重发资金命令，停止操作与业务提交争用同一行锁。 */
@Repository
public class C2cSubmissions {
    public record Submission(long userId,String key,String kind,String hash,String status,UUID resultId,String errorCode,String errorMessage) { }
    private final JdbcTemplate jdbc;
    public C2cSubmissions(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional
    public Submission admit(long userId,String key,String kind,String hash) {
        jdbc.update("""
                INSERT INTO c2c_submissions(user_id,request_key,kind,request_hash) VALUES(?,?,?,?)
                ON CONFLICT(user_id,request_key) DO NOTHING
                """,userId,key,kind,hash);
        return matching(get(userId,key,false),kind,hash);
    }

    Submission get(long userId,String key,boolean lock) {
        return jdbc.query("SELECT * FROM c2c_submissions WHERE user_id=? AND request_key=?"+(lock?" FOR UPDATE":""),this::read,userId,key)
                .stream().findFirst().orElse(null);
    }

    public Submission lookup(long userId,String key,String kind,String hash) {
        return matching(get(userId,key,false),kind,hash);
    }

    Submission begin(long userId,String key,String kind,String hash) {
        var value=matching(get(userId,key,true),kind,hash);
        if(value==null) throw new IllegalStateException("原请求尚未持久接收");
        if(value.status().equals("REJECTED")) throw new IllegalStateException(value.errorMessage()==null?"原请求已停止":value.errorMessage());
        return value;
    }

    void succeed(long userId,String key,UUID resultId) {
        int changed=jdbc.update("""
                UPDATE c2c_submissions SET status='SUCCEEDED',result_id=?,updated_at=now()
                WHERE user_id=? AND request_key=? AND status='PROCESSING'
                """,resultId,userId,key);
        if(changed!=1) throw new IllegalStateException("原请求提交状态已变更");
    }

    @Transactional
    public void reject(long userId,String key,String kind,String hash,String code,String message) {
        var row=matching(get(userId,key,true),kind,hash);
        if(row==null || !row.status().equals("PROCESSING")) return;
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,79}")) throw new IllegalArgumentException("拒绝原因无效");
        String text=message==null?"请求未被接受":message.substring(0,Math.min(240,message.length()));
        jdbc.update("""
                UPDATE c2c_submissions SET status='REJECTED',error_code=?,error_message=?,updated_at=now()
                WHERE user_id=? AND request_key=? AND status='PROCESSING'
                """,code,text,userId,key);
    }

    @Transactional
    public Submission stop(long userId,String key,String kind,String hash) {
        jdbc.update("""
                INSERT INTO c2c_submissions(user_id,request_key,kind,request_hash,status,error_code,error_message)
                VALUES(?,?,?,?,'REJECTED','REQUEST_STOPPED','原请求已停止，未重新提交')
                ON CONFLICT(user_id,request_key) DO NOTHING
                """,userId,key,kind,hash);
        var row=matching(get(userId,key,true),kind,hash);
        if(row.status().equals("PROCESSING")) {
            // 业务事务持有本行直到广告/订单与资金意图共同提交，因此此处不能取消已被接受的交易。
            jdbc.update("""
                    UPDATE c2c_submissions SET status='REJECTED',error_code='REQUEST_STOPPED',
                        error_message='原请求已停止，未重新提交',updated_at=now()
                    WHERE user_id=? AND request_key=? AND status='PROCESSING'
                    """,userId,key);
        }
        return get(userId,key,false);
    }

    @Transactional
    void expireUnstarted(int limit) {
        // 跳过实际业务事务正在持有的行，避免把正在提交的请求误判为失败。
        jdbc.update("""
                UPDATE c2c_submissions SET status='REJECTED',error_code='REQUEST_EXPIRED',
                    error_message='原请求未完成接收，未重新提交',updated_at=now()
                WHERE (user_id,request_key) IN (
                    SELECT user_id,request_key FROM c2c_submissions WHERE status='PROCESSING' AND created_at<now()-interval '2 minutes'
                    ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
                """,limit);
    }

    private static Submission matching(Submission row,String kind,String hash) {
        if(row!=null && (!row.kind().equals(kind) || !row.hash().equals(hash))) throw new IllegalStateException("原请求内容不匹配");
        return row;
    }
    private Submission read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Submission(r.getLong("user_id"),r.getString("request_key"),r.getString("kind"),r.getString("request_hash"),
                r.getString("status"),r.getObject("result_id",UUID.class),r.getString("error_code"),r.getString("error_message"));
    }
}
