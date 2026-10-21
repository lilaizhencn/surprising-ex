package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问gateway_kyc_provider_events；Service事务同时提交回执去重、资料结果与通知意图。 */
@Repository
public class KycProviderEventRepository {
    private final JdbcTemplate jdbc;
    public KycProviderEventRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean record(String provider,String eventKey,Instant now) {
        return jdbc.update("""
                INSERT INTO gateway_kyc_provider_events(provider,event_key,received_at)
                VALUES (?,?,?) ON CONFLICT(provider,event_key) DO NOTHING
                """,provider,eventKey,Timestamp.from(now))==1;
    }
}
