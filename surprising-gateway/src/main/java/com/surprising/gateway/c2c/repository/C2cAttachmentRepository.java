package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAttachment;
import com.surprising.gateway.c2c.model.C2cChatRows.UploadUsage;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_attachments；对象存储IO和授权由Service负责。 */
@Repository
public class C2cAttachmentRepository {
    private final JdbcTemplate jdbc;

    public C2cAttachmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean readyProof(UUID attachmentId, long owner, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_attachments WHERE attachment_id=? AND user_id=? AND order_id=? AND purpose='PROOF' AND status='READY')
                """, Boolean.class, attachmentId, owner, orderId));
    }

    public boolean readyPaymentQr(UUID attachmentId, long owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_attachments WHERE attachment_id=? AND user_id=? AND purpose='PAYMENT_QR' AND status='READY')
                """, Boolean.class, attachmentId, owner));
    }
    public Optional<C2cAttachment> find(UUID id) {
        return jdbc.query("SELECT * FROM c2c_attachments WHERE attachment_id=?",this::read,id).stream().findFirst();
    }
    public List<C2cAttachment> readyByIds(Collection<UUID> ids) {
        var distinct=ids.stream().distinct().toList();
        if(distinct.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM c2c_attachments WHERE status='READY' AND attachment_id IN ("
                +String.join(",",Collections.nCopies(distinct.size(),"?"))+")",this::read,distinct.toArray());
    }
    public void markReady(UUID id) {
        jdbc.update("UPDATE c2c_attachments SET status='READY' WHERE attachment_id=? AND status='UPLOADING'",id);
    }
    public UploadUsage usage(long user) {
        return jdbc.queryForObject("""
                SELECT count(*) AS files,COALESCE(sum(byte_count),0) AS bytes FROM c2c_attachments
                WHERE user_id=? AND created_at>now()-interval '24 hours'
                """,(r,n)->new UploadUsage(r.getLong("files"),r.getLong("bytes")),user);
    }
    public void insert(UUID id,long user,UUID order,UUID conversation,String purpose,String key,String mime,int bytes,String hash,String name) {
        jdbc.update("""
                INSERT INTO c2c_attachments(attachment_id,user_id,order_id,conversation_id,purpose,storage_key,content_type,byte_count,sha256,original_name)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,user,order,conversation,purpose,key,mime,bytes,hash,name);
    }
    private C2cAttachment read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new C2cAttachment(r.getObject("attachment_id",UUID.class),r.getLong("user_id"),r.getObject("order_id",UUID.class),
                r.getObject("conversation_id",UUID.class),r.getString("purpose"),r.getString("storage_key"),r.getString("content_type"),
                r.getInt("byte_count"),r.getString("sha256"),r.getString("original_name"),r.getString("status"));
    }

}
