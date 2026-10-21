package com.surprising.gateway.provider.announcement.repository;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅写gateway_announcement_reads。 */
@Repository
public class AnnouncementReadRepository {
    private final JdbcTemplate jdbc;
    public AnnouncementReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void insert(long id,long userId,Instant now) {
        jdbc.update("INSERT INTO gateway_announcement_reads(announcement_id,user_id,read_at) VALUES (?,?,?) ON CONFLICT (announcement_id,user_id) DO NOTHING",id,userId,Timestamp.from(now));
    }
    public void clear(long id) { jdbc.update("DELETE FROM gateway_announcement_reads WHERE announcement_id=?",id); }
    /** 关联主表/目标表仅为同一事务内选择有效公告；按ID持有主表共享锁，防止编辑清空已读后旧读取又写回。只写本人已读表。 */
    public int markAllRead(long userId,ClientFilter filter,Instant now) {
        return jdbc.update("""
                WITH eligible AS MATERIALIZED (
                SELECT a.announcement_id FROM gateway_announcements a
                 WHERE a.status = 'PUBLISHED' AND a.starts_at <= ?
                   AND (a.expires_at IS NULL OR a.expires_at > ?)
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PRODUCT_LINE'
                          AND (t.target_value = 'ALL' OR ? = 'ALL' OR t.target_value = ?))
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PLATFORM'
                          AND (t.target_value = 'ALL' OR t.target_value = ?))
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PLACEMENT'
                          AND (t.target_value = 'ALL' OR t.target_value = ?))
                   AND NOT EXISTS (SELECT 1 FROM gateway_announcement_reads r
                        WHERE r.announcement_id = a.announcement_id AND r.user_id = ?)
                 ORDER BY a.announcement_id FOR SHARE OF a
                )
                INSERT INTO gateway_announcement_reads(announcement_id,user_id,read_at)
                SELECT announcement_id,?,? FROM eligible
                ON CONFLICT (announcement_id,user_id) DO NOTHING
                """, Timestamp.from(now), Timestamp.from(now), filter.productLine(), filter.productLine(), filter.platform(), filter.placement(), userId, userId, Timestamp.from(now));
    }
}
