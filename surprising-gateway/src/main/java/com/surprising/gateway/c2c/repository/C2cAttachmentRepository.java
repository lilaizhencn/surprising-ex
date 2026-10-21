package com.surprising.gateway.c2c.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_attachments；对象存储IO和授权由Service负责。 */
@Repository
public class C2cAttachmentRepository {
    private final JdbcTemplate jdbc;

    public C2cAttachmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean readyPaymentQr(UUID attachmentId, long owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_attachments WHERE attachment_id=? AND user_id=? AND purpose='PAYMENT_QR' AND status='READY')
                """, Boolean.class, attachmentId, owner));
    }
}
